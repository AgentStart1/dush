package com.storyteller_f.dush.appium

import io.appium.java_client.AppiumBy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openqa.selenium.support.ui.ExpectedConditions

class AgentFlowTest : BaseAppiumTest() {

    @Test
    fun displaysDefaultAgent() {
        findByIdOrText("nav-agents", "Agents").click()
        assertTrue(waitForText("Gemma Assistant"), "Default agent should be created and visible")
        assertTrue(waitForText("Default"), "Default agent should be marked as Default")
    }

    @Test
    fun opensNewAgentEditor() {
        findByIdOrText("nav-agents", "Agents").click()
        findById("new-agent").click()

        assertTrue(waitForText("New agent"), "Agent editor title should say 'New agent'")
        assertTrue(waitForText("Name"), "Name field should be visible")
        assertTrue(waitForText("System prompt"), "System prompt field should be visible")
        assertTrue(waitForText("Temperature"), "Temperature slider should be visible")
        scrollDown()
        assertTrue(waitForText("Max tokens"), "Max tokens field should be visible")
        assertTrue(waitForText("Save"), "Save button should be visible")
    }

    @Test
    fun createsCustomAgent() {
        findByIdOrText("nav-agents", "Agents").click()
        findById("new-agent").click()
        waitForText("Name")

        val editTexts = driver.findElements(
            AppiumBy.androidUIAutomator("""new UiSelector().className("android.widget.EditText")""")
        )
        // First EditText is Name
        editTexts[0].clear()
        editTexts[0].sendKeys("My Test Agent")
        // Second EditText is System prompt
        editTexts[1].clear()
        editTexts[1].sendKeys("You are a test agent.")

        scrollDown()
        findByText("Save").click()

        assertTrue(waitForText("My Test Agent"), "Created agent should appear in the agents list")
    }

    @Test
    fun editsExistingAgent() {
        findByIdOrText("nav-agents", "Agents").click()
        waitForText("Gemma Assistant")

        // Tap the default agent card to edit it
        findByText("Gemma Assistant").click()

        assertTrue(waitForText("Edit agent"), "Should open editor with 'Edit agent' title")

        val editTexts = driver.findElements(
            AppiumBy.androidUIAutomator("""new UiSelector().className("android.widget.EditText")""")
        )
        editTexts[0].clear()
        editTexts[0].sendKeys("Renamed Agent")

        scrollDown()
        findByText("Save").click()

        assertTrue(waitForText("Renamed Agent"), "Agent should appear with the new name")
    }
}
