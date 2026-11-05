package com.lagradost.cloudstream3.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class Vidsonic() : ExtractorApi() {
    override val name: String = "Vidsonic"
    override val mainUrl: String = "https://vidsonic.net"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val response = app.get(url).text
        val encodedStreamUrl = response
            .substringAfter("const _0x1 = ")
            .substringBefore(";")
            .replace("'", "")

        val streamUrl = encodedStreamUrl
            .replace("|", "")

            .chunked(2)
            .map {
                it.toInt(16).toChar()
            }
            .joinToString("")
            .reversed()

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            )
        )
    }
}