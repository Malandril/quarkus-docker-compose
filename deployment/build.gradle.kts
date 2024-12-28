plugins {
    `java-library`
}

dependencies {
    annotationProcessor("io.quarkus:quarkus-extension-processor")
    implementation("io.quarkus:quarkus-arc-deployment")
    implementation("io.quarkus:quarkus-core-deployment")
    implementation("io.quarkus:quarkus-devservices-deployment")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation(project(":runtime"))
    testImplementation("io.quarkus:quarkus-junit5-internal")
}
