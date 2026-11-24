package ani.sanin.plugins.nuvio.runtime

import ani.sanin.plugins.nuvio.NuvioCryptoBridge
import ani.sanin.plugins.nuvio.NuvioDomBridge
import ani.sanin.plugins.nuvio.NuvioFetchBridge
import ani.sanin.plugins.nuvio.NuvioHostFunctions
import ani.sanin.plugins.nuvio.NuvioUrlBridge
import ani.sanin.plugins.nuvio.NuvioWasmBridge
import com.dokar.quickjs.QuickJs

internal interface NuvioHostModule {
    fun register(runtime: QuickJs)
}

internal class NuvioHostApiRegistry {
    private val modules = mutableListOf<NuvioHostModule>()

    fun addModule(module: NuvioHostModule) {
        modules.add(module)
    }

    fun registerAll(runtime: QuickJs) {
        modules.forEach { it.register(runtime) }
    }

    companion object {
        fun create(
            scraperId: String,
            settingsJson: String,
            callArgsJson: String,
            onResult: (String) -> Unit,
            includeCallBindings: Boolean,
        ): NuvioHostApiRegistry = NuvioHostApiRegistry().apply {
            addModule(
                NuvioHostFunctions(
                    scraperId = scraperId,
                    scraperSettingsJson = settingsJson,
                    callArgsJson = if (includeCallBindings) callArgsJson else "{}",
                    onResult = onResult,
                )
            )
            addModule(NuvioFetchBridge())
            addModule(NuvioUrlBridge())
            addModule(NuvioCryptoBridge())
            addModule(NuvioWasmBridge())
            addModule(NuvioDomBridge())
        }
    }
}
