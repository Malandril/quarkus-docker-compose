package io.quarkiverse.quarkus.docker.compose.2.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class DockerCompose2Processor {

    private static final String FEATURE = "docker-compose-2";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
