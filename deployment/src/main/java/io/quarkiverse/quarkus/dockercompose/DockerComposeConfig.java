package io.quarkiverse.quarkus.dockercompose;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.docker-compose")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface DockerComposeConfig {
    /**
     * Enables the quarkus docker-compose devservices
     */
    @WithDefault("true")
    Boolean enabled();

    /**
     * Always restart the docker-compose services between each application reload
     */
    @WithDefault("false")
    Boolean alwaysRestart();

    /**
     * The docker-compose file to run, it should be located in your resources folder.
     */
    @WithDefault("docker-compose.yml")
    String filename();

    /**
     * A map of docker compose services to configure. The keys of the map must match a docker-compose service name
     */
    Map<String, ServiceConfig> services();

    interface ServiceConfig {

        // @formatter:off
        /**
         * Docker compose environment variable to set this can then be used in the docker-compose.
         * <br/>
         * The variable will be prefixed by the service's name in uppercase, and with all non word characters replaced
         * by _.
         * For example:
         *
         * [,property]
         * .application.properties
         * ----
         * quarkus.docker-compose.services.hello-svc.compose-env.HOSTNAME=customhostname
         * ----
         *
         * Will set the compose variable: `HELLO_SVC_PASSWORD=mypwd` that can be used in the docker-compose file
         *
         * [,yaml]
         * .docker-compose.yml
         * ----
         * services:
         *   hello-svc:
         *     image: hello-world
         *     environment:
         *       PASSWORD: "${HELLO_SVC_PASSWORD}"
         * ----
         *
         * @asciidoclet
         */
        // @formatter:on
        Map<String, String> composeEnv();

        /**
         * The container ports that will be exposed to the host, and that can be joined by the quarkus server.
         *
         * Each port will be exposed as a random port on the host, and two config properties will be exposed:
         *
         * [,property]
         * ----
         * docker-compose.service.service-name.host
         * docker-compose.service.service-name.port
         * ----
         *
         * @asciidoclet
         */
        Optional<List<Integer>> containerPorts();

        /**
         * True if the service should be started by docker compose
         */
        @WithDefault("true")
        Boolean enabled();

        /**
         * True if docker compose should wait for the container to be healthy before starting quarkus.
         */
        @WithDefault("false")
        Boolean waitForDockerHealthcheck();

        /**
         * Set a log to wait for, before starting the quarkus application.
         */
        Optional<String> waitForLog();

        /**
         * The timeout to wait for a service before an error is thrown
         */
        @WithDefault("1m")
        Duration waitTimeout();
    }

}
