package com.storyteller_f.dush.agent.runtime

actual fun createLocalLlmRuntime(cacheDir: String): LocalLlmRuntime = LiteRtLocalLlmRuntime(cacheDir)
