package com.lagradost.cloudstream3.utils

import android.content.Context

object GitInfo {
    fun Context.currentCommitHash(): String = try {
        assets.open("git-hash.txt")
            .bufferedReader()
            .readText()
            .trim()
    } catch (_: Exception) {
        ""
    }
}
