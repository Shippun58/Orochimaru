package ani.sanin.cloudstream

import android.util.Base64
import android.util.Log
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CsEngineException(message: String) : Exception(message)

object CsEngine {

    interface CsNativeFetch { fun fetch(src: String, url: String, optsJson: String): String }
    interface CsNativeCrypto { fun crypto(payloadJson: String): String }
    interface CsNativeSleep { fun sleep(ms: Double) }
    interface CsNativeLog { fun log(line: String) }

    private val engineLock = Any()

    suspend fun call(
        sourceId: String,
        method: String,
        args: List<Any?>,
        providerJs: String
    ): String? = withContext(Dispatchers.IO) {
        synchronized(engineLock) {
            val result: String?
            QuickJs.create().use { qs ->
                qs.set("nativeFetch", CsNativeFetch::class.java, FetchBridge())
                qs.set("nativeCrypto", CsNativeCrypto::class.java, CryptoBridge())
                qs.set("nativeSleep", CsNativeSleep::class.java, SleepBridge())
                qs.set("nativeLog", CsNativeLog::class.java, LogBridge())
                qs.evaluate(BOOTSTRAP)
                qs.evaluate(wrapProvider(sourceId, providerJs))
                val src = sourceId.replace("'", "\\'")
                val argsJson = buildArgsJson(args)
                qs.evaluate(
                    "globalThis.__result = null;" +
                        "globalThis.__callProviderT('$src','$method',$argsJson,0)" +
                        ".then(function(v){globalThis.__result=v;}," +
                        "function(e){globalThis.__result='ERR__'+e;});"
                )
                var raw = qs.evaluate("globalThis.__result") as? String
                var pumps = 0
                while (raw == null && pumps < 8) {
                    qs.evaluate("1")
                    raw = qs.evaluate("globalThis.__result") as? String
                    pumps++
                }
                result = when {
                    raw == null -> null
                    raw.startsWith("ERR__") -> throw CsEngineException(raw.removePrefix("ERR__"))
                    else -> raw
                }
            }
            result
        }
    }

    fun wrapProvider(sourceId: String, providerJs: String): String {
        val src = sourceId.replace("'", "\\'")
        return """
        (function(){
          var __SOURCE_ID = '$src';
          var fetch = function(url, opts) { return globalThis.__fetch(__SOURCE_ID, url, opts); };
          var extractVideo = function(url, opts) { return globalThis.extractVideo(url, opts); };
          var console = {
            log:   function() { globalThis.__console(__SOURCE_ID, 'log', arguments); },
            warn:  function() { globalThis.__console(__SOURCE_ID, 'warn', arguments); },
            error: function() { globalThis.__console(__SOURCE_ID, 'error', arguments); },
            info:  function() { globalThis.__console(__SOURCE_ID, 'info', arguments); },
            debug: function() { globalThis.__console(__SOURCE_ID, 'debug', arguments); }
          };
          $providerJs
          globalThis.__providers['$src'] = {
            getInfo:         typeof getInfo === 'function' ? getInfo : null,
            getHome:         typeof getHome === 'function' ? getHome : null,
            popular:         typeof popular === 'function' ? popular : null,
            search:          typeof search === 'function' ? search : null,
            getDetail:       typeof getDetail === 'function' ? getDetail : null,
            getEpisodes:     typeof getEpisodes === 'function' ? getEpisodes : null,
            getVideoSources: typeof getVideoSources === 'function' ? getVideoSources : null
          };
        })();
        """.trimIndent()
    }

    private fun buildArgsJson(args: List<Any?>): String {
        val arr = org.json.JSONArray()
        for (a in args) {
            when (a) {
                null -> arr.put(org.json.JSONObject.NULL)
                is String -> arr.put(a)
                is Int -> arr.put(a)
                is Long -> arr.put(a)
                is Double -> arr.put(a)
                is Boolean -> arr.put(a)
                is Map<*, *> -> arr.put(org.json.JSONObject(a))
                is List<*> -> arr.put(org.json.JSONArray(a))
                else -> arr.put(a.toString())
            }
        }
        return arr.toString()
    }

