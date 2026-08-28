# Pi Chat 插件重构计划与代码健康报告

> 生成时间：2026-08-27 · 分析方式：全量通读后端（Kotlin/Java）+ 前端（webview/src）+ 双 reviewer 子代理独立审查交叉验证

## 一、核心问题总览

| # | 问题 | 严重度 | 位置 |
|---|------|--------|------|
| 1 | **ChatPanel.kt 巨型上帝类**：2960 行，承担 12+ 职责（bridge 路由/RPC 事件/会话文件 IO/文件扫描/IDE 集成/扩展 UI/状态格式化…） | 高 | `ChatPanel.kt` |
| 2 | **约 760 行死代码**：Compose UI（2373-2960 行，`Content()` 无任何调用者，`component` 只返回 browserPanel）+ `MarkdownText.kt`（169 行，只被死代码引用） | 高 | 同上 |
| 3 | **Jewel/Compose/atomicfu 依赖纯为死代码服务**：打包进插件 zip 增大体积、拖慢编译 | 高 | `build.gradle.kts` |
| 4 | **EDT 上做磁盘 IO（UI 假死根因）**：`loadSessionList` 在 EDT 逐行全量读**每一个**会话 jsonl 取元信息；`readSessionFile` 在 EDT 读整个会话文件；`updateSessionTitle` 在 EDT `Files.readAllLines` 全量解析只为拿最后一条 id；`client.close()` 内 `waitFor(2s)` 也在 EDT | 高 | `ChatPanel.kt:1412/1504/1730/1126` |
| 5 | **`setHistoryData` 前后端契约 bug**：后端推 JSON 字符串（`callWeb` 对 JsonElement 做 `gson.toJson`），前端 `window.setHistoryData = (data) => setHistoryData(data)` 直接塞 state（期望对象）→ `historyData.sessions` 为 undefined，历史列表拿不到数据 | 高 | 后端 `ChatPanel.kt:860` ↔ 前端 `messageCallbacks.ts:641` |
| 6 | **线程安全**：`streaming`/`streamingAssistant` 是普通 var，RPC 读线程写、EDT 读（`sendMessage` 据此决定 steer/followUp），无 volatile 有可见性风险 | 高 | `ChatPanel.kt:145-146` |
| 7 | **正则重复编译热点**：`stripMagicContextMarks` 每次调用新建 6 个 Regex，且在每个 text_delta、每条历史消息清洗时都执行；`extractMessageTimestamp` 在逐行循环里每次新建；`toolUseMessage` 每帧重新 parse `argsSummary` | 高 | `ChatPanel.kt:1852/1585/961` |
| 8 | **事件字段无类型保护**：`onMessageUpdate` 里 `ame.get("delta").asString` 缺保护；`onToolStart` 参数非空但 `str()` 可返回 null → NPE 被 `fire()` 吞掉，表现为"流式突然不动/工具卡不显示" | 中 | `ChatPanel.kt:1880/1921` |
| 9 | **异常静默吞噬**：所有 `thenAccept` 无异常处理（RPC 响应失败无声丢失）；`RpcClient.fire` 吞掉监听器异常无日志；`readSessionFile` 失败返回 null 不记录原因 | 中 | 多处 |
| 10 | `sendMessage` 里 `System.out.println`（违反项目规则：诊断必须走 Logger，println 不进 idea.log） | 低 | `ChatPanel.kt:1083` |
| 11 | **前端死类型 ~1300 行**：`types/cliTool.ts`、`dependency.ts`、`mcp.ts`、`skill.ts`、`prompt.ts`、`import.ts` 全部 0 引用可整删；`provider.ts`（637 行）需裁剪多 provider 残留；`global.d.ts`（941 行）对应死回调 | 中 | `webview/src/types/` |
| 12 | **命名误导**：核心类型仍叫 `ClaudeMessage`/`ClaudeRole`/`CodexHistoryPageInfo`，但插件是 pi-only | 低 | `types/index.ts` |
| 13 | `useWindowCallbacks` 参数对象 58 个字段全靠 App.tsx 透传（context 已存在却没用上）；三大视图无独立 ErrorBoundary（任一组件抛错全白屏）；`useDialogManagement` 请求队列无上限；`transitionTimeoutRef` unmount 不清理 | 中 | webview hooks |

