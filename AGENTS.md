# AGENTS.md

## 对话语言
- 所有对话、说明、方案、结论均使用简体中文
- 代码标识符、类名、方法名、枚举名、路径、命令可保留原文

## 实现前先出方案
- 修改代码前必须先给出实现计划，等待用户批准
- 未批准前只能进行只读分析，不得修改代码

## 项目简介
**Pi Chat** — IntelliJ IDEA 插件，将本地 [pi](https://pi.dev) coding agent 嵌入为右侧工具窗口（`com.ruigu.pichat`）。插件通过 `pi --mode rpc` 启动子进程（JSONL over stdin/stdout），会话文件与终端 pi 共用 `~/.pi/agent/sessions/`。UI 借鉴自 [jetbrains-cc-gui](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)（MIT），经精简移植。

## 实现约束
- 优先复用现有代码和工具类，避免重复造轮子；禁止批量格式化历史代码
- 新增/修改代码必须补充注释/文档（Kotlin/Java 用 KDoc/Javadoc，前端关键逻辑加注释）
- **绝不杀任何进程**（沙箱 IDE 或用户本地 IDEA）——用户会手动关闭沙箱再重建；`buildPlugin`/`prepareSandbox` 因文件锁失败时立即报告，禁止通过杀进程解决
- 构建/验证期间不得 kill 进程；只在用户确认后才执行涉及进程的操作
- 前端（webview）**不持有权威数据**——模型、会话、上下文、状态统计等全部来自后端并通过 bridge 事件推送；本地存储仅限纯本地 UI 状态（输入历史、字号）
- 插件是**非 Dynamic Plugin**——安装/更新需重启 IDE，勿追求热卸载
- ToolWindow **懒加载**：打开项目不会启动 pi，打开 "Pi Chat" 窗口才拉起 backend（窗口一开即需 session/model/status 等数据）
- 插件 `println` 不会进 idea.log——运行时诊断必须用 `com.intellij.openapi.diagnostic.Logger`（或 slf4j）；关键诊断日志统一加 `[PiChatDiag]` 前缀便于 grep
- `gradle.properties` 的 `localIdePath` 仅本地恢复以跑沙箱（runIde 用本机 IDEA），**必须排除在 commit/push 之外**，公共仓库保持注释状态
- 会话标题/删除等操作要与终端 pi 行为对齐（如 `update_title` 镜像 TUI 的 `renameSession` 追加 `session_info` 记录）
- 插件与终端 TUI 是**独立 pi 进程**，仅共享会话 jsonl；插件进程收不到 TUI 的流式事件——历史渲染以直接读 jsonl 为准（loadHistory 直接读 currentSessionFile，靠 startSessionWatcher 轮询刷新），不要假设流式事件会来
- 历史会话解析注意两类坑：magic-context 压缩产生的 `&lt;thinking&gt;` 标签错位需 stripThinkingTags；assistant 消息的 `toolCall` 块 + `role=toolResult` 消息需按 toolCallId 配对，不能忽略

## 构建与验证
```
# 一键打包插件 zip（先 webview npm build，再 clean buildPlugin；输出 build/distributions/pichat.zip）
.\build.ps1
# 仅 Kotlin 改动时增量打包：
.\build.ps1 -NoClean

# 运行沙箱 IDE（本地 IDE 需在 gradle.properties 临时启用 localIdePath）
.\gradlew.bat runIde

# 前端构建（webview → src/main/resources/web/index.html）
cd webview && npm install && npm run build

# 前端单元测试（vitest）
cd webview && npm test
```
- **webview 改动后必须 `npm run build` 再打包**——否则把旧 HTML/JS/CSS 打进插件；`build.ps1` 已自动串联两步
- 仅 webview 改动（无 Kotlin 修改）时，Gradle 的 `composedJar` 会报 UP-TO-DATE 复用旧 jar，**必须用 `clean buildPlugin`** 强制重新打包（`build.ps1` 默认即此）
- 用户反馈"插件仍是旧/fallback UI"时：先核对**实际安装的 jar**（不是 build/distributions 里的），并确认 `idea64.exe` 已完全退出，再沿 webview 构建链排查
- 前端 bridge（JS→Java）依赖 `sendToJava` **直接嵌入 HTML**（JBCefJSQuery 注入的 `window.cefQuery_<hash>_<index>` 函数），勿改用 onLoadEnd/executeJavaScript 注入——remote JCEF（IDEA 2026.2 / JCEF 144）下 onLoadEnd 可能丢失

## 文档索引
| 文件 | 内容 |
|------|------|
| [`README.md`](README.md) | 项目介绍、功能、安装、构建命令 |
| [`build.gradle.kts`](build.gradle.kts) | 构建配置（平台版本、Jewel 依赖、Java 25 工具链、排除规则） |
| [`src/main/resources/META-INF/plugin.xml`](src/main/resources/META-INF/plugin.xml) | 插件声明（toolWindow、依赖、版本号） |
| [`webview/src/ARCHITECTURE.md`](webview/src/ARCHITECTURE.md) | webview 前端架构（App.tsx 分层、hooks、数据流） |

### 关键源码位置
```
src/main/kotlin/com/ruigu/pichat/ui/    Kotlin UI 层
  ├─ PiToolWindowFactory.kt            工具窗口工厂
  ├─ ChatPanel.kt                      主面板（JCEF 浏览器 + pi RPC bridge、loadHistory、sessionWatcher）
  └─ MarkdownText.kt                   富文本渲染
src/main/java/com/ruigu/pichat/rpc/     Java RPC 层（子进程启动、JSONL 协议、pi 定位）
  ├─ RpcClient.java                    JSONL 客户端（getState 超时 20s）
  ├─ PiLocator.java                    pi CLI 定位
  ├─ PiListener.java / ExtensionUiRequest.java / RpcResponse.java
src/main/java/com/ruigu/pichat/ui/      ChatMessage.java（消息模型，含 toolResultDetails）
webview/src/                            React 19 + Vite + Tailwind 4 前端
  ├─ App.tsx                           编排层（视图路由 + 自定义 hooks）
  ├─ hooks/                            useWindowCallbacks（Java bridge 回调）等
  ├─ components/                       ChatHeader / ChatInputBox / MessageItem / history / settings / StatusPanel / toolBlocks
  └─ utils/                            bridge / toolConstants / messageUtils / todoShared 等
```

## 常见问题速查
- **JCEF**：JCEF 的 CefBrowser 无 `getTitle()` / `onTitleChange`——不要用 document.title 轮询做 JS→Java 通道；`JBCefJSQuery`/`cefQuery` 在 `loadHTML`(data:) 页面上可用（jetbrains-cc-gui 即此方案）
- **前端/后端 model id 契约**：publishModels 推纯 model id（如 `deepseek-v4-flash`），前端 normalizeModels 拼 `provider::id` 发 set_model，须与后端 findModel 的 modelKey 一致
- **会话删除**：删除成功 toast 由前端负责（乐观删除），后端仅在失败时提示（文件占用→error、批量部分失败→warning）
- **上下文上限设置**在用户自己的 pi 插件（magic-context）里实现，不在 pi 核心——处理上下文限制相关需求时看插件实现，别找 pi settings.json 覆盖项
- **IntelliJ AI Assistant**（JetBrains AI）经 `acp.registry.pi-acp` 集成 pi，会 spawn 自己的 pi 进程、输出 `AvailableCommand(skill:...)` 到 idea.log——易误判为插件输出
- 多个并发 pi 实例争用 `~/.pi/agent` 可能挂起 pi init 导致 get_state 不返回、UI 空白——RpcClient 已设 20s 超时兜底
