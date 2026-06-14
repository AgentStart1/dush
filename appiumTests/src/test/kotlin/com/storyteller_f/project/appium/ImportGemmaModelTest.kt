package com.storyteller_f.project.appium

import io.appium.java_client.AppiumBy
import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.android.options.UiAutomator2Options
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openqa.selenium.By
import org.openqa.selenium.WebElement
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.name

class ImportGemmaModelTest {
    private lateinit var driver: AndroidDriver
    private lateinit var wait: WebDriverWait

    @BeforeEach
    fun setUp() {
        val serverUrl = System.getenv("APPIUM_SERVER_URL") ?: "http://127.0.0.1:4723/"
        val modelPath = gemmaModelPath()
        assumeTrue(Files.isRegularFile(modelPath), "Gemma model is missing. Run scripts/download-gemma-model.sh first.")

        val options = UiAutomator2Options()
            .setPlatformName("Android")
            .setAutomationName("UiAutomator2")
            .setAppPackage(System.getenv("APP_PACKAGE") ?: "com.storyteller_f.project")
            .setAppActivity(System.getenv("APP_ACTIVITY") ?: ".MainActivity")
            .setAutoGrantPermissions(true)
            .setNoReset(false)

        System.getenv("ANDROID_DEVICE_NAME")?.let(options::setDeviceName)
        System.getenv("ANDROID_UDID")?.let(options::setUdid)
        System.getenv("APP_PATH")?.let { options.setApp(it) }

        driver = AndroidDriver(URI(serverUrl).toURL(), options)
        wait = WebDriverWait(driver, Duration.ofSeconds(30))
        driver.pushFile("/sdcard/Download/${modelPath.name}", modelPath.toFile())
    }

    @AfterEach
    fun tearDown() {
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
            wait.until(ExpectedConditions.elementToBeClickable(AppiumBy.androidUIAutomator("""new UiSelector().textContains("Downloads")"""))).click()
        }.recoverCatching {
            wait.until(ExpectedConditions.elementToBeClickable(AppiumBy.androidUIAutomator("""new UiSelector().textContains("Downloads")"""))).click()
        }

        wait.until(
            ExpectedConditions.elementToBeClickable(
                AppiumBy.androidUIAutomator("""new UiSelector().textContains("$fileName")"""),
            ),
        ).click()
    }

    private fun findByIdOrText(resourceId: String, text: String): WebElement {
        val packageName = System.getenv("APP_PACKAGE") ?: "com.storyteller_f.project"
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
        return Path.of(
            System.getenv("GEMMA_MODEL_PATH")
                ?: Path.of("models", "gemma", "gemma-4-E2B-it.litertlm").absolutePathString(),
        )
    }
}
