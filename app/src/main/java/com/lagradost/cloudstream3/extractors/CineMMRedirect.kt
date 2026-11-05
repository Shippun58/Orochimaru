package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import io.ktor.http.Url

private val mirrors = arrayOf(
    "hgplaycdn.com",
    "habetar.com",
    "yuguaab.com",
    "guxhag.com",
    "auvexiug.com",
    "xenolyzb.com",
    "haxloppd.com",
    "cavanhabg.com",
    "dumbalag.com",
    "uasopt.com"
)

class HgplayCDN: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://hgplaycdn.com"
}

class Habetar: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://habetar.com"
}

class Yuguaab: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://yuguaab.com"
}

class Guxhag: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://guxhag.com"
}

class Auvexiug: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://auvexiug.com"
}

class Xenolyzb: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://xenolyzb.com"
}

class Haxloppd: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://haxloppd.com"
}

class Cavanhabg: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://cavanhabg.com"
}

class Dumbalag: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://dumbalag.com"
}

class Uasopt: VidHidePro() {
    override val name: String = "CineMM"
    override val mainUrl: String = "https://uasopt.com"
}

class Dhcplay: CineMMRedirect() {
    override val mainUrl: String = "https://dhcplay.com"
}

class HglinkTo: CineMMRedirect() {
    override val mainUrl: String = "https://hglink.to"
}

abstract class CineMMRedirect : ExtractorApi() {
    override val name: String = "CineMMRedirect"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Url(url).encodedPath
        val mirror = mirrors.random()

        val mirrorUrlWithVideoId = "https://$mirror$videoId"
        loadExtractor(mirrorUrlWithVideoId, referer, subtitleCallback, callback)
    }
}
