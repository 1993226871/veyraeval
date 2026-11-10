# Python Agent 接入

## 应用端账号上报

Python 主动上报使用仓库源码中的 `PushClient.login`，由应用指定项目名，首次成功提交后自动创建项目。完整订单示例见[应用端上报指南](tasks-and-rule-sets.md)。

以下章节适用于已发布 SDK 的 Worker 接口。


第一次使用可先跟随 [完整 Python 评测示例](python-evaluation-walkthrough.md)：从安装 SDK 到两个 Agent 的对比报告，含独立项目、规则配置和工具轨迹分析。

需要 Python 3.11 或更高版本。SDK `0.1.0` 已发布到 [PyPI](https://pypi.org/project/veyraeval-sdk/0.1.0/)，可直接安装，无需下载仓库。下面的订单示例文件仍位于仓库的 `examples/python-order-agent/`；自己的 Agent 项目只需引入 SDK 并按接入示例注册处理函数。

## 配置文件接入（0.1.1 起）

推荐在“Agent 接入”注册 Agent 后创建接入凭证，点击“复制配置”，保存到自己 Agent 项目的 `agent-eval.local.json`。任务集和实验可以重复使用同一 Agent，无需每次创建任务都生成密钥。

此接口从 Python SDK `0.1.1` 起提供；`0.1.0` 不支持。请在自己的 Agent Python 环境执行 `python -m pip install --upgrade veyraeval-sdk==0.1.1`。开发时也可在平台源码根目录执行 `python -m pip install ./sdks/python`。配置文件示例：

```json
{
  "platform_url": "https://localhost:8443",
  "agent_id": "平台注册的Agent UUID",
  "agent_version": "v1",
  "api_key": "创建凭证时显示的Worker接入密钥",
  "ca_cert": "E:/VeyraEval/.tools/local/tls/server.crt"
}
```

`ca_cert` 是本机开发证书路径；使用受信任的正式 HTTPS 证书时省略此字段。远程运行 Agent 时须使用它能访问的平台地址，并将开发证书复制到该运行环境。不要关闭 TLS 校验。

配置好后，保留原有业务处理函数，直接注册：

```python
from threading import Event
from agent_eval import AgentResult, EvaluationWorker


def handle(task):
    return AgentResult({"answer": "Hello " + task.input["name"]})


worker = EvaluationWorker.from_config("agent-eval.local.json", handler=handle)
try:
    worker.start()
    Event().wait()
except KeyboardInterrupt:
    pass
finally:
    worker.stop()
```

在平台使用输入 `{"name": "Alice"}` 创建相应任务，启动实验后，Worker 才会领取并执行。接入密钥负责鉴权，不需要向 SDK 提供平台登录账号和密码。

也可以运行仓库现有订单示例：

```powershell
python examples/python-order-agent/main.py --config "C:/my-agent/agent-eval.local.json"
```

可选 `spool_path` 指定持久暂存文件，默认 `.agent-eval/spool.sqlite`；`ca_cert` 与 `spool_path` 的相对路径均以配置文件所在目录为基准。多个 Worker 进程应使用各自独立的 spool。修改配置后重启 Worker 生效。请把 `agent-eval.local.json` 和 `.agent-eval/` 加入自己项目的 `.gitignore`，勿提交实际密钥。

## 环境变量接入（已发布 0.1.0 可用）

```bash
python -m pip install veyraeval-sdk==0.1.1
export AGENT_EVAL_URL=https://your-platform.example
export AGENT_EVAL_AGENT_ID=<平台注册的Agent UUID>
export AGENT_EVAL_API_KEY=<本次创建的接入密钥>
export AGENT_EVAL_AGENT_VERSION=v1
export AGENT_EVAL_SPOOL=/private/path/agent-spool.sqlite
python examples/python-order-agent/main.py
```

远程平台必须使用 HTTPS。开发 CA 可通过 `SSL_CERT_FILE` 配置；不要关闭 TLS 校验。`AGENT_EVAL_API_KEY` 是 Worker 接入密钥，不是模型供应商密钥，不能放到前端或任务输入里。

```python
from threading import Event
from agent_eval import AgentResult, EvaluationWorker, observe

@observe("tool", name="query_order")
def query_order(order_id):
    return {"amount": 100, "status": "paid"}

def agent(context):
    if context.cancelled:
        return AgentResult({"status": "cancelled"})
    return AgentResult(query_order(context.input["order_id"]))

worker = EvaluationWorker.from_env().register_agent("YOUR_AGENT_UUID", agent)
try:
    worker.start()
    Event().wait()
finally:
    worker.stop()
```

SDK 自动建立任务根 Span。用 `observe` 标记工具、模型、检索等步骤，或使用显式上下文采集；异步调用和完整选项见 [SDK 详细说明](../../sdks/python/README.md)。采集内容先脱敏再进入有界 SQLite spool。平台只看到上报的信息，未标记的内部逻辑不会自动出现；这些轨迹属于 Worker 上报证据。

SDK 独立续约，处理函数应检查取消信号和任务时限。执行标记先于业务处理持久化，进程崩溃后的不确定业务不会自动重跑；需要重试时在平台显式创建新实验。上报和完成请求可按原身份恢复，不重新执行业务。

每个进程使用自己的持久 spool，限制访问权限并保留到已确认消息发送完成。完整业务结果和附件还受平台大小、执行次数及存储限制。离线时检查网络、证书、密钥是否撤销、Agent ID/版本和空间资源上限。
