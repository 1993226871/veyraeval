# SDK 发布与依赖引入

Python 使用 PyPI（用户通过 pip 安装），Java 使用 Maven Central（用户通过 Maven/Gradle 引入）。**Python SDK `veyraeval-sdk==0.1.2` 已发布到 PyPI 和 TestPyPI。Java SDK `io.github.1993226871:veyraeval-sdk:0.1.1` 已与协议包和父 POM 一起发布到 Maven Central。** 项目仓库、分支推送和 SDK 公开发布是不同操作，均应按各自授权执行。

2026-10-27 发布的 Python `0.1.0` 来自提交 `4cb1c20c30086d5fac75435c29e2399d5488869e`。[TestPyPI 发布运行](https://github.com/1993226871/veyraeval/actions/runs/40462602375)与[正式 PyPI 发布运行](https://github.com/1993226871/veyraeval/actions/runs/40463092611)均通过环境审批后成功上传。两个站点的文件已重新下载并比对各自 CI 清单的 SHA-256，在全新环境中通过安装、依赖、导入和10份协议资源验证。不同运行会重新构建，两个站点的压缩包哈希不必相同；各自必须匹配对应发布运行的 manifest。

同日发布的 Java `0.1.0` 来自提交 `04058c3`。[Portal上传校验运行](https://github.com/1993226871/veyraeval/actions/runs/40474230908)通过后由维护者在Portal执行正式发布。9个POM/jar及9份签名已从公开Central重新下载并逐一匹配CI bundle，独立项目使用全新Maven缓存编译、运行通过。详细签名与部署记录见[Java发布指南](maven-central.md)。

## 主动上报版本

Python `0.1.2` 和 Java `0.1.1` 新增 `PushClient`，支持账号登录、按项目名自动创建项目，以及从 Agent 应用端上报运行和轨迹。已有的 Worker 接入方式继续可用。安装前可在 [PyPI](https://pypi.org/project/veyraeval-sdk/) 或 [Maven Central](https://central.sonatype.com/artifact/io.github.1993226871/veyraeval-sdk) 查询公开版本。

## Python 0.1.1 更新

Python `0.1.1` 新增 `EvaluationWorker.from_config(path, handler=...)`：从私有 JSON 读取平台地址、Agent ID、版本和 Worker 接入密钥，可选配置开发证书及持久暂存路径。平台创建凭证后展示可复制配置，关闭弹窗即清除该次展示内容。原有环境变量接入仍可用。

版本号、工作流默认值和构建产物校验同步更新；Worker 注册上报版本与包元数据一致。发布仍依次通过 CI、TestPyPI 安装验收和正式 PyPI 上传，不能将本地构建视为已发布。当前可下载版本以 [PyPI 版本列表](https://pypi.org/project/veyraeval-sdk/#history) 和对应 GitHub Actions 发布结果为准。

## 当前名称与真正的发布前提

| 项目 | 当前源码标识 | 发布前必须确认 |
| --- | --- | --- |
| Python 分发名 | `veyraeval-sdk`，源码版本 `0.1.2` | 已发布并验证；后续版本沿用已配置的 Trusted Publisher，每次上传仍单独审批。Python 导入名仍是 `agent_eval`。 |
| Java SDK | `io.github.1993226871:veyraeval-sdk:0.1.1` | 已发布并通过全新缓存的独立消费项目验证；后续版本继续单独审批。 |
| Java 协议 | `io.github.1993226871:veyraeval-protocol:0.1.1` | 已与 SDK 一起发布，消费者自动解析共享 DTO/Jackson 协议依赖。 |
| Java 父 POM | `io.github.1993226871:veyraeval-platform:0.1.1`，类型 `pom` | 已发布以解析版本/BOM/许可证等继承元数据。没有发布 API、Worker、core 或 infrastructure jar。 |

已选定 `io.github.1993226871` 为 Maven groupId，并同步父 POM、所有模块父引用、内部依赖和公开接入文档；维护者已确认该 namespace 为 Verified。品牌及分发包统一为 VeyraEval；Python 导入名 `agent_eval`、Java 包名 `io.agenteval.*` 和平台接入环境变量保持兼容，不随分发坐标更名。

PyPI 与 Central 的公开 SDK 包会暴露其代码；私有 GitHub 仓库不会让公开注册表中的源码包变成私有。发布前也应确认公开范围、账号适用规则及上游依赖许可证。

## 已准备的构建能力

- Python `pyproject.toml` 包含 README、仓库 URL、SPDX `Apache-2.0` 和 SDK 自带 LICENSE；wheel 包含10份规范 schema 副本。
- Maven 父 POM包含项目、许可证、维护者及 SCM 元数据；SDK/protocol 二进制 jar附带 LICENSE。
- `sdk-distribution` profile生成 SDK/protocol 的 binary、sources 和 Javadoc jar。
- `.github/workflows/sdk-distributions.yml` 仅在手工触发时构建、检查并保存产物。没有注册表写凭证、没有 OIDC 发布权限、没有上传注册表的命令。
- `.github/workflows/python-sdk-release.yml` 是单独的 Python 发布工作流：默认仅构建，显式启用发布后才使用所选注册表的 OIDC Publisher。PyPI/TestPyPI 均已配置并完成首次发布。

本地准备：

```bash
./mvnw -P sdk-distribution -pl sdks/java -am verify
python scripts/verify_java_sdk_distribution.py
python -m pip install build==1.3.0 twine==6.2.0
python -m build sdks/python
python -m twine check sdks/python/dist/*
python scripts/verify_sdk_wheel.py sdks/python/dist/veyraeval_sdk-0.1.2-py3-none-any.whl
```

Python 默认先构建 sdist，再从 sdist构建 wheel，验证源码分发包本身能独立打包。Java 不能只把 SDK 的单个 jar传上去：缺失 protocol 或父 POM 时，下游依赖解析会失败。

## Python：真正发布时的步骤

1. 确认公开名称、版本和 PyPI 所有权；创建/配置对应项目。若已有同名其他维护者项目，不得直接使用该名字。
2. 建议先在 TestPyPI 验证包元数据、安装及导入。源码中的 `AGENT_EVAL_API_KEY` 指平台接入密钥，与 PyPI 发布账号无关。
3. 配置 PyPI Trusted Publisher，绑定准确的 GitHub owner、repo、发布 workflow 和受保护 environment；给该 environment 配置人工批准。
4. 使用 `python-sdk-release.yml` 的独立发布作业：只下载本次构建的 SDK 产物，使用 `pypa/gh-action-pypi-publish`，将 OIDC `id-token: write` 限定在发布作业。构建作业不具备发布权限。
5. 你审核具体产物/版本并明确批准后才公开发布，再从干净环境按注册表依赖安装验证。已发布的同版本不能随意覆盖。

Python SDK 安装（选择注册表中已发布的对应版本）：

```bash
python -m pip install veyraeval-sdk==0.1.2
```

```python
from agent_eval import AgentResult, EvaluationWorker, observe
```

### Python 首次发布的账号配置

PyPI 与 TestPyPI 使用独立账号和项目权限，分别注册并配置双因素认证。不要在聊天、源码或普通配置文件里填写密码或 Token；本方案使用 Trusted Publishing，不需要保存长期 PyPI Token。

先在 GitHub 仓库 Settings → Environments 中创建 `testpypi` 和 `pypi`，限制部署来源为 `main`。若仓库套餐支持 Required reviewers，配置人工审批；若不支持，该工作流不会自动获得人工审批能力，须由有权限的维护者在确认具体提交和产物后手工触发。不要将仅写了 `environment` 误认为已配置审批。

本仓库于2026-10-27已配置这两个 GitHub environment：部署分支只允许 `main`，Required reviewer 为维护者 `1993226871`。允许发起工作流的维护者本人审批，适配单维护者流程；发布前仍须检查此次运行的 manifest 和实际产物。GitHub 环境配置不等于 PyPI/TestPyPI 的 Publisher 已绑定，后者需要在对应注册表单独完成。

在对应注册表的 Publishing 页面填写下表。不存在的项目使用 Pending Publisher；已有且属于你的项目使用项目管理页的 Trusted Publisher。已有但不属于你的项目必须换名称。

| 字段 | 值 |
| --- | --- |
| PyPI Project Name | `veyraeval-sdk` |
| Owner | `1993226871` |
| Repository | `veyraeval` |
| Workflow | `python-sdk-release.yml`（只填文件名） |
| Environment | TestPyPI 填 `testpypi`，PyPI 填 `pypi` |

当前 `veyraeval-sdk` 在 PyPI 和 TestPyPI 均已发布本项目的 `0.1.2` 版本。后续版本应使用项目管理页的 Trusted Publisher；新项目的 Pending Publisher 不会预留名称。

已存在的 PyPI 账号无需重新注册。将 GitHub 关联到 PyPI 账号不等于为此仓库创建了 Trusted Publisher；需要在 [PyPI Publishing](https://pypi.org/manage/account/publishing/) 中，按上表注册本项目的 Pending Publisher（已有本人项目则进入项目管理配置）。TestPyPI 账号及 Publisher 需要在测试站点单独配置。

### 手工演练与正式发布

1. 工作流合入 `main` 后，在 Actions → Python SDK release 选择 Run workflow，分支选 `main`，`registry=testpypi`，`version=0.1.2`，保持 `publish=false`。不会上传注册表。
2. 下载 `python-sdk-distributions` 和 `python-sdk-release-manifest`，核对源码提交、包名、版本和 SHA-256。构建会运行 SDK 测试、sdist→wheel 构建、Twine 检查、许可证/Schema 验证及独立虚拟环境安装。
3. 确认 TestPyPI 项目归属和 Publisher 绑定后，明确批准一次测试上传，再选择相同提交对应的 `main`、`registry=testpypi`、`publish=true`。每次运行重新构建，应核对这次运行的 manifest；人工环境审批可在构建结束后检查实际产物。
4. 从 TestPyPI 安装验证成功，再核对 PyPI 绑定与正式发布范围，明确批准正式上传；选择 `registry=pypi`、`publish=true`。工作流只允许从 `main` 公开上传，不因普通 push/tag 自动发布，不跳过同版本已存在错误。
5. 正式发布完成后按最终包名重新安装并运行实际 Agent 接入示例，再更新 README、安装文档和 Release。上传成功不能替代与实际平台交互验收。

TestPyPI 安装演练（包名按最终确认值替换）：在干净环境先从正式 PyPI 安装依赖，再仅从 TestPyPI 安装 SDK，避免混用两个索引来解析依赖。

```bash
python -m venv .venv-sdk-check
# 激活环境后执行；Windows: .venv-sdk-check\Scripts\Activate.ps1
# Linux/macOS: source .venv-sdk-check/bin/activate
python -m pip install httpx==0.28.1 jsonschema==4.23.0
python -m pip install --no-deps --index-url https://test.pypi.org/simple/ veyraeval-sdk==0.1.2
python -m pip check
python -c "from agent_eval import AgentResult, EvaluationWorker, observe"
```

PyPI 与 TestPyPI 均发布可公开下载的文件。测试上传也需要审核，不能因为是测试站点就认为文件私有。上传失败后先查明是否已有部分文件成功，不要自动改版本反复上传。

## Java：真正发布时的步骤

1. 在 Sonatype Central Portal 注册并验证 namespace，确认最终 groupId、artifactId 和版本。
2. 确认源码、Javadoc、POM 元数据、许可证和共享依赖齐全；给发布文件生成所需的签名与校验和。
3. 按 Central 要求创建发布 Token 和 GPG 签名配置，存放在专用账号/受保护 environment 中；不得写入仓库或本文件。
4. 已准备 `sdk-central` profile及独立的 `java-sdk-release.yml` 工作流，具体配置和操作见[Java签名与Maven Central发布](maven-central.md)。profile固定只生成签名bundle，不直接上传；工作流先验证bundle，再通过USER_MANAGED请求提交Portal校验，正式发布另行确认。只选择父POM、protocol、SDK，不发布服务端模块。
5. 你批准具体 bundle后才上传/发布；在干净的外部消费者项目中验证 Maven Central 可以解析完整依赖链。

用户现在可直接使用已发布坐标：

```xml
<dependency>
  <groupId>io.github.1993226871</groupId>
  <artifactId>veyraeval-sdk</artifactId>
  <version>0.1.1</version>
</dependency>
```

```kotlin
implementation("io.github.1993226871:veyraeval-sdk:0.1.1")
```

用户可按[Java接入](java-sdk.md)直接从 Maven Central 引入依赖，或按[Python接入](python-sdk.md)直接从 PyPI 安装，无需本地构建SDK。贡献者仍可使用源码构建方式开发。

## 官方参考

- [PyPI发布说明](https://docs.pypi.org/publishing/publishing-to-pypi/)、[Trusted Publishing](https://docs.pypi.org/trusted-publishers/using-a-publisher/)。
- [Sonatype namespace验证](https://central.sonatype.org/register/namespace/)、[发布必备元数据/签名/源码要求](https://central.sonatype.org/publish/requirements/)、[官方Maven发布插件](https://central.sonatype.org/publish/publish-portal-maven/)。
