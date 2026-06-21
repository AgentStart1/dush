package com.storyteller_f.dush.appium

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationTest : BaseAppiumTest() {

    @Test
    fun launchesToChatScreen() {
        assertTrue(waitForText("Chats"), "App should launch to the Chat list screen")
        assertTrue(waitForText("New chat"), "Chat screen should show the New chat button")
    }

    @Test
    fun navigateToModelsTab() {
        findByIdOrText("nav-models", "Models").click()
        assertTrue(waitForText("Models"), "Models screen title should appear")
        assertTrue(waitForText("Import"), "Import button should be visible")
    }

    @Test
    fun navigateToAgentsTab() {
        findByIdOrText("nav-agents", "Agents").click()
        assertTrue(waitForText("Agents"), "Agents screen title should appear")
        assertTrue(waitForText("New agent"), "New agent button should be visible")
    }

    @Test
    fun navigateToSettingsTab() {
        findByIdOrText("nav-settings", "Settings").click()
        assertTrue(waitForText("Settings"), "Settings screen title should appear")
    }

    @Test
    fun cyclesThroughAllTabs() {
        findByIdOrText("nav-models", "Models").click()
        assertTrue(waitForText("Import"), "Should show Models screen")

        findByIdOrText("nav-agents", "Agents").click()
        assertTrue(waitForText("New agent"), "Should show Agents screen")

        findByIdOrText("nav-settings", "Settings").click()
        assertTrue(waitForText("Notifications"), "Should show Settings screen")

        findByIdOrText("nav-chat", "Chat").click()
        assertTrue(waitForText("New chat"), "Should return to Chat screen")
    }
}
