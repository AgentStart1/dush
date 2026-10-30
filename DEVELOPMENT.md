# Development

## Android band voice

`androidApp/.../agent/band` owns the Android-only BLE adapter, per-connection protocol state, secure peripheral registry, local speech model and foreground service. UI actions go through `PeripheralHost`; recognition, decoding, disk and cryptographic work stay off the UI thread. Thread/message storage remains in the existing shared Room schema; this feature does not change it.

The Band 6 NFC transport uses the 2021 extended channel on characteristics `00000016` (write) and `00000017` (notify), with the Huami UUID suffix. Authentication endpoint `0x82` uses NIST B-163 and the previously paired device key. Voice endpoint `0x10` carries length-prefixed 20 ms Opus frames. Downlink voice commands use the authenticated session's AES/CRC/sequence. This firmware requires nonempty suggestions in command `03`; an empty list led to no microphone audio in device tests. Commands `07 00`, `07 01` and `09` carry intermediate recognition, final recognition and reply text respectively. Protocol research was cross-checked against the vendor APK and [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge).

Android MediaCodec decodes raw Opus; Vosk receives the decoder's actual PCM sample rate and provides local streaming recognition and endpoint detection. `ReplyWorker` carries binding/session IDs so completed work cannot echo to a different or superseded utterance. Disconnect or unbind closes codec/recognizer/GATT resources. A bounded wake lock covers each utterance. Audio, transcripts, MAC addresses and pairing/session keys must not be written to diagnostic logs.

Model downloads use the official Vosk HTTPS URL, bounded ZIP extraction and staged publication. Bindings use Keystore AES-GCM and `noBackupFilesDir`. The model also stays outside backup. Do not commit device configuration or recordings.

Run focused checks:

```sh
./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug validateDebugScreenshotTest
./gradlew :appiumTests:test --tests '*PeripheralsTest'
```

Acquire the shared device lease before device operations. Appium tests clear debug app data; run them before provisioning real credentials/models. For the optional real-decoder test, provision the extracted `vosk-model-small-cn-0.22` folder under the debug app's `no_backup/band-speech/` and an authorized, length-prefixed codec-1 Opus recording at `cache/band-test-opus.bin`. Then run `:androidApp:connectedDebugAndroidTest`. The speech test skips when those private fixtures are absent. After automated checks, manually verify live partial text, final message routing, replies and unbind with a physical band.

## Android application IDs

| Build type | Application ID |
| --- | --- |
| debug | `com.storyteller_f.dush.debug` |
| release | `com.storyteller_f.dush` |
| alpha | `com.storyteller_f.dush.alpha` |

The three builds can be installed together. Android gives each application ID its
own data directory, including the Room database, speech model files and preferences.
The namespace and Kotlin package names remain `com.storyteller_f.dush`.

Build with `./gradlew :androidApp:assembleDebug`, `:androidApp:assembleRelease`
or `:androidApp:assembleAlpha`. On Windows, use `./gradlew.bat`.

EasyLauncher adds a blue `DEBUG` ribbon to debug launcher icons and an orange
`ALPHA` ribbon to alpha launcher icons, including the round icons. Release icons
have no ribbon. Alpha uses the `-alpha` version-name suffix and the release
signing configuration when it is provided. CI builds alpha for main-branch
pushes and release for tags.

Alpha replaces the former daily build type and uses `.alpha` instead of `.daily`.
An installed daily build keeps its own data; alpha does not automatically migrate it.

The new debug application ID starts with a separate data directory. Existing data
under `com.storyteller_f.dush` is not automatically copied into the debug app.

The Appium test task installs debug and defaults to `com.storyteller_f.dush.debug`.
`APP_PACKAGE` can override the target package for an explicitly prepared build.
The default activity is the fully qualified `com.storyteller_f.dush.MainActivity`,
because its class package does not follow the application ID suffix.

## Room schema ownership

`AgentDatabase`, its entities, DAOs and migrations belong to `shared/commonMain`.
The Android and JVM KSP tasks use `shared/schemas` as the canonical schema history.
Keep all versioned JSON files there; versions 1, 2 and 3 and the existing
`MIGRATION_1_2` / `MIGRATION_2_3` migration chain remain unchanged.

