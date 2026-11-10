# 从应用上报 Agent 评测

## 在平台配置一次

1. 进入「评分规则集」，创建或复制示例。直接编辑整份 JSON，保存并发布。打开已发布版本，复制「评分规则版本 ID」。
2. 应用使用平台账号登录 SDK，并为每轮传入项目名称。首次有效上报时，平台在该账号的工作空间创建项目；后续同名上报归到已有项目。
3. 「SDK 接入」会显示已上报项目。自动评测默认开启，可在项目首次出现后切换，影响之后接收的运行。

规则集 JSON 示例：

```json
{
  "entries": [
    {
      "name": "答案一致性",
      "dimension": "outcome",
      "required_to_pass": true,
      "weight": 1,
      "definition": {
        "type": "RULE",
        "criterion_id": "answer_exact",
        "threshold": 1,
        "required_evidence": ["output", "expected"],
        "config": {
          "operator": "eq",
          "selector": "/output",
          "expected_selector": "/expected"
        }
      }
    }
  ]
}
```

规则集也支持 LLM Judge 和隔离执行的自定义 Python。已接入模型后，可在规则集页面选择模型并复制「规则 + LLM 示例」，查看完整可编辑 JSON。发布会固定版本及其依赖。平台模型按个人额度计费，私有模型由供应商向对应 API 账户计费。

## 在应用执行并上报

当前主动上报接口使用本仓库中的 SDK 源码。登录后 SDK 使用平台会话上报，保存的重放文件只包含运行数据。会话过期时重新登录并重放已保存的运行。Python 本地安装示例：

```powershell
python -m pip install -e ./sdks/python -e ./examples/python-evaluation-demo
python -m order_agent.push_demo --username <平台邮箱> --project <项目名> --suite <评分规则版本ID> --variant buggy --ca <本机证书路径>
python -m order_agent.push_demo --username <平台邮箱> --project <项目名> --suite <评分规则版本ID> --variant correct --ca <本机证书路径>
```

命令会在终端隐藏输入平台密码。订单示例使用同一个 Agent 名称、任务集版本和 namespace，分别上报 v1 和 v2。在自己的应用中可以这样接入：

```python
from getpass import getpass
from agent_eval import PushClient

with PushClient.login("https://eval.example.com", "user@example.com", getpass("平台密码: ")) as client:
    run = client.start_run(
        project="订单助手评测",
        namespace="订单回归",
        name="订单助手 v2",
        agent_name="订单助手",
        agent_version="v2",
        dataset_version="orders-v1",
        suite_version_id="替换为已发布规则版本 ID",
    )
    with run.case(
        id="order-A",
        instruction="查询订单 A 的金额",
        input={"order_id": "A"},
        expected={"amount": 100},
    ) as case:
        case.output = order_agent({"order_id": "A"})
    report_id = run.submit()
```

已有的 `@observe("tool")` 和 `span(...)` 会把内部调用收集到当前任务轨迹。Java SDK 使用 `PushClient.login` 登录，然后调用 `startRun`（第一个参数为项目名）、`runCase` 和 `submit`，可继续用 `Telemetry.tool` 和 `Telemetry.span` 打点。

- `namespace` 用于在项目内归类。应用指定 Agent 名称、版本、运行显示名和任务集版本。每道题使用稳定、唯一的 `case_id`。同一项目、namespace 和任务集版本下，题目、输入或参考答案改动后使用新版本。
- 一轮提交包含已执行完的任务与内联轨迹，最多 1000 道题、2000 个轨迹事件和 8 MiB 请求。大批评测按应用侧约定拆分并标明分组。评分需要的输入和输出应在上报前按业务规则脱敏。
- 需要断线重试时，在提交前用 `run.save(path)` 保存到受限的本地文件。恢复时使用 `client.upload_file(path)`，或用示例的 `--replay` 参数。相同运行 ID 和内容会返回原报告；重放上传不会再次调用 Agent。Java 对应 `save` 和 `uploadFile`。

## 评分和查看报告

- **自动评测开启**时，证据冻结完成后整轮入队评分。
- **自动评测关闭**时，报告显示「待评测」。可以先看输入、回答和轨迹。点击「开始评测」会显示规则版本及费用预估，再点「确认开始评测」。
- 报告中的「Agent 评分趋势」展示同一 Agent、同一任务集和规则集下的结果质量分。通过可比性检查的点才会连线，点击运行链接可打开对应报告。通过率和评分覆盖在报告中单独显示。
- 逐任务报告区分回答未达标、执行异常、证据不足和评分器异常。失败样例可以下载 JSONL 带回应用题库，文件携带原参考答案和证据来源。
