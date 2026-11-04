import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;

public final class CreateSigningIdentity {
    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments.length == 0 ? "signing" : arguments[0]).toAbsolutePath();
        String alias = "ign-project-module";
        String keystoreName = alias + ".keystore";
        for (String name : new String[]{keystoreName, "signing.properties", alias + ".cer"}) {
            if (Files.exists(directory.resolve(name))) {
                throw new IOException("Refusing to overwrite an existing signing identity: " + directory.resolve(name));
            }
        }
        Files.createDirectories(directory);
        restrict(directory, "rwx------");
        Path temporary = Files.createTempDirectory(directory, "initialize-");
        try {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            Path keystore = temporary.resolve(keystoreName);
            String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
            String keytool = Path.of(System.getProperty("java.home"), "bin", executable).toString();
            run(password, keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "3072",
                    "-sigalg", "SHA256withRSA", "-validity", "3650", "-storetype", "JKS",
                    "-keystore", keystore.toString(), "-storepass:env", "IGN_PROJECT_SIGNING_PASSWORD",
                    "-keypass:env", "IGN_PROJECT_SIGNING_PASSWORD", "-dname",
                    "CN=Ignition Project Module, OU=Module Development, O=ETech", "-noprompt");
            restrict(keystore, "rw-------");
            Path certificate = temporary.resolve(alias + ".cer");
            run(password, keytool, "-exportcert", "-alias", alias, "-keystore", keystore.toString(),
                    "-storepass:env", "IGN_PROJECT_SIGNING_PASSWORD", "-file", certificate.toString());
            Properties properties = new Properties();
            properties.setProperty("ignition.signing.certAlias", alias);
            properties.setProperty("ignition.signing.keystorePassword", password);
            properties.setProperty("ignition.signing.certPassword", password);
            Path settings = temporary.resolve("signing.properties");
            try (OutputStream output = Files.newOutputStream(settings)) {
                properties.store(output, "Local signing secrets - never commit or share this file");
            }
            restrict(settings, "rw-------");
            for (String name : new String[]{keystoreName, alias + ".cer", "signing.properties"}) {
                Files.move(temporary.resolve(name), directory.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            }
            System.out.println("Created independent signing identity in " + directory);
            System.out.println("Alias: " + alias + ". Credentials were stored locally and were not printed.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void run(String password, String... command) throws Exception {
        ProcessBuilder process = new ProcessBuilder(command).inheritIO();
        process.environment().put("IGN_PROJECT_SIGNING_PASSWORD", password);
        if (process.start().waitFor() != 0) throw new IOException("Signing identity initialization failed");
    }

    private static void restrict(Path path, String permissions) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl != null) {
                AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class));
                if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
                acl.setAcl(List.of(entry.build()));
            }
        }
    }
}