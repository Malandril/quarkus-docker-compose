package io.quarkiverse.quarkus.docker.compose.2.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class DockerCompose2ResourceTest {

    @Test
    public void testHelloEndpoint() {
        given()
                .when().get("/docker-compose-2")
                .then()
                .statusCode(200)
                .body(is("Hello docker-compose-2"));
    }
}
