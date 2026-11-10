# Java SDK：签名与 Maven Central 发布

Java SDK `0.1.0` 已发布 Maven Central，可直接通过 Maven/Gradle 引入；命名空间为已验证的 `io.github.1993226871`。

2026-10-27，[发布运行](https://github.com/1993226871/veyraeval/actions/runs/40474230908)将提交 `04058c37f61baa8f01fdd1f80a15314790ae1a53` 的签名包以 USER_MANAGED 提交 Portal，通过 VALIDATED 校验，部署 ID 为 `723fb6e8-5444-4715-a677-d78cedfaec32`。维护者在 Portal 执行正式发布后，已从公共仓库下载9个 POM/jar 和9份签名，逐一与该运行的 bundle 比对一致。独立项目使用全新 Maven 缓存，仅声明 SDK 依赖，成功从公共 Central 解析 SDK、protocol、父POM，编译并运行 `AgentResult`/`ProtocolCodec` 示例。本次验收覆盖公共依赖解析、编译和基础 API 运行；具体Agent的实际业务接入按其任务集验收。

实际发布 bundle 的 SHA-256 为 `d5396b16cdec8d5601ea2b9cde2ce53ed0fe173db598acee997326d8ed2df9f7`。正式公钥指纹为 `1F423457DBF791BF056AEB0A5A140871A03A12FC`，已在公钥服务器发布并核对。以下步骤供维护后续版本使用；本地构建测试密钥从未上传或用于正式发布。

## 1. 发布哪些组件

| 坐标 | 发布文件 |
| --- | --- |
| `io.github.1993226871:veyraeval-platform:0.1.0` | 父 POM |
| `io.github.1993226871:veyraeval-protocol:0.1.0` | POM、binary、sources、Javadoc |
| `io.github.1993226871:veyraeval-sdk:0.1.0` | POM、binary、sources、Javadoc |

总计9个 POM/jar，每个文件都带 PGP 签名和校验和。不发布 API、Worker、core、infrastructure 或示例组件。

`sdk-distribution` 只生成分发包；`sdk-central` 增加签名和 Central bundle，固定 `skipUpload=true`、`autoPublish=false`。因此下面的 `deploy` 命令**只在本地生成 bundle**，不向 Central 上传：

```bash
./mvnw -B -P sdk-distribution,sdk-central -pl sdks/java -am clean deploy
```

命令需要事先通过环境变量提供正式签名密钥。不要去掉 `-pl sdks/java -am`。即使生成了其他组件，`verify_maven_bundle.py` 也会拒绝超出上述3个组件的包。

## 2. 创建正式 GPG 密钥

Windows 建议打开 **Git Bash** 执行以下命令；这些命令也适用于已安装 GnuPG 和 GitHub CLI 的 Linux/macOS。正式密钥由维护者在自己机器创建，不使用本地构建测试密钥。

```bash
gpg --version
gh auth status
export GPG_TTY=$(tty)
gpg --full-generate-key
```

根据交互提示选择支持签名的 RSA 密钥（例如 RSA and RSA，4096位），有效期例如1年；填写用于公开发布的姓名/维护者名称和邮箱，设置强口令。公钥中的姓名和邮箱会随公钥公开；可自行选择适合公开使用的邮箱。私钥和口令不要发到聊天、放进源码或普通配置文件。

查看完整指纹，并替换下一条命令中的占位值：

```bash
gpg --list-secret-keys --keyid-format long
SIGNING_FINGERPRINT='替换为上方完整密钥指纹'
```

发布的是公钥，不是私钥。以下命令让 Central 能获取公钥并校验签名：

```bash
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys "$SIGNING_FINGERPRINT"
```

公钥服务同步可能需要时间；Portal 的 VALIDATED 状态才说明服务端验证已通过。记录密钥到期时间，持续维护备份、续期和撤销方式。

## 3. 配置 GitHub 的签名材料

仓库已配置 `maven-central` environment，部署分支只允许 `main`，Required reviewer 为 `1993226871`。支持单维护者发起并审批，但每次上传和正式发布仍需明确确认范围。

先配置**环境级** Secret 和 Variable。以下命令不会把私钥写到仓库文件，`gh secret set` 会加密保存到 GitHub：

```bash
# 受口令保护的ASCII私钥，通过管道直接进入Secret，不复制到聊天。
gpg --armor --export-secret-keys "$SIGNING_FINGERPRINT" |
  gh secret set MAVEN_GPG_KEY --env maven-central --repo 1993226871/veyraeval

# 按gh提示输入刚才的密钥口令；不要把口令写进命令参数。
gh secret set MAVEN_GPG_PASSPHRASE --env maven-central --repo 1993226871/veyraeval

# 公钥不是Secret，保存到同一环境的Variable，用于独立签名校验。
gpg --armor --export "$SIGNING_FINGERPRINT" |
  gh variable set MAVEN_GPG_PUBLIC_KEY --env maven-central --repo 1993226871/veyraeval
```

也可在仓库 Settings → Environments → maven-central 的 Environment secrets / Environment variables 中填写对应值。必须保留 ASCII 密钥的完整 BEGIN/END 行及换行。只导出选定指纹的密钥，不上传整个个人 keyring。

## 4. 创建 Central Portal Token

1. 登录 [Central User Token](https://central.sonatype.com/usertoken)，点击 Generate User Token，填写名称和有效期。
2. 将生成的 token username、token password 分别保存到以下两个环境级 Secret。它们是 Portal Token 的两部分，不是 GitHub 登录名或 GitHub 密码。

```bash
gh secret set CENTRAL_USERNAME --env maven-central --repo 1993226871/veyraeval
gh secret set CENTRAL_PASSWORD --env maven-central --repo 1993226871/veyraeval
```

Token 关闭弹窗后不能再次查看；在自己的密码管理器保存，设置到期提醒。不要发送到聊天。

最终应有4个 Environment secrets：`MAVEN_GPG_KEY`、`MAVEN_GPG_PASSPHRASE`、`CENTRAL_USERNAME`、`CENTRAL_PASSWORD`；以及1个 Environment variable：`MAVEN_GPG_PUBLIC_KEY`。检查名称是否存在即可，不要输出密钥值。

## 5. 工作流分阶段执行

工作流合入默认分支后，进入 Actions → Java SDK release preparation → Run workflow，版本填写 `main` 根 POM 中的版本。本轮主动上报版本为 `0.1.1`。

| mode | 行为 | 凭据和审批 |
| --- | --- | --- |
| `build`（默认） | 测试SDK、生成binary/sources/Javadoc并保存unsigned artifact | 不需要签名或Central凭据，不上传注册表 |
| `bundle` | 在main重新构建、签名、生成bundle，独立验证所有签名和校验和，保存bundle及manifest | 需maven-central审批及签名Secret、公钥Variable；不上传Central |
| `upload` | 同bundle，然后将校验过的同一zip提交Portal，轮询至VALIDATED | 需本次明确上传授权、环境审批及Central Token；使用USER_MANAGED，不自动正式发布 |

先运行 `build`，再在正式密钥配置后运行 `bundle`。下载本次运行的 `java-sdk-signed-central-bundle`，核对源码提交、版本和 SHA-256。`bundle` 与 `upload` 会各自重新构建，应检查对应运行的实际清单。

本地独立校验已签名包：

```bash
python scripts/verify_java_sdk_distribution.py
python scripts/verify_maven_bundle.py target/central-publishing/central-bundle.zip --public-key /path/to/public.asc
```

Windows 若从 PowerShell 使用 Git for Windows 的 GPG，给第二条命令附加 `--gpg D:/Git/usr/bin/gpg.exe --msys-paths`（路径按安装位置调整）。

上传前应确认三个组件的版本均未在 Central 发布。`upload` 只有一次上传请求，不自动重试未知结果；网络超时后先查看 [Portal deployments](https://central.sonatype.com/publishing/deployments)，避免重复提交。工作流保存 deployment ID 并等待服务端校验；FAILED或超时都不能声称发布成功。

## 6. 正式发布和消费者验收

Portal 的 VALIDATED 表示校验通过，**还未正式发布**。核对 deployment ID、三个组件和版本，单独确认正式发布范围后，再在 Portal 执行 Publish。不要在未审核的部署上点击 Publish。

等待 PUBLISHED 后，使用全新的 Maven 本地仓库，在独立项目中只声明：

```xml
<dependency>
  <groupId>io.github.1993226871</groupId>
  <artifactId>veyraeval-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

确认从 Central 能解析 SDK、protocol 和父POM，编译运行用户Agent示例，并跑通实际平台接入。完成后更新Java安装指南和README；已发布的同版本不能覆盖。

## 官方参考

- [Central Maven插件及skipUpload](https://central.sonatype.org/publish/publish-portal-maven/)
- [Portal Token](https://central.sonatype.org/publish/generate-portal-token/)
- [PGP签名、公钥发布](https://central.sonatype.org/publish/requirements/gpg/)
- [Maven GPG插件的BC signer及环境变量](https://maven.apache.org/plugins/maven-gpg-plugin/sign-mojo.html)
- [Portal API及USER_MANAGED语义](https://central.sonatype.org/publish/publish-portal-api/)
