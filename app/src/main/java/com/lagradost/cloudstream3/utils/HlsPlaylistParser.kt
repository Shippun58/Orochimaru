

package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.base64DecodeArray
import io.ktor.http.Url
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlin.uuid.Uuid

@Suppress("unused")
object HlsPlaylistParser {
    private const val LOG_TAG: String = "HlsPlaylistParser"
    private const val PLAYLIST_HEADER: String = "#EXTM3U"
    private const val TAG_PREFIX: String = "#EXT"
    private const val TAG_VERSION: String = "#EXT-X-VERSION"
    private const val TAG_PLAYLIST_TYPE: String = "#EXT-X-PLAYLIST-TYPE"
    private const val TAG_DEFINE: String = "#EXT-X-DEFINE"
    private const val TAG_SERVER_CONTROL: String = "#EXT-X-SERVER-CONTROL"
    private const val TAG_STREAM_INF: String = "#EXT-X-STREAM-INF"
    private const val TAG_PART_INF: String = "#EXT-X-PART-INF"
    private const val TAG_PART: String = "#EXT-X-PART"
    private const val TAG_I_FRAME_STREAM_INF: String = "#EXT-X-I-FRAME-STREAM-INF"
    private const val TAG_IFRAME: String = "#EXT-X-I-FRAMES-ONLY"
    private const val TAG_MEDIA: String = "#EXT-X-MEDIA"
    private const val TAG_TARGET_DURATION: String = "#EXT-X-TARGETDURATION"
    private const val TAG_DISCONTINUITY: String = "#EXT-X-DISCONTINUITY"
    private const val TAG_DISCONTINUITY_SEQUENCE: String = "#EXT-X-DISCONTINUITY-SEQUENCE"
    private const val TAG_PROGRAM_DATE_TIME: String = "#EXT-X-PROGRAM-DATE-TIME"
    private const val TAG_INIT_SEGMENT: String = "#EXT-X-MAP"
    private const val TAG_INDEPENDENT_SEGMENTS: String = "#EXT-X-INDEPENDENT-SEGMENTS"
    private const val TAG_MEDIA_DURATION: String = "#EXTINF"
    private const val TAG_MEDIA_SEQUENCE: String = "#EXT-X-MEDIA-SEQUENCE"
    private const val TAG_START: String = "#EXT-X-START"
    private const val TAG_ENDLIST: String = "#EXT-X-ENDLIST"
    private const val TAG_KEY: String = "#EXT-X-KEY"
    private const val TAG_SESSION_KEY: String = "#EXT-X-SESSION-KEY"
    private const val TAG_BYTERANGE: String = "#EXT-X-BYTERANGE"
    private const val TAG_GAP: String = "#EXT-X-GAP"
    private const val TAG_SKIP: String = "#EXT-X-SKIP"
    private const val TAG_PRELOAD_HINT: String = "#EXT-X-PRELOAD-HINT"
    private const val TAG_RENDITION_REPORT: String = "#EXT-X-RENDITION-REPORT"
    private const val TAG_DATERANGE: String = "#EXT-X-DATERANGE"
    private const val TYPE_AUDIO: String = "AUDIO"
    private const val TYPE_VIDEO: String = "VIDEO"
    private const val TYPE_SUBTITLES: String = "SUBTITLES"
    private const val TYPE_CLOSED_CAPTIONS: String = "CLOSED-CAPTIONS"
    private const val TYPE_PART: String = "PART"
    private const val TYPE_MAP: String = "MAP"
    private const val METHOD_NONE: String = "NONE"
    private const val METHOD_AES_128: String = "AES-128"
    private const val METHOD_SAMPLE_AES: String = "SAMPLE-AES"

    private const val METHOD_SAMPLE_AES_CENC: String = "SAMPLE-AES-CENC"
    private const val METHOD_SAMPLE_AES_CTR: String = "SAMPLE-AES-CTR"
    private const val KEYFORMAT_PLAYREADY: String = "com.microsoft.playready"
    private const val KEYFORMAT_IDENTITY: String = "identity"
    private const val KEYFORMAT_WIDEVINE_PSSH_BINARY: String =
        "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val KEYFORMAT_WIDEVINE_PSSH_JSON: String = "com.widevine"
    private const val BOOLEAN_TRUE: String = "YES"
    private const val BOOLEAN_FALSE: String = "NO"
    private const val ATTR_CLOSED_CAPTIONS_NONE: String = "CLOSED-CAPTIONS=NONE"
    private val REGEX_AVERAGE_BANDWIDTH: Regex = Regex("AVERAGE-BANDWIDTH=(\\d+)\\b")
    private val REGEX_VIDEO: Regex = Regex("VIDEO=\"(.+?)\"")
    private val REGEX_AUDIO: Regex = Regex("AUDIO=\"(.+?)\"")
    private val REGEX_SUBTITLES: Regex = Regex("SUBTITLES=\"(.+?)\"")
    private val REGEX_CLOSED_CAPTIONS: Regex = Regex("CLOSED-CAPTIONS=\"(.+?)\"")
    private val REGEX_BANDWIDTH: Regex = Regex("[^-]BANDWIDTH=(\\d+)\\b")
    private val REGEX_CHANNELS: Regex = Regex("CHANNELS=\"(.+?)\"")
    private val REGEX_VIDEO_RANGE: Regex = Regex("VIDEO-RANGE=(SDR|PQ|HLG)")
    private val REGEX_CODECS: Regex = Regex("CODECS=\"(.+?)\"")
    private val REGEX_SUPPLEMENTAL_CODECS: Regex = Regex("SUPPLEMENTAL-CODECS=\"(.+?)\"")
    private val REGEX_RESOLUTION: Regex = Regex("RESOLUTION=(\\d+x\\d+)")
    private val REGEX_FRAME_RATE: Regex = Regex("FRAME-RATE=([\\d\\.]+)\\b")
    private val REGEX_TARGET_DURATION: Regex = Regex("$TAG_TARGET_DURATION:(\\d+)\\b")
    private val REGEX_ATTR_DURATION: Regex = Regex("DURATION=([\\d\\.]+)\\b")
    private val REGEX_ATTR_DURATION_PREFIXED: Regex = Regex("[:,]DURATION=([\\d\\.]+)\\b")
    private val REGEX_PART_TARGET_DURATION: Regex = Regex("PART-TARGET=([\\d\\.]+)\\b")
    private val REGEX_VERSION: Regex = Regex("$TAG_VERSION:(\\d+)\\b")
    private val REGEX_PLAYLIST_TYPE: Regex = Regex("$TAG_PLAYLIST_TYPE:(.+)\\b")
    private val REGEX_CAN_SKIP_UNTIL: Regex = Regex("CAN-SKIP-UNTIL=([\\d\\.]+)\\b")
    private val REGEX_CAN_SKIP_DATE_RANGES: Regex = compileBooleanAttrPattern("CAN-SKIP-DATERANGES")
    private val REGEX_SKIPPED_SEGMENTS: Regex = Regex("SKIPPED-SEGMENTS=(\\d+)\\b")
    private val REGEX_HOLD_BACK: Regex = Regex("[:|,]HOLD-BACK=([\\d\\.]+)\\b")
    private val REGEX_PART_HOLD_BACK: Regex = Regex("PART-HOLD-BACK=([\\d\\.]+)\\b")
    private val REGEX_CAN_BLOCK_RELOAD: Regex = compileBooleanAttrPattern("CAN-BLOCK-RELOAD")
    private val REGEX_MEDIA_SEQUENCE: Regex = Regex("$TAG_MEDIA_SEQUENCE:(\\d+)\\b")
    private val REGEX_MEDIA_DURATION: Regex = Regex("$TAG_MEDIA_DURATION:([\\d\\.]+)\\b")
    private val REGEX_MEDIA_TITLE: Regex = Regex("$TAG_MEDIA_DURATION:[\\d\\.]+\\b,(.+)")
    private val REGEX_LAST_MSN: Regex = Regex("LAST-MSN" + "=(\\d+)\\b")
    private val REGEX_LAST_PART: Regex = Regex("LAST-PART" + "=(\\d+)\\b")
    private val REGEX_TIME_OFFSET: Regex = Regex("TIME-OFFSET=(-?[\\d\\.]+)\\b")
    private val REGEX_BYTERANGE: Regex = Regex("$TAG_BYTERANGE:(\\d+(?:@\\d+)?)\\b")
    private val REGEX_ATTR_BYTERANGE: Regex = Regex("BYTERANGE=\"(\\d+(?:@\\d+)?)\\b\"")
    private val REGEX_BYTERANGE_START: Regex = Regex("BYTERANGE-START=(\\d+)\\b")
    private val REGEX_BYTERANGE_LENGTH: Regex = Regex("BYTERANGE-LENGTH=(\\d+)\\b")
    private val REGEX_METHOD: Regex = Regex(
        ("METHOD=("
                + METHOD_NONE
                + "|"
                + METHOD_AES_128
                + "|"
                + METHOD_SAMPLE_AES
                + "|"
                + METHOD_SAMPLE_AES_CENC
                + "|"
                + METHOD_SAMPLE_AES_CTR
                + ")"
                + "\\s*(?:,|$)")
    )
    private val REGEX_KEYFORMAT: Regex = Regex("KEYFORMAT=\"(.+?)\"")
    private val REGEX_KEYFORMATVERSIONS: Regex = Regex("KEYFORMATVERSIONS=\"(.+?)\"")
    private val REGEX_URI: Regex = Regex("URI=\"(.+?)\"")
    private val REGEX_IV: Regex = Regex("IV=([^,.*]+)")
    private val REGEX_TYPE: Regex = Regex(
        ("TYPE=("
                + TYPE_AUDIO
                + "|"
                + TYPE_VIDEO
                + "|"
                + TYPE_SUBTITLES
                + "|"
                + TYPE_CLOSED_CAPTIONS
                + ")")
    )
    private val REGEX_PRELOAD_HINT_TYPE: Regex = Regex("TYPE=($TYPE_PART|$TYPE_MAP)")
    private val REGEX_LANGUAGE: Regex = Regex("LANGUAGE=\"(.+?)\"")
    private val REGEX_NAME: Regex = Regex("NAME=\"(.+?)\"")
    private val REGEX_GROUP_ID: Regex = Regex("GROUP-ID=\"(.+?)\"")
    private val REGEX_CHARACTERISTICS: Regex = Regex("CHARACTERISTICS=\"(.+?)\"")
    private val REGEX_INSTREAM_ID: Regex = Regex("INSTREAM-ID=\"((?:CC|SERVICE)\\d+)\"")
    private val REGEX_AUTOSELECT: Regex = compileBooleanAttrPattern("AUTOSELECT")
    private val REGEX_DEFAULT: Regex = compileBooleanAttrPattern("DEFAULT")
    private val REGEX_FORCED: Regex = compileBooleanAttrPattern("FORCED")
    private val REGEX_INDEPENDENT: Regex = compileBooleanAttrPattern("INDEPENDENT")
    private val REGEX_GAP: Regex = compileBooleanAttrPattern("GAP")
    private val REGEX_PRECISE: Regex = compileBooleanAttrPattern("PRECISE")
    private val REGEX_VALUE: Regex = Regex("VALUE=\"(.+?)\"")
    private val REGEX_IMPORT: Regex = Regex("IMPORT=\"(.+?)\"")
    private val REGEX_ID: Regex = Regex("[:,]ID=\"(.+?)\"")
    private val REGEX_CLASS: Regex = Regex("CLASS=\"(.+?)\"")
    private val REGEX_START_DATE: Regex = Regex("START-DATE=\"(.+?)\"")
    private val REGEX_CUE: Regex = Regex("CUE=\"(.+?)\"")
    private val REGEX_END_DATE: Regex = Regex("END-DATE=\"(.+?)\"")
    private val REGEX_PLANNED_DURATION: Regex = Regex("PLANNED-DURATION=([\\d.]+)\\b")
    private val REGEX_END_ON_NEXT: Regex = compileBooleanAttrPattern("END-ON-NEXT")
    private val REGEX_ASSET_URI: Regex = Regex("X-ASSET-URI=\"(.+?)\"")
    private val REGEX_ASSET_LIST_URI: Regex = Regex("X-ASSET-LIST=\"(.+?)\"")
    private val REGEX_RESUME_OFFSET: Regex = Regex("X-RESUME-OFFSET=(-?[\\d.]+)\\b")
    private val REGEX_PLAYOUT_LIMIT: Regex = Regex("X-PLAYOUT-LIMIT=([\\d.]+)\\b")
    private val REGEX_SNAP: Regex = Regex("X-SNAP=\"(.+?)\"")
    private val REGEX_RESTRICT: Regex = Regex("X-RESTRICT=\"(.+?)\"")
    private val REGEX_VARIABLE_REFERENCE: Regex = Regex("\\{\\$([a-zA-Z0-9\\-_]+)\\}")
    private val REGEX_CLIENT_DEFINED_ATTRIBUTE_PREFIX: Regex = Regex("\\b(X-[A-Z0-9-]+)=")

