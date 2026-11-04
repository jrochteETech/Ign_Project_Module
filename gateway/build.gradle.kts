plugins {
    `java-library`
}

val sdkVersion = rootProject.extra["sdkVersion"].toString()
val ignitionTarget = rootProject.extra["ignitionTarget"].toString()
val sdkRuntime by configurations.creating

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

sourceSets.main {
    java.srcDir("src/ignition${ignitionTarget.replace(".", "")}/java")
}

tasks.processResources {
    doFirst { delete(destinationDir) }
    from(rootProject.tasks.named("generateBundleMetadata"))
    if (!rootProject.extra["frameworkOnly"].toString().toBoolean()) {
        from(rootProject.tasks.named<Zip>("bundleProject").flatMap { it.archiveFile })
    }
}

dependencies {
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:$sdkVersion")
    sdkRuntime("com.inductiveautomation.ignitionsdk:gateway-api:$sdkVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

val validateProject = tasks.register<JavaExec>("validateProject") {
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().output + sdkRuntime
    mainClass.set("com.etech.project.ProjectValidator")
    args(ignitionTarget)
}

tasks.named("check") { dependsOn(validateProject) }
tasks.jar { dependsOn(validateProject) }