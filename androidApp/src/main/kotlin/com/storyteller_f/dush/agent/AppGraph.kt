package com.storyteller_f.dush.agent

import android.content.Context
import androidx.room.Room
import com.storyteller_f.dush.agent.data.AgentDatabase
import com.storyteller_f.dush.agent.data.MIGRATION_1_2
import com.storyteller_f.dush.agent.data.MIGRATION_2_3
import com.storyteller_f.dush.agent.notify.AgentNotificationHelper
import com.storyteller_f.dush.agent.repository.AgentRepository
import com.storyteller_f.dush.agent.repository.ChatRepository
import com.storyteller_f.dush.agent.repository.ConnectionRepository
import com.storyteller_f.dush.agent.runtime.AgentRunner
import com.storyteller_f.dush.agent.runtime.KoogInferenceRuntime
import com.storyteller_f.dush.agent.runtime.AndroidInferenceRuntime
import com.storyteller_f.dush.agent.repository.AndroidConnectionSecrets
import com.storyteller_f.dush.agent.data.MIGRATION_3_4
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import com.storyteller_f.dush.agent.band.BandHub

object AppGraph {
    val coordination = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)
    lateinit var inferenceRuntime: com.storyteller_f.dush.agent.runtime.InferenceRuntime
        private set
    lateinit var bands: BandHub
        private set
    lateinit var appContext: Context
        private set
    lateinit var database: AgentDatabase
        private set
    lateinit var connectionRepository: ConnectionRepository
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
        bands = BandHub(appContext)
        database = Room.databaseBuilder(appContext, AgentDatabase::class.java, "on-device-agent.db")
            .fallbackToDestructiveMigration(false)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        notificationHelper = AgentNotificationHelper(appContext)
        inferenceRuntime = AndroidInferenceRuntime(appContext, KoogInferenceRuntime { HttpClient(OkHttp) })
        val services = AgentServices(database, AndroidConnectionSecrets(appContext), inferenceRuntime,
            healthContext = bands.healthTool::contextFor)
        connectionRepository = services.connections
        agentRepository = services.agents
        chatRepository = services.chats
        agentRunner = services.runner
    }
}