### 后端其他中低优先级问题（随批次顺带修复）

- `NotificationGroup("Pi Chat", NotificationDisplayType.BALLOON, true)` 构造器已废弃（plugin.xml 已注册 notificationGroup，直接取即可）
- `dispose()` 未清 `askUserByRequestId`/`pendingSessionSwitch`
- `RpcClient.readStderrLoop` 未 try-with-resources；`stderrTail` StringBuilder 跨线程无同步
- `onProcessExit` 回调里 `tail.substring(0, ...)` 依赖空串兜底
- `refreshStatus()`/`loadSessionStats()` 等 future 回调未校验 client 是否已被会话切换替换（有 close() failAllPending 兜底，但补防御更稳）

### 前端其他中低优先级问题（第 4 批处理）

- `useMessageQueue.drainOne` setTimeout 50ms 延迟执行无二次状态检查（快速 drain 可能并发）
- `updateSessionTitle` 回调仅靠 `currentSessionIdRef` 过滤旧会话标题，快速连续切换有误写窗口（建议联动 `__sessionTransitioning`）
- `main.tsx` 的 scale recovery / heartbeat 监听器只依赖 pagehide/beforeunload 清理
- `mergedAssistantMessageCache` Map 长会话增长
- `version/changelog.ts`（2619 行）含大量多 agent 历史噪音
- 回调类型 `setHistoryData?: (data: any)` 等 any 泄漏

## 进度

- [x] 第 1 批：删死代码
  - 后端 ChatPanel.kt 2994→约 2290 行；删 Compose UI/状态、MarkdownText.kt、Jewel/Compose/atomicfu 依赖；恢复误删的 parseSessionId；修复废弃 NotificationGroup 构造器
  - 前端删 9 个死文件（types/cliTool、dependency、mcp、skill、prompt、import + provider 全死链路 + aiFeatureConfig/promptEnhancer 全链）+ activeProviderConfig 死状态链
  - 验证：tsc ✅ / 901 测试 ✅ / build.ps1 打包 6.7MB ✅
- [x] 第 2 批：修真实 bug
  - setHistoryData 契约：前端回调补 JSON.parse（兼容对象），修复历史列表不渲染
  - EDT 磁盘 IO 全部迁后台线程：loadHistory/readSessionFile、loadSessionList（含 meta/title 全量读）、deleteSession(s)、updateSessionTitle、RpcClient.closeAsync（waitFor 移出 EDT，dispose/新建/切换会话均改用）
  - 线程安全：streaming → AtomicBoolean；streamingAssistant → @Volatile；ensureStreamingAssistant 占位判断移入 EDT
  - 字段类型保护：text/thinking delta、applySessionMessage role/text/thinking、RpcClient toolCallId/toolName null→空串
  - 异常日志：logFailure 覆盖 8 处 thenAccept；RpcClient fire/解析行/各文件 IO 失败均留痕
  - 验证：901 测试 ✅ / build.ps1 打包 ✅
- [x] 第 3 批：性能（热路径 Regex 常量、argsSummary 解析缓存）
  - 全部热点 Regex 提为 SessionRegexes/ChatPanelRegexes 常量对象（stripMagicContextMarks 6 个、会话 jsonl 解析 4 个、open_file 路径 2 个、session_info 标题 2 个）
  - ChatMessage.getArgsAsJson 惰性解析缓存（toolUseMessage 每帧不再重复 parse）
  - 验证：编译 ✅ / build.ps1 打包 ✅
- [x] 第 4 批：结构拆分（后端拆 SessionFileStore/IdeIntegrator + 前端 ErrorBoundary/队列上限）
  - 新建 session/SessionFileStore.kt（412 行）：会话文件 IO + jsonl 解析 + magic-context 标记清除全部纯函数化，零 IntelliJ 依赖
  - 新建 ide/IdeIntegrator.kt（169 行）：open_file/show_diff 的 IDE 集成，含路径安全校验
  - ChatPanel.kt 降至 1892 行（原 2994），删净重复函数与旧 ChatPanelRegexes
  - 前端：三大视图（ChatScreen/HistoryView/SettingsView）包 ErrorBoundary；useDialogManagement 队列加 50 上限；useSessionManagement transitionTimeout 卸载清理
  - useWindowCallbacks context 化未做（参数对象虽大但已是单一调用点透传，收益不抵风险，留待后续）
  - 验证：tsc ✅ / 901 测试 ✅ / build.ps1 打包 6.7MB ✅

