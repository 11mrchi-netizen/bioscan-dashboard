package com.bioscan.hometerminal.home

import android.content.Context
import androidx.activity.ComponentActivity
import com.bioscan.hometerminal.env.Measurement
import com.bioscan.hometerminal.env.SensorInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Stand-in for a build without the Google Home APIs SDK (HOME_API_SDK_VERSION unset in local.properties): it reads
 * nothing and says why. The real source is src/homeapi/.../GoogleHomeSource.kt.
 */
object GoogleHomeSource : HomeSource {
    override fun init(context: Context) = Unit
    override fun access(): Flow<HomeAccess> = flowOf(HomeAccess.NO_SDK)
    override fun bind(activity: ComponentActivity) = Unit
    override suspend fun requestAccess(): String =
        "This build has no Google Home APIs SDK. See docs/home-environment/README.md."
    override fun sensors(): Flow<List<SensorInfo>> = flowOf(emptyList())
    override fun measurements(): Flow<Measurement> = emptyFlow()
}
