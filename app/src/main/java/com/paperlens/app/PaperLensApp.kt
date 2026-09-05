package com.paperlens.app

import android.app.Application
import com.paperlens.app.di.AppGraph

class PaperLensApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
