plugins {
    id("io.freefair.lombok") version "8.11" apply false
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.freefair.lombok")
    dependencies {
        "implementation"(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    }

    tasks.withType<JavaCompile> {
        options.release = 21
    }

}