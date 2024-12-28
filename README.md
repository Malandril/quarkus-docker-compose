<div align="center">

# Quarkus - Docker Compose
</div>
<br>

## Description


Quarkus  extension for setting up local devservices from a [Docker Compose](https://github.com/docker/compose) file.

This extension starts the services from a `docker-compose.yml` file as `Dev Services`, thus it is designed to run in
`dev` and `test` mode only!


## Quarkus Docker Compose usage


The extension expects a docker compose file in the resources folder named `docker-compose.yml`.

In this file you can define the services that you want to be started in `dev` and `test`

If you want to expose the service to your quarkus application you can add a port to your quarkus configuration,
this will expose the service to a random port that can be reached by the quarkus application.

It will then expose two
configuration properties with the `host` and `port` that can be used to reach the service.

### Example
For example for accessing the following service from your application:
```yaml
services:
  myservice:
    image: traefik/whoami
```

You can define the port the container listens to:

```properties
quarkus.docker-compose.services."myservice".container-ports=80
```

It will expose the following properties which can be used to reach the server:
```properties
docker-compose.services.myservice.port
docker-compose.services.myservice.host
```