    private fun compileBooleanAttrPattern(attribute: String): Regex {
        return Regex("$attribute=($BOOLEAN_FALSE|$BOOLEAN_TRUE)")
    }

    @Throws(ParserException::class)
    private fun parseIntAttr(line: String, pattern: Regex): Int {
        return parseStringAttr(line, pattern, emptyMap()).toInt()
    }

    private fun parseOptionalIntAttr(line: String, pattern: Regex, defaultValue: Int): Int =
        pattern.find(line)?.groupValues?.get(1)?.toInt() ?: defaultValue

    data class SchemeData(

        val uuid: Uuid,

        val licenseServerUrl: String? = null,

        val mimeType: String,

        val data: ByteArray
    )

    object Util {

        fun splitAtFirst(value: String, regex: String): Array<String> {
            return value.split(regex.toRegex(), limit = 2).toTypedArray()
        }

        fun split(value: String, regex: String): Array<String> {
            return value.split(regex.toRegex()).toTypedArray()
        }

        fun splitCodecs(codecs: String?): Array<String> {
            if (codecs.isNullOrEmpty()) {
                return arrayOf()
            }
            return split(codecs.trim(), "(\\s*,\\s*)")
        }

        fun getCodecsOfType(
            codecs: String?,

            trackType: Int
        ): String? {
            val codecArray: Array<String> = splitCodecs(codecs)
            if (codecArray.isEmpty()) {
                return null
            }
            val builder = StringBuilder()
            for (codec in codecArray) {
                if (trackType == MimeTypes.getTrackTypeOfCodec(codec)) {
                    if (builder.isNotEmpty()) {
                        builder.append(",")
                    }
                    builder.append(codec)
                }
            }
            return if (builder.isNotEmpty()) builder.toString() else null
        }

        fun getCodecsWithoutType(
            codecs: String?,

            trackType: Int
        ): String? {
            val codecArray = splitCodecs(codecs)
            if (codecArray.isEmpty()) {
                return null
            }
            val builder = StringBuilder()
            for (codec in codecArray) {
                if (trackType != MimeTypes.getTrackTypeOfCodec(codec)) {
                    if (builder.isNotEmpty()) {
                        builder.append(",")
                    }
                    builder.append(codec)
                }
            }
            return if (builder.isNotEmpty()) builder.toString() else null
        }
    }

    object UrlUtil {
        fun resolveToUrl(baseUrl: String?, referenceUrl: String?): Url {
            return Url(resolve(baseUrl, referenceUrl))
        }

        private
        const val INDEX_COUNT: Int = 4

        private
        const val SCHEME_COLON: Int = 0

        private
        const val PATH: Int = 1

        private
        const val QUERY: Int = 2

        private
        const val FRAGMENT: Int = 3

