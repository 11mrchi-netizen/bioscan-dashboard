package com.bioscan.fieldterminal.domain

// What the app's root shows for a session state (MainActivity.AuthGate). The session is reloaded, and refreshed when its
// access token has expired, every time the app returns to the foreground; while that happens the state can pass through
// INITIALIZING or a network refresh failure. Once the app has been shown, those transient states keep it on screen (with
// a banner) instead of replacing it, which used to throw away the open page, its sheets and forms and restart at Status
// (after the camera, a file picker, or a long time in the background). Sign-in is asked for only when the session is
// really gone.

enum class AuthState { AUTHENTICATED, INITIALIZING, NOT_AUTHENTICATED, REFRESH_NETWORK_ERROR, REFRESH_FAILED }

enum class GateView { APP, APP_RECONNECTING, LOADING, OFFLINE, SIGN_IN, SESSION_EXPIRED }

fun gateView(state: AuthState, appShown: Boolean): GateView = when (state) {
    AuthState.AUTHENTICATED -> GateView.APP
    AuthState.INITIALIZING -> if (appShown) GateView.APP_RECONNECTING else GateView.LOADING
    AuthState.REFRESH_NETWORK_ERROR -> if (appShown) GateView.APP_RECONNECTING else GateView.OFFLINE
    AuthState.NOT_AUTHENTICATED -> GateView.SIGN_IN
    AuthState.REFRESH_FAILED -> GateView.SESSION_EXPIRED
}

val GateView.showsApp: Boolean get() = this == GateView.APP || this == GateView.APP_RECONNECTING
