package com.storyteller_f.dush.appium

import io.appium.java_client.AppiumBy
import org.junit.jupiter.api.Test
import org.openqa.selenium.NoSuchElementException
import org.junit.jupiter.api.Assertions.*
import java.time.Duration

/** Runs against an already-added physical peripheral and preserves its registration and model data. */
class PeripheralHealthTest : BaseAppiumTest() {
    override val resetAppDataBeforeTest = false
    private fun scroll(id: String) {
        val target = AppiumBy.androidUIAutomator("""new UiSelector().resourceId("$id")""")
        wait.until {
            if (driver.findElements(target).any { it.isDisplayed }) true
            else {
                try { driver.findElement(AppiumBy.androidUIAutomator("""new UiScrollable(new UiSelector().resourceId("peripheral-detail-scroll")).scrollIntoView(new UiSelector().resourceId("$id"))""")) }
                catch (expected: NoSuchElementException) { }
                driver.findElements(target).any { it.isDisplayed }
            }
        }
    }
    @Test fun synchronizesHealthHistoryAndDisplaysTimestampedMetrics() {
        repeat(6) {
            if (!idExists("nav-peripherals", Duration.ofSeconds(1))) driver.navigate().back()
        }
        findById("nav-peripherals").click()
        wait.until { driver.findElements(AppiumBy.androidUIAutomator("""new UiSelector().resourceIdMatches("peripheral-item-.*")""")).isNotEmpty() }
        driver.findElements(AppiumBy.androidUIAutomator("""new UiSelector().resourceIdMatches("peripheral-item-.*")""")).first().click()
        assertTrue(waitForText("外设详情"))
        findById("peripheral-connection-status")
        scroll("peripheral-health-sync")
        findById("peripheral-health-sync")
        val previous = driver.findElements(AppiumBy.androidUIAutomator("""new UiSelector().textStartsWith("历史同步：")""")).firstOrNull()?.text
        val after = System.nanoTime()+1_100_000_000L
        wait.until { System.nanoTime() >= after }
        findById("peripheral-health-sync").click()
        wait.until { source ->
            val text=source.pageSource.orEmpty()
            val current = source.findElements(AppiumBy.androidUIAutomator("""new UiSelector().textStartsWith("历史同步：")""")).firstOrNull()?.text
            current != null && current != previous && text.contains("历史同步：") && !text.contains("历史同步：暂无记录") &&
                !text.contains("正在同步步数") && !text.contains("正在同步手动心率")
        }
        scroll("peripheral-health-steps")
        assertFalse(findById("peripheral-health-steps").text.contains("尚未读取"))
        scroll("peripheral-health-heart")
        assertTrue(findById("peripheral-health-heart").text.isNotBlank())
        scroll("peripheral-health-sleep")
        assertTrue(findById("peripheral-health-sleep").text.isNotBlank())
    }
}
