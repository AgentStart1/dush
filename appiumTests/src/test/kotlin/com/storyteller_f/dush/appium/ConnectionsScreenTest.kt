package com.storyteller_f.dush.appium

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionsScreenTest : BaseAppiumTest() {
    @Test fun opensConnectionConfiguration() {
        findByIdOrText("nav-connections", "连接").click()
        findByIdOrText("connection-add", "添加连接").click()
        assertTrue(waitForText("连接配置"))
        assertTrue(idExists("connection-model"))
        assertTrue(idExists("connection-test"))
    }
    @Test fun savesServiceConnectionWithoutModelFiles() {
        findByIdOrText("nav-connections", "连接").click()
        findByIdOrText("connection-add", "添加连接").click()
        findByIdOrText("connection-name", "名称").sendKeys("Test service")
        findByIdOrText("connection-provider", "OpenRouter").click()
        findByIdOrText("LlmdHttp", "LlmdHttp").click()
        findByIdOrText("connection-model", "精确模型 ID").sendKeys("test-model")
        driver.hideKeyboard()
        findByIdOrText("connection-save", "保存").click()
        assertTrue(waitForText("连接已保存"))
        driver.navigate().back()
        assertTrue(waitForText("Test service"))
    }
}
