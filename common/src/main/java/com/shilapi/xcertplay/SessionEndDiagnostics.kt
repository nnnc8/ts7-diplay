package com.shilapi.xcertplay

internal enum class SessionEndBy(val label: String) {
    USER("user"),
    SETTINGS("settings"),
    SYSTEM("system"),
    FAILURE("failure"),
}

/** Small pure formatters keep lifecycle diagnostics consistent and easy to verify. */
internal object SessionEndDiagnostics {
    fun requested(reason: String, by: SessionEndBy, terminateProcess: Boolean): String =
        "Session end requested reason=$reason by=${by.label} terminateProcess=$terminateProcess"

    fun activityStopped(finishing: Boolean): String =
        "Activity stopped finishing=$finishing"

    fun activityDestroyed(finishing: Boolean, changingConfigurations: Boolean): String =
        "Activity destroyed finishing=$finishing changingConfigurations=$changingConfigurations"
}
