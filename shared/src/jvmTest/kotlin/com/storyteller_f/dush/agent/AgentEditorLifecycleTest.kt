package com.storyteller_f.dush.agent

import com.storyteller_f.dush.agent.data.ConnectionDao
import com.storyteller_f.dush.agent.data.ConnectionEntity
import com.storyteller_f.dush.agent.repository.AgentRepository
import com.storyteller_f.dush.agent.repository.ConnectionRepository
import com.storyteller_f.dush.agent.ui.AgentEditorHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentEditorLifecycleTest {
    @Test fun closeJobIncludesConnectionObservationCleanup() = runTest {
        val path = Files.createTempFile("dush-editor-lifecycle", ".db")
        val db = openDatabase(path.toString())
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val dao = object : ConnectionDao by db.connectionDao() {
            override fun observeAll() = flow<List<ConnectionEntity>> {
                try {
                    emit(emptyList())
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        release.await()
                        cleaned.complete(Unit)
                    }
                }
            }
        }
        val host = AgentEditorHost(null, AgentRepository(db.agentDao(), db.connectionDao()),
            ConnectionRepository(dao, MemorySecrets()), StandardTestDispatcher(testScheduler))
        try {
            started.await()
            val closing = host.close()
            cleanupStarted.await()
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            closing.join()
            assertTrue(cleaned.isCompleted)
        } finally {
            release.complete(Unit)
            host.close().join()
            db.close()
            Files.deleteIfExists(path)
        }
    }
}
