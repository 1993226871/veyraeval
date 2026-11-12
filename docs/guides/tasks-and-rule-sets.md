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

先在「评分规则集 → 从平台示例开始」点击「复制规则评分示例」，打开已发布的 `v1` 并复制版本 ID。这个示例使用三道本地订单任务和确定性评分规则。

在 Windows 的 PowerShell 中从仓库根目录运行。订单示例会从 PyPI 安装 Python SDK `0.1.2`。

```powershell
python -m pip install -e ./examples/python-evaluation-demo
$email = "替换成你的平台邮箱"
$suite = "替换成刚复制的评分规则版本 ID"
python -m order_agent.push_demo --url "https://localhost:8443" --username $email --project "订单助手评测" --suite $suite --variant correct --ca ".tools/local/tls/server.crt"
```

命令会提示输入密码，输入时不回显。脚本执行后会打印报告链接。首次上报创建「订单助手评测」项目和「订单助手 v2」运行，三道题全部正确时，报告显示 3/3 通过和三个 `query_order` 工具调用。默认自动评测，报告可能先显示等待证据。远端平台按实际地址配置 `--url`，使用公共信任证书时省略 `--ca`。

如需观察趋势，再使用同样的项目名和规则版本，将 `--variant correct` 换成 `--variant buggy`。脚本会上报同一 Agent 的 v1，使用同一 namespace 和题库版本。

在自己的应用环境运行 `python -m pip install veyraeval-sdk==0.1.2`，再把下例的 `order_agent(...)` 替换为你的 Agent 调用。

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

已有的 `@observe("tool")` 和 `span(...)` 会把内部调用收集到当前任务轨迹。Java SDK `0.1.1` 使用 `PushClient.login` 登录，然后调用 `startRun`（第一个参数为项目名）、`runCase` 和 `submit`，可继续用 `Telemetry.tool` 和 `Telemetry.span` 打点。

- `namespace` 用于在项目内归类。应用指定 Agent 名称、版本、运行显示名和任务集版本。每道题使用稳定、唯一的 `case_id`。同一项目、namespace 和任务集版本下，题目、输入或参考答案改动后使用新版本。
- 一轮提交包含已执行完的任务与内联轨迹，最多 1000 道题、2000 个轨迹事件和 8 MiB 请求。大批评测按应用侧约定拆分并标明分组。评分需要的输入和输出应在上报前按业务规则脱敏。
- 需要断线重试时，在提交前用 `run.save(path)` 保存到受限的本地文件。恢复时使用 `client.upload_file(path)`，或用示例的 `--replay` 参数。相同运行 ID 和内容会返回原报告；重放上传不会再次调用 Agent。Java 对应 `save` 和 `uploadFile`。

## 评分和查看报告

- **自动评测开启**时，证据冻结完成后整轮入队评分。
- **自动评测关闭**时，报告显示「待评测」。可以先看输入、回答和轨迹。点击「开始评测」会显示规则版本及费用预估，再点「确认开始评测」。
- 在侧栏打开「评测看板」，可按项目、Agent、命名空间、任务集版本、评分规则版本和时间范围筛选最近 100 轮实验。每条趋势最多展示 8 轮，通过可比性检查的点才会连线。点击运行记录可打开单次报告，也可从筛选结果选两轮详细对比。
- 逐任务报告区分回答未达标、执行异常、证据不足和评分器异常。失败样例可以下载 JSONL 带回应用题库，文件携带原参考答案和证据来源。