        private fun resolve(baseUrl: String?, referenceUrl: String?): String {
            var baseUrl = baseUrl
            var referenceUrl = referenceUrl
            val url = StringBuilder()

            baseUrl = baseUrl ?: ""
            referenceUrl = referenceUrl ?: ""

            val refIndices: IntArray = getUrlIndices(referenceUrl)
            if (refIndices[SCHEME_COLON] != -1) {

                url.append(referenceUrl)
                removeDotSegments(url, refIndices[PATH], refIndices[QUERY])
                return url.toString()
            }

            val baseIndices: IntArray = getUrlIndices(baseUrl)
            if (refIndices[FRAGMENT] == 0) {

                return url.append(baseUrl, 0, baseIndices[FRAGMENT]).append(referenceUrl).toString()
            }

            if (refIndices[QUERY] == 0) {

                return url.append(baseUrl, 0, baseIndices[QUERY]).append(referenceUrl).toString()
            }

            if (refIndices[PATH] != 0) {

                val baseLimit = baseIndices[SCHEME_COLON] + 1
                url.append(baseUrl, 0, baseLimit).append(referenceUrl)
                return removeDotSegments(
                    url,
                    baseLimit + refIndices[PATH],
                    baseLimit + refIndices[QUERY]
                )
            }

            if (referenceUrl[refIndices[PATH]] == '/') {

                url.append(baseUrl, 0, baseIndices[PATH]).append(referenceUrl)
                return removeDotSegments(
                    url,
                    baseIndices[PATH],
                    baseIndices[PATH] + refIndices[QUERY]
                )
            }

            if (baseIndices[SCHEME_COLON] + 2 < baseIndices[PATH]
                && baseIndices[PATH] == baseIndices[QUERY]
            ) {

                url.append(baseUrl, 0, baseIndices[PATH]).append('/').append(referenceUrl)
                return removeDotSegments(
                    url,
                    baseIndices[PATH],
                    baseIndices[PATH] + refIndices[QUERY] + 1
                )
            } else {

                val lastSlashIndex = baseUrl.lastIndexOf('/', baseIndices[QUERY] - 1)
                val baseLimit = if (lastSlashIndex == -1) baseIndices[PATH] else lastSlashIndex + 1
                url.append(baseUrl, 0, baseLimit).append(referenceUrl)
                return removeDotSegments(url, baseIndices[PATH], baseLimit + refIndices[QUERY])
            }
        }

        private fun removeDotSegments(
            url: StringBuilder,
            offset: Int,
            limit: Int
        ): String {
            var offset = offset
            var limit = limit
            if (offset >= limit) {

                return url.toString()
            }
            if (url[offset] == '/') {

                offset++
            }

            var segmentStart = offset
            var i = offset
            while (i <= limit) {
                val nextSegmentStart = if (i == limit) {
                    i
                } else if (url[i] == '/') {
                    i + 1
                } else {
                    i++
                    continue
                }

                if (i == segmentStart + 1 && url[segmentStart] == '.') {

                    url.deleteRange(segmentStart, nextSegmentStart)
                    limit -= nextSegmentStart - segmentStart
                    i = segmentStart
                } else if (i == segmentStart + 2 && url[segmentStart] == '.' && url[segmentStart + 1] == '.') {

                    val prevSegmentStart = url.lastIndexOf("/", segmentStart - 2) + 1
                    val removeFrom = if (prevSegmentStart > offset) prevSegmentStart else offset
                    url.deleteRange(removeFrom, nextSegmentStart)
                    limit -= nextSegmentStart - removeFrom
                    segmentStart = prevSegmentStart
                    i = prevSegmentStart
                } else {
                    i++
                    segmentStart = i
                }
            }
            return url.toString()
        }

        private fun getUrlIndices(urlString: String?): IntArray {
            val indices = IntArray(INDEX_COUNT)
            if (urlString.isNullOrEmpty()) {
                indices[SCHEME_COLON] = -1
                return indices
            }

            val length = urlString.length
            var fragmentIndex = urlString.indexOf('#')
            if (fragmentIndex == -1) {
                fragmentIndex = length
            }
            var queryIndex = urlString.indexOf('?')
            if (queryIndex == -1 || queryIndex > fragmentIndex) {

                queryIndex = fragmentIndex
            }

            var schemeIndexLimit = urlString.indexOf('/')
            if (schemeIndexLimit == -1 || schemeIndexLimit > queryIndex) {
                schemeIndexLimit = queryIndex
            }
            var schemeIndex = urlString.indexOf(':')
            if (schemeIndex > schemeIndexLimit) {

                schemeIndex = -1
            }

            val hasAuthority =
                schemeIndex + 2 < queryIndex && urlString[schemeIndex + 1] == '/' && urlString[schemeIndex + 2] == '/'
            var pathIndex: Int
            if (hasAuthority) {
                pathIndex = urlString.indexOf('/', schemeIndex + 3)
                if (pathIndex == -1 || pathIndex > queryIndex) {
                    pathIndex = queryIndex
                }
            } else {
                pathIndex = schemeIndex + 1
            }

            indices[SCHEME_COLON] = schemeIndex
            indices[PATH] = pathIndex
            indices[QUERY] = queryIndex
            indices[FRAGMENT] = fragmentIndex
            return indices
        }
    }

    private object C {

        val CLEARKEY_UUID = Uuid.fromLongs(-0x1d8e62a7567a4c37L, 0x781AB030AF78D30EL)

        val WIDEVINE_UUID = Uuid.fromLongs(-0x121074568629b532L, -0x5c37d8232ae2de13L)

        val PLAYREADY_UUID = Uuid.fromLongs(-0x65fb0f8667bfbd7aL, -0x546d19a41f77a06bL)

        const val CENC_TYPE_cenc: String = "cenc"

        const val CENC_TYPE_cbc1: String = "cbc1"

        const val CENC_TYPE_cens: String = "cens"

        const val CENC_TYPE_cbcs: String = "cbcs"

        const val ROLE_FLAG_MAIN: Int = 1

        const val ROLE_FLAG_ALTERNATE: Int = 1 shl 1

        const val ROLE_FLAG_SUPPLEMENTARY: Int = 1 shl 2

        const val ROLE_FLAG_COMMENTARY: Int = 1 shl 3

        const val ROLE_FLAG_DUB: Int = 1 shl 4

        const val ROLE_FLAG_EMERGENCY: Int = 1 shl 5

        const val ROLE_FLAG_CAPTION: Int = 1 shl 6

        const val ROLE_FLAG_SUBTITLE: Int = 1 shl 7

        const val ROLE_FLAG_SIGN: Int = 1 shl 8

        const val ROLE_FLAG_DESCRIBES_VIDEO: Int = 1 shl 9

        const val ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND: Int = 1 shl 10

        const val ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY: Int = 1 shl 11

        const val ROLE_FLAG_TRANSCRIBES_DIALOG: Int = 1 shl 12

        const val ROLE_FLAG_EASY_TO_READ: Int = 1 shl 13

        const val ROLE_FLAG_TRICK_PLAY: Int = 1 shl 14

        const val TRACK_TYPE_NONE: Int = -2

        const val TRACK_TYPE_UNKNOWN: Int = -1

        const val TRACK_TYPE_DEFAULT: Int = 0

        const val TRACK_TYPE_AUDIO: Int = 1

        const val TRACK_TYPE_VIDEO: Int = 2

        const val TRACK_TYPE_TEXT: Int = 3

        const val TRACK_TYPE_IMAGE: Int = 4

        const val TRACK_TYPE_METADATA: Int = 5

        const val TRACK_TYPE_CAMERA_MOTION: Int = 6

        const val SELECTION_FLAG_DEFAULT: Int = 1

        const val SELECTION_FLAG_FORCED: Int = 1 shl 1

        const val SELECTION_FLAG_AUTOSELECT: Int = 1 shl 2
    }

    object MimeTypes {
        const val BASE_TYPE_VIDEO: String = "video"
        const val BASE_TYPE_AUDIO: String = "audio"
        const val BASE_TYPE_TEXT: String = "text"
        const val BASE_TYPE_IMAGE: String = "image"
        const val BASE_TYPE_APPLICATION: String = "application"

        const val VIDEO_MP4: String = "$BASE_TYPE_VIDEO/mp4"
        const val VIDEO_MATROSKA: String = "$BASE_TYPE_VIDEO/x-matroska"
        const val VIDEO_WEBM: String = "$BASE_TYPE_VIDEO/webm"
        const val VIDEO_H263: String = "$BASE_TYPE_VIDEO/3gpp"
        const val VIDEO_H264: String = "$BASE_TYPE_VIDEO/avc"
        const val VIDEO_APV: String = "$BASE_TYPE_VIDEO/apv"
        const val VIDEO_H265: String = "$BASE_TYPE_VIDEO/hevc"
        const val VIDEO_VP8: String = "$BASE_TYPE_VIDEO/x-vnd.on2.vp8"
        const val VIDEO_VP9: String = "$BASE_TYPE_VIDEO/x-vnd.on2.vp9"
        const val VIDEO_AV1: String = "$BASE_TYPE_VIDEO/av01"
        const val VIDEO_MP2T: String = "$BASE_TYPE_VIDEO/mp2t"
        const val VIDEO_MP4V: String = "$BASE_TYPE_VIDEO/mp4v-es"
        const val VIDEO_MPEG: String = "$BASE_TYPE_VIDEO/mpeg"
        const val VIDEO_PS: String = "$BASE_TYPE_VIDEO/mp2p"
        const val VIDEO_MPEG2: String = "$BASE_TYPE_VIDEO/mpeg2"
        const val VIDEO_VC1: String = "$BASE_TYPE_VIDEO/wvc1"
        const val VIDEO_DIVX: String = "$BASE_TYPE_VIDEO/divx"
        const val VIDEO_FLV: String = "$BASE_TYPE_VIDEO/x-flv"
        const val VIDEO_DOLBY_VISION: String = "$BASE_TYPE_VIDEO/dolby-vision"
        const val VIDEO_OGG: String = "$BASE_TYPE_VIDEO/ogg"
        const val VIDEO_AVI: String = "$BASE_TYPE_VIDEO/x-msvideo"
        const val VIDEO_MJPEG: String = "$BASE_TYPE_VIDEO/mjpeg"
        const val VIDEO_MP42: String = "$BASE_TYPE_VIDEO/mp42"
        const val VIDEO_MP43: String = "$BASE_TYPE_VIDEO/mp43"
        const val VIDEO_MV_HEVC: String = "$BASE_TYPE_VIDEO/mv-hevc"
        const val VIDEO_RAW: String = "$BASE_TYPE_VIDEO/raw"
        const val VIDEO_UNKNOWN: String = "$BASE_TYPE_VIDEO/x-unknown"

