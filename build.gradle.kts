import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.KeyStore
import java.util.Locale
import java.util.Properties

plugins {
    base
    id("io.ia.sdk.modl") version "0.5.0"
}

fun identityFor(branch: String): String {
    require(branch.isNotBlank() && branch != "HEAD" && branch.none { it.isISOControl() }) {
        "Supply the full branch name with -PprojectBranch (required for detached HEAD/container builds)"
    }
    val slug = branch.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")
        .trim('_').take(32).ifEmpty { "project" }
    val digest = MessageDigest.getInstance("SHA-256").digest(branch.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(12)
    return "etech_${slug}_$digest"
}

val ignitionTarget = providers.gradleProperty("ignitionTarget").getOrElse("8.3")
val sdkVersion = when (ignitionTarget) {
    "8.1" -> "8.1.33"
    "8.3" -> "8.3.0"
    else -> throw GradleException("ignitionTarget must be 8.1 or 8.3")
}
extra["sdkVersion"] = sdkVersion
extra["ignitionTarget"] = ignitionTarget

val branch = providers.gradleProperty("projectBranch").orElse(providers.environmentVariable("PROJECT_BRANCH"))
    .orElse(providers.exec {
        commandLine("git", "symbolic-ref", "--quiet", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }).get()
val identity = identityFor(branch)
val moduleId = "com.etech.project.$identity"
val frameworkOnly = branch == "main"
val moduleVersion = providers.gradleProperty("moduleVersion").getOrElse("0.1.0")
require(moduleVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))) {
    "moduleVersion must be a semantic version such as 1.0.0 or 1.0.0-SNAPSHOT"
}
val artifactName = "$identity-ignition-$ignitionTarget-$moduleVersion"
val projectSource = layout.projectDirectory.dir(providers.gradleProperty("projectSource${ignitionTarget.replace(".", "")}")
    .orElse(providers.gradleProperty("projectSource")).getOrElse("perspective-project"))

allprojects {
    group = "com.etech.project"
    version = moduleVersion
}

ignitionModule {
    name.set(if (frameworkOnly) "ETech Project Framework" else "ETech $identity")
    id.set(moduleId)
    fileName.set(artifactName)
    moduleVersion.set(project.version.toString())
    moduleDescription.set("Branch-based Perspective project framework")
    requiredIgnitionVersion.set(sdkVersion)
    requiredFrameworkVersion.set("8")
    freeModule.set(true)
    moduleDependencySpecs {
        register("com.inductiveautomation.perspective") {
            scope = "G"
            required = true
        }
    }
    projectScopes.put(":gateway", "G")
    hooks.put("com.etech.project.ProjectGatewayHook", "G")
    skipModlSigning.set(false)
}

