package com.storyteller_f.dush.appium

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelsScreenTest : BaseAppiumTest() {

    @Test
    fun displaysImportAndDownloadButtons() {
        findByIdOrText("nav-models", "Models").click()
        assertTrue(waitForText("Models"), "Models screen title should appear")
        assertTrue(waitForText("Import"), "Import button should be visible")
        assertTrue(waitForText("Gemma"), "Gemma download button should be visible")
    }

    @Test
    fun importButtonOpensDocumentPicker() {
        findByIdOrText("nav-models", "Models").click()
        findByIdOrText("models-import", "Import").click()

        val pickerVisible = textExists("Recent") || textExists("Downloads") || textExists("Browse")
        assertTrue(pickerVisible, "Document picker should open after tapping Import")

        driver.navigate().back()
        assertTrue(waitForText("Models"), "Should return to Models screen after dismissing picker")
    }
}
