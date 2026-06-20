package com.storyteller_f.dush

import android.app.Application
import com.storyteller_f.dush.agent.AppGraph

class OnDeviceAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureAppLogging(this)
        AppGraph.initialize(this)
    }
}
