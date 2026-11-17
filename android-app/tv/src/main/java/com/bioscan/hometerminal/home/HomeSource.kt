package com.bioscan.hometerminal.home

import android.content.Context
import androidx.activity.ComponentActivity
import com.bioscan.hometerminal.env.Measurement
import com.bioscan.hometerminal.env.SensorInfo
import kotlinx.coroutines.flow.Flow

/** Whether this app may read the home's devices. */
enum class HomeAccess { NO_SDK, CHECKING, NOT_GRANTED, GRANTED, UNAVAILABLE }

/**
 * Where the sensors are read from. The real one, [GoogleHomeSource] in src/homeapi, uses the Google Home APIs; a build
 * without the SDK gets the stand-in in src/homestub (see tv/build.gradle.kts).
 */
interface HomeSource {
    /** Creates the one Home APIs client. Called from Application.onCreate. */
    fun init(context: Context)

    fun access(): Flow<HomeAccess>

    /** Registers the activity that shows Google's consent screen. Must be called in the activity's onCreate. */
    fun bind(activity: ComponentActivity)

    /** Shows Google's consent screen. Returns null when access was granted, otherwise what to tell the person. */
    suspend fun requestAccess(): String?

    /** The devices that measure something, re-emitted when one is added, removed, renamed or moved. */
    fun sensors(): Flow<List<SensorInfo>>

    /** Every value the sensors report, as it arrives. */
    fun measurements(): Flow<Measurement>
}

val homeSource: HomeSource get() = GoogleHomeSource
