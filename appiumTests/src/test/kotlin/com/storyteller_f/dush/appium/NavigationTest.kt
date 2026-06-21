package com.storyteller_f.dush.appium

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationTest : BaseAppiumTest() {

    @Test
    fun launchesToChatScreen() {
        assertTrue(waitForText("Chats"), "App should launch to the Chat list screen")
        assertTrue(idExists("new-chat"), "Chat screen should show the New chat FAB")
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
        assertTrue(idExists("new-agent"), "New agent FAB should be visible")
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
        assertTrue(idExists("new-agent"), "Should show Agents screen")

        findByIdOrText("nav-settings", "Settings").click()
        assertTrue(waitForText("Notifications"), "Should show Settings screen")

        findByIdOrText("nav-chat", "Chat").click()
        assertTrue(idExists("new-chat"), "Should return to Chat screen")
    }
}
