# 开发

## Android 手环语音

`androidApp/.../agent/band` 管理 Android 专用 BLE 适配器、各连接的协议状态、安全外设注册表、本地语音模型和前台服务。UI 操作通过 `PeripheralHost` 执行；识别、解码、磁盘和加密工作不占用 UI 线程。对话与消息存储继续使用已有共享 Room schema，本功能不改变它。

小米手环 6 NFC 传输使用 2021 扩展通道，特征为 `00000016`（写）和 `00000017`（通知），带 Huami UUID 后缀。认证端点 `0x82` 使用 NIST B-163 和已配对设备密钥。语音端点 `0x10` 携带带长度前缀的 20 ms Opus 帧。下行语音命令使用已认证会话的 AES、CRC 和序列号。本固件要求命令 `03` 包含非空建议；设备测试中，空列表会导致没有麦克风音频。命令 `07 00`、`07 01` 和 `09` 分别携带中间识别结果、最终识别结果和回复文本。协议研究已与厂商 APK 和 [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) 交叉核对。

Android MediaCodec 解码原始 Opus；Vosk 使用解码器实际的 PCM 采样率，提供本地流式识别和端点检测。`ReplyWorker` 携带绑定与会话 ID，避免完成的工作回传到其他或已被取代的语音输入。断开或解绑时关闭 codec、recognizer 和 GATT 资源。每次语音输入使用有时限的唤醒锁。诊断日志不得记录音频、转录文本、MAC 地址及配对或会话密钥。

模型从官方 Vosk HTTPS URL 下载，限制 ZIP 解压范围，并通过暂存后发布。绑定使用 Keystore AES-GCM 和 `noBackupFilesDir`，模型也不参与备份。不要提交设备配置或录音。

运行聚焦检查：

```sh
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug validateDebugScreenshotTest
./gradlew :appiumTests:test --tests '*PeripheralsTest'
```

设备操作前取得共享设备租约。Appium 测试会清除 debug 应用数据，应在配置真实凭据和模型之前运行。可选的真实解码器测试需要在 debug 应用的 `no_backup/band-speech/` 放置已解压的 `vosk-model-small-cn-0.22` 文件夹，并在 `cache/band-test-opus.bin` 放置获准使用、带长度前缀的 codec-1 Opus 录音，再运行 `:androidApp:connectedDebugAndroidTest`。缺少这些私有夹具时语音测试跳过。自动检查后，使用实体手环手动验证实时中间文本、最终消息路由、回复和解绑。

## Android 应用 ID

| 构建类型 | 应用 ID |
| --- | --- |
| debug | `com.storyteller_f.dush.debug` |
| release | `com.storyteller_f.dush` |
| alpha | `com.storyteller_f.dush.alpha` |

三个构建可同时安装。Android 为每个应用 ID 分配独立数据目录，包括 Room 数据库、语音模型文件和偏好设置。namespace 与 Kotlin 包名保持 `com.storyteller_f.dush`。

通过 `./gradlew :androidApp:assembleDebug`、`:androidApp:assembleRelease` 或 `:androidApp:assembleAlpha` 构建。Windows 使用 `./gradlew.bat`。

EasyLauncher 为 debug 启动图标添加蓝色 `DEBUG` 飘带，为 alpha 图标添加橙色 `ALPHA` 飘带，包括圆形图标。Release 图标没有飘带。Alpha 使用 `-alpha` 版本名后缀；若提供 release 签名配置，则使用该配置。CI 在 main 分支推送时构建 alpha，标签触发时构建 release。

Alpha 取代原 daily 构建类型，以 `.alpha` 代替 `.daily`。已安装的 daily 构建保留自己的数据，alpha 不自动迁移。

新的 debug 应用 ID 使用独立数据目录。`com.storyteller_f.dush` 下的已有数据不会自动复制到 debug 应用。

Appium 测试任务安装 debug，默认目标为 `com.storyteller_f.dush.debug`。已明确准备好其他构建时，可通过 `APP_PACKAGE` 覆盖目标包。默认 Activity 是完整限定名 `com.storyteller_f.dush.MainActivity`，因为类所在的包不随应用 ID 后缀变化。

