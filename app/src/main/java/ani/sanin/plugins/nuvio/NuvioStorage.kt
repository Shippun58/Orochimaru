package ani.sanin.plugins.nuvio

import android.content.Context
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

internal object NuvioStorage {
    private val json = Json { ignoreUnknownKeys = true }

    fun repos(): Set<String> = PrefManager.getVal(PrefName.NuvioRepos)

    fun addRepo(url: String) {
        PrefManager.setVal(PrefName.NuvioRepos, repos() + url)
    }

    fun removeRepo(context: Context, url: String) {
        PrefManager.setVal(PrefName.NuvioRepos, repos() - url)
        installedMetadata()
            .filter { it.repositoryUrl == url }
            .forEach { deleteCode(context, it) }
        saveMetadata(installedMetadata().filterNot { it.repositoryUrl == url })
    }

    fun installedMetadata(): List<NuvioStoredScraper> =
        PrefManager.getVal<List<String>>(PrefName.NuvioInstalledScrapers)
            .mapNotNull { runCatching { json.decodeFromString<NuvioStoredScraper>(it) }.getOrNull() }

    private fun saveMetadata(items: List<NuvioStoredScraper>) {
        PrefManager.setVal(PrefName.NuvioInstalledScrapers, items.map { json.encodeToString(it) })
    }

    fun installed(context: Context): List<NuvioScraper> =
        installedMetadata().mapNotNull { stored ->
            val code = runCatching { codeFile(context, stored).readText() }.getOrNull()
            if (code.isNullOrBlank()) null else stored.withCode(code)
        }

    fun codeFile(context: Context, stored: NuvioStoredScraper): File =
        File(scrapersDir(context), "${codeKey(stored.repositoryUrl, stored.id)}.js")

    fun install(context: Context, stored: NuvioStoredScraper, code: String) {
        val current = installedMetadata().filterNot { it.id == stored.id }
        codeFile(context, stored).writeText(code)
        saveMetadata(current + stored)
    }

    fun uninstall(context: Context, stored: NuvioStoredScraper) {
        deleteCode(context, stored)
        saveMetadata(installedMetadata().filterNot { it.id == stored.id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        saveMetadata(installedMetadata().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun loadSettings(id: String): String =
        PrefManager.getNullableCustomVal("nuvio_settings_$id", "{}", String::class.java) ?: "{}"

    fun saveSettings(id: String, value: String) {
        PrefManager.setCustomVal("nuvio_settings_$id", value)
    }

    private fun deleteCode(context: Context, stored: NuvioStoredScraper) {
        runCatching { codeFile(context, stored).delete() }
    }

    private fun scrapersDir(context: Context): File =
        File(context.filesDir, "nuvio_scrapers").apply { mkdirs() }

    private fun codeKey(repositoryUrl: String, id: String): String {
        val raw = "${repositoryUrl.lowercase()}:$id"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
