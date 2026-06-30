package com.storyteller_f.dush.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class StripChatTemplateMarkersTest {

    @Test
    fun removesCompleteTurnMarkerWithNewlines() {
        val input = "<|turn>model\n<turn|>\n正常回复内容"
        assertEquals("正常回复内容", input.stripChatTemplateMarkers())
    }

    @Test
    fun removesInlineTurnMarkerMidResponse() {
        // Reproduces the bug: safeRender emits content with turn markers injected mid-stream
        val input = "番茄之所以是水果：<|turn>model\n<turn|>\n<|turn>model\n1. 果实的作用"
        assertEquals("番茄之所以是水果：1. 果实的作用", input.stripChatTemplateMarkers())
    }

    @Test
    fun removesMultipleTurnMarkers() {
        val input = "<|turn>user\n<turn|>\n提问<|turn>model\n<turn|>\n回答"
        assertEquals("提问回答", input.stripChatTemplateMarkers())
    }

    @Test
    fun removesPartialTurnMarkerWithoutClosingToken() {
        // <|turn>model\n without the <turn|> closing — seen mid-stream
        val input = "内容<|turn>model\n续写内容"
        assertEquals("内容续写内容", input.stripChatTemplateMarkers())
    }

    @Test
    fun leavesCleanTextUnchanged() {
        val input = "是的，番茄是水果。"
        assertEquals(input, input.stripChatTemplateMarkers())
    }

    @Test
    fun handlesEmptyString() {
        assertEquals("", "".stripChatTemplateMarkers())
    }
}
