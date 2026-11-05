package com.lagradost.cloudstream3.utils

import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import com.lagradost.cloudstream3.mvvm.Resource

open class ConsistentLiveData<T>(initValue : T? = null) : LiveData<T>(initValue) {
    @Volatile private var internalValue : T? = initValue

    override fun getValue(): T? {
        return internalValue
    }

    val postedValue : T? get() = super.getValue()

    public override fun postValue(value : T?) {
        super.postValue(value)
        internalValue = value
    }

    @MainThread
    public override fun setValue(value: T?) {
        super.setValue(value)
        internalValue = value
    }
}

class ResourceLiveData<T>(initValue : Resource<T>? = null) : ConsistentLiveData<Resource<T>>(initValue) {
    var success
        get() = when(val output = this.value) {
            is Resource.Success<T> -> {
                output.value
            }
            else -> null
        }
        set(value) = this.postValue(value?.let { Resource.Success<T>(it) } )
}