        const val AUDIO_MP4: String = "$BASE_TYPE_AUDIO/mp4"
        const val AUDIO_AAC: String = "$BASE_TYPE_AUDIO/mp4a-latm"
        const val AUDIO_MATROSKA: String = "$BASE_TYPE_AUDIO/x-matroska"
        const val AUDIO_WEBM: String = "$BASE_TYPE_AUDIO/webm"
        const val AUDIO_MPEG: String = "$BASE_TYPE_AUDIO/mpeg"
        const val AUDIO_MPEG_L1: String = "$BASE_TYPE_AUDIO/mpeg-L1"
        const val AUDIO_MPEG_L2: String = "$BASE_TYPE_AUDIO/mpeg-L2"
        const val AUDIO_MPEGH_MHA1: String = "$BASE_TYPE_AUDIO/mha1"
        const val AUDIO_MPEGH_MHM1: String = "$BASE_TYPE_AUDIO/mhm1"
        const val AUDIO_RAW: String = "$BASE_TYPE_AUDIO/raw"
        const val AUDIO_ALAW: String = "$BASE_TYPE_AUDIO/g711-alaw"
        const val AUDIO_MLAW: String = "$BASE_TYPE_AUDIO/g711-mlaw"
        const val AUDIO_AC3: String = "$BASE_TYPE_AUDIO/ac3"
        const val AUDIO_E_AC3: String = "$BASE_TYPE_AUDIO/eac3"
        const val AUDIO_E_AC3_JOC: String = "$BASE_TYPE_AUDIO/eac3-joc"
        const val AUDIO_AC4: String = "$BASE_TYPE_AUDIO/ac4"
        const val AUDIO_TRUEHD: String = "$BASE_TYPE_AUDIO/true-hd"
        const val AUDIO_DTS: String = "$BASE_TYPE_AUDIO/vnd.dts"
        const val AUDIO_DTS_HD: String = "$BASE_TYPE_AUDIO/vnd.dts.hd"
        const val AUDIO_DTS_EXPRESS: String = "$BASE_TYPE_AUDIO/vnd.dts.hd;profile=lbr"
        const val AUDIO_DTS_X: String = "$BASE_TYPE_AUDIO/vnd.dts.uhd;profile=p2"
        const val AUDIO_VORBIS: String = "$BASE_TYPE_AUDIO/vorbis"
        const val AUDIO_OPUS: String = "$BASE_TYPE_AUDIO/opus"
        const val AUDIO_AMR: String = "$BASE_TYPE_AUDIO/amr"
        const val AUDIO_AMR_NB: String = "$BASE_TYPE_AUDIO/3gpp"
        const val AUDIO_AMR_WB: String = "$BASE_TYPE_AUDIO/amr-wb"
        const val AUDIO_FLAC: String = "$BASE_TYPE_AUDIO/flac"
        const val AUDIO_ALAC: String = "$BASE_TYPE_AUDIO/alac"
        const val AUDIO_MSGSM: String = "$BASE_TYPE_AUDIO/gsm"
        const val AUDIO_OGG: String = "$BASE_TYPE_AUDIO/ogg"
        const val AUDIO_WAV: String = "$BASE_TYPE_AUDIO/wav"
        const val AUDIO_MIDI: String = "$BASE_TYPE_AUDIO/midi"
        const val AUDIO_IAMF: String = "$BASE_TYPE_AUDIO/iamf"

        const val AUDIO_EXOPLAYER_MIDI: String = "$BASE_TYPE_AUDIO/x-exoplayer-midi"

        const val AUDIO_UNKNOWN: String = "$BASE_TYPE_AUDIO/x-unknown"

        const val TEXT_VTT: String = "$BASE_TYPE_TEXT/vtt"
        const val TEXT_SSA: String = "$BASE_TYPE_TEXT/x-ssa"
        const val TEXT_UNKNOWN: String = "$BASE_TYPE_TEXT/x-unknown"

        const val APPLICATION_MP4: String = "$BASE_TYPE_APPLICATION/mp4"
        const val APPLICATION_WEBM: String = "$BASE_TYPE_APPLICATION/webm"

        const val APPLICATION_MATROSKA: String = "$BASE_TYPE_APPLICATION/x-matroska"

        const val APPLICATION_MPD: String = "$BASE_TYPE_APPLICATION/dash+xml"
        const val APPLICATION_M3U8: String = "$BASE_TYPE_APPLICATION/x-mpegURL"
        const val APPLICATION_SS: String = "$BASE_TYPE_APPLICATION/vnd.ms-sstr+xml"
        const val APPLICATION_ID3: String = "$BASE_TYPE_APPLICATION/id3"
        const val APPLICATION_CEA608: String = "$BASE_TYPE_APPLICATION/cea-608"
        const val APPLICATION_CEA708: String = "$BASE_TYPE_APPLICATION/cea-708"
        const val APPLICATION_SUBRIP: String = "$BASE_TYPE_APPLICATION/x-subrip"
        const val APPLICATION_TTML: String = "$BASE_TYPE_APPLICATION/ttml+xml"
        const val APPLICATION_TX3G: String = "$BASE_TYPE_APPLICATION/x-quicktime-tx3g"
        const val APPLICATION_MP4VTT: String = "$BASE_TYPE_APPLICATION/x-mp4-vtt"
        const val APPLICATION_MP4CEA608: String = "$BASE_TYPE_APPLICATION/x-mp4-cea-608"

        const val APPLICATION_VOBSUB: String = "$BASE_TYPE_APPLICATION/vobsub"
        const val APPLICATION_PGS: String = "$BASE_TYPE_APPLICATION/pgs"
        const val APPLICATION_SCTE35: String = "$BASE_TYPE_APPLICATION/x-scte35"
        const val APPLICATION_SDP: String = "$BASE_TYPE_APPLICATION/sdp"

        const val APPLICATION_CAMERA_MOTION: String = "$BASE_TYPE_APPLICATION/x-camera-motion"

        const val APPLICATION_DEPTH_METADATA: String = "$BASE_TYPE_APPLICATION/x-depth-metadata"

        const val APPLICATION_EMSG: String = "$BASE_TYPE_APPLICATION/x-emsg"
        const val APPLICATION_DVBSUBS: String = "$BASE_TYPE_APPLICATION/dvbsubs"
        const val APPLICATION_EXIF: String = "$BASE_TYPE_APPLICATION/x-exif"
        const val APPLICATION_ICY: String = "$BASE_TYPE_APPLICATION/x-icy"
        const val APPLICATION_AIT: String = "$BASE_TYPE_APPLICATION/vnd.dvb.ait"
        const val APPLICATION_RTSP: String = "$BASE_TYPE_APPLICATION/x-rtsp"

        const val APPLICATION_MEDIA3_CUES: String = "$BASE_TYPE_APPLICATION/x-media3-cues"

        const val APPLICATION_EXTERNALLY_LOADED_IMAGE: String = "$BASE_TYPE_APPLICATION/x-image-uri"

