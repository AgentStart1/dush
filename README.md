dush 是 Android Agent 聊天应用，支持通知、聊天气泡和手环语音。业务层使用 Kotlin Multiplatform；Desktop/JVM 当前仍为模板入口，iOS/Web 尚未启用。

See [DEVELOPMENT.md](DEVELOPMENT.md) for Android build isolation and Room schema ownership.

### 推理服务连接

在 **连接 → +** 添加推理服务，填写名称、精确模型 ID 和服务配置，点击 **测试连接** 后 **保存**：

- **OpenRouter**：默认地址 `https://openrouter.ai/api/v1`，需要 API key。
- **OpenAI**：默认地址 `https://api.openai.com/v1`，使用 Chat Completions，需要 API key。
- **OpenAICompatible**：填写兼容服务的 API base URL（通常以 `/v1` 结尾），密钥按服务要求填写。
- **LlmdHttp**：连接运行在电脑上的 llmd，例如 `http://<电脑局域网地址>:11435/v1`；填写 llmd 实际已导入的模型 ID，不含 `.litertlm`。Android 的 `127.0.0.1` 指手机本身。仅在可信网络使用未加密 HTTP。
- **LlmdAndroid**：填写已安装 llmd 的包名 `com.storytellerf.llmd`（或加 `.debug`、`.alpha`、`.e2e`）。点击 **授权 llmd**，在 llmd 内启用对应模型服务，等待 Ready 后测试。Android llmd 使用 Binder，不监听 HTTP 端口；当前接口返回完整回复，停止后 dush 忽略迟到结果，服务端可能继续推理。

在 **Agents → 配置 Agent** 中选择连接，或使用默认连接。连接可以编辑、设为默认或删除；删除会解除 Agent 的显式关联，之后使用当前默认连接。HTTP 服务的回复实时显示；**停止回复**保留已生成内容。失败或取消的回复不会再次进入模型上下文，失败不自动重发请求。

API key 在 Android Keystore 加密后保存在不参与备份的私有目录，不写入聊天或连接数据库。编辑时留空保留密钥；**清除已有密钥**可显式移除。测试连接仅发送简单测试提示，可能消耗服务额度。聊天内容及绑定手环的临时健康摘要会发送给所选推理服务，请选择你信任的服务。

dush 不再下载、导入、加载或删除本地语言模型文件。升级保留 Agent、提示词、聊天记录、气泡与外设配置，并解除旧本地模型关联；请配置服务连接。旧模型文件不会自动上传或删除，如需释放空间可在 Android 设置中清理应用数据（这也会删除聊天和配置）。手环语音识别模型仍由 dush 单独管理。

### 手环语音（Android）

支持已配对的小米手环 6 NFC：直接连接手环，在手机本地识别中文，实时将识别文字显示在手环上，并将最终文字发送到绑定的对话。对话回复会在手环上按 600ms debounce 合并流式更新（文字停止变化后刷新，最终回复立即发送），最多显示前 300 个字符，不自动分页。手机保留完整回复；重新唤起语音会停止旧回复的显示。可在 **Thread settings → 对话 Agent → 配置 Agent → System prompt** 中手动设置简短回复要求，例如“回答不超过 300 字符”。应用不会自动修改 Agent 提示词。

1. 停止小米运动健康或其他应用对这只手环的连接。
2. 打开首页 **外设** 标签，点 **+** 添加外设。填写名称、蓝牙地址和已有的 32 位十六进制配对密钥；也可以导入 JSON：

   ```json
   { "address": "YOUR_BAND_BLUETOOTH_ADDRESS", "auth_key": "YOUR_32_HEX_CHARACTER_PAIRING_KEY" }
   ```

3. 在外设详情允许蓝牙连接，查看连接状态、电量、型号及版本信息。菜单支持连接/断开、刷新信息、改名、绑定对话和移除外设；设备未提供的信息显示“未提供”。
4. 在外设详情下载约 42 MB 的中文识别模型。下载完成后，识别不需要联网。
5. 打开目标对话，进入 **Thread settings → 绑定外设**，选择已添加的外设。也可以从外设详情绑定到已有对话。
6. 状态显示已连接后，在手环唤起语音。停顿后会结束识别，最长录音 20 秒；识别文字会进入绑定对话。

外设详情的 **健康数据** 会显示今日步数、最近有效心率及记录时间、昨晚的已记录睡眠时长和近 7 天的历史汇总。连接后自动同步，也可点 **同步近 7 天**；同步保留手环上的原始记录。**测量心率** 进行一次单次测量，需要佩戴好手环。没有有效记录时显示暂无记录，不把缺失数据当成 0。睡眠仅按手环标记的睡眠分钟汇总，未推断深睡或 REM，历史记录可能不完整。

绑定对话可直接询问“我今天走了多少步”“最近心率是多少”“昨晚记录了多久睡眠”。每次回答会读取该对话绑定外设的本地健康摘要；连接可用时先尝试刷新步数，离线时使用带时间的已有记录。健康缓存加密保存在本机，保留 31 天；移除外设会删除对应本地健康缓存。不会自动更改 Agent 的系统提示词配置。

一只外设同时只能绑定一个对话，解除对话绑定后仍保留外设、密钥和连接，可改绑到其他对话。未绑定对话的外设可以连接并读取设备信息。**断开连接**会保留配置；**移除外设**只删除 dush 中的配置与密钥，不解除手环的厂商配对。配对配置使用 Android Keystore 加密并保存在不参与备份的应用私有目录。已有手环配置会自动保留，无需重新添加。应用本身不需要 root，但需要事先取得现有配对密钥；当前不提供首次绑定流程。

在 **Thread settings → 对话 Agent → 选择 Agent** 中可以切换这个对话使用的助手，列表会显示各 Agent 的模型。**配置 Agent** 可以打开当前助手的配置；切换仅影响此对话后续的消息和手环语音。

手环连接通过前台服务维持，打开应用时会恢复设为连接的外设。Agent 回复需要在 dush 中配置推理服务连接；语音识别模型与语言模型分开配置。当前识别模型为 [Vosk 中文小模型](https://alphacephei.com/vosk/models)，其他手环型号尚未验证。

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- Desktop app:
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
  - Standard run: `./gradlew :desktopApp:run`
- Web app:
  - Wasm target (faster, modern browsers): `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
  - JS target (slower, supports older browsers): `./gradlew :webApp:jsBrowserDevelopmentRun`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Desktop tests: `./gradlew :shared:jvmTest`
- Web tests:
  - Wasm target: `./gradlew :shared:wasmJsTest`
  - JS target: `./gradlew :shared:jsTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
