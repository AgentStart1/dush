package com.storyteller_f.dush.appium

import io.appium.java_client.AppiumBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.util.UUID

/** Exercises synthetic peripherals without clearing the user's imported models or existing registry. */
class PeripheralsTest : BaseAppiumTest() {
    override val resetAppDataBeforeTest = false
    private fun home() {
        repeat(6) {
            if (idExists("nav-peripherals", Duration.ofSeconds(1))) return
            driver.navigate().back()
        }
        fail<Unit>("Home tabs unavailable")
    }
    private fun scrollTo(id: String) {
        val target = AppiumBy.androidUIAutomator("""new UiSelector().resourceId("$id")""")
        wait.until {
            if (driver.findElements(target).any { it.isDisplayed }) true
            else {
                val container = if (driver.findElements(AppiumBy.androidUIAutomator("""new UiSelector().resourceId("peripheral-detail-scroll")""")).isNotEmpty()) "peripheral-detail-scroll" else "peripheral-add-scroll"
                val bounds = driver.findElement(AppiumBy.androidUIAutomator("""new UiSelector().resourceId("$container")""")).rect
                // Keep gestures inside app content so Android's edge navigation is not triggered.
                driver.executeScript("mobile: swipeGesture", mapOf("left" to bounds.x + 40, "top" to bounds.y + 40,
                    "width" to bounds.width - 80, "height" to bounds.height - 120, "direction" to "up", "percent" to 0.7))
                driver.findElements(target).any { it.isDisplayed }
            }
        }
    }
    private fun openAdd() {
        home(); findById("nav-peripherals").click(); findById("peripheral-add").click()
    }
    @Test fun rejectsInvalidAddressWithoutAddingAPeripheral() {
        openAdd()
        findById("peripheral-address").sendKeys("invalid-address"); driver.hideKeyboard()
        scrollTo("peripheral-save"); findById("peripheral-save").click()
        assertTrue(waitForText("请输入有效的外设蓝牙地址"))
    }
    @Test fun addsRenamesAndRemovesAnOfflinePeripheral() {
        val name = "UI测试外设-${UUID.randomUUID().toString().take(6)}"
        val renamed = "$name-改名"
        openAdd()
        findById("peripheral-name").clear(); findById("peripheral-name").sendKeys(name); driver.hideKeyboard()
        scrollTo("peripheral-address"); findById("peripheral-address").sendKeys("02:00:00:00:00:04"); driver.hideKeyboard()
        scrollTo("peripheral-key"); findById("peripheral-key").sendKeys("00112233445566778899aabbccddeeff"); driver.hideKeyboard()
        scrollTo("peripheral-auto-connect"); findById("peripheral-auto-connect").click()
        scrollTo("peripheral-save"); findById("peripheral-save").click()
        try {
            assertTrue(waitForText("外设详情"))
            assertTrue(waitForText("已断开"))
            scrollTo("peripheral-rename-input"); findById("peripheral-rename-input").clear(); findById("peripheral-rename-input").sendKeys(renamed); driver.hideKeyboard()
            scrollTo("peripheral-rename-save"); findById("peripheral-rename-save").click()
            driver.navigate().back()
            assertTrue(waitForText(renamed))
            driver.findElement(AppiumBy.androidUIAutomator("""new UiSelector().text("$renamed")""")).click()
            scrollTo("peripheral-remove"); findById("peripheral-remove").click()
            findById("peripheral-remove-confirm").click()
            assertTrue(idExists("peripheral-add", Duration.ofSeconds(15)))
            assertFalse(driver.pageSource.orEmpty().contains(renamed))
        } finally {
            // Cleanup is limited to this suite's synthetic device if an earlier assertion failed.
            if ((driver.pageSource.orEmpty().contains(name) || driver.pageSource.orEmpty().contains(renamed)) && !driver.pageSource.orEmpty().contains("外设已移除")) {
                scrollTo("peripheral-remove")
                findById("peripheral-remove").click()
                findById("peripheral-remove-confirm").click()
            }
        }
    }
}