        const val IMAGE_JPEG: String = "$BASE_TYPE_IMAGE/jpeg"
        const val IMAGE_JPEG_R: String = "$BASE_TYPE_IMAGE/jpeg_r"
        const val IMAGE_PNG: String = "$BASE_TYPE_IMAGE/png"
        const val IMAGE_HEIF: String = "$BASE_TYPE_IMAGE/heif"
        const val IMAGE_HEIC: String = "$BASE_TYPE_IMAGE/heic"
        const val IMAGE_AVIF: String = "$BASE_TYPE_IMAGE/avif"
        const val IMAGE_BMP: String = "$BASE_TYPE_IMAGE/bmp"
        const val IMAGE_WEBP: String = "$BASE_TYPE_IMAGE/webp"
        const val IMAGE_RAW: String = "$BASE_TYPE_IMAGE/raw"

        const val CODEC_E_AC3_JOC: String = "ec+3"

        data class Mp4aObjectType(val objectTypeIndication: Int, val audioObjectTypeIndication: Int)

        private
        val MP4A_RFC_6381_CODEC_PATTERN: Regex =
            Regex("^mp4a\\.([a-zA-Z0-9]{2})(?:\\.([0-9]{1,2}))?$")

        fun isDolbyVisionCodec(
            codecs: String?, supplementalCodecs: String?
        ): Boolean {
            if (codecs == null) {
                return false
            }
            if (codecs.startsWith("dvhe") || codecs.startsWith("dvh1")) {

                return true
            }
            if (supplementalCodecs == null) {
                return false
            }

            return (supplementalCodecs.startsWith("dvhe") && codecs.startsWith("hev1"))
                    || (supplementalCodecs.startsWith("dvh1") && codecs.startsWith("hvc1"))
                    || (supplementalCodecs.startsWith("dvav") && codecs.startsWith("avc3"))
                    || (supplementalCodecs.startsWith("dva1") && codecs.startsWith("avc1"))
                    || (supplementalCodecs.startsWith("dav1") && codecs.startsWith("av01"))
        }

        fun getObjectTypeFromMp4aRFC6381CodecString(codec: String?): Mp4aObjectType? {
            val groups = MP4A_RFC_6381_CODEC_PATTERN.find(codec ?: return null)?.groupValues
            if (groups.isNullOrEmpty()) {
                return null
            }
            val objectTypeIndicationHex: String = groups[1]
            val audioObjectTypeIndicationDec: String? = groups.getOrNull(2)
            val objectTypeIndication: Int
            var audioObjectTypeIndication = 0
            try {
                objectTypeIndication = objectTypeIndicationHex.toInt(16)
                if (audioObjectTypeIndicationDec != null) {
                    audioObjectTypeIndication = audioObjectTypeIndicationDec.toInt()
                }
            } catch (e: NumberFormatException) {
                return null
            }
            return Mp4aObjectType(objectTypeIndication, audioObjectTypeIndication)
        }

        fun getMimeTypeFromMp4ObjectType(objectType: Int): String? {
            return when (objectType) {
                0x20 -> VIDEO_MP4V
                0x21 -> VIDEO_H264
                0x23 -> VIDEO_H265
                0x60, 0x61, 0x62, 0x63, 0x64, 0x65 -> VIDEO_MPEG2
                0x6A -> VIDEO_MPEG
                0x69, 0x6B -> AUDIO_MPEG
                0x6C -> MimeTypes.IMAGE_JPEG
                0xA3 -> VIDEO_VC1
                0xB1 -> VIDEO_VP9
                0x40, 0x66, 0x67, 0x68 -> AUDIO_AAC
                0xA5 -> AUDIO_AC3
                0xA6 -> AUDIO_E_AC3
                0xA9, 0xAC -> AUDIO_DTS
                0xAA, 0xAB -> AUDIO_DTS_HD
                0xAD -> AUDIO_OPUS
                0xAE -> AUDIO_AC4
                0xDD -> AUDIO_VORBIS
                else -> null
            }
        }

        fun getMediaMimeType(codecOrNull: String?): String? {
            var codec = codecOrNull ?: return null
            codec = codec.trim().lowercase()
            if (codec.startsWith("avc1") || codec.startsWith("avc3")) {
                return MimeTypes.VIDEO_H264
            } else if (codec.startsWith("hev1") || codec.startsWith("hvc1")) {
                return MimeTypes.VIDEO_H265
            } else if (codec.startsWith("dvav")
                || codec.startsWith("dva1")
                || codec.startsWith("dvhe")
                || codec.startsWith("dvh1")
            ) {
                return MimeTypes.VIDEO_DOLBY_VISION
            } else if (codec.startsWith("av01")) {
                return MimeTypes.VIDEO_AV1
            } else if (codec.startsWith("vp9") || codec.startsWith("vp09")) {
                return MimeTypes.VIDEO_VP9
            } else if (codec.startsWith("vp8") || codec.startsWith("vp08")) {
                return MimeTypes.VIDEO_VP8
            } else if (codec.startsWith("mp4a")) {
                var mimeType: String? = null
                if (codec.startsWith("mp4a.")) {
                    val objectType: Mp4aObjectType? = getObjectTypeFromMp4aRFC6381CodecString(codec)
                    if (objectType != null) {
                        mimeType = getMimeTypeFromMp4ObjectType(objectType.objectTypeIndication)
                    }
                }
                return mimeType ?: MimeTypes.AUDIO_AAC
            } else if (codec.startsWith("mha1")) {
                return MimeTypes.AUDIO_MPEGH_MHA1
            } else if (codec.startsWith("mhm1")) {
                return MimeTypes.AUDIO_MPEGH_MHM1
            } else if (codec.startsWith("ac-3") || codec.startsWith("dac3")) {
                return MimeTypes.AUDIO_AC3
            } else if (codec.startsWith("ec-3") || codec.startsWith("dec3")) {
                return AUDIO_E_AC3
            } else if (codec.startsWith(CODEC_E_AC3_JOC)) {
                return AUDIO_E_AC3_JOC
            } else if (codec.startsWith("ac-4") || codec.startsWith("dac4")) {
                return MimeTypes.AUDIO_AC4
            } else if (codec.startsWith("dtsc")) {
                return MimeTypes.AUDIO_DTS
            } else if (codec.startsWith("dtse")) {
                return MimeTypes.AUDIO_DTS_EXPRESS
            } else if (codec.startsWith("dtsh") || codec.startsWith("dtsl")) {
                return MimeTypes.AUDIO_DTS_HD
            } else if (codec.startsWith("dtsx")) {
                return MimeTypes.AUDIO_DTS_X
            } else if (codec.startsWith("opus")) {
                return MimeTypes.AUDIO_OPUS
            } else if (codec.startsWith("vorbis")) {
                return MimeTypes.AUDIO_VORBIS
            } else if (codec.startsWith("flac")) {
                return MimeTypes.AUDIO_FLAC
            } else if (codec.startsWith("stpp")) {
                return MimeTypes.APPLICATION_TTML
            } else if (codec.startsWith("wvtt")) {
                return TEXT_VTT
            } else if (codec.contains("cea708")) {
                return APPLICATION_CEA708
            } else if (codec.contains("eia608") || codec.contains("cea608")) {
                return APPLICATION_CEA608
            } else {
                return null
            }
        }

        private fun getTopLevelType(mimeType: String?): String? {
            if (mimeType == null) {
                return null
            }
            val indexOfSlash = mimeType.indexOf('/')
            if (indexOfSlash == -1) {
                return null
            }
            return mimeType.substring(0, indexOfSlash)
        }

        fun isAudio(mimeType: String?): Boolean = BASE_TYPE_AUDIO == getTopLevelType(mimeType)
        fun isVideo(mimeType: String?): Boolean = BASE_TYPE_VIDEO == getTopLevelType(mimeType)
        fun isText(mimeType: String?): Boolean = BASE_TYPE_TEXT == getTopLevelType(mimeType)
                || APPLICATION_MEDIA3_CUES == mimeType
                || APPLICATION_CEA608 == mimeType
                || APPLICATION_CEA708 == mimeType
                || APPLICATION_MP4CEA608 == mimeType
                || APPLICATION_SUBRIP == mimeType
                || APPLICATION_TTML == mimeType
                || APPLICATION_TX3G == mimeType
                || APPLICATION_MP4VTT == mimeType
                || APPLICATION_VOBSUB == mimeType
                || APPLICATION_PGS == mimeType
                || APPLICATION_DVBSUBS == mimeType;

        fun isImage(mimeType: String?): Boolean = BASE_TYPE_IMAGE == getTopLevelType(mimeType)
                || APPLICATION_EXTERNALLY_LOADED_IMAGE == mimeType;