    private class FetchBridge : CsNativeFetch {
        override fun fetch(src: String, url: String, optsJson: String): String {
            return try {
                val opts = if (optsJson.isBlank()) JSONObject() else JSONObject(optsJson)
                val method = opts.optString("method", "GET").uppercase()
                val timeoutMs = opts.optLong("timeoutMs", 0L)
                val builder = Request.Builder().url(url)
                val body = when (method) {
                    "GET", "HEAD" -> null
                    else -> opts.optString("body", "").toRequestBody(null)
                }
                builder.method(method, body)
                val headers = opts.optJSONObject("headers")
                if (headers != null) {
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        builder.header(k, headers.optString(k))
                    }
                }
                val client = Injekt.get<NetworkHelper>().client
                val call = client.newCall(builder.build())
                if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
                call.execute().use { resp ->
                    val bodyText = resp.body?.string().orEmpty()
                    val h = JSONObject()
                    resp.headers.forEach { (k, v) -> h.put(k, v) }
                    JSONObject()
                        .put("ok", resp.isSuccessful)
                        .put("status", resp.code)
                        .put("statusText", resp.message)
                        .put("headers", h)
                        .put("url", resp.request.url.toString())
                        .put("body", bodyText)
                        .toString()
                }
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
            }
        }
    }

    private class CryptoBridge : CsNativeCrypto {
        override fun crypto(payloadJson: String): String {
            return try {
                val p = JSONObject(payloadJson)
                when (p.optString("op")) {
                    "sha256" -> {
                        val md = MessageDigest.getInstance("SHA-256")
                        md.digest(p.optString("message").toByteArray(Charsets.UTF_8)).toHex()
                    }
                    "aesCtrDecrypt" -> {
                        val key = hexToBytes(p.optString("keyHex"))
                        val counter = hexToBytes(p.optString("counterHex"))
                        val data = Base64.decode(p.optString("dataB64"), Base64.DEFAULT)
                        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(key, "AES"),
                            IvParameterSpec(counter)
                        )
                        Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
                    }
                    else -> ""
                }
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "crypto error").toString()
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray {
            val s = hex.trim().removePrefix("0x")
            return ByteArray(s.length / 2) {
                s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        }
    }

    private class SleepBridge : CsNativeSleep {
        override fun sleep(ms: Double) {
            try {
                Thread.sleep(ms.toLong())
            } catch (_: InterruptedException) {
            }
        }
    }

    private class LogBridge : CsNativeLog {
        override fun log(line: String) {
            Log.d("CsEngine", line)
        }
    }

    private const val BOOTSTRAP = """
var __fetchSeq = 0;
globalThis.__providers = globalThis.__providers || {};
globalThis.__result = null;

globalThis.__fetch = function(src, url, opts) {
  opts = opts || {};
  var raw;
  try { raw = nativeFetch(String(src), String(url), JSON.stringify(opts)); }
  catch (e) { return Promise.reject('fetch bridge error: ' + e); }
  var res;
  try { res = JSON.parse(raw || 'null'); } catch (e) { return Promise.reject('bad fetch response JSON'); }
  if (!res || res.error) { return Promise.reject((res && res.error) || 'fetch failed: ' + url); }
  var headers = res.headers || {};
  if (typeof headers.get !== 'function') {
    headers.get = function(k) { return headers[String(k).toLowerCase()] || null; };
  }
  return Promise.resolve({
    ok: !!res.ok,
    status: res.status || 0,
    statusText: res.statusText || '',
    headers: headers,
    url: res.url || url,
    body: res.body || '',
    text: function() { return Promise.resolve(res.body || ''); },
    json: function() {
      try { return Promise.resolve(JSON.parse(res.body || 'null')); }
      catch (e) { return Promise.reject('Invalid JSON: ' + e); }
    }
  });
};

globalThis.__crypto = function(op, payload) {
  var msg = { op: op };
  for (var k in payload) { if (Object.prototype.hasOwnProperty.call(payload, k)) msg[k] = payload[k]; }
  var raw;
  try { raw = nativeCrypto(JSON.stringify(msg)); } catch (e) { return Promise.reject('crypto bridge error: ' + e); }
  if (!raw) return Promise.reject('crypto failed');
  if (raw.indexOf('{"error":') === 0) {
    try { return Promise.reject(JSON.parse(raw).error); } catch (e) { return Promise.reject('crypto failed'); }
  }
  return Promise.resolve(raw);
};
globalThis.sha256Hex = function(message) { return globalThis.__crypto('sha256', { message: String(message) }); };
globalThis.aesCtrDecrypt = function(opts) {
  return globalThis.__crypto('aesCtrDecrypt', { keyHex: opts.keyHex, counterHex: opts.counterHex, dataB64: opts.dataB64 });
};
globalThis.base64ToBytes = function(b64) {
  var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var lookup = {}; for (var i = 0; i < chars.length; i++) lookup[chars.charAt(i)] = i;
  var s = String(b64).replace(/[^A-Za-z0-9+/]/g, '');
  var out = []; var n = s.length;
  for (var j = 0; j < n; j += 4) {
    var e1 = lookup[s.charAt(j)], e2 = lookup[s.charAt(j + 1)];
    var e3 = lookup[s.charAt(j + 2)], e4 = lookup[s.charAt(j + 3)];
    out.push((e1 << 2) | (e2 >> 4));
    if (j + 2 < n) out.push(((e2 & 15) << 4) | (e3 >> 2));
    if (j + 3 < n) out.push(((e3 & 3) << 6) | e4);
  }
  return out;
};
globalThis.bytesToHex = function(bytes) {
  var h = ''; for (var i = 0; i < bytes.length; i++) { var x = (bytes[i] & 255).toString(16); h += x.length === 1 ? '0' + x : x; } return h;
};
globalThis.bytesToB64 = function(bytes) {
  var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var out = '', i = 0;
  while (i < bytes.length) {
    var c1 = bytes[i++] & 255, c2 = i < bytes.length ? bytes[i++] & 255 : NaN, c3 = i < bytes.length ? bytes[i++] & 255 : NaN;
    out += chars.charAt(c1 >> 2) + chars.charAt(((c1 & 3) << 4) | (c2 >> 4))
        + (isNaN(c2) ? '=' : chars.charAt(((c2 & 15) << 2) | (c3 >> 6)))
        + (isNaN(c3) ? '=' : chars.charAt(c3 & 63));
  }
  return out;
};

globalThis.__console = function(src, level, args) {
  try {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      parts.push(typeof a === 'string' ? a : JSON.stringify(a));
    }
    nativeLog(level + ' [' + src + '] ' + parts.join(' '));
  } catch (e) {}
};

if (typeof globalThis.setTimeout !== 'function') {
  globalThis.setTimeout = function(fn, ms) {
    Promise.resolve().then(function() { try { fn(); } catch (e) {} });
    return 0;
  };
  globalThis.clearTimeout = function() {};
}

globalThis.htmlText = function(html) {
  if (!html) return '';
  return String(html)
    .replace(/<[^>]*>/g, '')
    .replace(/&#x([0-9a-fA-F]+);/g, function(_, h) { return String.fromCodePoint(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function(_, d) { return String.fromCodePoint(parseInt(d, 10)); })
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ')
    .trim();
};

globalThis.absUrl = function(href, base) {
  if (!href) return '';
  if (/^https?:\/\//i.test(href)) return href;
  if (href.indexOf('//') === 0) return 'https:' + href;
  if (!base) return href;
  if (href.indexOf('/') === 0) {
    var m = base.match(/^(https?:\/\/[^\/]+)/i);
    return m ? m[1] + href : href;
  }
  return base.replace(/\/$/, '') + '/' + href;
};

globalThis.unpackJs = function(source) {
  var s = String(source);
  if (s.indexOf('}(') === -1 || s.indexOf(".split('|')") === -1) return s;
  var body = s.slice(s.indexOf("}('") + 3, s.indexOf(".split('|'),0,{}))"));
  body = body.replace(/\\'/g, "'");
  var payload = body.slice(0, body.indexOf("',"));
  var dict = body.slice(body.indexOf("'", body.indexOf("',") + 2) + 1, body.lastIndexOf("'")).split('|');
  function r62(t){ var a=0; for (var i=0;i<t.length;i++){ var c=t.charCodeAt(i); a = a*62 + (c<=57 ? c-48 : c>=97 ? c-87 : c-29); } return a; }
  return payload.replace(/[0-9A-Za-z]+/g, function(k){ var i=r62(k); return (i<dict.length && dict[i]!=='') ? dict[i] : k; });
};

globalThis.extractVideo = function(embedUrl, opts) {
  return Promise.reject('No extractor for host: ' + String(embedUrl).replace(/^https?:\/\//i, '').split('/')[0]);
};

globalThis.__callProvider = function(sourceId, method, argsJson) {
  var args;
  try { args = JSON.parse(argsJson || '[]'); } catch (e) { return Promise.reject('Bad argsJson: ' + e); }
  var ns = globalThis.__providers[sourceId];
  if (!ns) return Promise.reject('Provider not loaded: ' + sourceId);
  var fn = ns[method];
  if (typeof fn !== 'function') return Promise.reject('Provider ' + sourceId + ' missing method: ' + method);
  function stringifyErr(e) {
    if (!e) return 'unknown error';
    if (typeof e === 'string') return e;
    if (e instanceof Error) return e.message || String(e);
    if (typeof e === 'object' && e.message) return String(e.message);
    try { return JSON.stringify(e); } catch (_) { return String(e); }
  }
  try {
    var r = fn.apply(null, args);
    return Promise.resolve(r)
      .then(function(v) { return JSON.stringify(v == null ? null : v); })
      .catch(function(e) { return Promise.reject(stringifyErr(e)); });
  } catch (e) { return Promise.reject(stringifyErr(e)); }
};

globalThis.__callProviderT = function(sourceId, method, argsJson, ms) {
  return globalThis.__callProvider(sourceId, method, argsJson);
};
"""
}
