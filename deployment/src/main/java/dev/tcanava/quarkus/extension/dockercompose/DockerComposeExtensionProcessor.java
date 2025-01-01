package dev.tcanava.quarkus.extension.dockercompose;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.runtime.configuration.ConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.DockerHealthcheckWaitStrategy;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;

@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = GlobalDevServicesConfig.Enabled.class)
public class DockerComposeExtensionProcessor {

    public static final Pattern NON_WORD = Pattern.compile("\\W+");
    private static final Logger log = LoggerFactory.getLogger(DockerComposeExtensionProcessor.class);
    private static final String FEATURE = "docker-compose";
    private static volatile Map<String, RunningDevService> runningDevServices;
    private final DockerComposeConfig extensionConfig;

    public DockerComposeExtensionProcessor(DockerComposeConfig extensionConfig) {
        this.extensionConfig = extensionConfig;
    }

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    private void closeServices() {
        if (runningDevServices != null) {
            log.info("Stopping docker compose services");
            for (RunningDevService devService : runningDevServices.values()) {
                try {
                    devService.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @BuildStep
    public void createContainer(CuratedApplicationShutdownBuildItem curatedApplicationShutdownBuildItem,
                                DockerStatusBuildItem dockerStatusBuildItem, LiveReloadBuildItem liveReloadBuildItem,
                                BuildProducer<DevServicesResultBuildItem> devProducer,
                                BuildProducer<HotDeploymentWatchedFileBuildItem> watcherProducer,
                                BuildProducer<ValidationPhaseBuildItem.ValidationErrorBuildItem> errorProducer)
            throws IOException {
        if (!extensionConfig.enabled()) {
            return;
        }
        if (!dockerStatusBuildItem.isContainerRuntimeAvailable()) {
            errorProducer.produce(validationError("Docker isn't working, cannot run docker compose"));
            return;
        }
        Path tmpPath = Files.createTempFile(FEATURE, "quarkus");
        File composeFile = tmpPath.toFile();
        composeFile.deleteOnExit();
        DockerComposeExtensionState currentState;
        try {
            currentState = load(tmpPath);
        } catch (JacksonYAMLParseException e) {
            log.debug("Invalid docker-compose yaml", e);
            errorProducer.produce(validationError("Invalid docker-compose " + e.getMessage()));
            return;
        }
        if (currentState == null) {
            log.warn("Could not find the docker compose file {}", extensionConfig.filename());
            return;
        }
        DockerComposeModel composeYaml = currentState.dockerComposeYaml();
        var url = Thread.currentThread().getContextClassLoader().getResource(extensionConfig.filename());
        if (url != null) {
            watcherProducer.produce(new HotDeploymentWatchedFileBuildItem(url.getPath()));
        }
        List<ValidationPhaseBuildItem.ValidationErrorBuildItem> errors = validateConfig(composeYaml);
        if (!errors.isEmpty()) {
            errorProducer.produce(errors);
            return;
        }

        if (runningDevServices != null) {
            DockerComposeExtensionState oldState = liveReloadBuildItem.getContextObject(DockerComposeExtensionState.class);
            if (extensionConfig.alwaysRestart() || !currentState.equals(oldState)) {
                closeServices();
            } else {
                for (var serviceEntry : runningDevServices.entrySet()) {
                    devProducer.produce(serviceEntry.getValue().toBuildItem());
                }
                return;
            }
        }

        ComposeContainer container = new ComposeContainer(composeFile);
        List<String> services = new ArrayList<>();
        for (var service : composeYaml.services().entrySet()) {
            if (!extensionConfig.services().containsKey(service.getKey())) {
                services.add(service.getKey());
            } else if (extensionConfig.services().get(service.getKey()).enabled()) {
                services.add(service.getKey());
            }
        }

        configureContainers(container, services);
        curatedApplicationShutdownBuildItem.addCloseTask(this::closeServices, true);
        log.info("Starting docker compose services: {}", String.join(",", services));
        container.start();
        runningDevServices = produceDevServices(devProducer, services, container);
        liveReloadBuildItem.setContextObject(DockerComposeExtensionState.class, currentState);
    }

    private void configureContainers(ComposeContainer container, List<String> services) {
        container.withServices(services.toArray(String[]::new));
        for (var service : services) {
            var serviceConfig = extensionConfig.services().get(service);
            if (serviceConfig == null) {
                continue;
            }
            WaitAllStrategy waitAll = new WaitAllStrategy().withStartupTimeout(serviceConfig.waitTimeout());
            if (serviceConfig.containerPorts().isPresent()) {
                for (Integer port : serviceConfig.containerPorts().get()) {
                    container.withExposedService(service, port);
                }
            }
            for (var env : serviceConfig.composeEnv().entrySet()) {
                String prefix = NON_WORD.matcher(service.toUpperCase()).replaceAll("_");
                container.withEnv(prefix + "_" + env.getKey(), env.getValue());
            }
            if (serviceConfig.waitForLog().isPresent()) {
                waitAll.withStrategy(new LogMessageWaitStrategy().withRegEx(serviceConfig.waitForLog().get()));
            }
            if (serviceConfig.waitForDockerHealthcheck()) {
                waitAll.withStrategy(new DockerHealthcheckWaitStrategy());
            }
            container.waitingFor(service, waitAll);
        }
    }

    private Map<String, RunningDevService> produceDevServices(BuildProducer<DevServicesResultBuildItem> devProducer,
                                                              List<String> serviceNames,
                                                              ComposeContainer container) {
        var devServices = new HashMap<String, RunningDevService>();
        for (var service : serviceNames) {
            Map<String, String> configOverrides = new HashMap<>();
            var serviceConfig = extensionConfig.services().get(service);
            if (serviceConfig != null) {
                if (serviceConfig.containerPorts().isPresent()) {
                    for (Integer port : serviceConfig.containerPorts().get()) {
                        configOverrides.put("docker-compose.service.%s.host".formatted(service),
                                            container.getServiceHost(service, port));
                        Integer servicePort = container.getServicePort(service, port);
                        configOverrides.put("docker-compose.service.%s.port".formatted(service),
                                            String.valueOf(servicePort));
                    }
                }
            }
            String containerId = container.getContainerByServiceName(service)
                                          .map(ContainerState::getContainerId)
                                          .orElse("");
            RunningDevService devService = new RunningDevService(FEATURE + " " + service,
                                                                 "Docker compose service " + service,
                                                                 containerId,
                                                                 container::close,
                                                                 configOverrides);
            devServices.put(service, devService);
            devProducer.produce(devService.toBuildItem());
        }
        return devServices;
    }

    /**
     * Validate that all the configured services exist in the compose file
     * @param composeYaml The parsed compose file
     * @return A list of errors, empty if none are found
     */
    private List<ValidationPhaseBuildItem.ValidationErrorBuildItem> validateConfig(DockerComposeModel composeYaml) {
        List<ValidationPhaseBuildItem.ValidationErrorBuildItem> errors = new ArrayList<>();
        for (var service : extensionConfig.services().entrySet()) {
            if (!composeYaml.services().containsKey(service.getKey())) {
                errors.add(validationError("The service %s is missing from the docker-compose file, cannot configure it".formatted(
                        service.getKey())));
            }
        }
        return errors;
    }

    private static ValidationPhaseBuildItem.@NotNull ValidationErrorBuildItem validationError(String service) {
        return new ValidationPhaseBuildItem.ValidationErrorBuildItem(new ConfigurationException(service));
    }

    /**
     * Load a docker compose file from the resources, and copies it to the path.
     * Needed because test container docker-compose needs a file location.
     * @param tmpFile a path the docker-compose will be copied to
     * @return The all the extension state that can be used to check for difference between live reloads
     */
    private DockerComposeExtensionState load(Path tmpFile) throws IOException {
        try (var is = Thread.currentThread().getContextClassLoader().getResourceAsStream(extensionConfig.filename())) {
            if (is == null) {
                return null;
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            DockerComposeModel composeYaml = mapper.readValue(is, DockerComposeModel.class);
            // Reset to write to temporary file
            is.reset();
            try (OutputStream os = Files.newOutputStream(tmpFile)) {
                is.transferTo(os);
            }
            return new DockerComposeExtensionState(extensionConfig, composeYaml);
        }
    }

    record DockerComposeExtensionState(DockerComposeConfig oldExtensionConfig, DockerComposeModel dockerComposeYaml) {

    }

    record DockerComposeModel(Map<String, Object> services) {
    }
}