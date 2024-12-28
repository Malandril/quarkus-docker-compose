package io.quarkiverse.quarkus.dockercompose;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.config.ConfigProvider;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.http.HttpClientRequest;
import io.vertx.mutiny.core.http.HttpClientResponse;

@Path("/")
public class SimpleRestPassThrough {
    @Inject
    Vertx vertx;

    @GET
    @Path("/{service}")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<Buffer> res(@PathParam("service") String service) {
        String host = ConfigProvider.getConfig()
                .getValue("docker-compose.service.\"%s\".host".formatted(service), String.class);
        int port = ConfigProvider.getConfig()
                .getValue("docker-compose.service.\"%s\".port".formatted(service), Integer.class);
        return vertx.createHttpClient()
                .request(HttpMethod.GET, port, host, "/")
                .onItem()
                .transformToUni(HttpClientRequest::connect)
                .onItem()
                .transformToUni(HttpClientResponse::body);
    }
}
