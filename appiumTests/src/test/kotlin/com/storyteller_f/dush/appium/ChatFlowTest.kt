package com.storyteller_f.dush.appium

import io.appium.java_client.AppiumBy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openqa.selenium.support.ui.ExpectedConditions

class ChatFlowTest : BaseAppiumTest() {

    @Test
    fun createsNewChat() {
        findByText("New chat").click()
        assertTrue(waitForText("Chat"), "Should navigate to the chat thread screen")
        assertTrue(waitForText("Message"), "Message input field should be visible")
        assertTrue(waitForText("Send"), "Send button should be visible")
    }

    @Test
    fun sendsUserMessage() {
        findByText("New chat").click()
        waitForText("Message")

        val input = wait.until(
            ExpectedConditions.elementToBeClickable(
                AppiumBy.androidUIAutomator("""new UiSelector().className("android.widget.EditText")""")
            )
        )
        input.sendKeys("Hello from Appium")

        findByText("Send").click()

        assertTrue(waitForText("Hello from Appium"), "Sent message should appear in the chat")
        assertTrue(waitForText("You"), "User message label should appear")
    }

    @Test
    fun newChatAppearsInChatList() {
        findByText("New chat").click()
        waitForText("Message")

        val input = wait.until(
            ExpectedConditions.elementToBeClickable(
                AppiumBy.androidUIAutomator("""new UiSelector().className("android.widget.EditText")""")
            )
        )
        input.sendKeys("Test thread title")

        findByText("Send").click()
        waitForText("Test thread title")

        driver.navigate().back()

        assertTrue(waitForText("Test thread title"), "Chat list should show the thread with the message as title")
    }
}
