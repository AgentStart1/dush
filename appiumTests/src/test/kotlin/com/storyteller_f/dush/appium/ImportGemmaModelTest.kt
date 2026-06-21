package com.storyteller_f.dush.appium

import io.appium.java_client.AppiumBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class ImportGemmaModelTest : BaseAppiumTest() {
    private val appLogPath = "files/logs/app.log"

    @BeforeEach
    fun setUpModel() {
        val modelPath = gemmaModelPath()
        assumeTrue(Files.isRegularFile(modelPath), "Gemma model is missing. Run scripts/download-gemma-model.sh first.")
        pushModelWithAdb(modelPath)
    }

    @AfterEach
    fun tearDownModel(testInfo: TestInfo) {
        pullAppLog(testInfo)
    }

    @Test
    fun importsGemma4E2BItModel() {
        findByIdOrText("nav-models", "Models").click()
        findByIdOrText("models-import", "Import").click()

        selectModelFromDocumentPicker(gemmaModelPath().name)

        val importWait = WebDriverWait(driver, Duration.ofSeconds(180))
        val modelImported = runCatching {
            importWait.until {
                it.findElements(AppiumBy.androidUIAutomator("""new UiSelector().textContains("gemma-4-E2B-it.litertlm")""")).isNotEmpty() &&
                    it.findElements(AppiumBy.androidUIAutomator("""new UiSelector().textContains("Available")""")).isNotEmpty()
            }
        }.getOrDefault(false)
        assertTrue(
            modelImported,
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

    private fun gemmaModelPath(): Path {
        System.getenv("GEMMA_MODEL_PATH")?.let { return Path.of(it) }
        val projectRoot = Path.of(System.getProperty("project.root.dir"))
        return projectRoot.resolve(Path.of("models", "gemma", "gemma-4-E2B-it.litertlm"))
    }

    private fun pushModelWithAdb(modelPath: Path) {
        adb("shell", "mkdir", "-p", "/sdcard/Download")
        adb("push", modelPath.toString(), "/sdcard/Download/${modelPath.name}")
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
            adbOutput("shell", "run-as", appPackage, "cat", "/data/data/$appPackage/$appLogPath")
        }.onSuccess { log ->
            Files.writeString(outputPath, log)
        }.onFailure { error ->
            Files.writeString(
                errorPath,
                "Failed to pull $appLogPath from ${appPackage()}: ${error.message}\n",
            )
        }
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
        val command = mutableListOf(resolveAdbPath())
        System.getenv("ANDROID_UDID")?.let {
            command += "-s"
            command += it
        }
        command += args
        return command
    }
}
