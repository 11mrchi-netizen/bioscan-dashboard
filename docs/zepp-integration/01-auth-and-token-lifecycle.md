# 01 — Zepp authentication and token lifecycle

**Linear:** [DAV-111](https://linear.app/biodashboard/issue/DAV-111) · **Status:** research complete

## Two API surfaces

Zepp Health exposes two distinct API surfaces. They differ in data coverage,
authentication model, and stability guarantees.

### 1. Official Zepp/Huami OAuth REST API

The documented, developer-facing API at `api-open.huami.com`.

**OAuth 2.0 Authorization Code Grant:**
```
1. Redirect → https://user.HuaMi.com/oauth/index.html#/
     ?client_id=APP_ID
     &redirect_uri=REGISTERED_URI
     &response_type=code
     &state=OPAQUE
2. User grants consent → redirect with ?code=AUTH_CODE
3. POST https://auth.huami.com/oauth2/access_token
     client_id, client_secret, grant_type=authorization_code,
     redirect_uri, code
   → { access_token, refresh_token, expires_in, token_type }
4. POST https://auth.huami.com/oauth2/refresh_token
     client_id, client_secret, grant_type=refresh_token,
     refresh_token
   → new access_token + refresh_token
```

**Token lifetimes:**
| Token | Lifetime |
|-------|----------|
| Authorization code | 5 minutes |
| Access token | 90 days (default) |
| Refresh token | 10 years |

**Regional endpoints:**
| Region | User portal | API |
|--------|-------------|-----|
| Default | `user.HuaMi.com` | `api-open.huami.com` |
| China | `user-cn.HuaMi.com` | (same?) |
| US | `user-US.HuaMi.com` | (same?) |

**Available scopes:**
| Scope | Data |
|-------|------|
| profile | userId, gender, height, weight, nickName, avatar |
| activity | daily/hourly steps, distance, calories |
| sleep | sleep times, deep/shallow sleep duration |
| heartrate | continuous HR data and summaries |
| motion | raw 3-byte motion sensor data (1440 min/day) |
| sport | activity summaries (cycling, running) |
| sportDetail | detailed activity data |
| notifyme | send notifications to wearables |

**Limitations:** No HRV, no training load, no VO2max, no SpO2, no stress,
no skin temperature, no sleep stages (only deep/shallow). The official API's
scope set predates the modern Zepp OS metrics.

**Registration:** Requires contacting Zepp Health for app_id/app_secret
(historically via email to the Huami developer team). No self-service
developer portal for REST API credentials as of this research.

### 2. Zepp mobile API (unofficial)

The internal API used by the Zepp mobile app. Reverse-engineered, not
officially documented, but community-validated.

**Authentication:** Header-based `apptoken` extracted via HTTPS proxy capture
from the Zepp mobile app's network traffic.

**Token lifetime:** ~30 days. No refresh mechanism — requires re-capture when
expired.

**Regional endpoints:**
- `api-mifit-us3.zepp.com` (US)
- `api-mifit-cn.zepp.com` (China)
- Region-specific — wrong region returns 403 or empty data

**Available data (superset of official API):**
| Data | Endpoint pattern | Notes |
|------|-----------------|-------|
| HRV | `/v2/users/me/events?eventType=hrv` | RMSSD available |
| Heart rate | `/users/{id}/heartRate` | Continuous |
| Sleep + stages | `/v1/data/band_data.json` | Deep/light/REM/awake |
| Training load | `/v2/watch/users/{id}/WatchSportStatistics/SPORT_LOAD` | |
| VO2max | `/v2/watch/users/{id}/WatchSportStatistics/VO2_MAX` | |
| SpO2 | via events endpoint | |
| Stress | via events endpoint | |
| Skin temperature | via events endpoint | Delta from baseline |
| Sport history | `/v1/sport/{sport}/history.json` | |
| Weight | `/users/{id}/members/-1/weightRecords` | |
| Blood pressure | `/users/me/bloodPressure` | |

**Risks:**
- Endpoints can change without notice (internal API)
- Token capture requires HTTPS proxy setup (one-time per device)
- ~30-day re-auth cycle (manageable for single-user personal project)
- Rate limiting observed on some older login paths (HTTP 429)

## Recommendation for Field Terminal

**Use the mobile API (option 2)** for the initial integration:

1. **Data coverage is the deciding factor.** The official API lacks HRV,
   training load, VO2max, sleep stages, SpO2, and stress — exactly the metrics
   that make a Zepp integration valuable beyond what Health Connect already
   provides. Without these, the official API offers nothing that isn't already
   in `wearable_daily` and `sleep_daily` via Health Connect.

2. **Single-user personal project.** The 30-day token re-capture cycle is
   acceptable for one person. A multi-user product would need the official
   OAuth flow, but this isn't one.

3. **Server-side only.** The `apptoken` is stored in Supabase secrets or a
   server-side env var — never in the Android client. This satisfies the
   acceptance criterion "no Zepp secret is required in the Android client."

4. **Re-auth procedure:** When the token expires (~30 days), capture a new one
   via HTTPS proxy (mitmproxy/Charles) on the phone running the Zepp app, then
   update the server-side secret. This is a 2-minute manual step once a month.

5. **Future upgrade path:** If/when Zepp Health opens self-service developer
   registration with expanded scopes (HRV, training load), migrate to the
   official OAuth flow. The data normalization layer (DAV-113+) is the same
   regardless of auth method.

## Token storage design

```
Supabase secrets (or Edge Function env vars):
  ZEPP_APP_TOKEN    = <captured apptoken>
  ZEPP_USER_ID      = <user id from capture>
  ZEPP_API_HOST     = api-mifit-us3.zepp.com (or region-appropriate)

No Zepp credentials in:
  - Android app code
  - Client-side JavaScript
  - Git repository
```

## Recovery procedure

1. Token expires → sync starts returning 401/403
2. On phone: configure HTTPS proxy (mitmproxy)
3. Open Zepp app, navigate to any data screen
4. Extract `apptoken` from captured request headers
5. Update `ZEPP_APP_TOKEN` in Supabase secrets
6. Sync resumes automatically on next run

## Open items for DAV-112 (extraction PoC)

- [ ] Confirm actual regional endpoint for this account (Taiwan-based)
- [ ] Verify HRV data granularity (per-reading timestamps vs daily summary)
- [ ] Test sleep stages data format (base64-encoded blob per zepp-health-cli)
- [ ] Determine training load calculation method (Zepp-native vs derivable)
- [ ] Measure typical API response times
- [ ] Document the exact proxy capture steps for this specific device setup

## Sources

- [Zepp Health REST API Wiki](https://github.com/zepp-health/rest-api/wiki)
- [Zepp Health OAuth Android SDK](https://github.com/zepp-health/oauth-Android-sdk)
- [zepp-health-cli (unofficial)](https://github.com/m4ary/zepp-health-cli)
- [Zepp OS Architecture](https://docs.zepp.com/docs/guides/architecture/arc/)
- [Zepp Health GitHub Discussions](https://github.com/orgs/zepp-health/discussions/276)
