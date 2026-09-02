package io.jobclaw.web;

import io.jobclaw.session.SessionManager;
import io.jobclaw.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceControllerDirectoryTest {

    @TempDir
    Path tempDir;

    @Test
    void listsOnlyChildDirectories() throws Exception {
        Path alpha = Files.createDirectory(tempDir.resolve("alpha"));
        Path beta = Files.createDirectory(tempDir.resolve("Beta"));
        Files.writeString(tempDir.resolve("ignored.txt"), "not a directory");
        WorkspaceController controller = controller();

        ResponseEntity<?> response = controller.browseDirectories(tempDir.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        WorkspaceController.DirectoryListing listing = (WorkspaceController.DirectoryListing) response.getBody();
        assertThat(listing).isNotNull();
        assertThat(listing.currentPath()).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
        assertThat(listing.directories())
                .extracting(WorkspaceController.DirectoryEntry::path)
                .containsExactly(alpha.toString(), beta.toString());
    }

    @Test
    void rejectsMissingDirectory() {
        WorkspaceController controller = controller();

        ResponseEntity<?> response = controller.browseDirectories(tempDir.resolve("missing").toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private WorkspaceController controller() {
        WorkspaceRegistry registry = mock(WorkspaceRegistry.class);
        SessionManager sessionManager = mock(SessionManager.class);
        when(sessionManager.listUserSessionRecords()).thenReturn(List.of());
        return new WorkspaceController(registry, sessionManager);
    }
}
