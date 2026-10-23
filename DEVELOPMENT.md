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
own data directory, including the Room database, model files and preferences.
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

对话设置中的 Agent 选择由 `ThreadAgentSettingsHost` 协调，观察对话、Agent 和模型列表。通过 DAO 定向更新现有 `agentId` 字段，避免覆盖同时更新的标题或气泡设置；不改变数据库结构。手环在每次提交识别结果时读取当前对话的 Agent。

手环回复通过 ReplyWorker 观察当前 worker 的 Streaming 消息，合并积压的累计文本；取消并等待观察任务结束后发送最终快照。BandConnection 在同一会话中用 BandReplyStream 对前 300 个 Unicode 字符执行 600ms trailing debounce，最终快照绕过等待立即发送，不分页。会话 ID 隔离迟到回复；解绑和重连销毁当前显示状态。不会自动修改 Agent 系统提示词。命令 09 使用字符限额，ASR 命令 07 保留原字节限额。

首次绑定研究：对当前手环仅发送认证模块 0x82 的能力查询 00，响应 `10 00 01 01 02 00 00`，按官方解析为 version=1、apiLevel=2、allowLocalBinding=0。官方 AuthModule2/w6 首次绑定路径调用云端签名并在手环校验后建立 ECDH 认证密钥。现阶段不提供纯本地首次绑定；研究不应对已有设备发送 startLocalBinding/startCloudBinding/verifySignature 或解绑、重置命令。已有密钥的认证与首次绑定必须分开处理。

外设中心：`PeripheralStore` 将外设注册和可选的 thread 关联分开。沿用 `band-connections.enc` 和原 Keystore alias，读取旧 JSON 时为缺失的名称/连接开关提供默认值，保留原记录 ID、地址、密钥和 thread。解除对话绑定只清空 thread，不删除注册。服务按 ID 协调每个连接的生命周期，添加或修改其他外设不会中断现有连接；改名不改变连接身份，绑定变化会取消旧语音会话。未下载 ASR 模型也可连接读取设备信息。

设备详情通过已认证 GATT 连接串行读取 Huami 电量信息和标准 Device Information 特征；刷新请求交给连接所有者执行。`PeripheralHost` 使用应用注入的后台串行 dispatcher，UI 只渲染不可变状态并处理权限、文件选择和导航。首次添加仅接受当前支持的小米手环 6 NFC 地址与已有密钥，不触发厂商配对。Gadgetbridge 仅用作协议核对资料，未引入其应用源码或声明其他型号已受支持。

`PeripheralStoreDeviceTest` 使用独立缓存目录覆盖旧配置迁移、加密持久化、解绑保留凭据、重复地址和移除；`PeripheralsTest` 覆盖页面操作，保留实际设备数据并只操作离线虚拟外设。

## Peripheral health data

`BandHealthConnection` shares the existing connection owner's event loop. Steps use Huami characteristic 7, heart notifications use the standard Heart Rate Measurement characteristic, and single-shot measurement uses the manual control command. Activity history uses characteristics 4/5 (types 01 and 02), counter-checked bounded transfers and the band's timezone-bearing start timestamp. No activity acknowledgement/delete command 03 is sent, so reads can be repeated. The history parser and protocol choices were checked against Gadgetbridge's Huami fetch implementations; no Gadgetbridge app source/library dependency was introduced.

Band 6 activity samples are 8-byte minute records. Heart rates 0/255 are missing values, not measurements. Sleep is the reported category 120 only; do not apply speculative deep/REM thresholds. Night totals cover the previous local noon through today's noon/current time. Missing sleep markers remain unknown, not a claim of zero sleep. Historical daily steps are sums of received minutes and may be incomplete; the primary current-day counter is a separate timed reading. Dates use `java.time`, including signed quarter-hour BLE offsets and DST.

`PeripheralHealthStore` persists deduplicated minute and heart samples for 31 days via `EncryptedJsonFile`, outside Android backup with its own Keystore alias. The same envelope helper preserves the existing peripheral-credential file format and alias. Removed device IDs are retired in memory to prevent a late connection write from recreating deleted health data. Never log raw health packets, sensor values, timestamps tied to users, or tool context.

`PeripheralHealthTool` scopes every read to current thread bindings and rechecks ownership after suspended refresh/IO. It excludes Bluetooth addresses and credentials. `KoogAgentRunner` invokes this read-only adapter before generation and passes its timestamped summary as transient runtime context; it does not change the saved Agent system prompt. The local model runtime currently has no native function-calling interface, so this is automatic context provisioning, not a claim of model-initiated tool calls. Connected reads attempt a bounded step refresh on the BLE owner; history/measurements retain their actual sample times. Cancellation propagates and cache-read failure does not prevent an ordinary chat reply.

Tests: protocol counters, malformed/truncated data, sentinel heart rates, midnight/DST sleep totals and cross-thread isolation are unit tested. `PeripheralHealthStoreDeviceTest` covers encryption, retention, duplicate sync and deleted-device races. `PeripheralHealthRuntimeDeviceTest` verifies health context reaches the model adapter without editing the saved Agent prompt. `PeripheralHealthTest` runs a non-destructive sync/UI test against an already-added physical peripheral.
