# 从源码启动私人评测平台

平台当前版本为源码构建的 `0.1.0`；Python SDK 已发布 PyPI，Java SDK 已发布 Maven Central，可直接引入依赖。平台使用 Java 21、PostgreSQL、React 和自建证据采集；Java/Python SDK 运行在 Agent 所在环境，长轮询领取平台任务。

## 构建与启动

准备 JDK 21、Node.js 22.23.4、Python 3.11、Docker Engine 和 Docker Compose v2。Windows Docker Desktop 可验证基础平台；自定义 Python 执行需要另行通过验收的 Linux/runsc 节点。源码构建需要访问依赖仓库。

```bash
git clone https://github.com/1993226871/veyraeval.git
cd veyraeval
bash scripts/build-release.sh
python scripts/configure_deployment.py .tools/local --admin-email admin@example.test --development-tls
docker compose --env-file .tools/local/.env -f deploy/compose.yaml up --build -d --wait
```

开发证书只覆盖 localhost，保存为 `.tools/local/tls/server.crt` 和 `server.key`；生成的私有 `.env` 包含首次登录密码。访问 `https://localhost:8443`。浏览器和 SDK 需要信任该开发 CA；正式部署应替换为受信任的证书，并修改 `TLS_CERT_DIR`、公网 S3 origin、端口和绑定地址。默认仅绑定回环地址，配置过程不会开放公网。

Linux 上证书目录和私钥必须可被配置的 `WEB_UID/WEB_GID` 读取。生成器默认使用当前用户 UID/GID，私钥为 0600。生产证书通常采用受控组及 0640 权限。勿把实际配置、私钥或备份提交到 Git；项目已忽略常见秘密文件，但仍需在提交前检查。

平台首次登录必须修改密码。管理员创建的新用户默认获得 ¥5.00 累计平台评审额度，没有周期重置。自带 API Key 不扣平台额度。基础规则评测无需模型额度，仍受执行次数和存储预算限制。模型接入和价格口径见 [LLM 模型接入与额度](llm-models.md)。

## Windows 日常一键启动

完成上面的首次构建和配置后，在仓库根目录双击 `start.cmd`。脚本会按需打开 Docker Desktop，等待 Linux 引擎和平台服务就绪，然后打开 `https://localhost:8443`。首次等待默认最多 180 秒；Docker Desktop 必须已安装。

- `start.cmd`：启动平台，复用 `.tools/local/.env` 和现有镜像。
- `status.cmd`：查看各服务状态。
- `stop.cmd`：停止平台，保留容器和数据库、附件数据；不会退出 Docker Desktop 或停止其他项目。

配置在其他位置时，在 PowerShell 中指定已有文件：

```powershell
.\start.cmd -EnvFile "C:\your-deployment\.env"
.\status.cmd -EnvFile "C:\your-deployment\.env"
.\stop.cmd -EnvFile "C:\your-deployment\.env"
```

不需要自动打开浏览器时，可运行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/local-platform.ps1 -Action start -NoBrowser`。此参数只作用于当前 PowerShell 进程，不修改系统执行策略。脚本不会生成配置、重置密码、更换密钥、导入证书或重新构建镜像。代码更新后需要按构建流程更新镜像；初次安装不能直接用日常启动脚本替代。

## 完成第一次评测

1. 在「评分规则集」展开「从平台示例开始」，点击「复制规则评分示例」。选择已发布的 `v1`，复制「评分规则版本 ID」。
2. 从仓库源码安装 Python SDK 和订单示例。用平台邮箱登录，在应用端传入项目名和评分规则版本 ID。
3. 应用执行三道模拟订单任务并上报结果。首次成功上报后，「SDK 接入」会出现项目，「实验」会出现报告。默认自动评分，通常在证据收集后约 30 秒开始。

[按步骤运行第一个订单示例](tasks-and-rule-sets.md)

首次演示选择规则套件即可。LLM 评测需要管理员配置允许的供应商 origin、加密密钥、模型和不可变价格，再由用户将固定模型/价格绑定到评测器。Python 评测需要 [合格执行节点](custom-evaluators.md)；默认关闭，不能用普通容器替代 runsc。

## 部署边界

Compose 包含 Web、API、Worker、单一数据库迁移作业、PostgreSQL、Redis 和 RustFS S3。只有 Web 的 HTTPS 8443 和 S3 HTTPS 9443 映射到主机；数据库、Redis、S3 管理接口与 API 不直接暴露。附件签名使用单独的 S3 HTTPS origin，反向代理保留 Host 和路径。

默认的 RustFS 单节点单盘配置服务于小规模部署，没有冗余；它不是高可用存储。需要高可用时应配置合适的外部 S3 服务，并重新验证签名、存储和恢复。当前 Redis 是预留的临时基础设施，容量、额度和恢复状态由 PostgreSQL 决定。

参阅 [运维指南](operations.md)、[评分器指南](custom-evaluators.md)及[项目结构说明](../project-guide.md)。
