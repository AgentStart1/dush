package com.storyteller_f.dush.appium

import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.android.options.UiAutomator2Options
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.openqa.selenium.By
import org.openqa.selenium.WebElement
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlin.io.path.name

class ImportGemmaModelTest {
    private val appLogPath = "files/logs/app.log"
    private var isScreenRecording = false
    private lateinit var driver: AndroidDriver
    private lateinit var wait: WebDriverWait

    @BeforeEach
    fun setUp() {
        val serverUrl = System.getenv("APPIUM_SERVER_URL") ?: "http://127.0.0.1:4723/"
        val modelPath = gemmaModelPath()
        assumeTrue(Files.isRegularFile(modelPath), "Gemma model is missing. Run scripts/download-gemma-model.sh first.")
        pushModelWithAdb(modelPath)

        val options = UiAutomator2Options()
            .setPlatformName("Android")
            .setAutomationName("UiAutomator2")
            .setAppPackage(System.getenv("APP_PACKAGE") ?: "com.storyteller_f.dush")
            .setAppActivity(System.getenv("APP_ACTIVITY") ?: ".MainActivity")
            .setAutoGrantPermissions(true)
            .setNoReset(false)

        System.getenv("ANDROID_DEVICE_NAME")?.let(options::setDeviceName)
        System.getenv("ANDROID_UDID")?.let(options::setUdid)
        appPath()?.let(options::setApp)

        driver = AndroidDriver(URI(serverUrl).toURL(), options)
        driver.startRecordingScreen()
        isScreenRecording = true
        wait = WebDriverWait(driver, Duration.ofSeconds(30))
    }

    @AfterEach
    fun tearDown(testInfo: TestInfo) {
        saveScreenRecording(testInfo)
        pullAppLog(testInfo)
        if (::driver.isInitialized) {
            driver.quit()
        }
    }

    @Test
    fun importsGemma4E2BItModel() {
        findByIdOrText("nav-models", "Models").click()
        findByIdOrText("models-import", "Import").click()

        selectModelFromDocumentPicker(gemmaModelPath().name)

        assertTrue(
            waitUntilTextContains("gemma-4-E2B-it.litertlm") && waitUntilTextContains("Available"),
            "Imported Gemma model should appear as Available in the Models list.",
        )
    }

    private fun selectModelFromDocumentPicker(fileName: String) {
        runCatching {
            wait.until(ExpectedConditions.elementToBeClickable(AppiumBy.accessibilityId("Show roots"))).click()
            wait.until(
                ExpectedConditions.elementToBeClickable(
                    AppiumBy.androidUIAutomator("""new UiSelector().resourceId("android:id/title").text("Downloads")"""),
                ),
            ).click()
        }.recoverCatching {
            wait.until(
                ExpectedConditions.elementToBeClickable(
                    AppiumBy.androidUIAutomator("""new UiSelector().resourceId("android:id/title").text("Downloads")"""),
                ),
            ).click()
        }.getOrThrow()

        wait.until(
            ExpectedConditions.elementToBeClickable(
                AppiumBy.androidUIAutomator("""new UiSelector().textContains("$fileName")"""),
            ),
        ).click()
    }

    private fun findByIdOrText(resourceId: String, text: String): WebElement {
        val packageName = System.getenv("APP_PACKAGE") ?: "com.storyteller_f.dush"
        return runCatching {
            wait.until(ExpectedConditions.elementToBeClickable(By.id("$packageName:id/$resourceId")))
        }.getOrElse {
            wait.until(ExpectedConditions.elementToBeClickable(AppiumBy.androidUIAutomator("""new UiSelector().text("$text")""")))
        }
    }

    private fun waitUntilTextContains(text: String): Boolean {
        return runCatching {
            wait.until {
                it.findElements(AppiumBy.androidUIAutomator("""new UiSelector().textContains("$text")""")).isNotEmpty()
            }
        }.getOrDefault(false)
    }

    private fun gemmaModelPath(): Path {
        System.getenv("GEMMA_MODEL_PATH")?.let { return Path.of(it) }
        val projectRoot = Path.of(System.getProperty("project.root.dir"))
        return projectRoot.resolve(Path.of("models", "gemma", "gemma-4-E2B-it.litertlm"))
    }

    private fun appPath(): String? {
        return System.getenv("APP_PATH") ?: System.getProperty("app.path")
    }

    private fun pushModelWithAdb(modelPath: Path) {
        adb("shell", "mkdir", "-p", "/sdcard/Download")
        adb("push", modelPath.toString(), "/sdcard/Download/${modelPath.name}")
    }

    private fun saveScreenRecording(testInfo: TestInfo) {
        val outputDir = Path.of(System.getProperty("project.root.dir"), "build", "appium-videos")
        Files.createDirectories(outputDir)

        val videoPath = outputDir.resolve("${testFileStem(testInfo)}.mp4")
        val errorPath = outputDir.resolve("${testFileStem(testInfo)}.record-error.txt")
        Files.deleteIfExists(videoPath)
        Files.deleteIfExists(errorPath)

        runCatching {
            check(::driver.isInitialized && isScreenRecording) { "Appium screen recording was not started" }
            val content = driver.stopRecordingScreen()
            val decoded = Base64.getDecoder().decode(content)
            Files.write(videoPath, decoded)
        }.onFailure { error ->
            Files.writeString(errorPath, "Failed to save Appium screen recording: ${error.message}\n")
        }.also {
            isScreenRecording = false
        }
    }

    private fun pullAppLog(testInfo: TestInfo) {
        val outputDir = Path.of(System.getProperty("project.root.dir"), "build", "appium-logs")
        Files.createDirectories(outputDir)

        val logName = testFileStem(testInfo)
        val outputPath = outputDir.resolve("$logName.log")
        val errorPath = outputDir.resolve("$logName.pull-error.txt")
        Files.deleteIfExists(outputPath)
        Files.deleteIfExists(errorPath)

        runCatching {
            val appPackage = appPackage()
            val log = adbOutput("shell", "run-as", appPackage, "cat", "/data/data/$appPackage/$appLogPath")
            check(!log.startsWith("cat:") && !log.contains("No such file or directory")) { log }
            log
        }.onSuccess { log ->
            Files.writeString(outputPath, log)
        }.onFailure { error ->
            Files.writeString(
                errorPath,
                "Failed to pull $appLogPath from ${appPackage()}: ${error.message}\n",
            )
        }
    }

    private fun testFileStem(testInfo: TestInfo): String {
        return testInfo.displayName
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "appium-test" }
    }

    private fun adb(vararg args: String) {
        adbOutput(*args)
    }

    private fun adbOutput(vararg args: String): String {
        val command = adbCommand(*args)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "adb command failed with exit code $exitCode: ${command.joinToString(" ")}\n$output"
        }
        return output
    }

    private fun adbCommand(vararg args: String): List<String> {
        val command = mutableListOf(adbPath())
        System.getenv("ANDROID_UDID")?.let {
            command += "-s"
            command += it
        }
        command += args
        return command
    }

    private fun appPackage(): String {
        return System.getenv("APP_PACKAGE") ?: "com.storyteller_f.dush"
    }

    private fun adbPath(): String {
        val candidates = listOfNotNull(
            System.getenv("ADB"),
            System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/adb" },
            System.getenv("ANDROID_SDK_ROOT")?.let { "$it/platform-tools/adb" },
            "adb",
        )
        return candidates.firstOrNull { candidate ->
            candidate == "adb" || File(candidate).canExecute()
        } ?: "adb"
    }
}
