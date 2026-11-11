# VeyraEval · Java SDK

Run platform-assigned tasks in your own Java Agent. Capture visible tool/model calls and return evidence without adding a Spring dependency.

## Install

Requires JDK 21. Use `PushClient.login` to authenticate and pass the project name as the first argument to `startRun`. See the [push integration guide](https://github.com/1993226871/veyraeval/blob/main/docs/guides/tasks-and-rule-sets.md) for the full flow.

Version `0.1.1` is available from Maven Central:

```xml
<dependency>
  <groupId>io.github.1993226871</groupId>
  <artifactId>veyraeval-sdk</artifactId>
  <version>0.1.1</version>
</dependency>
```

```kotlin
repositories { mavenCentral() }
dependencies { implementation("io.github.1993226871:veyraeval-sdk:0.1.1") }
```

The protocol dependency and parent POM are also published. A fresh-cache independent Maven consumer has compiled and executed successfully. Runtime dependencies include Jackson and SQLite JDBC3.53.4.0; keep the SDK spool directory private to the Worker account.

## Build from source

Requires JDK 21. Use `PushClient.login` to authenticate and pass the project name as the first argument to `startRun`. See the [push integration guide](https://github.com/1993226871/veyraeval/blob/main/docs/guides/tasks-and-rule-sets.md) for the full flow.

 From the repository root:

```bash
./mvnw -pl sdks/java -am install
```

This installs the source-built SDK, its protocol dependency and parent POM into your local Maven repository for development. Regular SDK consumers can use Maven Central directly.

## Connect

Set `AGENT_EVAL_URL`, `AGENT_EVAL_API_KEY`, `AGENT_EVAL_AGENT_VERSION`, and optionally `AGENT_EVAL_SPOOL` and `AGENT_EVAL_INSTANCE_ID`. Only these explicit environment entries configure the SDK. Task inputs cannot replace configuration. Remote platforms require HTTPS.

```java
import io.agenteval.sdk.*;
import java.util.Map;

try (var worker = EvaluationWorker.fromEnvironment()) {
    worker.registerAgent("YOUR_AGENT_UUID", task -> {
        if (task.cancelled()) return AgentResult.of(Map.of("status", "cancelled"));
        return AgentResult.of(Telemetry.tool("query_order", () ->
            Map.of("amount", 100, "status", "paid")
        ));
    });
    worker.startAndAwait();
}
```

The Worker long-polls, confirms `started` before invoking the handler, renews leases on a separate thread, uploads on another thread, and submits a completion manifest. Each registered Agent gets a distinct server instance identity. Check `task.cancelled()` or `task.cancellation().await(timeout)` during long operations. Lease loss cancels local work and prevents a late success submission.

## Record calls and async work

`Telemetry.tool(name, supplier)` preserves the return value and exception, while recording elapsed time and exception type. For model, retrieval, custom or manual tool spans:

```java
try (var scope = Telemetry.span("model", "answer", request)) {
    try {
        var response = model.call(request);
        scope.output(response);
    } catch (RuntimeException failure) {
        scope.failed(failure);
        throw failure;
    }
}
```

An AutoCloseable cannot discover an exception by itself; call `failed` in manual spans, as shown. The `Telemetry.tool` wrapper does this for you. Instrument visible requests/responses and available usage explicitly; the SDK never captures hidden model reasoning.

Thread-pool work requires explicit propagation:

```java
var context = Telemetry.captureContext();
executor.submit(() -> Telemetry.runWithContext(context, () ->
    Telemetry.tool("lookup", () -> lookup())
));
```

The wrapper restores the pool thread's previous context. Wait for child work before returning the Agent result. Calls outside an active evaluation pass through. Spring applications can use the same wrappers; no annotation/AOP module is shipped in this version.

## Artifacts, redaction and recovery

- `task.artifact(path, "report.json", "application/json")` uploads an explicitly selected file and adds its ID to completion. File contents are the caller's responsibility. The upload request to object storage contains neither the platform Bearer nor the attempt token.
- Telemetry masks credential-like keys and configured execution credentials in values, names and keys before writing to the spool. Unrecognized objects become type markers; use JSON-compatible values for explicit span input/output. Error messages are excluded. Final `AgentResult.output` is sent unchanged to the platform; trace redaction does not rewrite the business result.
- Inline payloads above64KiB become redacted JSON attachments linked by `payload_ref`, with truncation visible. Strings are bounded to1MiB, nesting to16 and collections to128 entries. Automatic attachments and events share a100MiB queued-content budget; SQLite metadata and journaling need additional disk headroom.
- Event IDs/counters survive restart, and events are deleted only after acknowledgement. Execution markers store token hashes. Duplicate delivery does not invoke the handler again; a crash may leave execution unknown. There is no exactly-once guarantee and no automatic replay of unknown business operations.
- Resending queued evidence requires a valid assignment/token. Plaintext attempt tokens are not persisted, so stale orphan data may need explicit recovery or local cleanup. A new token never revives execution authority for expired work.
- HTTP responses have a25-second total deadline and a1MiB receive limit. Upload retries preserve event identities. Shutdown signals cancellation, interrupts pollers/IO, and waits up to6s; Java cannot safely force-stop a noncooperative handler. Resources are retained if threads remain active and can be closed by a later `close()` call.

## Example and tests

The reactor builds `examples/java-order-agent`. Run its `io.agenteval.examples.OrderAgent` main class with `--local` to query a fixed in-memory order. For a platform run, also set `AGENT_EVAL_AGENT_ID` and use the order-query template. The example has no real model or payment connection.

```bash
./mvnw -pl sdks/java -am test
python -m pytest sdks/python/tests -q
```

Both SDKs consume the same versioned contracts and fixture manifest. The Java SDK depends on `platform/protocol`, not server core, infrastructure or Spring modules.

## Registry publication

Version `0.1.1` is published to Maven Central. The 9 POM/jar files and 9 PGP signatures for the parent, protocol and SDK were downloaded from the public repository and matched the approved CI bundle. See the [Java signing and Central guide](https://github.com/1993226871/veyraeval/blob/main/docs/guides/maven-central.md) for release evidence and future releases. Consumers can use Maven/Gradle without a repository checkout. The Python SDK is also available on PyPI.

## License

Apache License 2.0; see the repository LICENSE and META-INF/LICENSE in the SDK jar.
