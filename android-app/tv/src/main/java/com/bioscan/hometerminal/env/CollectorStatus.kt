package com.bioscan.hometerminal.env

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the collector is doing, for the screen. The service and the activity share one process. */
object CollectorStatus {
    data class State(
        val running: Boolean = false,
        val sensors: List<SensorInfo> = emptyList(),
        /** The newest value of every sensor and metric, written or not. */
        val latest: Map<Pair<String, EnvMetric>, Measurement> = emptyMap(),
        val queued: Int = 0,
        val lastUploadAt: Instant? = null,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(f: (State) -> State) = _state.update(f)

    fun received(m: Measurement) = _state.update { it.copy(latest = it.latest + ((m.sensorId to m.metric) to m)) }
}
