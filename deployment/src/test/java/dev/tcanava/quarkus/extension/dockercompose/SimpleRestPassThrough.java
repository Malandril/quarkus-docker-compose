package dev.tcanava.quarkus.extension.dockercompose;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.http.HttpClientRequest;
import io.vertx.mutiny.core.http.HttpClientResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/hello")
public class SimpleRestPassThrough {
    @Inject
    Vertx vertx;
    @ConfigProperty(name = "docker-compose.service.hello.host")
    String host;
    @ConfigProperty(name = "docker-compose.service.hello.port")
    int port;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<Buffer> res() {
        return vertx.createHttpClient()
                    .request(HttpMethod.GET, port, host, "/")
                    .onItem()
                    .transformToUni(HttpClientRequest::connect)
                    .onItem()
                    .transformToUni(HttpClientResponse::body);
    }
}
