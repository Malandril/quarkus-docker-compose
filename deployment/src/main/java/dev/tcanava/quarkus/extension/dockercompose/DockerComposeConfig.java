package dev.tcanava.quarkus.extension.dockercompose;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Map;

@ConfigMapping(prefix = "quarkus.docker-compose")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface DockerComposeConfig {
    @WithDefault("true")
    Boolean enabled();

    @WithDefault("docker-compose.yml")
    String filename();

    Map<String, ServiceConfig> services();

    interface ServiceConfig {
        @WithDefault("true")
        Boolean enabled();

        List<Integer> containerPorts();
    }

}
