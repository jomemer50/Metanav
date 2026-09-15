package com.metanav.app

import android.app.Application
import com.metanav.app.engine.NavigationController

class MetanavApp : Application() {
    lateinit var controller: NavigationController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = NavigationController(this)
    }

    companion object {
        fun controller(app: Application): NavigationController = (app as MetanavApp).controller
    }
}
