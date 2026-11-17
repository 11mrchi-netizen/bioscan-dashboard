package com.bioscan.hometerminal

import android.app.Application
import com.bioscan.hometerminal.home.homeSource

class HomeTerminalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // One Home APIs client for the process, shared by the activity and the collector service.
        homeSource.init(this)
    }
}