val bundledProject = tasks.register<Zip>("bundleProject") {
    onlyIf { !frameworkOnly }
    from(projectSource)
    exclude("**/.git/**", "**/.DS_Store", "**/Thumbs.db")
    archiveFileName.set("bundled-project.zip")
    destinationDirectory.set(layout.buildDirectory.dir("project-bundle"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    includeEmptyDirs = false
    doFirst {
        require(projectSource.file("project.json").asFile.isFile) {
            "Project branches require project.json at ${projectSource.asFile}. Supply an unpacked Ignition project export."
        }
        require(projectSource.dir("com.inductiveautomation.perspective").asFile.isDirectory) {
            "Project source must contain Perspective resources (com.inductiveautomation.perspective)."
        }
        val sourcePath = projectSource.asFile.toPath()
        java.nio.file.Files.walk(sourcePath).use { paths ->
            require(paths.noneMatch { java.nio.file.Files.isSymbolicLink(it) }) { "Project source must not contain symlinks" }
        }
    }
}

val generateBundleMetadata = tasks.register("generateBundleMetadata") {
    dependsOn(bundledProject)
    inputs.property("branch", branch)
    inputs.property("version", moduleVersion)
    inputs.property("target", ignitionTarget)
    inputs.property("commit", providers.gradleProperty("gitCommit").getOrElse("unknown"))
    if (!frameworkOnly) inputs.file(bundledProject.flatMap { it.archiveFile })
    outputs.dir(layout.buildDirectory.dir("bundle-metadata"))
    doLast {
        val metadata = Properties()
        metadata.setProperty("module.id", moduleId)
        metadata.setProperty("project.name", identity)
        metadata.setProperty("module.version", moduleVersion)
        metadata.setProperty("ignition.target", ignitionTarget)
        metadata.setProperty("framework.only", frameworkOnly.toString())
        metadata.setProperty("git.branch", branch)
        metadata.setProperty("git.commit", providers.gradleProperty("gitCommit").getOrElse("unknown"))
        if (!frameworkOnly) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(bundledProject.get().archiveFile.get().asFile.readBytes())
                .joinToString("") { "%02x".format(it) }
            metadata.setProperty("project.sha256", digest)
        }
        val destination = layout.buildDirectory.file("bundle-metadata/project-module.properties").get().asFile
        destination.parentFile.mkdirs()
        destination.outputStream().use { metadata.store(it, "Generated module identity") }
    }
}

extra["frameworkOnly"] = frameworkOnly
extra["bundledProject"] = bundledProject
extra["generateBundleMetadata"] = generateBundleMetadata

tasks.register("verifyIdentity") {
    doLast {
        val samples = listOf("project/foo", "project-foo", "PROJECT/foo", "a".repeat(200), "a".repeat(201))
        check(samples.map(::identityFor).distinct().size == samples.size)
        samples.forEach { check(identityFor(it).matches(Regex("[a-z][a-z0-9_]{0,63}"))) }
        check(identityFor("project/foo") == identityFor("project/foo"))
        check(runCatching { identityFor("HEAD") }.isFailure)
        check(runCatching { identityFor("") }.isFailure)
        logger.lifecycle("Verified branch identities; current module: $moduleId")
    }
}

tasks.named("check") { dependsOn("verifyIdentity", ":gateway:check") }

val signingPropertiesFile = providers.gradleProperty("signingProperties").getOrElse("/run/secrets/signing_properties")
val signingKeystore = providers.gradleProperty("signingKeystore").getOrElse("/run/secrets/keystore")
val signingSettings = providers.provider {
    val source = file(signingPropertiesFile)
    require(source.isFile) { "Signing requires an external properties file: $signingPropertiesFile" }
    Properties().apply { source.inputStream().use { load(it) } }
}
fun signingSetting(key: String) = signingSettings.map {
    it.getProperty("ignition.signing.$key")
        ?: throw GradleException("Missing signing setting: ignition.signing.$key")
}
val signingCertificate = layout.buildDirectory.file("signing/signer.cer")
val exportSigningCertificate = tasks.register("exportSigningCertificate") {
    doNotTrackState("Uses external signing secrets")
    doLast {
        val password = signingSetting("keystorePassword").get().toCharArray()
        try {
            val keystore = KeyStore.getInstance(file(signingKeystore), password)
            val certificate = keystore.getCertificate(signingSetting("certAlias").get())
                ?: throw GradleException("The configured signing alias has no certificate")
            val destination = signingCertificate.get().asFile
            destination.parentFile.mkdirs()
            destination.writeBytes(certificate.encoded)
        } finally {
            password.fill('\u0000')
        }
    }
}

tasks.withType<io.ia.sdk.gradle.modl.task.SignModule>().configureEach {
    dependsOn(exportSigningCertificate)
    doNotTrackState("Module signing must run with current external secrets")
    keystorePath.set(signingKeystore)
    keystorePw.set(signingSetting("keystorePassword"))
    certFilePath.set(signingCertificate.map { it.asFile.absolutePath })
    alias.set(signingSetting("certAlias"))
    certPw.set(signingSettings.map {
        it.getProperty("ignition.signing.certPassword") ?: it.getProperty("ignition.signing.keystorePassword")
            ?: throw GradleException("A signing key password is required")
    })
}

tasks.register("exportArtifacts") {
    dependsOn("build")
    doLast {
        val module = layout.buildDirectory.file("$artifactName.modl").get().asFile
        check(module.isFile) { "Expected signed artifact: $module" }
        val destination = layout.projectDirectory.dir("dist").asFile.apply { mkdirs() }
        module.copyTo(destination.resolve(module.name), overwrite = true)
        val checksum = MessageDigest.getInstance("SHA-256").digest(module.readBytes())
            .joinToString("") { "%02x".format(it) }
        destination.resolve("${module.name}.sha256").writeText("$checksum  ${module.name}\n")
        layout.buildDirectory.file("bundle-metadata/project-module.properties").get().asFile
            .copyTo(destination.resolve("$artifactName.properties"), overwrite = true)
        logger.lifecycle("Exported signed module: ${module.name}")
    }
}