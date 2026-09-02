package io.jobclaw.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDefaultWorkspaceAndPersistsSessionMembership() throws Exception {
        Path defaultDirectory = Files.createDirectory(tempDir.resolve("default"));
        Path store = tempDir.resolve("state").resolve("workspaces.json");

        WorkspaceRegistry registry = new WorkspaceRegistry(store, defaultDirectory.toString());
        WorkspaceRecord workspace = registry.list().getFirst();
        registry.attachSession(workspace.getId(), "web:test");

        WorkspaceRegistry reloaded = new WorkspaceRegistry(store, defaultDirectory.toString());
        assertThat(reloaded.resolveWorkingDirectory("web:test"))
                .isEqualTo(defaultDirectory.toRealPath().toString());
        assertThat(reloaded.findBySession("web:test")).isPresent();
    }

    @Test
    void deduplicatesCanonicalPathsAndMovesSessionBetweenWorkspaces() throws Exception {
        Path first = Files.createDirectory(tempDir.resolve("first"));
        Path second = Files.createDirectory(tempDir.resolve("second"));
        WorkspaceRegistry registry = new WorkspaceRegistry(tempDir.resolve("workspaces.json"), first.toString());

        WorkspaceRecord duplicate = registry.create(first.resolve(".").toString(), "Duplicate");
        assertThat(registry.list()).hasSize(1);
        assertThat(duplicate.getId()).isEqualTo(registry.list().getFirst().getId());

        WorkspaceRecord secondWorkspace = registry.create(second.toString(), "Second");
        registry.attachSession(registry.list().getFirst().getId(), "web:test");
        registry.attachSession(secondWorkspace.getId(), "web:test");

        assertThat(registry.findBySession("web:test").orElseThrow().getId()).isEqualTo(secondWorkspace.getId());
        assertThat(registry.list().stream().filter(item -> item.getSessionKeys().contains("web:test"))).hasSize(1);
    }

    @Test
    void removingRegistrationDoesNotDeleteDirectoryOrFiles() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("project"));
        Path marker = Files.writeString(directory.resolve("keep.txt"), "keep");
        WorkspaceRegistry registry = new WorkspaceRegistry(tempDir.resolve("workspaces.json"), directory.toString());

        registry.remove(registry.list().getFirst().getId());

        assertThat(Files.readString(marker)).isEqualTo("keep");
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void rejectsMissingDirectory() {
        WorkspaceRegistry registry = new WorkspaceRegistry(
                tempDir.resolve("workspaces.json"), tempDir.resolve("fallback").toString());

        assertThatThrownBy(() -> registry.create(tempDir.resolve("missing").toString(), "Missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an existing directory");
    }

    @Test
    void migratesOnlyUnassignedLegacySessionsToFallback() throws Exception {
        Path first = Files.createDirectory(tempDir.resolve("first"));
        Path second = Files.createDirectory(tempDir.resolve("second"));
        WorkspaceRegistry registry = new WorkspaceRegistry(tempDir.resolve("workspaces.json"), first.toString());
        WorkspaceRecord secondWorkspace = registry.create(second.toString(), "Second");
        registry.attachSession(secondWorkspace.getId(), "web:owned");

        registry.attachLegacySessionsToFallback(List.of("web:owned", "web:legacy", "batch:test"));

        assertThat(registry.findBySession("web:owned").orElseThrow().getId()).isEqualTo(secondWorkspace.getId());
        assertThat(registry.resolveWorkingDirectory("web:legacy")).isEqualTo(first.toRealPath().toString());
        assertThat(registry.findBySession("batch:test")).isEmpty();
    }

    @Test
    void removesNonWebMembershipCreatedByLegacyMigration() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("project"));
        WorkspaceRegistry registry = new WorkspaceRegistry(tempDir.resolve("workspaces.json"), directory.toString());
        String workspaceId = registry.list().getFirst().getId();
        registry.attachSession(workspaceId, "web:keep");
        registry.attachSession(workspaceId, "batch:remove");

        registry.removeNonWebSessionMemberships();

        assertThat(registry.findBySession("web:keep")).isPresent();
        assertThat(registry.findBySession("batch:remove")).isEmpty();
    }
}
