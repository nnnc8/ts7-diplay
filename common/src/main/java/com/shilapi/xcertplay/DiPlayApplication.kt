package com.shilapi.xcertplay

import android.app.Application
import com.shilapi.xcertplay.network.Ts7HeadUnit
import java.io.File

class DiPlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionLogFile.pruneTs7Archives(File(filesDir, "logs"), Ts7HeadUnit.detected)
    }
}
