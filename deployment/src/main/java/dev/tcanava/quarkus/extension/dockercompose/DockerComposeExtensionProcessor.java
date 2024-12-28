package dev.tcanava.quarkus.extension.dockercompose;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RuntimeApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.dev.devservices.GlobalDevServicesConfig;
import io.quarkus.runtime.ExecutionMode;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.configuration.ConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.quarkus.deployment.builditem.DevServicesResultBuildItem.*;

@Slf4j
@BuildSteps(onlyIfNot = IsNormal.class, onlyIf = GlobalDevServicesConfig.Enabled.class)
public class DockerComposeExtensionProcessor {

    private static final String FEATURE = "docker-compose";
    private static final int SERVICE_PORT = 80;
    private final DockerComposeConfig extConfig;
    private static volatile Map<String, RunningDevService> devServices;

    public DockerComposeExtensionProcessor(DockerComposeConfig extConfig) {
        this.extConfig = extConfig;
    }

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    // TODO
    // restart on configuration change either docker or appli
    // add env variable settings which side ?
    // Allow restart always
    // Wait for logs
    @BuildStep
    public void createContainer(BuildProducer<DevServicesResultBuildItem> devProducer,
                                CuratedApplicationShutdownBuildItem curatedApplicationShutdownBuildItem,
                                BuildProducer<ValidationPhaseBuildItem.ValidationErrorBuildItem> errorProducer)
            throws IOException {
        if (devServices != null) {
            for (var serviceEntry : devServices.entrySet()) {
                devProducer.produce(serviceEntry.getValue().toBuildItem());
            }
            return;
        }
        if (!extConfig.enabled()) {
            return;
        }
        Path tmpPath = Files.createTempFile("docker", null);
        File composeFile = tmpPath.toFile();
        composeFile.deleteOnExit();

        ComposeFile composeYaml = load(tmpPath);
        if (composeYaml == null || composeYaml.services == null || composeYaml.services.isEmpty()) {
            log.warn("The docker compose file is empty");
            return;
        }
        List<ValidationPhaseBuildItem.ValidationErrorBuildItem> errors = validateConfig(composeYaml);
        if (!errors.isEmpty()) {
            errorProducer.produce(errors);
            return;
        }
        ComposeContainer container = new ComposeContainer(composeFile);

        List<String> services = new ArrayList<>();
        for (var service : composeYaml.services().entrySet()) {
            if (!extConfig.services().containsKey(service.getKey())) {
                services.add(service.getKey());
                continue;
            }
            if (extConfig.services().get(service.getKey()).enabled()) {
                services.add(service.getKey());
            }

        }

        container.withServices(services.toArray(String[]::new));
        for (var service : services) {
            var serviceConfig = extConfig.services().get(service);
            if (serviceConfig == null) {
                continue;
            }
            for (Integer port : serviceConfig.containerPorts()) {
                container.withExposedService(service, port);
            }
        }
        container.start();
        curatedApplicationShutdownBuildItem.addCloseTask(container::close, true);
        produceDevServices(devProducer, services, container);
    }


    private void produceDevServices(BuildProducer<DevServicesResultBuildItem> devProducer,
                                    List<String> services,
                                    ComposeContainer container) {
        devServices = new HashMap<>();
        for (var service : services) {
            Map<String, String> configOverrides = new HashMap<>();
            var serviceConfig = extConfig.services().get(service);
            if (serviceConfig != null) {
                for (Integer port : serviceConfig.containerPorts()) {
                    configOverrides.put("docker-compose.service.%s.host".formatted(service),
                                        container.getServiceHost(service, port));
                    configOverrides.put("docker-compose.service.%s.port".formatted(service),
                                        String.valueOf(container.getServicePort(service, port)));
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
    }

    private List<ValidationPhaseBuildItem.ValidationErrorBuildItem> validateConfig(ComposeFile composeYaml) {
        List<ValidationPhaseBuildItem.ValidationErrorBuildItem> errors = new ArrayList<>();
        for (var service : extConfig.services().entrySet()) {
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

    private ComposeFile load(Path path) throws IOException {
        ComposeFile composeYaml;
        try (var is = Thread.currentThread()
                            .getContextClassLoader()
                            .getResourceAsStream(extConfig.filename())) {
            if (is == null) {
                log.info("No docker compose file found");
                return null;
            }
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            composeYaml = mapper.readValue(is, ComposeFile.class);
            is.reset();
            try (OutputStream os = Files.newOutputStream(path)) {
                is.transferTo(os);
            }
        }
        return composeYaml;
    }

    record ComposeFile(Map<String, Service> services) {
        record Service(String image, List<Object> ports) {
        }
    }
}