# DVARA Spring Boot Starter: Gateway

Serve the OpenAI-compatible gateway API from inside your own Spring Boot application. Add one
dependency and the auto-configuration does the rest.

```xml
<dependency>
    <groupId>com.dvarahq</groupId>
    <artifactId>dvara-spring-boot-starter-gateway</artifactId>
    <version>1.8.2</version>
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
| `GET /v1/models` | the models your configured providers offer; a `ModelListFilter` bean narrows it per caller |
| `POST /v1/batches`, `POST /v1/files` | the OpenAI Batch API |

Every one of them except the batch paths runs the full governance pipeline: policy, PII,
guardrails, rate limiting and audit, before the call reaches a provider. A batch input file is
PII-scanned as a whole, and its models are checked by any `BatchModelCheck` bean your application
registers, at upload and again at submit; its lines do not run the rest of the pipeline. Batches are
tracked in memory, so a restart forgets them; the application logs that at startup.

Configure it the way you configure the standalone gateway: `application.yml`, environment
variables, or a `gateway.yaml` in the working directory or at the path in `DVARA_CONFIG_FILE`.

## Which starter do you need?

- **Your application calls the engines itself** and serves no LLM API: use
  `dvara-spring-boot-starter`. It maps no URLs.
- **Your application should also be the gateway** for its clients: use this one.
- **You want the gateway as its own process**: run `dvara-gateway-server`, one jar and one YAML
  file.

## What it adds to your application

It maps `/v1/**`, so if your application already routes `/v1` the two collide. Every request under
`/v1` carries a gateway API key, in your application as in the standalone gateway: a request
without one is refused with `401`, and there is no setting that serves it. Mint keys with the
gateway's `--generate-key` and put their hashes in a `gateway.yaml` your application points at
with `DVARA_CONFIG_FILE`. It brings Spring MVC, Bean Validation, springdoc, the actuator, the
Prometheus registry and OpenTelemetry tracing, because the endpoints need them. The health probes are open; `/actuator/prometheus` answers only
to the key in `DVARA_ACTUATOR_METRICS_API_KEY`, and the other actuator endpoints only to
`DVARA_ACTUATOR_API_KEY`.

## Ending a stream that is already running

A request filter can refuse a call before it reaches a provider. To end a streamed answer that
has already started, for example when an agent's session is ended mid-answer, register a
`StreamStopCheck` bean (from `dvara-gateway-core`):

```java
@Bean
StreamStopCheck endedSessions(EndedSessions ended) {
    return stream -> ended.contains(stream.sessionId())
            ? Optional.of(new StreamStopCheck.Stop(403, "session_ended", null, "The session was ended."))
            : Optional.empty();
}
```

The gateway asks every check once per chunk, before writing it, on `/v1/chat/completions`,
`/v1/responses` and `/v1/messages`. Keep a check cheap: read memory, never call the network.
When a check answers with a stop:

- the chunk in hand is not sent;
- the client gets one last error event in its API's shape: `data: {"error":{...}}` with no
  `[DONE]` on chat completions, `response.failed` on the Responses API, an `error` event on the
  Messages API;
- the provider connection is closed;
- the access log, the metrics and the audit record show the stop's code, and the tokens already
  sent are metered and billed.

A check that throws is logged and not asked again for that stream; it never ends one. With no
check registered, streams run exactly as before. Where the streaming guard holds an answer back
until the provider has finished, the first check runs when the guard starts to deliver.

## Licence

Apache License 2.0. See [LICENSE](../LICENSE).
