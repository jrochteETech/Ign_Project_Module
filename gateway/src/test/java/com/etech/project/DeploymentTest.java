package com.etech.project;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentTest {
    @TempDir Path directory;

    private static ProjectBundle bundle(String version) {
        return new ProjectBundle("com.etech.project.etech_test", "etech_test", version, "8.1", false,
                "hash-" + version, new byte[0]);
    }

    @Test
    void createsOwnedProjectAndPreservesEditsOnUnchangedRestart() throws Exception {
        FakeProjects projects = new FakeProjects();
        assertTrue(ManagedDeployment.deploy(bundle("1.0.0"), directory, projects));
        projects.content = "designer edit";
        assertFalse(ManagedDeployment.deploy(bundle("1.0.0"), directory, projects));
        assertEquals("designer edit", projects.content);
    }

    @Test
    void refusesUnownedCollisionWithoutExportOrReplacement() {
        FakeProjects projects = new FakeProjects();
        projects.content = "unrelated";
        assertThrows(IOException.class, () -> ManagedDeployment.deploy(bundle("1.0.0"), directory, projects));
        assertEquals("unrelated", projects.content);
        assertFalse(Files.exists(directory.resolve("backups")));
    }

    @Test
    void backsUpBeforeReplacementAndRetainsFiveBackups() throws Exception {
        FakeProjects projects = new FakeProjects();
        ManagedDeployment.deploy(bundle("1.0.0"), directory, projects);
        for (int version = 1; version <= 7; version++) {
            ManagedDeployment.deploy(bundle("1.0." + version), directory, projects);
        }
        try (var backups = Files.list(directory.resolve("backups"))) {
            assertEquals(5, backups.count());
        }
        assertEquals("replacement", projects.content);
        assertFalse(Files.exists(directory.resolve("pending.properties")));
    }

    @Test
    void exportFailureDoesNotMutateProject() throws Exception {
        FakeProjects projects = new FakeProjects();
        ManagedDeployment.deploy(bundle("1.0.0"), directory, projects);
        projects.failExport = true;
        assertThrows(IOException.class, () -> ManagedDeployment.deploy(bundle("1.0.1"), directory, projects));
        assertEquals("original", projects.content);
        assertFalse(Files.exists(directory.resolve("pending.properties")));
    }

    @Test
    void replacementFailureRestoresBackupAndPriorLedger() throws Exception {
        FakeProjects projects = new FakeProjects();
        ManagedDeployment.deploy(bundle("1.0.0"), directory, projects);
        projects.failReplace = true;
        assertThrows(IOException.class, () -> ManagedDeployment.deploy(bundle("1.0.1"), directory, projects));
        assertEquals("original", projects.content);
        assertFalse(Files.exists(directory.resolve("pending.properties")));
        assertFalse(ManagedDeployment.deploy(bundle("1.0.0"), directory, projects));
    }

    @Test
    void failedRollbackBlocksFutureDeployments() throws Exception {
        FakeProjects projects = new FakeProjects();
        ManagedDeployment.deploy(bundle("1.0.0"), directory, projects);
        projects.failReplace = true;
        projects.failRestore = true;
        Exception failure = assertThrows(IOException.class,
                () -> ManagedDeployment.deploy(bundle("1.0.1"), directory, projects));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(Files.exists(directory.resolve("pending.properties")));
        projects.failReplace = false;
        assertThrows(IOException.class, () -> ManagedDeployment.deploy(bundle("1.0.2"), directory, projects));
    }

    @Test
    void failedInitialCreationRequiresRecovery() {
        FakeProjects projects = new FakeProjects();
        projects.failCreate = true;
        assertThrows(IOException.class, () -> ManagedDeployment.deploy(bundle("1.0.0"), directory, projects));
        assertTrue(Files.exists(directory.resolve("pending.properties")));
    }

    @Test
    void validatesArchiveStructureAndRejectsTraversal() throws Exception {
        ProjectBundle.validateArchive(archive("project.json",
                "com.inductiveautomation.perspective/views/Test/resource.json"));
        assertThrows(IOException.class, () -> ProjectBundle.validateArchive(archive("../project.json")));
        assertThrows(IOException.class, () -> ProjectBundle.validateArchive(archive("C:/project.json")));
        assertThrows(IOException.class, () -> ProjectBundle.validateArchive(archive("project.json")));
        assertThrows(IOException.class, () -> ProjectBundle.validateArchive(new byte[0]));
    }

    private static byte[] archive(String... names) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("{}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static final class FakeProjects implements ManagedDeployment.ProjectAccess {
        String content;
        boolean failExport;
        boolean failReplace;
        boolean failRestore;
        boolean failCreate;

        public boolean exists() { return content != null; }
        public void create() throws IOException {
            if (failCreate) throw new IOException("Create failed");
            content = "original";
        }
        public void replace() throws IOException {
            content = "replacement";
            if (failReplace) throw new IOException("Replace failed");
        }
        public void export(OutputStream destination) throws IOException {
            if (failExport) throw new IOException("Export failed");
            destination.write(content.getBytes(StandardCharsets.UTF_8));
        }
        public void validateBackup(Path backup) throws IOException {
            if (Files.size(backup) == 0) throw new IOException("Empty backup");
        }
        public void restore(Path backup) throws IOException {
            if (failRestore) throw new IOException("Restore failed");
            content = Files.readString(backup);
        }
    }
}