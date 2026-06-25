package com.storyteller_f.dush.agent

import android.content.Context
import androidx.room.Room
import com.storyteller_f.dush.agent.data.AgentDatabase
import com.storyteller_f.dush.agent.data.MIGRATION_1_2
import com.storyteller_f.dush.agent.notify.AgentNotificationHelper
import com.storyteller_f.dush.agent.repository.AgentRepository
import com.storyteller_f.dush.agent.repository.ChatRepository
import com.storyteller_f.dush.agent.repository.ModelRepository
import com.storyteller_f.dush.agent.runtime.AgentRunner
import com.storyteller_f.dush.agent.runtime.KoogAgentRunner
import com.storyteller_f.dush.agent.runtime.LiteRtLocalLlmRuntime

object AppGraph {
    lateinit var appContext: Context
        private set
    lateinit var database: AgentDatabase
        private set
    lateinit var modelRepository: ModelRepository
        private set
    lateinit var agentRepository: AgentRepository
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var notificationHelper: AgentNotificationHelper
        private set
    lateinit var agentRunner: AgentRunner
        private set

    fun initialize(context: Context) {
        if (::database.isInitialized) return
        appContext = context.applicationContext
        database = Room.databaseBuilder(appContext, AgentDatabase::class.java, "on-device-agent.db")
            .fallbackToDestructiveMigration(false)
            .addMigrations(MIGRATION_1_2)
            .build()
        modelRepository = ModelRepository(appContext, database.modelDao())
        agentRepository = AgentRepository(database.agentDao(), database.modelDao())
        chatRepository = ChatRepository(database.chatDao(), database.agentDao())
        notificationHelper = AgentNotificationHelper(appContext)
        agentRunner = KoogAgentRunner(
            chatRepository = chatRepository,
            agentRepository = agentRepository,
            modelRepository = modelRepository,
            runtime = LiteRtLocalLlmRuntime(appContext),
        )
    }
}
