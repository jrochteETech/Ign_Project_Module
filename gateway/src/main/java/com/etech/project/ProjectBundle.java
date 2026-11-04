package com.etech.project;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public record ProjectBundle(String moduleId, String projectName, String version, String target,
                            boolean frameworkOnly, String hash, byte[] archive) {
    private static final int MAX_ARCHIVE_BYTES = 64 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;

    public static ProjectBundle load(ClassLoader loader, String expectedTarget) throws IOException {
        Properties metadata = new Properties();
        try (InputStream input = loader.getResourceAsStream("project-module.properties")) {
            if (input == null) throw new IOException("Module metadata is missing");
            metadata.load(input);
        }
        String moduleId = required(metadata, "module.id");
        String name = required(metadata, "project.name");
        String version = required(metadata, "module.version");
        String target = required(metadata, "ignition.target");
        String mode = required(metadata, "framework.only");
        if (!expectedTarget.equals(target) || !Set.of("true", "false").contains(mode)) {
            throw new IOException("Invalid module target or framework mode");
        }
        if (!name.matches("etech_[a-z0-9_]{1,58}") || !moduleId.equals("com.etech.project." + name)) {
            throw new IOException("Invalid module/project identity");
        }
        if (Boolean.parseBoolean(mode)) {
            try (InputStream input = loader.getResourceAsStream("bundled-project.zip")) {
                if (input != null) throw new IOException("Framework-only module must not contain a project");
            }
            return new ProjectBundle(moduleId, name, version, target, true, "", new byte[0]);
        }
        byte[] archive;
        try (InputStream input = loader.getResourceAsStream("bundled-project.zip")) {
            if (input == null) throw new IOException("Bundled project is missing");
            archive = input.readNBytes(MAX_ARCHIVE_BYTES + 1);
        }
        String hash = required(metadata, "project.sha256");
        if (archive.length > MAX_ARCHIVE_BYTES || !hash.equals(sha256(archive))) {
            throw new IOException("Bundled project exceeds size limit or checksum does not match");
        }
        validateArchive(archive);
        return new ProjectBundle(moduleId, name, version, target, false, hash, archive);
    }

    public static void validateArchive(byte[] archive) throws IOException {
        if (archive.length > MAX_ARCHIVE_BYTES) throw new IOException("Project archive exceeds 64 MiB");
        Set<String> names = new HashSet<>();
        boolean perspectiveResource = false;
        long expanded = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains(":")
                        || java.util.Arrays.asList(name.split("/", -1)).contains("..")
                        || java.util.Arrays.asList(name.split("/", -1)).contains(".")
                        || !names.add(name) || names.size() > 50000) {
                    throw new IOException("Unsafe, duplicate, or excessive archive entries: " + name);
                }
                if (name.startsWith("com.inductiveautomation.perspective/") && name.endsWith("/resource.json")) {
                    perspectiveResource = true;
                }
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > MAX_EXPANDED_BYTES) throw new IOException("Expanded project exceeds 256 MiB");
                }
            }
        }
        if (!names.contains("project.json") || !perspectiveResource) {
            throw new IOException("Expected project.json and Perspective resources at the ZIP root");
        }
    }

    public InputStream openArchive() {
        return new ByteArrayInputStream(archive);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String required(Properties metadata, String key) throws IOException {
        String value = metadata.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing module metadata: " + key);
        return value;
    }
}