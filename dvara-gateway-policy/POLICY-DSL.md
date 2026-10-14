# The policy DSL

A policy is YAML. This file describes version 1 of its grammar. The machine-readable form is
[`src/main/resources/policy-dsl/v1.schema.json`](src/main/resources/policy-dsl/v1.schema.json), a JSON Schema
(draft 2020-12) that ships in the `dvara-gateway-policy` jar at `policy-dsl/v1.schema.json`.

```yaml
version: "1"            # optional; omitted means 1
rules:
  - id: no-unreviewed-models
    description: Only reviewed models in production
    priority: 10          # lower runs first; default 100
    action: DENY          # DENY (default) or WARN_AGENT, case-insensitive
    deny_message: "gpt-4o is not approved"
    conditions:           # every condition given must match
      model:
        denylist: [gpt-4o]
```

## Rules

| Key | Meaning |
|---|---|
| `id` | The rule's name, shown in decisions and audit records. |
| `description` | Free text. |
| `priority` | Order of evaluation, lowest first. Default 100. |
| `action` | `DENY` stops the request; `WARN_AGENT` lets it go on with a warning. |
| `deny_message`, `warn_message` | The text the caller sees when the rule fires. |
| `conditions` | The conditions below. A rule has `conditions` or `expression`, not both. |
| `expression` | A CEL expression, in a distribution that compiles expressions. |

## Conditions

| Key | Shape |
|---|---|
| `model` | `allowlist` and/or `denylist` of model names. |
| `max_tokens` | `limit`: the largest `max_tokens` a request may ask for. |
| `tools` | `allowlist` and/or `denylist` of tool names offered in the request. |
| `data_residency` | `allowed_regions`: the regions a request may be served in. |
| `time_of_day` | `timezone`, `deny_hours` (`start`, `end` as `HH:MM`), `deny_days`. |
| `mcp_server`, `mcp_tool` | `allowlist` and/or `denylist`, for MCP tool calls. |
| `mcp_arg` | `rules`: each with `name` and a `pattern`, `denylist` or `allowlist` for that argument. |
| `budget_utilization` | `threshold_pct`: matches at or above this share of the budget used. Default 75. |

Which conditions compile depends on the distribution. A condition the running build cannot compile is refused
when the policy is saved or loaded, never ignored.

## Keys reserved for later versions

`extension`, `extensions`, `protocol_version`, `retention`, `retention_floor`, `residency_mode`, `fail_mode`,
`eval_gate`, `semantic` and `intent` have no meaning in version 1. A policy that uses one is refused with a message
that says the key is reserved, so that no policy written today can use one in a way a later version reads
differently.

## Versions and migration

- The grammar is versioned by the top-level `version`. A gateway reads the versions it names and refuses any other,
  with a message naming the version it reads. Version 1 accepts `"1"`, `"1.0"`, or no `version`.
- Within a version, the grammar only grows: a new optional key may be added, an existing key never changes meaning
  and is never removed.
- A change that would alter what an existing policy means is a new version. A gateway that introduces version N
  keeps reading version N-1 for at least one minor release, and the release notes say how to rewrite a policy.
- Unknown keys are always refused, never ignored, so a typo cannot silently weaken a rule.
