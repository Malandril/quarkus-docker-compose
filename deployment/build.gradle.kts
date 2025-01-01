plugins {
    `java-library`
}

dependencies {
    annotationProcessor("io.quarkus:quarkus-extension-processor")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("io.quarkus:quarkus-arc-deployment")
    implementation("io.quarkus:quarkus-core-deployment")
    implementation("io.quarkus:quarkus-devservices-deployment")
    implementation(project(":runtime"))
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-internal")
    testImplementation("io.quarkus:quarkus-rest")
    testImplementation("io.quarkus:quarkus-vertx")
    testImplementation("io.rest-assured:rest-assured")
}
