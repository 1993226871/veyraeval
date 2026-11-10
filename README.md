<div align="center">

# VeyraEval Platform

### Connect your agents. Inspect their work. Measure what matters.

面向自定义 Agent 的评测平台，连接任务、执行轨迹与质量证据。

<p>
  <img src="https://img.shields.io/badge/status-source_preview-0f766e?style=flat-square" alt="Status: source preview" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache_2.0-blue?style=flat-square" alt="License: Apache 2.0" /></a>
  <img src="https://img.shields.io/badge/backend-Java_21-007396?style=flat-square" alt="Backend target: Java 21" />
  <img src="https://img.shields.io/badge/SDKs-Java_%2B_Python-3776AB?style=flat-square" alt="SDKs: Java and Python" />
  <img src="https://img.shields.io/badge/evaluation-rules_%2B_LLM_%2B_code-0f766e?style=flat-square" alt="Evaluation methods: rules, LLM and code" />
</p>

<p>
  <a href="#overview">项目介绍</a> ·
  <a href="#architecture">架构</a> ·
  <a href="#sdk-preview">SDK 接入</a> ·
  <a href="#documentation">设计文档</a> ·
  <a href="#roadmap">路线图</a>
</p>

</div>

> [!IMPORTANT]
> 源码预览版 `0.1.0`：双语言SDK、自建证据、RULE/LLM/PYTHON评测、累计额度/对账、私人工作台、报告/对比、重评、删除清理、资源预算与审计均已实现。HTTPS Compose、双SDK三类评测、真实runsc隔离、浏览器完整流程和备份恢复已通过验收。生产执行节点需重新验收，真实付费模型准确率与高可用部署不在本次验证范围。参阅[快速开始](docs/guides/quickstart.md)、[验收记录](docs/development/acceptance.md)及[项目说明](docs/project-guide.md)。

<a id="overview"></a>

应用通过 SDK 上报 Agent、任务和执行轨迹。工作台管理项目密钥与 JSON 评分规则，并提供自动或手动评测、报告和评分趋势。参阅[应用主动上报指南](docs/guides/tasks-and-rule-sets.md)。
已完成本地部署的 Windows 用户可双击根目录 `start.cmd` 启动，使用 `status.cmd` 查看状态、`stop.cmd` 停止服务并保留数据。首次安装及配置路径参数见[快速开始](docs/guides/quickstart.md)。

## Overview

VeyraEval Platform 旨在帮助开发者回答两个问题：**Agent 是否完成了任务，以及它是怎样完成的。**

你在自己的应用或 CI 中决定运行哪个 Agent 和任务集，用 Java 或 Python SDK 上报执行结果及轨迹。平台固定评分规则版本，按项目设置自动或手动评分，生成可追溯的报告和质量趋势。

它适用于自定义 Agent 的质量回归、版本比较和失败分析。接入不要求使用特定 Agent 框架，也不要求开放入站 HTTP 端口。

### Designed around evidence

- **Your agent, your runtime.** Agent 在你自己的环境中执行，SDK 负责任务接入和轨迹上报。
- **Private by default.** 每位用户拥有独立工作空间，管理自己的 Agent、任务集、评测器和实验。
- **Evaluate outcomes and behavior.** 分开评估任务结果、执行过程和效率，定位具体失败步骤。
- **Extend the criteria.** 使用基础模板起步，再配置业务规则、编写评分提示词或添加 Python 评测函数。
- **Keep experiments traceable.** 固定任务、评测器和证据版本，重评保留历史结果。
- **Control judge usage.** 平台模型按人民币累计额度预留与结算，新用户默认 ¥5.00；也可接入本人私有 Key，由供应商计费、不扣平台额度。

<a id="architecture"></a>
## Architecture

```mermaid
flowchart LR
    subgraph App[用户应用或 CI]
        Cases[本地任务集] --> Agent[执行 Agent]
        Agent --> SDK[Java / Python SDK 收集结果和轨迹]
    end
    subgraph Platform[评测平台]
        Intake[项目密钥校验与上报] --> Evidence[冻结整轮证据]
        Evidence --> Gate[自动或手动评测]
        Gate --> Eval[规则 / LLM Judge / 自定义 Python]
        Eval --> Report[报告、版本对比与评分趋势]
    end
    SDK -->|上报已执行的运行| Intake
```

**运行由应用发起。** SDK 为每道题关联输出和轨迹，现有显式埋点可继续记录工具、模型和中间结果。

轨迹采集、存储、查询、展示和评测均由项目自建，不依赖 Langfuse 或 LangSmith。

## Evaluation methods

| 方式 | 适用内容 | 示例 | 当前状态 |
| --- | --- | --- | --- |
| Rule-based | 可明确表达的事实和约束 | Schema、参数、显式依赖、调用次数 | 可执行并查询报告 |
| LLM-as-judge | 开放性内容和语义判断 | 信息完整性、证据支持、工具结果使用是否合理 | 已实现模型网关、证据评分、累计额度与对账 |
| Custom Python | 特定业务计算与自定义指标 | 根据折扣、优惠券和运费计算应退金额 | 已实现，需通过验收的 Linux/runsc 执行节点 |

三类评测器使用统一的结果结构：状态、分数、通过结论、解释和证据引用。Python 代码在平台的强化隔离环境执行，默认无网络访问。

> [!NOTE]
> “任务失败”“证据不足”和“评分器异常”是不同状态。用户上报的执行记录也不等同于独立环境验证；平台会标明证据来源和可评估范围。

