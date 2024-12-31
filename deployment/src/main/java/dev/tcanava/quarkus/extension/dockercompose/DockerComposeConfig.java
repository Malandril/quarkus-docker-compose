package dev.tcanava.quarkus.extension.dockercompose;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "quarkus.docker-compose")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface DockerComposeConfig {
    @WithDefault("true")
    Boolean enabled();

    @WithDefault("false")
    Boolean alwaysRestart();

    @WithDefault("docker-compose.yml")
    String filename();

    Map<String, ServiceConfig> services();

    interface ServiceConfig {
        Map<String,String> containerEnv();

        List<Integer> containerPorts();

        @WithDefault("true")
        Boolean enabled();

        @WithDefault("false")
        Boolean waitForDockerHealthcheck();

        Optional<String> waitForLog();

        @WithDefault("1m")
        Duration waitTimeout();
    }

}
