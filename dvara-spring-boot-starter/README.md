# DVARA Spring Boot Starter

Add DVARA's governance engines to a Spring Boot application. One dependency, and the
auto-configuration does the rest.

```xml
<dependency>
    <groupId>com.dvarahq</groupId>
    <artifactId>dvara-spring-boot-starter</artifactId>
    <version>1.8.1</version>
</dependency>
```

Snapshot versions are not published to Maven Central. Run `./mvnw install` at the root of this
repository and this one lands in your local Maven repository; a tagged release is published to
Central.

There is no component scan to add and no configuration class to import. A plain
`@SpringBootApplication` gets these beans:

| Bean | What it does |
|---|---|
| `PolicyEngine` | compiles and evaluates the rule DSL |
| `PiiDetector`, `PiiEnforcer` | structured PII detection, with BLOCK, REDACT or LOG |
| `GuardrailDetector` | prompt-injection and content checks |
| `RateLimiter` | per key, per minute, in process. Off until `dvara.llm-gateway.rate-limit.enabled` is `true` |
| `AuditWriter` | an HMAC-chained log on local disk once `dvara.audit.file.path` is set; check it with `FileAuditChainVerifier` |
| `LlmProvider`, one per configured key | OpenAI, Anthropic, Gemini, Bedrock, Ollama and nine more |
| `RoutingStrategy`, `RoutingEngine` | model-prefix, round-robin, weighted and canary routing |

Configure it the way you configure anything in Spring Boot: `application.yml` or environment
variables. A `gateway.yaml` in the working directory, or at the path in `DVARA_CONFIG_FILE`, works
too.

Those four routing strategies are the ones this build serves. A route that asks for another one is
refused by name at startup.

## Serving the `/v1` endpoints

This starter maps no URLs. It is for governing the calls your own application makes: evaluate a
policy, redact PII, run a guardrail, rate-limit, write an audit record.

To serve the OpenAI-compatible API from your application as well, use
[`dvara-spring-boot-starter-gateway`](../dvara-spring-boot-starter-gateway/README.md) instead. It
is this starter plus the request pipeline, and it claims `/v1/**`. If you want the API without an
application to put it in, run the gateway as its own process: one jar and one YAML file.

## The policy module

An application that serves the API cannot start without the policy module, because the request
pipeline needs `PolicyEngine`, `PiiEnforcer`, `GuardrailEnforcer` and the other policy services. An
application that only wants the provider clients can leave it out:

```xml
<exclusions>
    <exclusion>
        <groupId>com.dvarahq</groupId>
        <artifactId>dvara-gateway-policy</artifactId>
    </exclusion>
</exclusions>
```

## Licence

Apache License 2.0. See [LICENSE](../LICENSE).