## Room schema 归属

`AgentDatabase` 及其实体、DAO 和迁移属于 `shared/commonMain`。Android 与 JVM KSP 任务统一使用 `shared/schemas` 作为权威 schema 历史。所有版本化 JSON 文件都保留在这里；版本 1、2、3，以及已有 `MIGRATION_1_2` / `MIGRATION_2_3` 迁移链保持不变。

针对 [issue #18](https://github.com/storytellerF/dush/issues/18)，已评估按应用构建类型拆分 schema，并有意不配置：

- `shared` 使用 `com.android.kotlin.multiplatform.library`，其[单变体架构](https://developer.android.com/kotlin/multiplatform/plugin#key-features-and-differences)不支持构建类型或 product flavor。应用的 debug、release 和 alpha 变体消费同一份编译后的数据库定义。
- Schema JSON 是编译期历史，不是运行时数据库文件。运行时通过上述应用 ID 隔离；拆分 schema 文件夹不会增加数据隔离。
- 将同一历史复制到 debug、release 和 alpha 目录，会产生没有对应编译变体却独立维护的副本。根据请求的任务名称或全局属性选择 schema 目录，也会让组合构建依赖调用细节。

将来数据库定义确实分化时，先引入显式编译或模块边界，从已有文件建立每条 schema 历史，并为每条演进路径定义和验证迁移。不要丢弃历史 schema 或重置数据库版本来实现构建隔离。

数据库变更后，运行 `./gradlew :shared:kspAndroidMain :shared:kspKotlinJvm`，审查生成的 schema 改动。一起构建 Android 变体，检查应用 ID，而不改变共享 schema 配置：

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease :androidApp:assembleAlpha
```

对话设置中的 Agent 选择由 `ThreadAgentSettingsHost` 协调，观察对话、Agent 和推理连接列表。通过 DAO 定向更新现有 `agentId` 字段，避免覆盖同时更新的标题或气泡设置；不改变数据库结构。手环在每次提交识别结果时读取当前对话的 Agent。

手环回复通过 ReplyWorker 观察当前 worker 的 Streaming 消息，合并积压的累计文本；取消并等待观察任务结束后发送最终快照。BandConnection 在同一会话中用 BandReplyStream 对前 300 个 Unicode 字符执行 600ms trailing debounce，最终快照绕过等待立即发送，不分页。会话 ID 隔离迟到回复；解绑和重连销毁当前显示状态。不会自动修改 Agent 系统提示词。命令 09 使用字符限额，ASR 命令 07 保留原字节限额。

首次绑定研究：对当前手环仅发送认证模块 0x82 的能力查询 00，响应 `10 00 01 01 02 00 00`，按官方解析为 version=1、apiLevel=2、allowLocalBinding=0。官方 AuthModule2/w6 首次绑定路径调用云端签名并在手环校验后建立 ECDH 认证密钥。现阶段不提供纯本地首次绑定；研究不应对已有设备发送 startLocalBinding/startCloudBinding/verifySignature 或解绑、重置命令。已有密钥的认证与首次绑定必须分开处理。

外设中心：`PeripheralStore` 将外设注册和可选的 thread 关联分开。沿用 `band-connections.enc` 和原 Keystore alias，读取旧 JSON 时为缺失的名称/连接开关提供默认值，保留原记录 ID、地址、密钥和 thread。解除对话绑定只清空 thread，不删除注册。服务按 ID 协调每个连接的生命周期，添加或修改其他外设不会中断现有连接；改名不改变连接身份，绑定变化会取消旧语音会话。未下载 ASR 模型也可连接读取设备信息。

设备详情通过已认证 GATT 连接串行读取 Huami 电量信息和标准 Device Information 特征；刷新请求交给连接所有者执行。`PeripheralHost` 使用应用注入的后台串行 dispatcher，UI 只渲染不可变状态并处理权限、文件选择和导航。首次添加仅接受当前支持的小米手环 6 NFC 地址与已有密钥，不触发厂商配对。Gadgetbridge 仅用作协议核对资料，未引入其应用源码或声明其他型号已受支持。

`PeripheralStoreDeviceTest` 使用独立缓存目录覆盖旧配置迁移、加密持久化、解绑保留凭据、重复地址和移除；`PeripheralsTest` 覆盖页面操作，保留实际设备数据并只操作离线虚拟外设。


## 外设健康数据

`BandHealthConnection` 共享现有连接管理者的事件循环。步数使用 Huami 特征 7，心率通知使用标准 Heart Rate Measurement 特征，单次测量使用手动控制命令。活动历史使用特征 4/5（类型 01 和 02）、带计数器校验且有界的传输，以及包含时区的手环起始时间戳。不发送活动确认或删除命令 03，因此可重复读取。历史解析器和协议选择已与 Gadgetbridge 的 Huami 获取实现核对；没有引入 Gadgetbridge 应用源码或库依赖。

手环 6 活动采样是每分钟 8 字节记录。心率 0/255 表示缺失值，不是测量值。睡眠仅采用上报类别 120，不使用推测的深睡或 REM 阈值。夜间汇总范围为前一天本地中午至当天中午或当前时间。缺失睡眠标记保持未知，不能声称睡眠为零。历史每日步数为已收到分钟记录之和，可能不完整；主要的当日计数另行定时读取。日期使用 `java.time`，包括带符号的 BLE 一刻钟时区偏移和夏令时。

`PeripheralHealthStore` 通过 `EncryptedJsonFile` 去重并保留 31 天的分钟与心率采样，使用独立 Keystore alias，且不参与 Android 备份。同一个封装辅助方法保留已有外设凭据文件格式和 alias。已删除设备 ID 在内存中标记退役，防止迟到的连接写入重建已删除的健康数据。日志不得记录原始健康包、传感器值、关联用户的时间戳或工具上下文。

`PeripheralHealthTool` 将每次读取限制在当前对话绑定范围，并在挂起刷新或 I/O 后重新检查归属。它不包含蓝牙地址和凭据。`KoogAgentRunner` 在生成前调用此只读适配器，将带时间戳的摘要作为临时运行时上下文，不修改已保存的 Agent 系统提示词。这是自动上下文提供，不是模型发起的工具执行。摘要发送给选定推理服务，不追加到已保存历史或 Agent 设置。已连接读取在 BLE 管理者上尝试有界步数刷新；历史与测量保留实际采样时间。取消继续传播，缓存读取失败不妨碍普通聊天回复。

测试：协议计数器、畸形或截断数据、心率哨兵值、跨午夜和夏令时的睡眠汇总，以及跨对话隔离均有单元测试。`PeripheralHealthStoreDeviceTest` 覆盖加密、保留策略、重复同步和已删除设备竞态。`PeripheralHealthRuntimeDeviceTest` 验证健康上下文到达模型适配器且不修改已保存的 Agent 提示词。`PeripheralHealthTest` 针对已添加的实体外设运行非破坏性的同步与 UI 测试。

## 推理服务架构

`shared/commonMain` 管理 `ConnectionRepository`、`AgentRepository`、`ChatRepository`、`ConnectionHost`、`InferenceRuntime`、`KoogInferenceRuntime` 和 `KoogAgentRunner`。UUID 与时间使用 Kotlin 公共 API。Koog 1.3 提供方客户端和显式注入的 Ktor 传输遵循 summer 的集成方式，不打包完整 agent 包或 LiteRT 运行时。OpenAI 兼容 HTTP（含桌面 llmd）使用 Chat Completions SSE。每次收集自行管理并关闭客户端和传输。提供方错误映射为安全消息，不保留响应正文、URL、凭据或异常原因。绝不为这些客户端启用 HTTP 正文或请求头日志。

`ConnectionSecrets` 是平台凭据边界；Android 复用现有 Keystore／原子文件辅助方法，在 `noBackupFilesDir` 使用独立 alias 和文件。数据库实体只存元数据。Android 备份已禁用，避免恢复加密凭据文件却没有对应 Keystore 密钥。`AgentServices` 为两个平台组合共享仓库和运行器。`DesktopGraph` 提供随包 SQLite 驱动、所有已有迁移、OkHttp 和原生凭据存储适配器，不导入 Android 类型。

`ConnectionHost` 在应用注入的后台串行调度器上协调编辑器操作，作用域随页面释放。API 密钥只存临时状态，绝不使用 `rememberSaveable`。测试使用合成内容，不使用用户历史。

`AgentEditorHost.close()` 发出取消请求并返回作用域 Job。独立拥有数据库的调用方须先 `close().join()`，等待连接观察任务退出后再关闭 Room；只请求取消可能使 SQLite 语句清理与数据库关闭竞争。组合内的编辑器仍归属 `WorkspaceHost`，由其挂起关闭过程等待子任务退出。生命周期回归通过受控的观察任务清理验证此边界。

Schema v4 新增 `connections` 和 `agents.connectionId`。旧 `models` 元数据／表及 `modelId` 列仅为保留 schema 演进路径而存在，不参与文件操作或本地推理。迁移 3→4 清除本地关联，将中断回复标为 Canceled，同时保留历史、Agent、对话关联和气泡设置。版本 1–3 不变；schema v4 由 KSP 生成。删除连接、验证／保存 Agent 连接和默认选择使用 DAO 事务。凭据存储在 SQLite 之外：保存中断可能留下未使用的加密密钥，但不会发布未配置完成的连接。凭据写入失败时元数据不变。任何凭据写入前先验证必需凭据，因此被拒绝的清空操作保留原有密钥和元数据。

`AndroidInferenceRuntime` 通过复制的公开 llmd AIDL 契约调用 LlmdAndroid。它以 AUTO_CREATE | IMPORTANT 绑定，检查 authorization、engineReady 和 activeModel，并限制 Binder 请求大小。授权由用户从编辑器发起。当前服务契约只有一个完成回调，没有远程取消 RPC；本地取消通过解绑和丢弃迟到结果实现，不声称 llmd 内推理已经停止。它绝不启动或切换 llmd 模型服务。

WorkManager、前台通知、完成通知、气泡和手环路由仍属于 Android 适配器。每个对话使用唯一 REPLACE 任务，新用户消息到达时取消前一个回复；取消通过有界清理将原助手行标为 Canceled。失败更新同一行，保留部分输出，不自动重试。每次回复开始时，DAO 事务先将该对话未完成的助手行标为 Canceled，再插入新行，包括进程死亡后 WorkManager 重启同一 worker 的情况。发送与停止控件观察实际唯一 WorkManager 任务状态，不把持久化的 Streaming 行当作活动任务证据。后续请求只纳入 Complete 消息。每次运行读取有范围限制的外设上下文，仅临时使用；已保存 Agent 提示词不变。

验证：

```sh
./gradlew :shared:testAndroidHostTest :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :desktopApp:compileKotlin
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.storyteller_f.dush.agent.band.PeripheralHealthRuntimeDeviceTest
./gradlew :appiumTests:test --tests '*ConnectionsScreenTest' --tests '*NavigationTest'
```

安装或操作设备的命令执行前，取得共享 Android 设备租约。JVM 测试覆盖真实 Room 持久化与迁移、编辑器 Host、运行器失败和取消，以及 Koog HTTP 契约。Android 设备测试覆盖 Binder 契约、断开和取消路径、加密凭据及外设上下文隔离。Android 手动冒烟流程保留已有应用数据，验证连接测试／保存／重启、通过 ReplyWorker 的真实 llmd 聊天及取消。完整 Appium 套件会清除 debug 数据；未选择隔离包时，不要针对已配置的安装运行。可选 `realOpenRouterStreaming` 测试仅在测试进程中有 `DUSH_RUN_OPENROUTER_TEST=1` 和 `OPENROUTER_API_KEY` 时运行；它向 `openai/gpt-4.1-nano` 发送合成提示词，断言输出非空，不打印密钥或响应。不要以 Gradle 属性或参数传入凭据。

可选的真实 llmd HTTP 测试需在 `:shared:jvmTest` 前设置 `DUSH_TEST_LLMD_URL`（包含 `/v1`），并将 `DUSH_TEST_LLMD_MODEL` 设为已安装模型的精确 ID。真实 Android llmd 验证需要已安装服务、已导入模型、服务启用且 Ready，以及用户批准 dush 包名／签名。已分别检查 llmd 授权页 Cancel（仍拒绝访问）和 Allow（聊天成功）。手动 dush 流程还验证 ReplyWorker 聊天、取消及取消后的新回复。

配置并授权服务后，运行按需启用的真实服务回归测试：

```sh
./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.storyteller_f.dush.agent.runtime.RealLlmdCrossAppDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.llmdPackage=com.storytellerf.llmd.debug \
  -Pandroid.testInstrumentationRunnerArguments.llmdModel=gemma-4-E2B-it
```

缺少任一参数时这些测试跳过。它们使用内存 dush 数据库和合成提示词。取消覆盖保留第二个 Binder 绑定，让 llmd 能完成旧请求并交付迟到回调；随后执行另一次真实推理，检查被取消行没有变化。测试不声称存在远程取消 RPC，也不停止其他客户端正在使用的模型。调用方管理的截止时间仍表现为协程取消；只有适配器自己的截止时间才转换成安全的服务超时消息。

## 桌面／JVM 工作区

`WorkspaceHost` 在注入的后台串行调度器上管理窗口导航、观察列表、对话选择和命令。共享连接／Agent 编辑器内容及 `SharedDushTheme` 也供 Android 使用；Android 提供动态颜色和 llmd 授权。WorkManager、通知、气泡和 BLE 仍是 Android 适配器。未发送消息草稿由窗口 Host 按对话管理。导航恢复选定草稿，提交消息成功后移除。草稿仅保存在内存，不写入聊天历史，也不在应用退出后恢复。每个打开的对话有独立 `ThreadHost`、Room 生成的 `PagingSource` 和缓存在该 Host 作用域中的 `Pager`。Room 失效机制在数据库变化（含流式文本）后刷新消息。初始与后续页大小为 50，预取距离为 5，禁用占位符。`LazyPagingItems` 通过 Paging 的 refresh／append 加载状态处理访问提示和重试，没有并行维护的手动历史缓存或重试状态机。推理仍单独读取完整上下文。导航立即发布新 Host，并请求取消旧 Host，不等待其结束。旧分页任务无法访问新 Host 状态。清理完成前，各对话作用域仍是窗口作用域的子级，因此窗口关闭会等待所有退役会话结束后再关闭数据库。无需数据库 schema 变更。列表使用反向布局，在视觉底部保留稳定的首个哨兵。靠近历史边缘时再加载 50 条消息，并保留稳定消息键。在底部时由哨兵锚定并跟随新文本。用户向上滚动后，定位完全交给 LazyColumn：没有显式滚动命令或高度补偿，正在增长的回复内部也不例外。

`ForegroundReplies` 管理应用生命周期内的回复。命令通道在跨越挂起点时串行处理发送、替换和停止，替换前等待已取消管理者结束，忽略过时完成事件。停止控件仅由活动任务驱动。接收工作前，将遗留 Pending／Streaming 助手行标为 Canceled。关闭时等待运行器清理，再关闭 Room。`DesktopGraph` 在数据库整个生命周期持有操作系统文件锁，防止其他进程针对活动回复执行恢复。无需 schema 变更。JVM 入口使用 `awaitApplication`：默认阻塞的 `application` 入口会终止进程，绕过外围关闭清理。关闭窗口必须先到达安全日志 `operation=shutdown outcome=complete`，再退出进程。

`DesktopConnectionSecrets` 使用 [java-keyring](https://github.com/javakeyring/java-keyring) 1.0.4，并在 IO 上串行执行原生调用。由于各后端的条目缺失异常不统一，使用不含机密的哈希标记文件追踪本次安装写入的密钥。先写原生条目，再写标记和元数据；崩溃可能留下未使用的原生条目，但绝不会发布密钥尚未写入的已配置连接。不要单独移动凭据标记目录。删除时先写入空原生封装，再删除原生条目和标记；标记清理中断后可安全重试，不会把凭据存储访问错误当作条目缺失。权威标记目录身份为原生条目划分命名空间。API 密钥、请求、响应正文、用户文本及原始异常不得进入诊断日志。桌面使用 Kermit 控制台输出器，仅记录安全的启动和关闭事件。

在仓库根目录运行（Windows 使用 `./gradlew.bat`）：

```sh
./gradlew :desktopApp:run
./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

启动冒烟测试前，将 `DUSH_DATA_DIR` 设为隔离的绝对目录。不要针对已配置的用户环境运行自动化。桌面测试覆盖 graph 持久化、中断回复恢复和独占管理；原生凭据往返与清除测试在 Windows 使用唯一合成记录，结束后删除。macOS／Linux 凭据后端需要在相应系统手动验证。共享调度器测试覆盖替换顺序、停止、失败、关闭清理和进程重启。

桌面手动流程：配置／测试／保存 HTTP 连接，创建／编辑 Agent，创建聊天，观察部分和最终回复，停止回复，再次发送，回复期间切换页面，重启后检查历史与设置。合成 SSE 夹具验证传输和接线，但不能证明真实模型推理。真实 llmd／OpenRouter 测试仍按上述方式显式启用。iOS／Web target 及其旧模板源码继续禁用。

`DesktopRealServiceTest` 通过真实桌面组合、原生凭据存储和 OpenRouter 传输添加按需验收。测试进程环境中设置 `DUSH_RUN_OPENROUTER_TEST=1` 和 `OPENROUTER_API_KEY` 后运行：

```sh
./gradlew :desktopApp:test --tests '*DesktopRealServiceTest' --rerun
```

它向 `openai/gpt-4.1-nano` 发送合成提示词，消耗服务配额。测试覆盖连接测试、流式响应期间导航、停止后再次回复、流式响应期间关闭，以及重新打开持久化历史和凭据。结束后删除临时原生凭据，断言绝不包含密钥。在已登录测试用户的会话运行：Windows SSH 登录不能提供与交互登录相同的凭据存储访问；Linux 需要该桌面的 D-Bus 会话和已解锁的 Secret Service。不要用内存密钥存储代替此验收检查。

Linux 原生 UI 自动化还需要运行时 classpath 中的 Java ATK wrapper，以及 `-Djavax.accessibility.assistive_technologies=org.GNOME.Accessibility.AtkWrapper`。Ubuntu 安装预构建的 `libatk-wrapper-java` 和 `libatk-wrapper-java-jni` 包，并将 `/usr/share/java/java-atk-wrapper.jar` 加入 classpath。Appium 和应用须运行在同一显示与 D-Bus 会话。仅有可见窗口不能证明 AT-SPI 能发现控件。向其他操作系统传输 host 构建的类时，包含匹配目标平台的 Skiko 运行时。

Intel macOS 还需要额外 SQLite JNI 运行时：当前 `sqlite-bundled-jvm:2.7.0` 产物包含 macOS ARM64，但没有 `osx_x64` 二进制。classpath 中没有兼容的预构建 `natives/osx_x64/libsqliteJni.dylib` 时，数据库无法在 Intel macOS 启动。Intel 验收使用单独在 host 构建的 SQLite 2.7.0 JNI 库，普通应用发行包不包含它；不要将该次运行当作默认 Intel macOS 包的验证。原生启动失败会以错误完成就绪状态，让 graph 能关闭数据库并释放数据目录锁。macOS 凭据验收须在图形登录会话运行；应用首次读取由其他启动器创建的凭据时，批准系统 Keychain 提示。
