package com.storyteller_f.dush.agent.band

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.storyteller_f.dush.agent.data.*
import com.storyteller_f.dush.agent.repository.*
import com.storyteller_f.dush.agent.runtime.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeripheralHealthRuntimeDeviceTest {
    @Test fun passesScopedHealthToRuntimeWithoutChangingSavedAgentPrompt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context,AgentDatabase::class.java).build()
        try {
            database.modelDao().upsert(ModelEntity("model","test",ModelSource.Imported,"unused",1,ModelStatus.Available,"cpu",null,0,true))
            val agent = AgentRepository(database.agentDao(),database.modelDao())
            val agentId = agent.save(null,"test","用户自己的提示词","model",0.7,128)
            val chats = ChatRepository(database.chatDao(),database.agentDao())
            val thread = chats.createThread(agentId)
            chats.appendUserMessage(thread,"我今天走了多少步？")
            var received = ""
            val runtime = object : LocalLlmRuntime {
                override suspend fun initialize(modelPath: String, backend: String) = Unit
                override fun generate(systemPrompt: String,history: List<ConversationMessage>,temperature: Double,maxTokens: Int) = flowOf("已读取外设数据").also { received=systemPrompt }
                override suspend fun close() = Unit
            }
            val runner = KoogAgentRunner(chats,agent,ModelRepository(context,database.modelDao()),runtime,healthContext={ id ->
                assertEquals(thread,id); "read_peripheral_health: today_steps=1234"
            })
            assertTrue(runner.run(thread,agentId,"worker").isSuccess)
            assertTrue(received.contains("用户自己的提示词"));assertTrue(received.contains("today_steps=1234"))
            assertEquals("用户自己的提示词",agent.getAgent(agentId)?.systemPrompt)
            assertEquals("已读取外设数据",chats.messages(thread).last().content)
        } finally { database.close() }
    }
}
