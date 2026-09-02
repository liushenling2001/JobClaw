package io.jobclaw.web;

import io.jobclaw.session.Session;
import io.jobclaw.session.SessionManager;
import io.jobclaw.workspace.WorkspaceRecord;
import io.jobclaw.workspace.WorkspaceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceRegistry workspaceRegistry;
    private final SessionManager sessionManager;

    public WorkspaceController(WorkspaceRegistry workspaceRegistry, SessionManager sessionManager) {
        this.workspaceRegistry = workspaceRegistry;
        this.sessionManager = sessionManager;
        this.workspaceRegistry.removeNonWebSessionMemberships();
        this.workspaceRegistry.attachLegacySessionsToFallback(
                sessionManager.listUserSessionRecords().stream()
                        .map(record -> record.getSessionId())
                        .filter(sessionKey -> sessionKey.startsWith("web:"))
                        .toList());
    }

    @GetMapping
    public List<WorkspaceRecord> list() {
        return workspaceRegistry.list();
    }

    @GetMapping("/directories")
    public ResponseEntity<?> browseDirectories(@RequestParam(required = false) String path) {
        if (path == null || path.isBlank()) {
            List<DirectoryEntry> roots = StreamSupport
                    .stream(FileSystems.getDefault().getRootDirectories().spliterator(), false)
                    .map(root -> new DirectoryEntry(root.toString(), root.toString()))
                    .toList();
            return ResponseEntity.ok(new DirectoryListing(null, null, roots));
        }

        try {
            Path directory = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isDirectory(directory)) {
                return ResponseEntity.badRequest().body(Map.of("error", "目录不存在：" + path));
            }
            if (!Files.isReadable(directory)) {
                return ResponseEntity.badRequest().body(Map.of("error", "目录不可读取：" + directory));
            }
            List<DirectoryEntry> children;
            try (Stream<Path> stream = Files.list(directory)) {
                children = stream
                        .filter(Files::isDirectory)
                        .sorted(Comparator.comparing(item -> item.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .map(item -> new DirectoryEntry(item.getFileName().toString(), item.toString()))
                        .toList();
            }
            Path parent = directory.getParent();
            return ResponseEntity.ok(new DirectoryListing(
                    directory.toString(),
                    parent == null ? null : parent.toString(),
                    children));
        } catch (IOException | RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "无法读取目录：" + e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody WorkspaceRequest request) {
        try {
            return ResponseEntity.ok(workspaceRegistry.create(request.path(), request.title()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> rename(@PathVariable String id, @RequestBody WorkspaceRequest request) {
        try {
            return ResponseEntity.ok(workspaceRegistry.rename(id, request.title()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@PathVariable String id) {
        try {
            workspaceRegistry.remove(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id) {
        return workspaceRegistry.find(id)
                .<ResponseEntity<?>>map(workspace -> {
                    Path path = Path.of(workspace.getPath());
                    return ResponseEntity.ok(Map.of(
                            "id", workspace.getId(),
                            "path", workspace.getPath(),
                            "exists", Files.isDirectory(path),
                            "readable", Files.isReadable(path),
                            "writable", Files.isWritable(path)
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/sessions")
    public ResponseEntity<?> createSession(@PathVariable String id) {
        WorkspaceRecord workspace = workspaceRegistry.find(id).orElse(null);
        if (workspace == null) {
            return ResponseEntity.notFound().build();
        }
        String sessionKey = "web:" + UUID.randomUUID().toString().replace("-", "");
        Session session = sessionManager.getOrCreate(sessionKey);
        session.setWorkingDirectory(workspace.getPath());
        workspaceRegistry.attachSession(id, sessionKey);
        return ResponseEntity.ok(Map.of(
                "sessionKey", sessionKey,
                "workspaceId", id,
                "workingDirectory", workspace.getPath()
        ));
    }

    public record WorkspaceRequest(String path, String title) { }

    public record DirectoryEntry(String name, String path) { }

    public record DirectoryListing(String currentPath, String parentPath, List<DirectoryEntry> directories) { }
}
