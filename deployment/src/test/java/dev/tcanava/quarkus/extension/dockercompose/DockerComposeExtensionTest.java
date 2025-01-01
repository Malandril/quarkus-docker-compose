package dev.tcanava.quarkus.extension.dockercompose;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DockerComposeExtensionTest {

    public static final String DEFAULT_HOSTNAME = "customtesthostname";
    @RegisterExtension
    final static QuarkusDevModeTest test;
    private static final String dockerCompose = """
            services:
              hello:
                image: traefik/whoami
                hostname: "${HELLO_HOSTNAME}"
            """;
    private static final String properties = """
                                                 quarkus.docker-compose.services.hello.container-ports[0]=80
                                                 quarkus.docker-compose.services.hello.compose-env.HOSTNAME=customtesthostname
                                                 """;

    static {

        test = new QuarkusDevModeTest().setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                                                                           .addClass(SimpleRestPassThrough.class)
                                                                           .addAsResource(new StringAsset(dockerCompose),
                                                                                          "docker-compose.yml")
                                                                           .addAsResource(new StringAsset(properties),
                                                                                          "application.properties"));
    }

    @Test
    public void testSimpleDockerCompose() {
        RestAssured.when().get("/hello").then().statusCode(200).body(containsString(DEFAULT_HOSTNAME));
    }

    @Test
    public void testModifyingDockerComposeTriggersRestart() {
        RestAssured.when().get("/hello").then().statusCode(200).body(containsString(DEFAULT_HOSTNAME));
        String newString = "hello quarkus";
        test.modifyResourceFile("docker-compose.yml", s -> s.replace("${HELLO_HOSTNAME}", newString));
        RestAssured.when().get("/hello").then().statusCode(200).body(containsString(newString));
    }

    @Test
    public void testModifyingExtensionConfigTriggersRestart() {
        RestAssured.when().get("/hello").then().statusCode(200).body(containsString(DEFAULT_HOSTNAME));
        String newString = "hello from quarkus config";
        test.modifyResourceFile("application.properties", s -> s.replace(DEFAULT_HOSTNAME, newString));
        RestAssured.when().get("/hello").then().statusCode(200).body(containsString(newString));
    }

    @Test
    public void testModifyingExtensionConfigTriggersRestart2() {
        test.modifyResourceFile("application.properties",
                                s -> s.replace("quarkus.docker-compose.services.hello.compose-env.HOSTNAME=" + DEFAULT_HOSTNAME,
                                               ""));
        String res = RestAssured.when().get("/hello").then().statusCode(200).extract().body().asString();
        Pattern pattern = Pattern.compile("(Hostname: .*)");
        assertFalse(res.contains("customtesthostname"));
        Matcher matcher = pattern.matcher(res);
        assertTrue(matcher.find());
        String hostName = matcher.group(1);
        RestAssured.when().get("/hello").then().statusCode(200).body(containsString(hostName));
    }
}
