package com.storyteller_f.dush

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.storyteller_f.dush.agent.AppGraph
import com.storyteller_f.dush.agent.notify.AgentNotificationHelper
import com.storyteller_f.dush.agent.ui.AndroidAgentApp

class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppGraph.initialize(applicationContext)
        setContent {
            AndroidAgentApp(
                initialThreadId = intent.getStringExtra(AgentNotificationHelper.EXTRA_THREAD_ID),
                bubbleMode = true,
            )
        }
    }
}
