package com.miguanm7a.hsr

import android.app.Application
import android.util.Log

class March7thApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "March7thAssistant 启动，filesDir=${filesDir.absolutePath}")
    }

    companion object {
        const val TAG = "MaaTermux"

        lateinit var instance: March7thApplication
            private set
    }
}
