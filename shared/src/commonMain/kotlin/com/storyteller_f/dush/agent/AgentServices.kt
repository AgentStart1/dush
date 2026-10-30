package com.storyteller_f.dush.agent

import com.storyteller_f.dush.agent.data.AgentDatabase
import com.storyteller_f.dush.agent.repository.*
import com.storyteller_f.dush.agent.runtime.*

/** Platform composition roots own database, secrets and transport lifetimes. */
class AgentServices(database: AgentDatabase, secrets: ConnectionSecrets, val runtime: InferenceRuntime,
    healthContext: suspend (String) -> String? = { null }) {
    val connections = ConnectionRepository(database.connectionDao(), secrets)
    val agents = AgentRepository(database.agentDao(), database.connectionDao())
    val chats = ChatRepository(database.chatDao(), database.agentDao())
    val runner: AgentRunner = KoogAgentRunner(chats, agents, connections, runtime, healthContext)
}
