
package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.base64DecodeArray

class Videa : ExtractorApi() {
    override val name = "Videa"
    override val mainUrl = "https://videa.hu"
    override val requiresReferer = false

    private val videaSecret = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = url
        var key = ""

        val visitedUrls = mutableSetOf<String>()
        var count = 10
        while (!visitedUrls.contains(currentUrl) && count > 0) {
            visitedUrls += currentUrl
            count -= 1

            val webUrl = getXmlUrl(currentUrl) { cookie ->  } ?: return
            val response = app.get(webUrl)
            val body = response.body
            val rawBytes = body.bytes()
            body.close()

            val isXml = rawBytes.size >= 5 &&
                    rawBytes[0] == 0x3C.toByte() &&
                    rawBytes[1] == 0x3F.toByte() &&
                    rawBytes[2] == 0x78.toByte() &&
                    rawBytes[3] == 0x6D.toByte() &&
                    rawBytes[4] == 0x6C.toByte()

            val videaXml = if (isXml) {
                rawBytes.decodeToString()
            } else {

                val xsHeader = response.headers["X-Videa-Xs"] ?: return
                key += xsHeader
                rc4DecryptBytes(rawBytes, key)
            }

            val redirectMatch = """<error.*?"noembed".*>(.*)</error>""".toRegex().find(videaXml)

            if (redirectMatch != null && redirectMatch.groupValues[1] != currentUrl) {
                currentUrl = redirectMatch.groupValues[1]
            } else {
                parseVideoSources(videaXml, callback)
                break
            }
        }
    }

    private suspend fun getXmlUrl(url: String, cookieCallback: (String) -> Unit = {}): String? {
        val response = app.get(url)
        val size = response.size

        if(size != null && size > 5_000_000) {

            return null
        }
        val html = response.text

        response.headers["Set-Cookie"]?.let { cookieHeader ->
            """sl=([^;]+)""".toRegex().find(cookieHeader)?.let {
                cookieCallback(it.value)
            }
        }

        val playerUrl = if ("/player" in url) {
            url
        } else {
            val iframeMatch = """<iframe.*?src="(/player\?[^\"]+)""".toRegex().find(html)
            iframeMatch?.let { "$mainUrl${it.groupValues[1]}" } ?: return null
        }

        val playerResponse = app.get(playerUrl)
        val playerHtml = playerResponse.text

        playerResponse.headers["Set-Cookie"]?.let { cookieHeader ->
            """sl=([^;]+)""".toRegex().find(cookieHeader)?.let {
                cookieCallback(it.value)
            }
        }

        val nonceMatch = """_xt\s*=\s*"([^"]+)""".toRegex().find(playerHtml) ?: return null
        val (s, t, key) = generateTokens(nonceMatch.groupValues[1])

        val videoParam = when {
            "f=" in playerUrl -> "f=" + playerUrl.substringAfter("f=").substringBefore("&")
            "v=" in playerUrl -> "v=" + playerUrl.substringAfter("v=").substringBefore("&")
            else -> return null
        }

        return "$mainUrl/player/xml?platform=desktop&$videoParam&_s=$s&_t=$t"
    }

    private fun generateTokens(nonce: String): Triple<String, String, String> {
        val lo = nonce.take(32)
        val s = nonce.substring(32)
        var result = ""

        for (i in 0 until 32) {
            val index = videaSecret.indexOf(lo[i]) - 31
            result += s[i - index]
        }

        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val randomSeed = (1..8).map { chars.random() }.joinToString("")

        val key = result.substring(16) + randomSeed
        return Triple(randomSeed, result.take(16), key)
    }

    private suspend fun parseVideoSources(xml: String, callback: (ExtractorLink) -> Unit) {
        val sourceRegex = """video_source\s*name="([^"]+)".*exp="([^"]+)"[^>]*>([^<]+)""".toRegex()
        val sources = sourceRegex.findAll(xml).toList()

        for (sourceMatch in sources) {
            val sourceName = sourceMatch.groupValues[1]
            val exp = sourceMatch.groupValues[2]
            var sourceUrl = sourceMatch.groupValues[3]

            if (sourceUrl.startsWith("//")) {
                sourceUrl = "https:$sourceUrl"
            }

            val hashMatch = """<hash_value_$sourceName>([^<]+)<""".toRegex().find(xml)

            hashMatch?.let { match ->
                val hash = match.groupValues[1]
                val finalUrl = "$sourceUrl?md5=$hash&expires=$exp".replace("&amp;", "&")

                callback(
                    newExtractorLink(
                        name,
                        "$sourceName - $name",
                        finalUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = mainUrl
                    }
                )
            }
        }
    }

    private fun rc4DecryptBytes(encryptedBytes: ByteArray, key: String): String {

        val isBase64 = encryptedBytes.all { byte ->
            val char = byte.toInt() and 0xFF
            char in 32..126 || char == 10 || char == 13
        }

        val actualEncryptedBytes = if (isBase64) {
            val base64String = encryptedBytes.decodeToString()
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .trim()
            base64DecodeArray(base64String)
        } else {
            encryptedBytes
        }

        val keyBytes = key.encodeToByteArray()

        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (keyBytes[i % keyBytes.size].toInt() and 0xFF)) % 256
            s[i] = s[j].also { s[j] = s[i] }
        }

        var i = 0
        j = 0
        val result = ByteArray(actualEncryptedBytes.size)
        for (k in actualEncryptedBytes.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            s[i] = s[j].also { s[j] = s[i] }
            val keyStreamByte = s[(s[i] + s[j]) % 256]
            result[k] = ((actualEncryptedBytes[k].toInt() and 0xFF) xor keyStreamByte).toByte()
        }

        return result.decodeToString()
    }
}
