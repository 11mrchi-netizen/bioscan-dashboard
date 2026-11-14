package com.bioscan.hometerminal.home

import android.content.Context
import androidx.activity.ComponentActivity
import com.bioscan.hometerminal.env.EnvMetric
import com.bioscan.hometerminal.env.MatterUnits
import com.bioscan.hometerminal.env.Measurement
import com.bioscan.hometerminal.env.SensorInfo
import com.google.home.FactoryRegistry
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.HomeException
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.matter.standard.AirQuality
import com.google.home.matter.standard.AirQualitySensorDevice
import com.google.home.matter.standard.AirQualityTrait
import com.google.home.matter.standard.BasicInformation
import com.google.home.matter.standard.CarbonDioxideConcentrationMeasurement
import com.google.home.matter.standard.CarbonDioxideConcentrationMeasurementTrait
import com.google.home.matter.standard.HumiditySensorDevice
import com.google.home.matter.standard.IlluminanceMeasurement
import com.google.home.matter.standard.LightSensorDevice
import com.google.home.matter.standard.Pm25ConcentrationMeasurement
import com.google.home.matter.standard.Pm25ConcentrationMeasurementTrait
import com.google.home.matter.standard.RelativeHumidityMeasurement
import com.google.home.matter.standard.RootNodeDevice
import com.google.home.matter.standard.TemperatureMeasurement
import com.google.home.matter.standard.TemperatureSensorDevice
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads the home's sensors through the Google Home APIs (developers.home.google.com/apis/android). Every value comes
 * from a subscription the SDK keeps to the device (a trait flow), so nothing is polled.
 *
 * Which devices count: anything with a temperature, humidity, light or air-quality endpoint. IKEA TIMMERFLOTTE and the
 * Lengceoi thermometer are temperature + humidity sensors, MYGGSPRAY has a light sensor beside its motion sensor, and
 * ALPSTUGA is an air-quality sensor that also carries temperature and humidity.
 */
object GoogleHomeSource : HomeSource {
    private val registry = FactoryRegistry(
        traits = listOf(
            TemperatureMeasurement,
            RelativeHumidityMeasurement,
            IlluminanceMeasurement,
            AirQuality,
            CarbonDioxideConcentrationMeasurement,
            Pm25ConcentrationMeasurement,
            BasicInformation,
        ),
        types = listOf(
            TemperatureSensorDevice,
            HumiditySensorDevice,
            LightSensorDevice,
            AirQualitySensorDevice,
            RootNodeDevice,
        ),
    )

    // The SDK wants one client per process.
    @Volatile private var client: HomeClient? = null
    private val home: HomeClient get() = checkNotNull(client) { "GoogleHomeSource.init was not called" }

    override fun init(context: Context) {
        if (client != null) return
        synchronized(this) {
            if (client == null) {
                client = Home.getClient(context.applicationContext, HomeConfig(coroutineContext = Dispatchers.IO, factoryRegistry = registry))
            }
        }
    }

    override fun access(): Flow<HomeAccess> = home.hasPermissions().map {
        when (it) {
            PermissionsState.GRANTED -> HomeAccess.GRANTED
            PermissionsState.NOT_GRANTED -> HomeAccess.NOT_GRANTED
            PermissionsState.PERMISSIONS_STATE_UNAVAILABLE -> HomeAccess.UNAVAILABLE
            else -> HomeAccess.CHECKING
        }
    }

    override fun bind(activity: ComponentActivity) {
        home.registerActivityResultCallerForPermissions(activity)
    }

    override suspend fun requestAccess(): String? = try {
        val result = home.requestPermissions()
        when (result.status) {
            PermissionsResultStatus.SUCCESS -> null
            PermissionsResultStatus.CANCELLED -> "Access was not given."
            else -> result.errorMessage ?: "Google Home did not give access."
        }
    } catch (e: HomeException) {
        "Google Home error: ${e.message}"
    }

