# mieai-bot

`mieai-bot` 是 MieBot 的 Kotlin 群聊 AI 插件。当前插件版本为 `0.0.6`，要求 Java 21、MieBot `1.0.6` 和 Plugin API `3.2.0`。

## 构建

先在 MieBot 根项目生成本地插件 SDK：

```powershell
cd D:\开发文档\miebot
.\gradlew.bat pluginSdkRepository --no-configuration-cache
```

再构建插件：

```powershell
cd D:\开发文档\mieai-qqbot
$env:JAVA_HOME='E:\JAVA\dragonwell-21.0.11.0.11+10-GA'
.\gradlew.bat clean test pf4jSqliteTest jar --no-configuration-cache --no-daemon
```

输出文件为 `build/libs/mieai-bot-0.0.6.jar`。也可以通过 `-PqqbotSdkRepository=<路径>` 指定其他 MieBot SDK 仓库。

## 安装

在 MieBot 管理端上传插件 JAR，为机器人创建并启用 `mieai-bot` 绑定。宿主会为每个绑定生成独立的 `config.yml` 和数据目录；插件在该目录保存 `history.db`，不同机器人绑定的数据互不共享。

首次使用前至少填写：

- `api.baseUrl`：只填写协议、域名和可选端口，不填写 `/v1` 或具体接口路径。
- `api.apiKey`：AI 服务密钥。
- `api.protocol`：`openai-old` 或 `openai-new`。
- `api.primaryModel`：主模型名称。

默认配置的每个字段均有填写说明。不要把包含真实 API 密钥的绑定配置提交到版本库。

普通群消息的概率和关键词触发依赖 QQ 开放平台启用“接收所有消息”，并要求机器人订阅 `GROUP_AND_C2C_EVENT`；仅收到 @ 消息时，请先检查该平台权限和 Gateway Intents。

## 指令与别名

原始指令始终有效，命令名与子命令不区分大小写。`/mieai` 和 `/mieai help` 可由所有群成员查看；修改群设置的四条指令仅限当前群的群主和管理员使用。

| 原始指令 | 用途 | 别名配置 | 设置别名后的调用形式 |
| --- | --- | --- | --- |
| `/mieai` | 显示所有指令及用途；主别名还可以替代 `/mieai` 前缀 | `commands.mainAlias` | `/别名`、`/别名 help`、`/别名 prob 50` 等 |
| `/mieai help` | 显示完整帮助，不修改群设置 | `commands.helpAlias` | `/别名` |
| `/mieai prob <1-100>` | 设置当前群 AI 聊天概率 | `commands.probAlias` | `/别名 <1-100>` |
| `/mieai prompt <提示词>` | 设置当前群独立系统提示词 | `commands.promptAlias` | `/别名 <提示词>` |
| `/mieai keyword <关键词>` | 设置当前群独立触发关键词，并覆盖该群的默认关键词 | `commands.keywordAlias` | `/别名 <关键词>` |
| `/mieai chat` | 切换当前群 AI 聊天的启用或禁用状态 | `commands.chatAlias` | `/别名` |

别名默认全部为空，空字符串表示不启用该别名。配置值只填写指令名称本身，不填写开头的 `/`：

```yaml
commands:
  mainAlias: "ai"
  helpAlias: "查询mieai指令"
  probAlias: "设置概率"
  promptAlias: "设置提示词"
  keywordAlias: "设置关键词"
  chatAlias: "切换聊天"
```

以上示例会启用 `/ai`、`/查询mieai指令`、`/设置概率 50`、`/设置提示词 请简洁回答`、`/设置关键词 小助手` 和 `/切换聊天`。因为 `mainAlias` 可以替代主前缀，所以 `/ai help`、`/ai prob 50`、`/ai prompt ...`、`/ai keyword ...`、`/ai chat` 也有效；其他五个别名都是独立的顶级指令，不需要再加 `/mieai`。

每个非空别名最多 64 个 Unicode 字符，不能包含 `/`、空白字符或控制字符，不能与内置主指令 `mieai` 冲突；所有别名忽略大小写后必须唯一。设置别名不会改变原指令权限，发送 `/mieai`、`/mieai help` 或对应帮助别名时会显示当前已经启用的别名。

`/mieai chat` 在启用和禁用之间切换。所有成功修改都会立即原子写回 `config.yml`；提示词或关键词超限时不会保存，并使用配置中的提醒文本引用回复。

## 行为说明

- @机器人、关键词和概率三种方式只会为同一条消息创建一次回复。
- 所有 AI 回复均显式引用触发消息。
- 同一群的 AI 任务严格串行，不同群并行；队列满时会引用提示。
- 引用消息只沿 SQLite 中的引用链取上文，不足时不会使用普通群历史补足；未引用消息按该群此前消息取上文。
- 图片链接至少等待 3 秒后下载并以 Base64 保存。启用图片理解后，所有以 `mimo-v2.5` 开头的模型使用 OpenAI 兼容的 Chat Completions、`Authorization: Bearer` 和 Base64 多图输入。
- 主模型超时、网络错误、非成功 HTTP、响应解析错误或空回复时，会用备用模型重试；备用窗口到期后的下一条消息重新探测主模型。
- SQLite 按配置的本地时间清理过期消息、单群超量消息和全库超量消息。
