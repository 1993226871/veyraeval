# Java Agent 接入

## 应用端账号上报

Java SDK `0.1.1` 的主动上报使用 `PushClient.login(URI, username, password)`。调用 `startRun` 时把项目名作为第一个参数，随后用 `runCase` 和 `submit` 执行并上报。平台端步骤见[应用端上报指南](tasks-and-rule-sets.md)。

以下章节适用于已发布 SDK 的 Worker 接口。


需要 JDK 21。将以下依赖加到自己的 Agent 项目即可从 Maven Central 安装。 [Maven Central](https://central.sonatype.com/artifact/io.github.nijikamori/veyraeval-sdk/0.1.1)

已有项目可修改 Maven 依赖的 `groupId` 或 Gradle 依赖字符串的第一段。Java 的 `import io.agenteval.*` 保持原样。 [Maven 发布指南](maven-central.md)

```xml
<dependency>
  <groupId>io.github.nijikamori</groupId>
  <artifactId>veyraeval-sdk</artifactId>
  <version>0.1.1</version>
</dependency>
```

Gradle Kotlin DSL：

```kotlin
repositories { mavenCentral() }
dependencies { implementation("io.github.nijikamori:veyraeval-sdk:0.1.1") }
```

在平台注册 Agent 并创建一次性凭证，将 `AGENT_EVAL_URL`、`AGENT_EVAL_AGENT_ID`、`AGENT_EVAL_API_KEY`、`AGENT_EVAL_AGENT_VERSION` 和 `AGENT_EVAL_SPOOL` 注入自己的进程。远程地址必须为 HTTPS；开发 CA 需要导入专用 Java truststore，并通过 `javax.net.ssl.trustStore` 指定。不要关闭 TLS 校验。

```java
try (var worker = EvaluationWorker.fromEnvironment()) {
    worker.registerAgent(System.getenv("AGENT_EVAL_AGENT_ID"), context -> {
        if (context.cancelled()) return AgentResult.of(Map.of("status", "cancelled"));
        return AgentResult.of(Telemetry.tool("query_order", () ->
            Map.of("amount", 100, "status", "paid")));
    });
    Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
    worker.startAndAwait();
}
```

这里使用 `io.agenteval.sdk` 中的 Worker、结果和 Telemetry 类型及 `java.util.Map`。完整演示见 [OrderAgent](../../examples/java-order-agent/src/main/java/io/agenteval/examples/OrderAgent.java)。

SDK 自动创建任务根 Span，工具调用使用显式包装，跨线程通过 SDK 的上下文包装传递；当前没有 Java 注解集成模块。内部未包装的调用不会自动被平台观测到。SQLite spool、独立心跳、取消和崩溃未知状态语义与 Python SDK 一致。

只有平台持久确认后才清理相应缓存；处理函数崩溃后不会自动重新调用真实业务。显式重跑会重新执行工具并可能重新产生模型费用。完整 API 和异步示例见 [Java SDK 说明](../../sdks/java/README.md)。
