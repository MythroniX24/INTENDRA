package com.interndra.plugin

import android.content.Context
import android.util.Log
import com.interndra.data.model.PluginEntry
import com.interndra.data.model.PluginStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// All plugin implementations are in the same package — no imports needed.
// Plugins are instantiated in registerBuiltInPlugins() below.

/**
 * PluginManager — lightweight plugin registry for INTERNDRA.
 *
 * Plugins extend the app with new commands, data sources, or integrations.
 * This is a local-first plugin system: plugins are Kotlin interfaces loaded
 * from the same APK (no dynamic class loading / reflection from external APKs
 * — that would create a security surface).
 *
 * To add a plugin: implement IPlugin, annotate it, and register it in
 * InterndraApplication via pluginManager.register(MyPlugin(context)).
 */
interface IPlugin {
    val id: String
    val name: String
    val description: String
    val version: String
    val author: String

    suspend fun initialize(context: Context): Boolean
    suspend fun execute(command: String, args: Map<String, String>): PluginResult
    fun getSupportedCommands(): List<String>
    fun teardown()
}

data class PluginResult(
    val success: Boolean,
    val output: String,
    val data: Map<String, Any> = emptyMap(),
    val error: String = ""
)

class PluginManager(private val context: Context) {

    companion object {
        private const val TAG = "PluginManager"
    }

    private val registry = mutableMapOf<String, IPlugin>()

    private val _plugins = MutableStateFlow<List<PluginEntry>>(emptyList())
    val plugins: StateFlow<List<PluginEntry>> = _plugins.asStateFlow()

    // ── Register ──────────────────────────────────────────────────────────
    suspend fun register(plugin: IPlugin): Boolean = withContext(Dispatchers.IO) {
        try {
            val initialized = plugin.initialize(context)
            if (!initialized) {
                Log.w(TAG, "Plugin ${plugin.id} failed to initialize")
                return@withContext false
            }
            registry[plugin.id] = plugin
            refreshPluginList()
            Log.d(TAG, "Plugin registered: ${plugin.name} v${plugin.version}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error registering plugin ${plugin.id}: ${e.message}")
            false
        }
    }

    fun unregister(pluginId: String) {
        registry[pluginId]?.teardown()
        registry.remove(pluginId)
        refreshPluginList()
        Log.d(TAG, "Plugin unregistered: $pluginId")
    }

    // ── Execute ───────────────────────────────────────────────────────────
    suspend fun execute(
        command: String,
        args: Map<String, String> = emptyMap()
    ): PluginResult = withContext(Dispatchers.IO) {
        val plugin = findPluginForCommand(command)
            ?: return@withContext PluginResult(false, "", error = "No plugin handles command: $command")
        try {
            plugin.execute(command, args)
        } catch (e: Exception) {
            PluginResult(false, "", error = e.message ?: "Plugin error")
        }
    }

    private fun findPluginForCommand(command: String): IPlugin? =
        registry.values.firstOrNull { command in it.getSupportedCommands() }

    // ── Built-in plugins ──────────────────────────────────────────────────
    suspend fun registerBuiltInPlugins() {
        val pluginsToRegister = listOf(
            TermuxPlugin(context),
            GitPlugin(context),
            PackageManagerPlugin(context),
            NetworkPlugin(context)
        )
        var count = 0
        for (plugin in pluginsToRegister) {
            val ok = register(plugin)
            if (ok) count++
        }
        Log.i(TAG, "Registered $count/${pluginsToRegister.size} built-in plugins")
    }

    // ── State ─────────────────────────────────────────────────────────────
    private fun refreshPluginList() {
        _plugins.value = registry.values.map { p ->
            PluginEntry(
                id          = p.id,
                name        = p.name,
                description = p.description,
                version     = p.version,
                author      = p.author,
                status      = PluginStatus.ACTIVE,
                commands    = p.getSupportedCommands().joinToString(","),
                installedAt = System.currentTimeMillis()
            )
        }
    }

    fun getPlugin(id: String): IPlugin? = registry[id]
    fun getAll(): List<IPlugin> = registry.values.toList()
    fun count(): Int = registry.size
    fun isRegistered(id: String): Boolean = registry.containsKey(id)
}
