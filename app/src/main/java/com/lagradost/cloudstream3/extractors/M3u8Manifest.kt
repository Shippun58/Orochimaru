package com.lagradost.cloudstream3.extractors

object M3u8Manifest {

    fun extractLinks(m3u8Data: String): ArrayList<Pair<String, String>> {
        val data: ArrayList<Pair<String, String>> = ArrayList()
        Regex("\"(.*?)\":\"(.*?)\"").findAll(m3u8Data).forEach {
            var quality = it.groupValues[1].replace("auto", "Auto")
            if (quality != "Auto" && !quality.endsWith('p')) quality += "p"
            val url = it.groupValues[2]
            data.add(Pair(url, quality))
        }
        return data
    }
}