package com.iltan.settle

import android.app.Application
import android.content.Context

class SettleApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this).also { it.start() }
    }
}

/** The process-wide [AppGraph], reachable from any context. */
val Context.appGraph: AppGraph
    get() = (applicationContext as SettleApplication).graph