For [issue #18](https://github.com/storytellerF/dush/issues/18), schema separation
by application build type was evaluated and is intentionally not configured:

- `shared` uses `com.android.kotlin.multiplatform.library`, whose
  [single-variant architecture](https://developer.android.com/kotlin/multiplatform/plugin#key-features-and-differences)
  does not support build types or product flavors. The application's debug,
  release and alpha variants consume the same compiled database definition.
- Schema JSON files are compile-time history, not runtime database files.
  Runtime isolation comes from the application IDs above; separate schema folders
  would not add runtime data isolation.
- Copying the same history into debug/release/alpha directories would create
  independently maintained copies without corresponding compilation variants.
  Selecting a schema directory from requested task names or a global property
  would also make combined builds depend on invocation details.

If database definitions actually diverge in future, first introduce explicit
compilation/module boundaries, seed each schema history from the existing files,
and define and verify migrations for each lineage. Do not discard historical
schemas or reset the database version to implement build isolation.

After changing the database, run `./gradlew :shared:kspAndroidMain :shared:kspKotlinJvm`
and review the generated schema changes. Build the Android variants together to
check their application IDs without changing the shared schema configuration:

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease :androidApp:assembleAlpha
```

对话设置中的 Agent 选择由 `ThreadAgentSettingsHost` 协调，观察对话、Agent 和推理连接列表。通过 DAO 定向更新现有 `agentId` 字段，避免覆盖同时更新的标题或气泡设置；不改变数据库结构。手环在每次提交识别结果时读取当前对话的 Agent。

手环回复通过 ReplyWorker 观察当前 worker 的 Streaming 消息，合并积压的累计文本；取消并等待观察任务结束后发送最终快照。BandConnection 在同一会话中用 BandReplyStream 对前 300 个 Unicode 字符执行 600ms trailing debounce，最终快照绕过等待立即发送，不分页。会话 ID 隔离迟到回复；解绑和重连销毁当前显示状态。不会自动修改 Agent 系统提示词。命令 09 使用字符限额，ASR 命令 07 保留原字节限额。

首次绑定研究：对当前手环仅发送认证模块 0x82 的能力查询 00，响应 `10 00 01 01 02 00 00`，按官方解析为 version=1、apiLevel=2、allowLocalBinding=0。官方 AuthModule2/w6 首次绑定路径调用云端签名并在手环校验后建立 ECDH 认证密钥。现阶段不提供纯本地首次绑定；研究不应对已有设备发送 startLocalBinding/startCloudBinding/verifySignature 或解绑、重置命令。已有密钥的认证与首次绑定必须分开处理。

外设中心：`PeripheralStore` 将外设注册和可选的 thread 关联分开。沿用 `band-connections.enc` 和原 Keystore alias，读取旧 JSON 时为缺失的名称/连接开关提供默认值，保留原记录 ID、地址、密钥和 thread。解除对话绑定只清空 thread，不删除注册。服务按 ID 协调每个连接的生命周期，添加或修改其他外设不会中断现有连接；改名不改变连接身份，绑定变化会取消旧语音会话。未下载 ASR 模型也可连接读取设备信息。

设备详情通过已认证 GATT 连接串行读取 Huami 电量信息和标准 Device Information 特征；刷新请求交给连接所有者执行。`PeripheralHost` 使用应用注入的后台串行 dispatcher，UI 只渲染不可变状态并处理权限、文件选择和导航。首次添加仅接受当前支持的小米手环 6 NFC 地址与已有密钥，不触发厂商配对。Gadgetbridge 仅用作协议核对资料，未引入其应用源码或声明其他型号已受支持。

`PeripheralStoreDeviceTest` 使用独立缓存目录覆盖旧配置迁移、加密持久化、解绑保留凭据、重复地址和移除；`PeripheralsTest` 覆盖页面操作，保留实际设备数据并只操作离线虚拟外设。

## Peripheral health data

`BandHealthConnection` shares the existing connection owner's event loop. Steps use Huami characteristic 7, heart notifications use the standard Heart Rate Measurement characteristic, and single-shot measurement uses the manual control command. Activity history uses characteristics 4/5 (types 01 and 02), counter-checked bounded transfers and the band's timezone-bearing start timestamp. No activity acknowledgement/delete command 03 is sent, so reads can be repeated. The history parser and protocol choices were checked against Gadgetbridge's Huami fetch implementations; no Gadgetbridge app source/library dependency was introduced.

Band 6 activity samples are 8-byte minute records. Heart rates 0/255 are missing values, not measurements. Sleep is the reported category 120 only; do not apply speculative deep/REM thresholds. Night totals cover the previous local noon through today's noon/current time. Missing sleep markers remain unknown, not a claim of zero sleep. Historical daily steps are sums of received minutes and may be incomplete; the primary current-day counter is a separate timed reading. Dates use `java.time`, including signed quarter-hour BLE offsets and DST.

`PeripheralHealthStore` persists deduplicated minute and heart samples for 31 days via `EncryptedJsonFile`, outside Android backup with its own Keystore alias. The same envelope helper preserves the existing peripheral-credential file format and alias. Removed device IDs are retired in memory to prevent a late connection write from recreating deleted health data. Never log raw health packets, sensor values, timestamps tied to users, or tool context.

`PeripheralHealthTool` scopes every read to current thread bindings and rechecks ownership after suspended refresh/IO. It excludes Bluetooth addresses and credentials. `KoogAgentRunner` invokes this read-only adapter before generation and passes its timestamped summary as transient runtime context; it does not change the saved Agent system prompt. This is automatic context provisioning, not model-initiated tool execution. The summary is sent to the selected inference service and is never appended to saved history or Agent settings. Connected reads attempt a bounded step refresh on the BLE owner; history/measurements retain their actual sample times. Cancellation propagates and cache-read failure does not prevent an ordinary chat reply.

Tests: protocol counters, malformed/truncated data, sentinel heart rates, midnight/DST sleep totals and cross-thread isolation are unit tested. `PeripheralHealthStoreDeviceTest` covers encryption, retention, duplicate sync and deleted-device races. `PeripheralHealthRuntimeDeviceTest` verifies health context reaches the model adapter without editing the saved Agent prompt. `PeripheralHealthTest` runs a non-destructive sync/UI test against an already-added physical peripheral.


## Inference service architecture

`shared/commonMain` owns `ConnectionRepository`, `AgentRepository`, `ChatRepository`,
`ConnectionHost`, `InferenceRuntime`, `KoogInferenceRuntime` and `KoogAgentRunner`.
UUID/time use Kotlin common APIs. Koog 1.3 provider clients and an explicitly injected
Ktor transport follow summer's integration; no full agent bundle or LiteRT runtime
is packaged. OpenAI-compatible HTTP (including desktop llmd) uses Chat Completions
SSE. Each collection owns and closes its client/transport. Provider errors are mapped
to safe messages without retaining response bodies, URLs, credentials or exception causes.
Never enable HTTP body/header logging for these clients.

`ConnectionSecrets` is the platform credential boundary; Android reuses the existing
Keystore/atomic-file helper with a separate alias/file in `noBackupFilesDir`.
Database entities contain metadata only. Android backup is disabled to avoid restoring
an encrypted credential file without its Keystore key. `AgentServices` composes the shared repositories and runner for both platforms.
`DesktopGraph` provides a bundled SQLite driver, all existing migrations, OkHttp and
a native credential-store adapter without Android imports.

`ConnectionHost` coordinates editor operations on an application-injected serial
background dispatcher. Its scope is disposed with the screen. API keys are transient
state and never `rememberSaveable`. Test uses synthetic content, not user history.

Schema v4 adds `connections` and `agents.connectionId`. Old `models` metadata/table
and `modelId` column remain solely to preserve the existing schema lineage; no file
operations or local inference code use them. Migration 3→4 clears local associations
and marks interrupted replies Canceled, preserving history, agents, thread associations
and bubble settings. Versions 1–3 remain unchanged; schema v4 is generated by KSP.
Connection deletion, Agent connection validation/save and default selection use DAO transactions. Credential storage
is outside SQLite: an interrupted save may leave an unused encrypted key, but it cannot
publish an unconfigured connection. A failed credential write leaves metadata untouched. Required credentials are validated before any credential write, so a rejected clear preserves the previously saved key and metadata.

`AndroidInferenceRuntime` dispatches LlmdAndroid to the copied public llmd AIDL contract.
It binds using AUTO_CREATE | IMPORTANT, checks authorization/engineReady/activeModel,
and limits Binder request size. Authorization is user initiated from the editor.
The current service contract has one completion callback and no remote cancel RPC:
local cancellation unbinds and discards late results, without claiming that inference
stops in llmd. It never starts or switches the llmd model service.

WorkManager, foreground notification, completion notifications, bubbles and band
routing remain Android adapters. A unique REPLACE job per thread cancels the prior reply when a new user message arrives; cancellation marks the existing assistant row Canceled using bounded cleanup.
Failures update that same row, preserve partial output, and are not automatically retried.
At reply start, a DAO transaction marks unfinished assistant rows in that thread Canceled
before inserting the new row, including when WorkManager restarts the same worker after
process death. Send/stop controls observe the actual unique WorkManager task state instead
of treating persisted Streaming rows as evidence of a live task.
Only Complete messages enter subsequent requests. Scoped peripheral context is read
for each run and is transient; the saved Agent prompt remains unchanged.

Verification:

```sh
./gradlew :shared:testAndroidHostTest :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :desktopApp:compileKotlin
./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.storyteller_f.dush.agent.band.PeripheralHealthRuntimeDeviceTest
./gradlew :appiumTests:test --tests '*ConnectionsScreenTest' --tests '*NavigationTest'
```

Acquire the shared Android device lease before commands that install or operate a device.
JVM tests exercise real Room persistence/migration, editor Hosts, runner failure/cancellation and Koog
HTTP contracts. Android device tests cover the Binder contract/disconnect/cancel paths, encrypted credentials
and peripheral context isolation. The manual Android smoke flow preserves existing app data and verifies
connection test/save/restart, real llmd chat through ReplyWorker, and cancellation. The full Appium suite
clears debug data; do not run it against a provisioned installation without choosing an isolated package. The optional `realOpenRouterStreaming` test runs only when
`DUSH_RUN_OPENROUTER_TEST=1` and `OPENROUTER_API_KEY` is available to the test process;
it sends a synthetic prompt to `openai/gpt-4.1-nano`, asserts nonempty output, and never
prints the key or response. Do not pass credentials as Gradle properties/arguments.

For an optional real llmd HTTP test, set `DUSH_TEST_LLMD_URL` (including `/v1`) and
`DUSH_TEST_LLMD_MODEL` to an exact installed model ID before `:shared:jvmTest`.
Real Android llmd verification uses an installed service with an imported model, enabled
and Ready, plus user approval for the dush package/signature. The llmd authorization
screen was checked both with Cancel (access remains denied) and Allow (chat succeeds).
The manual dush flow also verifies ReplyWorker chat, cancellation and a fresh reply afterward.

Run the opt-in real-service regression tests after provisioning and authorizing the service:

```sh
./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.storyteller_f.dush.agent.runtime.RealLlmdCrossAppDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.llmdPackage=com.storytellerf.llmd.debug \
  -Pandroid.testInstrumentationRunnerArguments.llmdModel=gemma-4-E2B-it
```

Without both arguments these tests skip. They use an in-memory dush database and
synthetic prompts. Cancellation coverage holds a second Binder binding so llmd can
finish the old request and deliver a late callback; it then executes another real
inference and checks that the canceled row is unchanged. It does not claim a remote
cancel RPC exists or stop a model used by other clients. A caller-owned deadline
remains coroutine cancellation; only the adapter's own deadline becomes a safe
service-timeout message.

## Desktop/JVM workspace

`WorkspaceHost` owns window navigation, observed lists, thread selection and commands on
an injected serial background dispatcher. Shared connection/Agent editor contents and
`SharedDushTheme` are also consumed by Android; Android supplies dynamic colors and llmd
authorization. WorkManager, notifications, bubbles and BLE remain Android adapters.
Unsent message drafts belong to the window Host and are keyed by thread. Navigation
restores the selected draft; successful message submission removes it. Drafts are
memory-only and are not written into chat history or restored after application exit.
Each open conversation has a separate `ThreadHost`, a Room-generated `PagingSource`
and a `Pager` cached in that Host's scope. Room invalidation refreshes messages after
database changes, including streamed text. Initial and subsequent page sizes are 50,
with prefetch distance 5 and placeholders disabled. `LazyPagingItems` handles access
hints and retry using Paging's refresh/append load states; there is no parallel manual
history cache or retry state machine. Inference still reads its full context separately.
Navigation publishes the new Host immediately and requests cancellation of the old
Host without joining it. Old paging work has no path to the new Host's state. Each
conversation scope remains a child of the window scope until cleanup completes, so
window shutdown still joins all retired sessions before the database can close.
No database schema change is required.
The list uses reverse layout and a stable first sentinel at the visual bottom.
Near the older edge it loads another 50 messages, preserving stable message keys.
At the bottom the sentinel remains the anchor and follows new text. After the
user scrolls upward, positioning is left entirely to LazyColumn: there are no
explicit scroll commands or height compensation, including inside a growing reply.

`ForegroundReplies` owns application-lifetime replies. Its command channel serializes
send/replace/stop across suspension, joins canceled owners before replacement and ignores
obsolete completion events. Only live tasks drive the stop control. Before accepting work,
it marks abandoned Pending/Streaming assistant rows Canceled. Shutdown joins runner cleanup
before closing Room. `DesktopGraph` holds an OS file lock for the entire database lifetime,
so another process cannot run recovery against live replies. No schema change is required.
The JVM entry point uses `awaitApplication`: the default blocking `application` entry
terminates the process and would bypass the surrounding shutdown cleanup. Closing the
window must reach the safe `operation=shutdown outcome=complete` log before process exit.

`DesktopConnectionSecrets` uses [java-keyring](https://github.com/javakeyring/java-keyring)
1.0.4 and serializes native calls on IO. Non-secret hashed marker files track keys written by
this installation because backend missing-item exceptions are not portable. The native item
is written before its marker and metadata; a crash can leave an unused native item but never
publish a configured connection with an unwritten key. Do not move the credential marker
directory separately. Deletion first writes an empty native envelope, then deletes the
native item and marker; retrying after interrupted marker cleanup is safe without
treating credential-store access errors as missing items. The canonical marker
directory identity namespaces native items. API keys,
requests, response bodies, user text and raw exceptions must not enter diagnostics. Desktop
uses Kermit's console writer and logs safe startup/shutdown events.

Run from the repository root (Windows: `./gradlew.bat`):

```sh
./gradlew :desktopApp:run
./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Set `DUSH_DATA_DIR` to an isolated absolute directory before launching smoke tests. Do not
run automation against a provisioned user profile. Desktop tests exercise graph persistence,
interrupted-reply recovery and exclusive ownership; native credential roundtrip/clear tests
run on Windows with unique synthetic records and remove their records afterward. macOS/Linux
credential backends require manual validation on those operating systems. Shared scheduler
tests cover replacement ordering, stop, failure, shutdown cleanup and process restart.

Manual desktop flow: configure/test/save an HTTP connection, create/edit an Agent, create a
chat, observe partial and final reply text, stop a reply, send again, switch pages during a
reply, then restart and check history/settings. A synthetic SSE fixture validates transport
and wiring but does not establish real model inference. Real llmd/OpenRouter tests remain
opt-in as described above. iOS/Web targets and their old template sources remain disabled.

`DesktopRealServiceTest` adds opt-in acceptance through the actual desktop composition,
native credential store and OpenRouter transport. With `DUSH_RUN_OPENROUTER_TEST=1` and
`OPENROUTER_API_KEY` in the test process environment, run:

```sh
./gradlew :desktopApp:test --tests '*DesktopRealServiceTest' --rerun
```

It sends synthetic prompts to `openai/gpt-4.1-nano` and consumes service quota. The tests
cover connection testing, navigation during a stream, stopping followed by another reply,
shutdown during a stream, and reopening persisted history and credentials. Temporary
native credentials are removed afterward; assertions never include the key. Run in the
logged-in test user's session: Windows SSH logons do not provide the same credential-store
access as an interactive logon, and Linux needs that desktop's D-Bus session and unlocked
Secret Service. Do not substitute an in-memory key store for this acceptance check.

Linux native UI automation also needs the Java ATK wrapper on the runtime classpath
and `-Djavax.accessibility.assistive_technologies=org.GNOME.Accessibility.AtkWrapper`.
On Ubuntu, install the prebuilt `libatk-wrapper-java` and `libatk-wrapper-java-jni`
packages and add `/usr/share/java/java-atk-wrapper.jar` to that classpath. Run Appium
and the application in the same display and D-Bus session. A visible window alone
does not establish that AT-SPI can discover its controls. Include the matching
platform's Skiko runtime when transferring host-built classes to another OS.

Intel macOS needs an additional SQLite JNI runtime: the current
`sqlite-bundled-jvm:2.7.0` artifact contains macOS ARM64, but no `osx_x64` binary.
Its database cannot start on Intel macOS without a compatible, prebuilt
`natives/osx_x64/libsqliteJni.dylib` on the classpath. The Intel acceptance run
used a separately host-built SQLite 2.7.0 JNI library; this is not included in the
normal application distribution. Do not treat that run as validation of the
default Intel macOS package. Native startup failures resolve readiness with an
error so the graph can close the database and release its data-directory lock.
On macOS, run credential acceptance in the graphical login session, and approve
the system Keychain prompt for the application when it first reads credentials
created by a different launcher.
