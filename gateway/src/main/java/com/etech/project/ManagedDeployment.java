package com.etech.project;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class ManagedDeployment {
    public interface ProjectAccess {
        boolean exists();
        void create() throws Exception;
        void replace() throws Exception;
        void export(OutputStream destination) throws Exception;
        void validateBackup(Path backup) throws Exception;
        void restore(Path backup) throws Exception;
    }

    public static boolean deploy(ProjectBundle bundle, Path stateDirectory, ProjectAccess projects) throws Exception {
        Files.createDirectories(stateDirectory);
        try (FileChannel channel = FileChannel.open(stateDirectory.resolve("deployment.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another project deployment is running");
            return deployLocked(bundle, stateDirectory, projects);
        }
    }

    private static boolean deployLocked(ProjectBundle bundle, Path stateDirectory, ProjectAccess projects) throws Exception {
        Path ledger = stateDirectory.resolve("ownership.properties");
        Path pending = stateDirectory.resolve("pending.properties");
        if (Files.exists(pending)) {
            throw new IOException("Interrupted deployment: inspect " + pending + " and restore its backup before retrying");
        }
        Properties previous = readProperties(ledger);
        boolean exists = projects.exists();
        if (exists && (!bundle.moduleId().equals(previous.getProperty("module.id"))
                || !bundle.projectName().equals(previous.getProperty("project.name")))) {
            throw new IOException("Refusing to overwrite an unowned project: " + bundle.projectName());
        }
        if (exists && bundle.hash().equals(previous.getProperty("project.sha256"))
                && bundle.version().equals(previous.getProperty("module.version"))) {
            return false;
        }

        Path backup = null;
        Path backups = stateDirectory.resolve("backups");
        if (exists) {
            Files.createDirectories(backups);
            Path temporary = Files.createTempFile(backups, "export-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    projects.export(output);
                }
                projects.validateBackup(temporary);
                backup = backups.resolve(String.format("%019d-%s.zip", System.currentTimeMillis(), UUID.randomUUID()));
                Files.move(temporary, backup, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        Properties next = new Properties();
        next.setProperty("module.id", bundle.moduleId());
        next.setProperty("project.name", bundle.projectName());
        next.setProperty("project.sha256", bundle.hash());
        next.setProperty("module.version", bundle.version());
        Properties transaction = new Properties();
        transaction.putAll(next);
        transaction.setProperty("backup", backup == null ? "none (new project)" : backup.toAbsolutePath().toString());
        writeProperties(pending, transaction);
        try {
            if (exists) projects.replace();
            else projects.create();
            writeProperties(ledger, next);
        } catch (Exception failure) {
            if (backup != null) {
                try {
                    projects.restore(backup);
                    writeProperties(ledger, previous);
                    Files.delete(pending);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        Files.delete(pending);
        if (Files.isDirectory(backups)) {
            try (var entries = Files.list(backups)) {
                List<Path> retained = entries.filter(path -> path.getFileName().toString().endsWith(".zip"))
                        .sorted(Comparator.reverseOrder()).toList();
                for (Path obsolete : retained.stream().skip(5).toList()) Files.delete(obsolete);
            }
        }
        return true;
    }

    private static Properties readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
        }
        return properties;
    }

    private static void writeProperties(Path destination, Properties properties) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), "state-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Module-managed project deployment");
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}