package com.kazemail.app

import android.app.Application
import com.kazemail.app.di.KazeMailContainer

class KazeMailApp : Application() {
    val container: KazeMailContainer by lazy { KazeMailContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.prewarmAsync()
    }
}