<a id="sdk-preview"></a>
## SDK integration

Python 和 Java SDK 源码提供主动上报及轨迹采集。已发布到 PyPI 和 Maven Central 的版本提供 Worker 接入。

新上报 API 的接入步骤及可运行订单示例见[应用主动上报指南](docs/guides/tasks-and-rule-sets.md)。以下示例记录已发布 SDK 的 Worker API。

### Python

```python
# python -m pip install veyraeval-sdk==0.1.1
from threading import Event
from agent_eval import AgentResult, EvaluationWorker, observe

worker = EvaluationWorker.from_env()

@observe(kind="tool")
def query_order(order_id: str):
    return {"amount": 100, "status": "paid"}  # 替换为自己的工具实现

def evaluate_agent(task):
    return AgentResult(query_order(task.input["order_id"]))

worker.register_agent("YOUR_AGENT_UUID", evaluate_agent)
try:
    worker.start()
    Event().wait()
finally:
    worker.stop()
```

### Java

```xml
<dependency>
  <groupId>io.github.1993226871</groupId>
  <artifactId>veyraeval-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
// 引入上述依赖后，在自己的 Java 21 Agent 项目中使用。
try (var worker = EvaluationWorker.fromEnvironment()) {
    worker.registerAgent("YOUR_AGENT_UUID", context ->
        AgentResult.of(Telemetry.tool("query_order", () ->
            Map.of("amount", 100, "status", "paid")
        ))
    );
    worker.startAndAwait();
}
```

两套SDK共用事件协议、运行标识和契约用例；Java提供显式包装和异步上下文传递，Python提供装饰器和上下文管理器。当前不包含Java注解集成模块。

## Technology

| 层次 | 已选技术 |
| --- | --- |
| Platform | Java 21 · Spring Boot 3 · Maven |
| Web | React · TypeScript · Vite |
| Data | PostgreSQL · Redis · S3-compatible storage |
| Agent integration | Java SDK · Python SDK · HTTPS long polling |
| Custom evaluators | Python · 独立强化隔离 Runner |
| Deployment | Linux · Docker Compose |
| Engineering | TDD · 共享协议契约 · 租户与额度并发测试 |

Docker Compose 组织服务，Python 代码执行使用独立Linux/runsc边界。依赖版本、lockfile与实际取得的镜像digest已锁定；Python SDK `0.1.0` 已发布 PyPI，Java SDK `0.1.0` 已发布 Maven Central，并通过独立环境安装/编译验证。

<a id="documentation"></a>
## Documentation

先读[项目完整说明](docs/project-guide.md)：架构、流程图、完整目录树、每个目录和文件的职责，以及推荐阅读顺序。

- [快速开始](docs/guides/quickstart.md)：源码构建与 HTTPS 平台启动。
- [完整 Python 使用示例](docs/guides/python-evaluation-walkthrough.md)：安装 SDK、运行正误两个 Agent、创建任务与规则、查看评分和工具轨迹，附可运行项目与实际结果。
- [LLM 模型接入与额度](docs/guides/llm-models.md)：三字段接入、平台新用户 ¥5.00 累计额度、自带 Key 的私有隔离与零平台扣款。
- [Python SDK](docs/guides/python-sdk.md) / [Java SDK](docs/guides/java-sdk.md)：接入自定义 Agent。
- [SDK 发布指南](docs/guides/sdk-publishing.md)：Python 已发布版本、发布验证及 Maven Central 的准备、命名空间、产物与审批。
- [Java 签名与 Maven Central 发布](docs/guides/maven-central.md)：GPG 密钥、Portal Token、环境配置及分阶段验证与发布。
- [自定义评测器](docs/guides/custom-evaluators.md)、[运维与恢复](docs/guides/operations.md)、[验收说明](docs/development/acceptance.md)。

本地设计草稿与实施过程不随当前源码分发。忽略规则不会删除旧提交中的文件；历史清理是另行授权的操作。

<a id="roadmap"></a>
## Roadmap

- [x] 明确产品范围、技术栈和 SDK Worker 接入模式。
- [x] 编写设计规格并完成独立可实施性审阅。
- [x] 编写分阶段实施计划，明确接口、测试与验收条件。
- [x] 确认实施计划，采用当前会话逐项TDD实现。
- [x] 实现共享协议、私人工作空间与后端身份基础。
- [x] 实现后端私人工作空间、版本化任务与持久化调度。
- [x] 打通 Java/Python SDK 和自建轨迹采集。
- [x] 实现规则、LLM、Python 三类评测器。
- [x] 完成额度账本、报告、证据定位和版本比较。
- [x] 实现实验删除、资源预算、审计记录和私人工作台。
- [x] 提供演示 Agent、基础任务集、部署与恢复指南。
- [x] 完成独立整分支审阅、全部修复和最终验收。

## Development

项目采用测试驱动开发。行为测试先于产品代码，重点验证跨空间隔离、双 SDK 协议一致性、重复消息、失联恢复、Python 隔离及并发额度结算。

在引入新能力前，先检查它是否符合设计规格；协议或评分语义的变化需要保留版本与兼容性说明。

## License

本项目采用 [Apache License 2.0](LICENSE)。第三方依赖仍遵循各自的许可证。

---

<div align="center">
  <sub>Measure outcomes. Understand the execution. Improve with evidence.</sub>
</div>
