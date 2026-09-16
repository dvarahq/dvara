# DVARA Spring Boot Starter: Gateway

Serve the OpenAI-compatible gateway API from inside your own Spring Boot application. Add one
dependency and the auto-configuration does the rest.

```xml
<dependency>
    <groupId>com.dvarahq</groupId>
    <artifactId>dvara-spring-boot-starter-gateway</artifactId>
    <version>1.8.0-SNAPSHOT</version>
</dependency>
```

Snapshot versions are not published to Maven Central. Run `./mvnw install` at the root of this
repository and this one lands in your local Maven repository; a tagged release is published to
Central.

This starter is [`dvara-spring-boot-starter`](../dvara-spring-boot-starter/README.md) plus the
request pipeline. Your application keeps everything the first starter gives it and also serves:

| Path | What it does |
|---|---|
| `POST /v1/chat/completions` | chat, streaming included |
| `POST /v1/responses` | the Responses API |
| `POST /v1/completions` | legacy text completion |
| `POST /v1/embeddings` | embeddings |
| `GET /v1/models` | the models your configured providers offer |
| `POST /v1/batches`, `POST /v1/files` | the OpenAI Batch API |

Every one of them runs the full governance pipeline: policy, PII, guardrails, rate limiting and
audit, before the call reaches a provider. Batches are tracked in memory, so a restart forgets
them; the application logs that at startup.

Configure it the way you configure the standalone gateway: `application.yml`, environment
variables, or a `gateway.yaml` in the working directory or at the path in `DVARA_CONFIG_FILE`.

## Which starter do you need?

- **Your application calls the engines itself** and serves no LLM API: use
  `dvara-spring-boot-starter`. It maps no URLs.
- **Your application should also be the gateway** for its clients: use this one.
- **You want the gateway as its own process**: run `dvara-gateway-server`, one jar and one YAML
  file.

## What it adds to your application

It maps `/v1/**`, so if your application already routes `/v1` the two collide. It brings Spring
MVC, Bean Validation, springdoc, the actuator, the Prometheus registry and OpenTelemetry tracing,
because the endpoints need them. The health probes are open; `/actuator/prometheus` answers only
to the key in `DVARA_ACTUATOR_METRICS_API_KEY`, and the other actuator endpoints only to
`DVARA_ACTUATOR_API_KEY`.

## Licence

Apache License 2.0. See [LICENSE](../LICENSE).
