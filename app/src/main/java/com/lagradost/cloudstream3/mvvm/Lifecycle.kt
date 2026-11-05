package com.lagradost.cloudstream3.mvvm

import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.doOnAttach
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.findViewTreeLifecycleOwner

fun <T> ComponentActivity.observe(liveData: LiveData<T>, action: (T) -> Unit) {
    observeNullable(liveData) { t -> t?.run(action) }
}

fun <T> ComponentActivity.observeNullable(liveData: LiveData<T>, action: (T?) -> Unit) {
    liveData.removeObservers(this)
    liveData.observe(this, action)
}

fun <T> View.observe(liveData: LiveData<T>, action: (T) -> Unit) {
    observeNullable(liveData) { t -> t?.run(action) }
}

fun <T> View.observeNullable(liveData: LiveData<T>, action: (T?) -> Unit) {
    doOnAttach { view ->

        val owner: LifecycleOwner? = view.findViewTreeLifecycleOwner()
        if(owner == null) {
            debugException { "Expected non-null findViewTreeLifecycleOwner" }
            return@doOnAttach
        }
        liveData.removeObservers(owner)
        liveData.observe(owner, action)
    }
}