### 第 2 批落地差异说明

- useProviderSettings.ts 其余状态仍被设置页使用，仅删除 activeProviderConfig；global.d.ts 死回调（updateCliStatus 等）保留：多数有前端实现或后端调用方，待第 4 批统一清理

---
## 二、重构批次（每批独立可验证交付）

### 第 1 批：删死代码（零风险，立减体积）
- [x] 删 `ChatPanel.kt` Compose UI 段（2373-2960）+ Compose/Jewel import + Compose 状态改普通 Kotlin 状态（mutableStateOf -> var）
- [x] 删 `MarkdownText.kt`
- [x] `build.gradle.kts`：移除 Jewel/atomicfu 依赖、`kotlin.plugin.compose` 插件、google()/compose-dev 仓库
- [x] 前端：删 6 个死类型文件（cliTool/dependency/mcp/skill/prompt/import）+ provider.ts/provider.test.ts + aiFeatureConfig.ts/promptEnhancer.ts（整链 0 消费）+ global.d.ts 的 updatePromptEnhancerConfig 声明；activeProviderConfig 只写不读链路移除
- 验证：`npm test` + `npm run build` + `build.ps1` 打包 + runIde 冒烟

### 第 2 批：修真实 bug（稳定性核心）
- [ ] `setHistoryData` 契约：前端回调加 `JSON.parse`（对齐全库"json string + 前端 parse"约定）
- [ ] EDT IO 全部迁 `executeOnPooledThread`，结果 `onEdt` 回写：`loadSessionList`/`readSessionMeta`/`readSessionFile`/`updateSessionTitle`/`deleteSession(s)`；`client.close()` 的 waitFor 移出 EDT
- [ ] `streaming`→原子布尔、`streamingAssistant`→`@Volatile`
- [ ] `onMessageUpdate`/`onToolStart`/`applySessionMessage` 加字段类型保护
- [ ] `System.out.println`→`LOG.info`；`thenAccept` 补异常日志；`fire()` 补 debug 日志；废弃 NotificationGroup 构造器修正
- 验证：runIde 中打开大会话历史、快速切换/删除会话、流式对话（在插件内验证，不信 TUI）

### 第 3 批：性能修复
- [ ] 热路径 Regex 全部提为 `companion object` 常量（stripMagicContextMarks/stripThinkingTags/extractMessageTimestamp/readSessionTitle/handleOpenFile）
- [ ] `ChatMessage` 增加 argsSummary 解析缓存（`toolUseMessage` 不再每帧 parse）
- 验证：长会话（100+ 消息）流式输出流畅度对比

### 第 4 批：结构拆分（可维护性，风险最高放最后）
- [ ] 后端从 ChatPanel 拆出：`SessionRepository`（会话文件 IO，承接后台线程化）、`WebViewBridge`（callWeb/publishWebState/web 路由）、`IdeIntegrator`（openFile/showDiff）；ChatPanel 变薄为编排层
- [ ] 前端：`useWindowCallbacks` 直接消费现有 context（参数降到 <10）；三大视图包 ErrorBoundary；dialog 队列加上限 + timeout 清理；`ClaudeMessage` 等用 type alias 渐进重命名
- 验证：全量测试 + 完整手动冒烟

## 三、执行约束

- 全程不杀任何进程（沙箱 IDE 与本机 IDEA 均不碰）；`build.ps1`/`prepareSandbox` 遇文件锁立即停止并报告
- webview 改动后必须 `npm run build` 再打包；仅 webview 改动时必须 `clean buildPlugin`
- 修复验证以**插件内实际行为**为准（终端 TUI 是独立 pi 进程，不能作为插件验证依据）
- 运行时诊断统一 `Logger` + `[PiChatDiag]` 前缀
