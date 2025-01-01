val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

subprojects {
    apply(plugin = "java")
    dependencies {
        "implementation"(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    }

    tasks.withType<JavaCompile> {
        options.release = 21
    }

}