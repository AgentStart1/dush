package com.storyteller_f.dush.appium

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsScreenTest : BaseAppiumTest() {

    @Test
    fun displaysSettingsContent() {
        findByIdOrText("nav-settings", "Settings").click()
        assertTrue(waitForText("Settings"), "Settings screen title should appear")
        assertTrue(
            waitForText("Notifications and bubbles"),
            "Notification description text should be visible"
        )
        assertTrue(
            waitForText("推理服务密钥在本机加密保存"),
            "Inference credential storage info text should be visible"
        )
    }

    @Test
    fun showsEnableNotificationsButton() {
        findByIdOrText("nav-settings", "Settings").click()
        assertTrue(
            waitForText("Enable notifications"),
            "Enable notifications button should be visible"
        )
    }
}
