package com.bioscan.hometerminal.env

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the collector after the Streamer boots or the app is updated, so it never needs the app opened by hand. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> EnvCollectorService.start(context)
        }
    }
}
