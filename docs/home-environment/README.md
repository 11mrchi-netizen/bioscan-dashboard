# Home environment

Bioscan reads the home's environment from the Matter sensors in Google Home. The collector is **Home Terminal**, an app
for the Google TV Streamer 4K (`android-app/tv`, package `com.bioscan.hometerminal`).

## Phase E1: collect (this phase)

| Sensor | Reaches Google Home as | What is read |
|---|---|---|
| IKEA TIMMERFLOTTE | TemperatureSensorDevice + HumiditySensorDevice | temperature, humidity |
| Lengceoi thermometer (the separate product, not the IR repeater) | TemperatureSensorDevice + HumiditySensorDevice | temperature, humidity |
| IKEA MYGGSPRAY | OccupancySensorDevice + LightSensorDevice | light (motion is not collected) |
| IKEA ALPSTUGA, once bought | AirQualitySensorDevice | air quality, CO₂, PM2.5, temperature, humidity |

The device types above are what Matter defines for these products. The first run on the Streamer will confirm them.
Home Terminal lists every device it found, and the sensors' types land in `home_sensors.device_types`.

Not collected yet: the Lengceoi IR repeater and the AC it drives. IR only sends commands, so Google Home's state for the
AC is the last command, not what the AC does. If it is added later, store it as commands (`ac_commanded`), not as
measurements.

### How it works

- `GoogleHomeSource` (`tv/src/homeapi`) subscribes to each sensor's trait through the Google Home APIs. Nothing is polled.
  The Home APIs give only the current value and its changes, never history, so the collector must keep listening.
- `EnvCollectorService` is a foreground service (type `specialUse`) that runs all day and starts again after a reboot
  (`BootReceiver`).
- `SampleGate` writes a value when it is the first for its sensor and metric, when it moved by at least one step, or
  when the sensor reports the same value 15 minutes after the last write. The steps are 0.1 °C, 0.5 %RH, 10 % of the
  light level (at least 1 lx), 10 ppm CO₂ and 1 µg/m³ PM2.5.
- Everything waits in a file outbox and is uploaded every minute as the signed-in user (RLS), so being offline or
  signed out loses nothing.
- A heartbeat goes to `collector_heartbeats` (stream `home_env`) every 5 minutes. A gap in the readings with no
  heartbeat means the collector was down. A gap with heartbeats means nothing changed.

### Tables (`supabase/migrations/20261110000000_home_environment.sql`)

- `home_sensors (user_id, id, name, room, device_types, vendor, product, first_seen_at, last_seen_at)`: `id` is the
  Home APIs device id.
- `home_env_readings (user_id, sensor_id, metric, measured_at, value, raw)`: `metric` is one of `temperature_c`,
  `humidity_pct`, `illuminance_lux`, `co2_ppm`, `pm25_ugm3` or `air_quality` (1 good … 6 extremely poor).
  `measured_at` is a **real instant**: when the collector received the value, in true UTC. Convert it with the zone,
  and never read its digits as local time.
  `raw` is the value as Matter sent it, so a conversion can be redone.

Light is the one conversion to check on the first run. Matter encodes illuminance as `10000 × log10(lux) + 1`, and the
collector converts on that basis. The Home APIs reference calls the value "lux", which may mean Google already
converts it. Compare the light level shown on the TV with Google Home's. If they disagree, the readings can be redone
from `raw`.

## Setting it up

### 1. The Google Home APIs SDK

The SDK is not on Google's Maven. Sign in at developers.home.google.com and download it from the Android SDK page,
"Set up the SDK" (the sample app's `libs.versions.toml` names version 17.1.0). Then either unpack it into `~/.m2/repository`,
or unpack the Maven layout into `android-app/home-sdk/` (git ignores it).
`android-app/local.properties` must contain:

```
HOME_API_SDK_VERSION=17.1.0
GOOGLE_WEB_CLIENT_ID=<the web client id the phone app already uses>
```

Without `HOME_API_SDK_VERSION` the TV app still builds, using `tv/src/homestub`, and says the SDK is missing. That is
the build the cloud sessions run.

### 2. Google Cloud (the same project as the phone app's sign-in)

1. **OAuth consent screen**: add your Google account as a test user. The Home APIs work unverified for up to 100 test
   users. Developer Console registration is needed only to publish.
2. **Credentials**: create an **Android** OAuth client for package `com.bioscan.hometerminal` with the debug keystore's
   SHA-1. That is the same SHA-1 as the phone app, which this module also signs with. This one client serves both the
   Google sign-in to Supabase and the Home APIs consent.

### 3. The Streamer

1. Settings › System › About › Android TV OS build: press OK 7 times to enable Developer options, then turn on
   **USB debugging** (or network debugging).
2. Connect with `adb connect <streamer-ip>:5555` and install: `./gradlew :tv:installDebug`.
3. Open **Home Terminal** from the apps row. Sign in with Google, then allow access to Google Home and pick the home.
   The sensors appear with their live values.

Google TV may restrict background work in standby or energy-saver modes. If readings stop while the TV is off, check
the heartbeats first, then Settings › System › Power & Energy.

## What this phase has to prove

These are the open questions from the evaluation. Note the answers here after the first run on the Streamer:

- [ ] The Home APIs work on Google TV. The docs only describe phones, and list the Streamer only as a hub.
- [ ] Google's consent screen can be completed with the remote, or with the Google TV phone app's keyboard.
- [ ] Credential Manager's Google sign-in works on the Streamer.
- [ ] The foreground service survives standby overnight (check the heartbeats).
- [ ] MYGGSPRAY reports a real light level (users report a constant 1 lx through Google Home).
- [ ] Which conversion the light value needs (see above).

If the Home APIs do not work on Google TV, the fallback is route B from the evaluation: share the sensors with Home
Assistant through Matter multi-admin, and keep the TV as the display.

## Later phases

- E2: the readings in Bioscan (web and Field Terminal), per room, and beside sleep.
- E3: the vacuum map (Xiaomi S10, Taiwan server) and the editable floor plan.
- E4: the bioscan skin for the Streamer (screensaver first, launcher last).