        fun getTrackType(mimeType: String?): Int {
            return if (mimeType.isNullOrEmpty()) {
                C.TRACK_TYPE_UNKNOWN
            } else if (isAudio(mimeType)) {
                C.TRACK_TYPE_AUDIO
            } else if (isVideo(mimeType)) {
                C.TRACK_TYPE_VIDEO
            } else if (isText(mimeType)) {
                C.TRACK_TYPE_TEXT
            } else if (isImage(mimeType)) {
                C.TRACK_TYPE_IMAGE
            } else if (APPLICATION_ID3 == mimeType
                || APPLICATION_EMSG == mimeType
                || APPLICATION_SCTE35 == mimeType
                || APPLICATION_ICY == mimeType
                || APPLICATION_AIT == mimeType
            ) {
                C.TRACK_TYPE_METADATA
            } else if (APPLICATION_CAMERA_MOTION == mimeType) {
                C.TRACK_TYPE_CAMERA_MOTION
            } else {
                C.TRACK_TYPE_UNKNOWN

            }
        }

        fun getTrackTypeOfCodec(codec: String?): Int {
            return getTrackType(getMediaMimeType(codec))
        }
    }

    object Mp4Box {

        const val FULL_HEADER_SIZE: Int = 12
        const val TYPE_pssh: Int = 0x70737368
    }

    object PsshAtomUtil {
        fun buildPsshAtom(
            systemId: Uuid, keyIds: Array<Uuid>?, data: ByteArray?
        ): ByteArray {
            val dataLength = data?.size ?: 0
            var psshBoxLength: Int =
                Mp4Box.FULL_HEADER_SIZE + 16  + 4  + dataLength
            if (keyIds != null) {
                psshBoxLength += 4  + (keyIds.size * 16)
            }
            val buffer = Buffer()
            buffer.writeInt(psshBoxLength)
            buffer.writeInt(Mp4Box.TYPE_pssh)
            buffer.writeInt(if (keyIds != null) 0x01000000 else 0 )
            systemId.toLongs { mostSignificantBits, leastSignificantBits ->
                buffer.writeLong(mostSignificantBits)
                buffer.writeLong(leastSignificantBits)
            }
            if (keyIds != null) {
                buffer.writeInt(keyIds.size)
                for (keyId in keyIds) {
                    keyId.toLongs { mostSignificantBits, leastSignificantBits ->
                        buffer.writeLong(mostSignificantBits)
                        buffer.writeLong(leastSignificantBits)
                    }
                }
            }
            if (data != null && data.size != 0) {
                buffer.writeInt(data.size)
                buffer.write(data)
            } else {
                buffer.writeInt(0)
            }
            return buffer.readByteArray()
        }
    }

    data class ParserException(
        override val message: String?,
        override val cause: Throwable?,

        val contentIsMalformed: Boolean,

        val dataType: Int
    ) : IOException(message, cause) {

        companion object {
            const val DATA_TYPE_MANIFEST: Int = 4
            fun createForMalformedManifest(
                message: String?,
                cause: Throwable?
            ): ParserException {
                return ParserException(
                    message,
                    cause,
                    true,
                    DATA_TYPE_MANIFEST
                )
            }
        }
    }

    data class DrmInitData(
        val schemeType: String,
        val schemeDatas: Array<SchemeData>,
    )

    @Throws(ParserException::class)
    private fun parseStringAttr(
        line: String, pattern: Regex, variableDefinitions: Map<String, String>
    ): String = parseOptionalStringAttr(line, pattern, variableDefinitions)
        ?: throw ParserException.createForMalformedManifest(
            "Couldn't match $pattern in $line",  null
        )

    private fun parseOptionalStringAttr(
        line: String,
        pattern: Regex,

        defaultValue: String?,
        variableDefinitions: Map<String, String>?
    ): String? {
        val value: String? = pattern.find(line)?.groupValues?.get(1) ?: defaultValue
        return if (variableDefinitions.isNullOrEmpty() || value == null)
            value
        else
            replaceVariableReferences(value, variableDefinitions)
    }

    private fun replaceVariableReferences(
        string: String, variableDefinitions: Map<String, String>?
    ): String =
        string.replace(REGEX_VARIABLE_REFERENCE) { matchResult ->
            variableDefinitions?.get(matchResult.groupValues[1]) ?: matchResult.value
        }

    private fun parseOptionalStringAttr(
        line: String, pattern: Regex, variableDefinitions: Map<String, String>?
    ): String? {
        return parseOptionalStringAttr(line, pattern, null, variableDefinitions)
    }

    @Throws(ParserException::class)
    private fun parseDrmSchemeData(
        line: String, keyFormat: String, variableDefinitions: Map<String, String>
    ): SchemeData? {
        val keyFormatVersions =
            parseOptionalStringAttr(line, REGEX_KEYFORMATVERSIONS, "1", variableDefinitions)
        if (KEYFORMAT_WIDEVINE_PSSH_BINARY == keyFormat) {
            val urlString = parseStringAttr(line, REGEX_URI, variableDefinitions)
            return SchemeData(
                uuid = C.WIDEVINE_UUID,
                mimeType = MimeTypes.VIDEO_MP4,
                data = base64DecodeArray(urlString.substring(urlString.indexOf(',')))
            )
        } else if (KEYFORMAT_WIDEVINE_PSSH_JSON == keyFormat) {
            return SchemeData(
                uuid = C.WIDEVINE_UUID,
                mimeType = "hls",
                data = line.encodeToByteArray()
            )
        } else if (KEYFORMAT_PLAYREADY == keyFormat && "1" == keyFormatVersions) {
            val urlString = parseStringAttr(line, REGEX_URI, variableDefinitions)
            val data: ByteArray =
                base64DecodeArray(urlString.substring(urlString.indexOf(',')))
            val psshData: ByteArray =
                PsshAtomUtil.buildPsshAtom(
                    systemId = C.PLAYREADY_UUID,
                    keyIds = null,
                    data = data
                )
            return SchemeData(
                uuid = C.PLAYREADY_UUID,
                mimeType = MimeTypes.VIDEO_MP4,
                data = psshData
            )
        }
        return null
    }

    private fun parseEncryptionScheme(method: String): String {
        return if (METHOD_SAMPLE_AES_CENC == method || METHOD_SAMPLE_AES_CTR == method)
            C.CENC_TYPE_cenc
        else
            C.CENC_TYPE_cbcs
    }

    private fun parseSelectionFlags(line: String): Int {
        var flags = 0
        if (parseOptionalBooleanAttribute(line, REGEX_DEFAULT, false)) {
            flags = flags or C.SELECTION_FLAG_DEFAULT
        }
        if (parseOptionalBooleanAttribute(line, REGEX_FORCED, false)) {
            flags = flags or C.SELECTION_FLAG_FORCED
        }
        if (parseOptionalBooleanAttribute(line, REGEX_AUTOSELECT, false)) {
            flags = flags or C.SELECTION_FLAG_AUTOSELECT
        }
        return flags
    }

    private fun parseRoleFlags(
        line: String, variableDefinitions: Map<String, String>
    ): Int {
        val concatenatedCharacteristics =
            parseOptionalStringAttr(line, REGEX_CHARACTERISTICS, variableDefinitions)
        if (concatenatedCharacteristics.isNullOrEmpty()) {
            return 0
        }
        val characteristics = Util.split(concatenatedCharacteristics, ",")

        var roleFlags = 0
        if (characteristics.contains("public.accessibility.describes-video")) {
            roleFlags = roleFlags or C.ROLE_FLAG_DESCRIBES_VIDEO
        }
        if (characteristics.contains("public.accessibility.transcribes-spoken-dialog")) {
            roleFlags = roleFlags or C.ROLE_FLAG_TRANSCRIBES_DIALOG
        }
        if (characteristics.contains("public.accessibility.describes-music-and-sound")) {
            roleFlags = roleFlags or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
        }
        if (characteristics.contains("public.easy-to-read")) {
            roleFlags = roleFlags or C.ROLE_FLAG_EASY_TO_READ
        }
        return roleFlags
    }

    private fun parseOptionalBooleanAttribute(
        line: String, pattern: Regex, defaultValue: Boolean
    ): Boolean {
        val matcher = pattern.find(line)?.groupValues?.get(1)
        return if (matcher != null) {
            matcher == BOOLEAN_TRUE
        } else {
            defaultValue
        }
    }

