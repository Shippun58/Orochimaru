package ani.sanin.cloudstream

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.extractorApis
import dalvik.system.PathClassLoader
import java.io.File
import java.io.InputStreamReader

class CsPluginException(message: String) : Exception(message)

object CsRuntime {

    private const val TAG = "CsRuntime"

    private val plugins = mutableMapOf<String, BasePlugin>()

    private val apis = mutableMapOf<String, List<MainAPI>>()

    var lastError: String? = null
        private set

    @Synchronized
    fun load(context: Context, source: CsInstalledSource): Boolean {

        setContext(context.applicationContext)
        CloudStreamApp.pinContext(context.applicationContext)
        if (plugins.containsKey(source.id)) return true

        val file = CsRepos.installedFile(context, source)
        if (!file.exists()) return false

        return runCatching {

            if (!file.setReadOnly()) {
                Log.e(TAG, "Failed to set read-only on ${file.name}")
            }
            val loader = PathClassLoader(file.absolutePath, context.classLoader)

            val manifest: BasePlugin.Manifest =
                loader.getResourceAsStream("manifest.json")?.use { stream ->
                    InputStreamReader(stream).use { reader ->
                        parseJson<BasePlugin.Manifest>(reader.readText())
                    }
                } ?: throw CsPluginException("No manifest.json found in ${file.name}")

            val className = manifest.pluginClassName
                ?: throw CsPluginException("No pluginClassName in ${file.name} manifest")

            @Suppress("UNCHECKED_CAST")
            val pluginClass = loader.loadClass(className) as Class<out BasePlugin>
            Log.i(TAG, "Loaded class $className for ${source.name}")

            val before = APIHolder.allProviders.size
            val instance = pluginClass.getDeclaredConstructor().newInstance()
            instance.filename = file.absolutePath
            Log.i(TAG, "Instantiated ${instance::class.java.name}, providers before=$before")
            if (manifest.requiresResources && instance is Plugin) {

                instance.resources = buildPluginResources(context, file)
            }
            if (instance is Plugin) {
                instance.load(context)
            } else {
                instance.load()
            }
            val after = APIHolder.allProviders.size
            val registered = APIHolder.allProviders.subList(before, after).toList()
            lastError = null
            plugins[source.id] = instance
            apis[source.id] = registered
            Log.i(TAG, "Loaded ${source.name} (${registered.size} providers, providers after=$after)")
            true
        }.getOrElse { t ->
            lastError = t::class.java.simpleName + (t.message?.let { ": $it" } ?: "")
            Log.e(TAG, "Failed to load ${source.name}: ${lastError}", t)
            plugins.remove(source.id)
            apis.remove(source.id)
            false
        }
    }

    fun apisFor(context: Context, source: CsInstalledSource): List<MainAPI> {
        if (!plugins.containsKey(source.id)) {
            if (!load(context, source)) return emptyList()
        }
        return apis[source.id].orEmpty()
    }

    fun isLoaded(sourceId: String): Boolean = plugins.containsKey(sourceId)

    fun openSettingsFor(
        context: Context,
        source: CsInstalledSource,
        activity: AppCompatActivity? = context as? AppCompatActivity,
    ): ((android.content.Context) -> Unit)? {
        if (!plugins.containsKey(source.id)) {
            if (!load(context, source)) return null
        }
        val plugin = plugins[source.id] as? Plugin ?: return null
        val act = activity ?: (context as? AppCompatActivity)

        val fresh = act?.let { live ->
            runCatching { freshSettingsOpener(context, source, live) }
                .onFailure { t ->
                    Log.e(TAG, "Fresh settings bind failed for ${source.name}", t)
                    lastError = "Fresh settings bind failed: ${t.message}"
                }
                .getOrNull()
        }
        val bound = fresh ?: run {
            Log.w(TAG, "Falling back to loaded plugin.openSettings for ${source.name} (fresh bind null/failed); resources=${plugin.resources != null}")
            lastError = if (fresh == null && plugin.openSettings == null) "No settings opener bound" else lastError
            plugin.openSettings
        } ?: return null

        return { ctx ->
            try {
                bound(ctx)
            } catch (t: Throwable) {
                Log.e(TAG, "Plugin ${source.name} failed to open settings", t)
                throw t
            }
        }
    }

    private fun freshSettingsOpener(
        context: Context,
        source: CsInstalledSource,
        activity: AppCompatActivity,
    ): ((android.content.Context) -> Unit)? {
        val file = CsRepos.installedFile(context, source)
        if (!file.exists()) return null

        if (!file.setReadOnly()) {
            Log.e(TAG, "Failed to set read-only on ${file.name}")
        }

        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val manifest = loader.getResourceAsStream("manifest.json")?.use { stream ->
            InputStreamReader(stream).use { reader ->
                parseJson<BasePlugin.Manifest>(reader.readText())
            }
        } ?: return null
        val className = manifest.pluginClassName ?: return null

        val providers = APIHolder.allProviders
        val extractors = extractorApis

        val beforeProviders = providers.withLock { providers.toList() }
        val beforeExtractors = extractors.withLock { extractors.toList() }

        var instance: Plugin? = null
        try {
            val created = loader.loadClass(className).getDeclaredConstructor().newInstance() as? Plugin
                ?: return null
            instance = created
            created.filename = file.absolutePath
            if (manifest.requiresResources) {
                created.resources = buildPluginResources(context, file)
                Log.d(TAG, "Built plugin resources for ${source.name} (pkg=${manifest.name}, hasRes=${created.resources!=null})")
            } else {
                Log.d(TAG, "No resources required for ${source.name}")
            }

            runCatching { created.load(activity) }
                .onFailure { t ->
                    Log.e(TAG, "Plugin ${source.name} load() failed during settings bind", t)
                }
        } finally {
            providers.withLock {
                providers.filter { it !in beforeProviders }.forEach { dup ->
                    providers.remove(dup)
                    runCatching { APIHolder.removePluginMapping(dup) }
                }
            }
            extractors.withLock {
                extractors.filter { it !in beforeExtractors }.forEach { dup ->
                    extractors.remove(dup)
                }
            }
        }
        return instance?.openSettings
    }

    private fun buildPluginResources(context: Context, file: File): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            .invoke(assets, file.absolutePath)
        @Suppress("DEPRECATION")
        return Resources(
            assets,
            context.resources.displayMetrics,
            context.resources.configuration
        )
    }

    @Synchronized
    fun unload(sourceId: String) {
        plugins.remove(sourceId)?.let { plugin ->
            runCatching { plugin.beforeUnload() }
        }
        apis.remove(sourceId)
    }
}
