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
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.runtime.configuration.ConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;

@Slf4j
@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = GlobalDevServicesConfig.Enabled.class)
public class DockerComposeExtensionProcessor {

    private static final String FEATURE = "docker-compose";
    private final DockerComposeConfig extensionConfig;
    private static volatile Map<String, RunningDevService> runningDevServices;
    private static volatile int dockerComposeYamlHash = 0;
    private static volatile int oldDockerComposeYamlHash = 0;
    private static volatile DockerComposeConfig oldExtensionConfig;

    public DockerComposeExtensionProcessor(DockerComposeConfig extensionConfig) {
        this.extensionConfig = extensionConfig;
    }

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    public void createContainer(CuratedApplicationShutdownBuildItem curatedApplicationShutdownBuildItem,
                                DockerStatusBuildItem dockerStatusBuildItem,
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
        DockerCompose compose;
        try {
            compose = load(tmpPath);
        } catch (JacksonYAMLParseException e) {
            log.debug("Invalid docker-compose yaml", e);
            errorProducer.produce(validationError("Invalid docker-compose " + e.getMessage()));
            return;
        }
        if (compose == null || compose.services == null || compose.services.isEmpty()) {
            log.warn("Could not find the docker compose file, or no services where found");
            return;
        }
        var url = Thread.currentThread().getContextClassLoader().getResource(extensionConfig.filename());
        if (url != null) {
            watcherProducer.produce(new HotDeploymentWatchedFileBuildItem(url.getPath()));
        }
        List<ValidationPhaseBuildItem.ValidationErrorBuildItem> errors = validateConfig(compose);
        if (!errors.isEmpty()) {
            errorProducer.produce(errors);
            return;
        }

        if (runningDevServices != null) {
            if (extensionConfig.alwaysRestart() || oldDockerComposeYamlHash != dockerComposeYamlHash || !extensionConfig.equals(
                    oldExtensionConfig)) {
                for (RunningDevService devService : runningDevServices.values()) {
                    devService.close();
                }
            } else {
                for (var serviceEntry : runningDevServices.entrySet()) {
                    devProducer.produce(serviceEntry.getValue().toBuildItem());
                }
                return;
            }
        }

        ComposeContainer container = new ComposeContainer(composeFile);
        List<String> services = new ArrayList<>();
        for (var service : compose.services().entrySet()) {
            if (!extensionConfig.services().containsKey(service.getKey())) {
                services.add(service.getKey());
            } else if (extensionConfig.services().get(service.getKey()).enabled()) {
                services.add(service.getKey());
            }
        }

        configureContainers(container, services);
        curatedApplicationShutdownBuildItem.addCloseTask(container::close, true);
        container.start();

        runningDevServices = produceDevServices(devProducer, services, container);
        oldExtensionConfig = extensionConfig;
        oldDockerComposeYamlHash = dockerComposeYamlHash;
    }

    private void configureContainers(ComposeContainer container, List<String> services) {
        container.withServices(services.toArray(String[]::new));
        for (var service : services) {
            var serviceConfig = extensionConfig.services().get(service);
            if (serviceConfig == null) {
                continue;
            }
            WaitAllStrategy waitAll = new WaitAllStrategy().withStartupTimeout(serviceConfig.waitTimeout());
            for (Integer port : serviceConfig.containerPorts()) {
                container.withExposedService(service, port);
            }
            for (var env : serviceConfig.containerEnv().entrySet()) {
                container.withEnv(service + "_" + env.getKey(), env.getValue());
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
                for (Integer port : serviceConfig.containerPorts()) {
                    configOverrides.put("docker-compose.service.%s.host".formatted(service),
                                        container.getServiceHost(service, port));
                    Integer servicePort = container.getServicePort(service, port);
                    configOverrides.put("docker-compose.service.%s.port".formatted(service),
                                        String.valueOf(servicePort));
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

    private List<ValidationPhaseBuildItem.ValidationErrorBuildItem> validateConfig(DockerCompose composeYaml) {
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

    private DockerCompose load(Path path) throws IOException {
        DockerCompose composeYaml;
        try (var is = Thread.currentThread().getContextClassLoader().getResourceAsStream(extensionConfig.filename())) {
            if (is == null) {
                log.info("No docker compose file found");
                return null;
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            composeYaml = mapper.readValue(is, DockerCompose.class);
            is.reset();
            try (OutputStream os = Files.newOutputStream(path)) {
                is.transferTo(os);
            }
            is.reset();
            dockerComposeYamlHash = Arrays.hashCode(is.readAllBytes());
        }
        return composeYaml;
    }

    record DockerCompose(Map<String, Service> services) {
        record Service(String image, List<Object> ports) {
        }
    }
}