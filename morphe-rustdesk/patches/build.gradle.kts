import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.OutputStream
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.21"
}

version = "1.2.0"

repositories {
    mavenCentral()
}

kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val desktopJar = registerVerifiedDownload(
    taskName = "downloadMorpheDesktop",
    filename = "morphe-desktop-2.0.0-all.jar",
    url = "https://github.com/MorpheApp/morphe-desktop/releases/download/v2.0.0/morphe-desktop-2.0.0-all.jar",
    sha256 = "855dd2d6fa5ed997a5884a44f8807c9b5bc95e69b5ebd58dfd7e8b897c86e2d7",
)

val d8Jar = registerVerifiedDownload(
    taskName = "downloadD8",
    filename = "r8-8.13.17.jar",
    url = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.13.17/r8-8.13.17.jar",
    sha256 = "d31fd0dc751d48740009cdd9a485126acb1d0d14c59b9f05579479940f4adf74",
)

val androidPlatform = registerVerifiedDownload(
    taskName = "downloadAndroidPlatform",
    filename = "platform-36_r02.zip",
    url = "https://dl.google.com/android/repository/platform-36_r02.zip",
    sha256 = "37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e",
)

val androidSdk = layout.buildDirectory.dir("tools/android-sdk")
val extractAndroidJar = tasks.register<Sync>("extractAndroidJar") {
    dependsOn(androidPlatform)
    from(androidPlatform.map { zipTree(it.outputs.files.singleFile) }) {
        include("android-36/android.jar")
    }
    into(androidSdk)
}

val androidJar = androidSdk.map { it.file("android-36/android.jar") }
val extensionClasses = layout.buildDirectory.dir("classes/extension")
val compileExtensionJava = tasks.register<JavaCompile>("compileExtensionJava") {
    dependsOn(extractAndroidJar)
    // Both trees are merged into one dex: the bytecode patches reference them from the same
    // extension archive.
    source(
        rootProject.fileTree("backup/src/main/java") { include("**/*.java") },
        rootProject.fileTree("ime/src/main/java") { include("**/*.java") },
    )
    classpath = files(androidJar)
    destinationDirectory.set(extensionClasses)
    options.release.set(8)
    options.encoding = "UTF-8"
}

val extensionDex = layout.buildDirectory.file("dex/extension.zip")
val compileExtensionDex = tasks.register<JavaExec>("compileExtensionDex") {
    dependsOn(compileExtensionJava)
    classpath = files(d8Jar)
    mainClass.set("com.android.tools.r8.D8")
    val classes = fileTree(extensionClasses) { include("**/*.class") }
    inputs.files(classes, androidJar)
    outputs.file(extensionDex)
    doFirst {
        val output = extensionDex.get().asFile
        output.parentFile.mkdirs()
        args(
            "--release", "--min-api", "22",
            "--lib", androidJar.get().asFile.absolutePath,
            "--output", output.absolutePath,
        )
        args(classes.files.sortedBy(File::getPath))
    }
}

dependencies {
    // The runtime already provides the Patcher API. Compile against the official CLI release to build without a GitHub Packages token.
    compileOnly(files(desktopJar))
}

val dexFile = layout.buildDirectory.file("dex/classes.zip")
val compilePatchDex = tasks.register<JavaExec>("compilePatchDex") {
    dependsOn(tasks.compileKotlin)
    classpath = files(d8Jar)
    mainClass.set("com.android.tools.r8.D8")
    val patchClasses = sourceSets.main.get().output.classesDirs
        .asFileTree.matching { include("**/*.class") }
    inputs.files(patchClasses, desktopJar)
    outputs.file(dexFile)
    doFirst {
        val output = dexFile.get().asFile
        output.parentFile.mkdirs()
        args(
            "--release", "--min-api", "26",
            "--classpath", desktopJar.get().outputs.files.singleFile.absolutePath,
            "--output", output.absolutePath,
        )
        args(patchClasses.files.sortedBy(File::getPath))
    }
}

tasks.jar {
    dependsOn(compilePatchDex, compileExtensionDex)
    archiveExtension.set("mpp")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(dexFile.map { zipTree(it) }) { include("classes.dex") }
    from(extensionDex.map { zipTree(it) }) {
        include("classes.dex")
        into("extensions")
        rename { "rustdesk-extension.mpe" }
    }
    manifest {
        attributes(
            "Name" to "RustDesk Patches",
            "Description" to "Japanese soft keyboard and settings backup patches for RustDesk",
            "Version" to project.version,
            "Patcher-Version" to "2.2.0",
            "License" to "AGPL-3.0-only",
        )
    }
}

tasks.register("buildAndroid") {
    group = "build"
    description = "Builds the .mpp patch bundle for Morphe Manager and Desktop."
    dependsOn(tasks.jar)
}

fun registerVerifiedDownload(taskName: String, filename: String, url: String, sha256: String): TaskProvider<Task> {
    val destination = layout.buildDirectory.file("tools/$filename")
    return tasks.register(taskName) {
        inputs.property("url", url)
        inputs.property("sha256", sha256)
        outputs.file(destination)
        doLast {
            val output = destination.get().asFile
            if (output.isFile) {
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(output.inputStream(), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
                if (digest.digest().joinToString("") { "%02x".format(it) } == sha256) return@doLast
            }
            output.parentFile.mkdirs()
            val temporary = File.createTempFile(filename, ".part", output.parentFile)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val connection = URI(url).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }
                connection.getInputStream().use { input ->
                    temporary.outputStream().use { stream ->
                        DigestInputStream(input, digest).copyTo(stream)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == sha256) { "SHA-256 mismatch for $filename" }
                temporary.copyTo(output, overwrite = true)
            } finally {
                temporary.delete()
            }
        }
    }
}