    override fun sensors(): Flow<List<SensorInfo>> = home.devices().map { devices ->
        devices.filter { measures(it) }.map { describe(it) }.sortedBy { it.name }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun measurements(): Flow<Measurement> = home.devices()
        .map { devices -> devices.filter { measures(it) }.associateBy { it.id.id } }
        // A new value of a sensor does not change this set; only adding or removing one restarts the subscriptions.
        .distinctUntilChanged { a, b -> a.keys == b.keys }
        .flatMapLatest { byId -> merge(*byId.values.flatMap { flowsFor(it) }.toTypedArray()) }

    private suspend fun measures(d: HomeDevice): Boolean =
        d.has(TemperatureSensorDevice) || d.has(HumiditySensorDevice) || d.has(LightSensorDevice) || d.has(AirQualitySensorDevice)

    private suspend fun describe(d: HomeDevice): SensorInfo {
        val types = buildList {
            if (d.has(TemperatureSensorDevice)) add("TemperatureSensorDevice")
            if (d.has(HumiditySensorDevice)) add("HumiditySensorDevice")
            if (d.has(LightSensorDevice)) add("LightSensorDevice")
            if (d.has(AirQualitySensorDevice)) add("AirQualitySensorDevice")
        }
        // Vendor and product come from the root endpoint; a device without one must not hold the list up.
        val info = runCatching {
            withTimeoutOrNull(5_000) { d.type(RootNodeDevice).first().standardTraits.basicInformation }
        }.getOrNull()
        return SensorInfo(
            id = d.id.id,
            name = d.name,
            room = runCatching { d.room()?.name }.getOrNull(),
            deviceTypes = types,
            vendor = info?.vendorName,
            product = info?.productName,
        )
    }

    private suspend fun flowsFor(d: HomeDevice): List<Flow<Measurement>> {
        val id = d.id.id
        val flows = mutableListOf<Flow<Measurement>>()

        // Temperature and humidity: the dedicated endpoint when there is one, else the air-quality sensor's own.
        val temperature: Flow<Double?>? = when {
            d.has(TemperatureSensorDevice) -> d.type(TemperatureSensorDevice).map { it.trait(TemperatureMeasurement)?.measuredValue?.toDouble() }
            d.has(AirQualitySensorDevice) -> d.type(AirQualitySensorDevice).map { it.trait(TemperatureMeasurement)?.measuredValue?.toDouble() }
            else -> null
        }
        temperature?.let { flows += it.readings(id, EnvMetric.TEMPERATURE_C) { raw -> MatterUnits.temperatureC(raw) } }

        val humidity: Flow<Double?>? = when {
            d.has(HumiditySensorDevice) -> d.type(HumiditySensorDevice).map { it.trait(RelativeHumidityMeasurement)?.measuredValue?.toDouble() }
            d.has(AirQualitySensorDevice) -> d.type(AirQualitySensorDevice).map { it.trait(RelativeHumidityMeasurement)?.measuredValue?.toDouble() }
            else -> null
        }
        humidity?.let { flows += it.readings(id, EnvMetric.HUMIDITY_PCT, MatterUnits::humidityPct) }

        if (d.has(LightSensorDevice)) {
            flows += d.type(LightSensorDevice).map { it.trait(IlluminanceMeasurement)?.measuredValue?.toDouble() }
                .readings(id, EnvMetric.ILLUMINANCE_LUX, MatterUnits::illuminanceLux)
        }

        if (d.has(AirQualitySensorDevice)) {
            val aq = d.type(AirQualitySensorDevice)
            flows += aq.map { it.trait(AirQuality)?.airQuality?.let(::airQualityLevel) }
                .readings(id, EnvMetric.AIR_QUALITY) { it }
            flows += aq.map { type ->
                type.trait(CarbonDioxideConcentrationMeasurement)?.let { c ->
                    val unit = c.measurementUnit
                    if (unit == null || unit == CarbonDioxideConcentrationMeasurementTrait.MeasurementUnitEnum.PPM) c.measuredValue?.toDouble() else null
                }
            }.readings(id, EnvMetric.CO2_PPM) { it }
            flows += aq.map { type ->
                type.trait(Pm25ConcentrationMeasurement)?.let { c ->
                    val unit = c.measurementUnit
                    if (unit == null || unit == Pm25ConcentrationMeasurementTrait.MeasurementUnitEnum.UGM3) c.measuredValue?.toDouble() else null
                }
            }.readings(id, EnvMetric.PM25_UGM3) { it }
        }
        return flows
    }

    private fun Flow<Double?>.readings(sensorId: String, metric: EnvMetric, convert: (Double) -> Double?): Flow<Measurement> =
        mapNotNull { raw -> raw?.let { r -> convert(r)?.let { Measurement(sensorId, metric, it, r, Instant.now()) } } }

    private fun airQualityLevel(q: AirQualityTrait.AirQualityEnum): Double? = when (q) {
        AirQualityTrait.AirQualityEnum.Good -> 1.0
        AirQualityTrait.AirQualityEnum.Fair -> 2.0
        AirQualityTrait.AirQualityEnum.Moderate -> 3.0
        AirQualityTrait.AirQualityEnum.Poor -> 4.0
        AirQualityTrait.AirQualityEnum.VeryPoor -> 5.0
        AirQualityTrait.AirQualityEnum.ExtremelyPoor -> 6.0
        else -> null
    }
}