    data class Variant(
        val url: Url,
        val format: Format,
        val videoGroupId: String?,
        val audioGroupId: String?,
        val subtitleGroupId: String?,
        val captionGroupId: String?,
    ) {

        fun isPlayableStandalone(playlist: HlsMultivariantPlaylist): Boolean =
            mustContainAudio(playlist) && !isTrickPlay()

        fun isTrickPlay(): Boolean = (format.roleFlags and C.ROLE_FLAG_TRICK_PLAY != 0)

        fun mustContainAudio(playlist: HlsMultivariantPlaylist): Boolean =
            audioGroupId == null || (playlist.audios.firstOrNull { it.groupId == audioGroupId }?.url == null)
    }

    data class Rendition(

        val url: Url?,

        val format: Format,

        val groupId: String,

        val name: String
    )

    data class HlsMultivariantPlaylist(

        val baseUri: String,

        val tags: List<String>,

        val variants: List<Variant>,

        val videos: List<Rendition>,

        val audios: List<Rendition>,

        val subtitles: List<Rendition>,

        val closedCaptions: List<Rendition>,

        val muxedAudioFormat: Format? = null,

        val muxedCaptionFormats: List<Format>?,

        val variableDefinitions: Map<String, String>,

        val sessionKeyDrmInitData: List<DrmInitData>,

        val hasIndependentSegments: Boolean,
    )

    data class VariantInfo(

        val averageBitrate: Int = Format.NO_VALUE,

        val peakBitrate: Int,

        val videoGroupId: String? = null,

        val audioGroupId: String? = null,

        val subtitleGroupId: String? = null,

        val captionGroupId: String? = null,
    )

    data class Format(

        val id: String? = null,

        val label: String? = null,

        val language: String? = null,

        val selectionFlags: Int = 0,

        val roleFlags: Int = 0,

        val averageBitrate: Int = NO_VALUE,

        val peakBitrate: Int = NO_VALUE,

        val bitrate: Int = NO_VALUE,

        val codecs: String? = null,

        val containerMimeType: String? = null,

        val sampleMimeType: String? = null,

        val width: Int = NO_VALUE,

        val height: Int = NO_VALUE,

        val frameRate: Float = NO_VALUE.toFloat(),

        val rotationDegrees: Int = 0,

        val pixelWidthHeightRatio: Float = 1.0f,

        val channelCount: Int = NO_VALUE,

        val accessibilityChannel: Int = NO_VALUE,

    ) {
        companion object {
            const val NO_VALUE: Int = -1
        }
    }

    private fun getVariantWithVideoGroup(variants: ArrayList<Variant>, groupId: String): Variant? =
        variants.firstOrNull { variant -> groupId == variant.videoGroupId }

    private fun getVariantWithAudioGroup(variants: ArrayList<Variant>, groupId: String): Variant? =
        variants.firstOrNull { variant -> groupId == variant.audioGroupId }

    private fun getVariantWithSubtitleGroup(
        variants: ArrayList<Variant>,
        groupId: String
    ): Variant? = variants.firstOrNull { variant -> groupId == variant.subtitleGroupId }

    private fun isDolbyVisionFormat(
        videoRange: String?,
        codecs: String?,
        supplementalCodecs: String?,
        supplementalProfiles: String?
    ): Boolean {
        if (!MimeTypes.isDolbyVisionCodec(codecs, supplementalCodecs)) {
            return false
        }
        if (supplementalCodecs == null) {

            return true
        }
        if (videoRange == null || supplementalProfiles == null) {

            return false
        }
        if ((videoRange == "PQ" && supplementalProfiles != "db1p")
            || (videoRange == "SDR" && supplementalProfiles != "db2g")
            || (videoRange == "HLG" && !supplementalProfiles.startsWith("db4"))
        ) {
            return false
        }
        return true
    }

    fun parse(baseUri: String, text: String): HlsMultivariantPlaylist? {
        val lines = text.lines()
        if (lines.any { it.startsWith(TAG_STREAM_INF) }) {
            return parseMultivariantPlaylist(lines.iterator(), baseUri)
        }
        return null
    }

