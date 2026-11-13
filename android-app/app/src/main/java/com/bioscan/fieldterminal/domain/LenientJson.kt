package com.bioscan.fieldterminal.domain

import kotlinx.serialization.json.Json

/** The app's JSON reader: unknown keys are ignored, so a column or field added on the server never breaks a read. */
val LenientJson = Json { ignoreUnknownKeys = true }
