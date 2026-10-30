package com.storyteller_f.dush

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.awaitApplication
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.storyteller_f.dush.agent.ui.SharedDushTheme
import com.storyteller_f.dush.agent.ui.WorkspaceApp
import com.storyteller_f.dush.agent.ui.WorkspaceHost
import kotlinx.coroutines.runBlocking

// Bridge the JVM entry point; awaitApplication returns without terminating the process,
// so reply cleanup and database closure below finish before the JVM exits.
fun main() = runBlocking {
    val graph = DesktopGraph.open()
    val host = WorkspaceHost(graph.services, graph.replies, graph.coordination)
    try {
        awaitApplication {
            Window(onCloseRequest = ::exitApplication, title = "dush",
                state = rememberWindowState(width = 1000.dp, height = 760.dp)) {
                SharedDushTheme {
                    WorkspaceApp(host, graph.directory.toString())
                }
            }
        }
    } finally {
        try { host.close() } finally { graph.close() }
    }
}
