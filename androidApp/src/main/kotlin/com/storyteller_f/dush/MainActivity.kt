package com.storyteller_f.dush

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.storyteller_f.dush.agent.AppGraph
import com.storyteller_f.dush.agent.notify.AgentNotificationHelper
import com.storyteller_f.dush.agent.ui.AndroidAgentApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppGraph.initialize(applicationContext)
        lifecycleScope.launch {
            try {
                AppGraph.bands.store.load()
                if (AppGraph.bands.store.devices.value.any { it.enabled } &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    AppGraph.bands.start(this@MainActivity)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { co.touchlab.kermit.Logger.w("Unable to restore band connections: ${failure::class.simpleName}") }
        }

        setContent {
            AndroidAgentApp(initialThreadId = intent.getStringExtra(AgentNotificationHelper.EXTRA_THREAD_ID))
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    AndroidAgentApp()
}
