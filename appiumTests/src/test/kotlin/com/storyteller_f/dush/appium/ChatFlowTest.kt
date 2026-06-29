package com.storyteller_f.dush.appium

import io.appium.java_client.AppiumBy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openqa.selenium.support.ui.ExpectedConditions

class ChatFlowTest : BaseAppiumTest() {

    @Test
    fun createsNewChat() {
        findById("new-chat").click()
        assertTrue(waitForText("Chat"), "Should navigate to the chat thread screen")
        assertTrue(idExists("send-button"), "Send button should be visible")
    }

    @Test
    fun sendsUserMessage() {
        findById("new-chat").click()
        assertTrue(idExists("send-button"), "Chat thread should be loaded")

        val input = wait.until(
            ExpectedConditions.elementToBeClickable(
                AppiumBy.androidUIAutomator("""new UiSelector().className("android.widget.EditText")""")
            )
        )
        input.sendKeys("Hello from Appium")

        findById("send-button").click()

        assertTrue(waitForText("Hello from Appium"), "Sent message should appear in the chat")
        assertTrue(waitForText("You"), "User message label should appear")
    }

    @Test
    fun inputRemainsInLowerHalfWhenKeyboardOpens() {
        findById("new-chat").click()
        assertTrue(idExists("send-button"), "Chat thread should be loaded")

        val screenHeight = driver.manage().window().size.height
        val inputSelector = AppiumBy.androidUIAutomator("""new UiSelector().className("android.widget.EditText")""")

        driver.findElement(inputSelector).click()
        wait.until { driver.isKeyboardShown }

        val inputY = driver.findElement(inputSelector).location.y
        // Threshold is screenHeight/3 (not /2): a full keyboard pushes the input to ~y=840 on a
        // 1920px screen, while the double-displacement bug pushes it near y=0
        assertTrue(
            inputY > screenHeight / 3,
            "Input should not be raised near the top when keyboard opens (y=$inputY, screenHeight=$screenHeight)",
        )
    }

    @Test
    fun inputAnimatesUpWithoutBounceWhenKeyboardOpens() {
        findById("new-chat").click()
        assertTrue(idExists("send-button"), "Chat thread should be loaded")

        val screenHeight = driver.manage().window().size.height
        val inputSelector = AppiumBy.androidUIAutomator("""new UiSelector().className("android.widget.EditText")""")
        fun findInput() = driver.findElement(inputSelector)

        // Actual animation measurement: record Y before click, then poll while keyboard opens
        val initialY = findInput().location.y
        findInput().click()

        // Poll Y positions while keyboard animation plays out; re-find each time to avoid stale refs
        val positions = mutableListOf(initialY)
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            val y = runCatching { findInput().location.y }.getOrNull() ?: break
            positions += y
            if (driver.isKeyboardShown) break
        }
        wait.until { driver.isKeyboardShown }
        positions += findInput().location.y

        val finalY = positions.last()
        assertTrue(finalY < initialY, "Input should move up when keyboard opens (initial=$initialY, final=$finalY)")
        assertTrue(finalY > screenHeight / 3,
            "Input should not be raised near the top after keyboard opens (y=$finalY, screenHeight=$screenHeight)")
        // Allow 2px rounding tolerance; catches adjustPan+imePadding double-displacement bounce
        assertTrue(positions.zipWithNext().all { (a, b) -> b <= a + 2 },
            "Input should only move upward during animation, not bounce (positions: $positions)")
    }

    @Test
    fun newChatAppearsInChatList() {
        findById("new-chat").click()
        assertTrue(idExists("send-button"), "Chat thread should be loaded")

        val input = wait.until(
            ExpectedConditions.elementToBeClickable(
                AppiumBy.androidUIAutomator("""new UiSelector().className("android.widget.EditText")""")
            )
        )
        input.sendKeys("Test thread title")

        findById("send-button").click()
        waitForText("Test thread title")

        driver.navigate().back()

        assertTrue(waitForText("Test thread title"), "Chat list should show the thread with the message as title")
    }
}
