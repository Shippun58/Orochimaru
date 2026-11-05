package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import kotlin.Throws

abstract class Plugin : BasePlugin() {

    @Throws(Throwable::class)
    open fun load(context: Context) {

        load()
    }

    var resources: Resources? = null

    var openSettings: ((context: Context) -> Unit)? = null
}
