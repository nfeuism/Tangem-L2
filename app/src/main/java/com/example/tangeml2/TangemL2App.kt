package com.example.tangeml2

import android.app.Application
import com.example.tangeml2.core.log.AppLog

/** Installs diagnostics as early as possible so start-up failures are captured. */
class TangemL2App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
    }
}
