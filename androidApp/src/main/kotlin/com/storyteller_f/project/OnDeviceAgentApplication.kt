package com.storyteller_f.project

import android.app.Application
import com.storyteller_f.project.agent.AppGraph

class OnDeviceAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureAppLogging(this)
        AppGraph.initialize(this)
    }
}