    @Throws(IOException::class)
    private fun parseMultivariantPlaylist(
        iterator: Iterator<String>, baseUri: String
    ): HlsMultivariantPlaylist {
        val urlToVariantInfos: HashMap<Url, ArrayList<VariantInfo>?> =
            HashMap<Url, ArrayList<VariantInfo>?>()
        val variableDefinitions = HashMap<String, String>()
        val variants: ArrayList<Variant> = ArrayList<Variant>()
        val videos: ArrayList<Rendition> = ArrayList<Rendition>()
        val audios: ArrayList<Rendition> = ArrayList<Rendition>()
        val subtitles: ArrayList<Rendition> = ArrayList<Rendition>()
        val closedCaptions: ArrayList<Rendition> = ArrayList<Rendition>()
        val mediaTags = ArrayList<String>()
        val sessionKeyDrmInitData: ArrayList<DrmInitData> = ArrayList<DrmInitData>()
        val tags = ArrayList<String>()
        var muxedAudioFormat: Format? = null
        var muxedCaptionFormats: ArrayList<Format> = arrayListOf()
        var noClosedCaptions = false
        var hasIndependentSegmentsTag = false

        var line: String
        while (iterator.hasNext()) {
            line = iterator.next()

            if (line.startsWith(TAG_PREFIX)) {

                tags.add(line)
            }
            val isIFrameOnlyVariant = line.startsWith(TAG_I_FRAME_STREAM_INF)

            if (line.startsWith(TAG_DEFINE)) {
                variableDefinitions[parseStringAttr(line, REGEX_NAME, variableDefinitions)] =
                    parseStringAttr(line, REGEX_VALUE, variableDefinitions)
            } else if (line == TAG_INDEPENDENT_SEGMENTS) {
                hasIndependentSegmentsTag = true
            } else if (line.startsWith(TAG_MEDIA)) {

                mediaTags.add(line)
            } else if (line.startsWith(TAG_SESSION_KEY)) {
                val keyFormat: String =
                    parseOptionalStringAttr(
                        line,
                        REGEX_KEYFORMAT,
                        KEYFORMAT_IDENTITY,
                        variableDefinitions
                    )!!
                val schemeData: SchemeData? =
                    parseDrmSchemeData(line, keyFormat, variableDefinitions)
                if (schemeData != null) {
                    val method: String =
                        parseStringAttr(line, REGEX_METHOD, variableDefinitions)
                    val scheme: String = parseEncryptionScheme(method)
                    sessionKeyDrmInitData.add(DrmInitData(scheme, arrayOf(schemeData)))
                }
            } else if (line.startsWith(TAG_STREAM_INF) || isIFrameOnlyVariant) {
                noClosedCaptions = noClosedCaptions or line.contains(ATTR_CLOSED_CAPTIONS_NONE)
                val roleFlags = if (isIFrameOnlyVariant) C.ROLE_FLAG_TRICK_PLAY else 0
                val peakBitrate: Int = parseIntAttr(line, REGEX_BANDWIDTH)
                val averageBitrate: Int =
                    parseOptionalIntAttr(line, REGEX_AVERAGE_BANDWIDTH, -1)
                val videoRange: String? =
                    parseOptionalStringAttr(line, REGEX_VIDEO_RANGE, variableDefinitions)
                var codecs: String? =
                    parseOptionalStringAttr(line, REGEX_CODECS, variableDefinitions)
                val supplementalCodecsStrings: String? =
                    parseOptionalStringAttr(
                        line,
                        REGEX_SUPPLEMENTAL_CODECS,
                        variableDefinitions
                    )
                var supplementalCodecs: String? = null
                var supplementalProfiles: String? = null
                if (supplementalCodecsStrings != null) {
                    val supplementalCodecsString: Array<String> =
                        Util.splitAtFirst(supplementalCodecsStrings, ",")

                    val codecsAndProfiles: Array<String> = Util.split(
                        supplementalCodecsString[0], "/"
                    )
                    supplementalCodecs = codecsAndProfiles[0]
                    if (codecsAndProfiles.size > 1) {
                        supplementalProfiles = codecsAndProfiles[1]
                    }
                }
                var videoCodecs: String? = Util.getCodecsOfType(codecs, C.TRACK_TYPE_VIDEO)
                if (isDolbyVisionFormat(
                        videoRange, videoCodecs, supplementalCodecs, supplementalProfiles
                    )
                ) {
                    videoCodecs = supplementalCodecs ?: videoCodecs
                    val nonVideoCodecs: String? =
                        Util.getCodecsWithoutType(codecs, C.TRACK_TYPE_VIDEO)
                    codecs =
                        if (nonVideoCodecs != null) "$videoCodecs,$nonVideoCodecs" else videoCodecs
                }

                val resolutionString: String? =
                    parseOptionalStringAttr(line, REGEX_RESOLUTION, variableDefinitions)
                var width: Int
                var height: Int
                if (resolutionString != null) {
                    val widthAndHeight: Array<String> = Util.split(resolutionString, "x")
                    width = widthAndHeight[0].toInt()
                    height = widthAndHeight[1].toInt()
                    if (width <= 0 || height <= 0) {

                        width = Format.NO_VALUE
                        height = Format.NO_VALUE
                    }
                } else {
                    width = Format.NO_VALUE
                    height = Format.NO_VALUE
                }
                var frameRate: Float = Format.NO_VALUE.toFloat()
                val frameRateString: String? =
                    parseOptionalStringAttr(line, REGEX_FRAME_RATE, variableDefinitions)
                if (frameRateString != null) {
                    frameRate = frameRateString.toFloat()
                }
                val videoGroupId: String? =
                    parseOptionalStringAttr(line, REGEX_VIDEO, variableDefinitions)
                val audioGroupId: String? =
                    parseOptionalStringAttr(line, REGEX_AUDIO, variableDefinitions)
                val subtitlesGroupId: String? =
                    parseOptionalStringAttr(line, REGEX_SUBTITLES, variableDefinitions)
                val closedCaptionsGroupId: String? =
                    parseOptionalStringAttr(line, REGEX_CLOSED_CAPTIONS, variableDefinitions)
                val url: Url
                if (isIFrameOnlyVariant) {
                    url =
                        UrlUtil.resolveToUrl(
                            baseUri,
                            parseStringAttr(line, REGEX_URI, variableDefinitions)
                        )
                } else if (!iterator.hasNext()) {
                    throw ParserException.createForMalformedManifest(
                        "#EXT-X-STREAM-INF must be followed by another line",  null
                    )
                } else {

                    line = replaceVariableReferences(iterator.next(), variableDefinitions)
                    url = UrlUtil.resolveToUrl(baseUri, line)
                }

                val variant =
                    Variant(
                        url = url,
                        format = Format(
                            id = variants.size.toString(),
                            containerMimeType = MimeTypes.APPLICATION_M3U8,
                            codecs = codecs,
                            averageBitrate = averageBitrate,
                            peakBitrate = peakBitrate,
                            width = width,
                            height = height,
                            frameRate = frameRate,
                            roleFlags = roleFlags,
                        ),
                        videoGroupId = videoGroupId,
                        audioGroupId = audioGroupId,
                        subtitleGroupId = subtitlesGroupId,
                        captionGroupId = closedCaptionsGroupId
                    )
                variants.add(variant)
                var variantInfosForUrl: ArrayList<VariantInfo>? = urlToVariantInfos[url]
                if (variantInfosForUrl == null) {
                    variantInfosForUrl = ArrayList()
                    urlToVariantInfos[url] = variantInfosForUrl
                }
                variantInfosForUrl.add(
                    VariantInfo(
                        averageBitrate,
                        peakBitrate,
                        videoGroupId,
                        audioGroupId,
                        subtitlesGroupId,
                        closedCaptionsGroupId
                    )
                )
            }
        }

        val deduplicatedVariants = variants.distinctBy { it.url }

        for (i in mediaTags.indices) {
            line = mediaTags[i]
            val groupId: String = parseStringAttr(line, REGEX_GROUP_ID, variableDefinitions)
            val name: String = parseStringAttr(line, REGEX_NAME, variableDefinitions)
            var formatBuilder = Format(
                id = "$groupId:$name",
                roleFlags = parseRoleFlags(line, variableDefinitions),
                selectionFlags = parseSelectionFlags(line),
                label = name,
                language = parseOptionalStringAttr(
                    line,
                    REGEX_LANGUAGE,
                    variableDefinitions
                ),
                containerMimeType = MimeTypes.APPLICATION_M3U8,
            )

            val referenceUrl: String? =
                parseOptionalStringAttr(line, REGEX_URI, variableDefinitions)
            val url: Url? =
                if (referenceUrl == null) null else UrlUtil.resolveToUrl(baseUri, referenceUrl)

            when (parseStringAttr(line, REGEX_TYPE, variableDefinitions)) {
                TYPE_VIDEO -> {
                    val variant: Variant? = getVariantWithVideoGroup(variants, groupId)
                    if (variant != null) {
                        val variantFormat: Format = variant.format
                        formatBuilder = formatBuilder.copy(
                            height = variantFormat.height,
                            width = variantFormat.width,
                            frameRate = variantFormat.frameRate,
                            codecs = Util.getCodecsOfType(variantFormat.codecs, C.TRACK_TYPE_VIDEO)
                        )
                    }
                    if (url == null) {

                    } else {

                        videos.add(Rendition(url = url, format = formatBuilder, groupId, name))
                    }
                }

                TYPE_AUDIO -> {
                    var sampleMimeType: String? = null
                    val variant = getVariantWithAudioGroup(variants, groupId)
                    if (variant != null) {
                        val codecs: String? =
                            Util.getCodecsOfType(variant.format.codecs, C.TRACK_TYPE_AUDIO)
                        formatBuilder = formatBuilder.copy(codecs = codecs)
                        sampleMimeType = MimeTypes.getMediaMimeType(codecs)
                    }
                    val channelsString: String? =
                        parseOptionalStringAttr(line, REGEX_CHANNELS, variableDefinitions)
                    if (channelsString != null) {
                        val channelCount: Int =
                            Util.splitAtFirst(channelsString, "/")[0].toInt()
                        formatBuilder = formatBuilder.copy(channelCount = channelCount)
                        if (MimeTypes.AUDIO_E_AC3 == sampleMimeType && channelsString.endsWith(
                                "/JOC"
                            )
                        ) {
                            sampleMimeType = MimeTypes.AUDIO_E_AC3_JOC
                            formatBuilder = formatBuilder.copy(codecs = MimeTypes.CODEC_E_AC3_JOC)
                        }
                    }
                    val format = formatBuilder.copy(sampleMimeType = sampleMimeType)
                    if (url != null) {

                        audios.add(Rendition(url, format, groupId, name))
                    } else if (variant != null) {

                        muxedAudioFormat = format
                    }
                }

                TYPE_SUBTITLES -> {
                    var sampleMimeType: String? = null
                    val variant = getVariantWithSubtitleGroup(variants, groupId)
                    if (variant != null) {
                        val codecs: String? =
                            Util.getCodecsOfType(variant.format.codecs, C.TRACK_TYPE_TEXT)
                        formatBuilder = formatBuilder.copy(
                            codecs = codecs,
                        )
                        sampleMimeType = MimeTypes.getMediaMimeType(codecs)
                    }
                    if (sampleMimeType == null) {
                        sampleMimeType = MimeTypes.TEXT_VTT
                    }
                    if (url != null) {
                        subtitles.add(
                            Rendition(
                                url,
                                formatBuilder.copy(sampleMimeType = sampleMimeType),
                                groupId,
                                name
                            )
                        )
                    } else {

                    }
                }

                TYPE_CLOSED_CAPTIONS -> {
                    val instreamId: String =
                        parseStringAttr(line, REGEX_INSTREAM_ID, variableDefinitions)
                    val accessibilityChannel: Int
                    val sampleMimeType: String
                    if (instreamId.startsWith("CC")) {
                        sampleMimeType = MimeTypes.APPLICATION_CEA608
                        accessibilityChannel = instreamId.substring(2).toInt()
                    } else   {
                        sampleMimeType = MimeTypes.APPLICATION_CEA708
                        accessibilityChannel = instreamId.substring(7).toInt()
                    }
                    muxedCaptionFormats.add(
                        formatBuilder.copy(
                            sampleMimeType = sampleMimeType,
                            accessibilityChannel = accessibilityChannel
                        )
                    )
                }

                else -> {}
            }
        }

        if (noClosedCaptions) {
            muxedCaptionFormats = arrayListOf()
        }

        return HlsMultivariantPlaylist(
            baseUri = baseUri,
            tags = tags,
            variants = deduplicatedVariants,
            videos = videos,
            audios = audios,
            subtitles = subtitles,
            closedCaptions = closedCaptions,
            muxedAudioFormat = muxedAudioFormat,
            muxedCaptionFormats = muxedCaptionFormats,
            hasIndependentSegments = hasIndependentSegmentsTag,
            variableDefinitions = variableDefinitions,
            sessionKeyDrmInitData = sessionKeyDrmInitData
        )
    }
}
