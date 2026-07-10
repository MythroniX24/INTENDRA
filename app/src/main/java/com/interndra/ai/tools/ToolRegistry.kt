package com.interndra.ai.tools

import com.interndra.data.model.ShellCommand

/**
 * ToolRegistry — a central, discoverable registry of all tools in the system.
 *
 * Inspired by OpenClaw's tool infrastructure where tools are registered,
 * sorted by sort key, checked for availability, and indexed by keyword
 * for fast matching. The registry unifies:
 *
 * 1. **Shell tools** from [com.interndra.ai.CommandRegistry] (50+ templates)
 * 2. **Plugin tools** from [com.interndra.plugin.PluginManager] (4 plugins, ~50 commands)
 * 3. **AI agent tools** from [com.interndra.ai.AiOrchestrator] (routed AI intents)
 *
 * OpenClaw reference:
 * ```typescript
 * const { visible, hidden } = buildToolPlan({
 *   descriptors: registry.getAll(),
 *   availability: checkRuntimeAvailability(),
 * });
 * ```
 *
 * ## Usage
 * ```kotlin
 * val registry = ToolRegistry()
 * registry.register(shellTool)
 * registry.registerAll(pluginManager.getAll().flatMap { it.toTools() })
 *
 * val best = registry.findBest("battery status")
 * val all = registry.listByCategory(ToolCategory.NETWORK)
 * ```
 */
class ToolRegistry {

    // ── Internal state ───────────────────────────────────────────────────
    private val descriptors = mutableMapOf<String, ToolDescriptor>()
    private val keywordIndex = mutableMapOf<String, MutableList<ToolDescriptor>>()

    /** Total registered tool count. */
    val size: Int get() = descriptors.size

    // ── Registration ────────────────────────────────────────────────────

    /**
     * Register a single tool descriptor.
     *
     * Each keyword is indexed (lowercased) for O(1) lookups in [findByKeyword].
     * If a tool with the same `name` already exists, it is replaced.
     */
    fun register(descriptor: ToolDescriptor) {
        descriptors[descriptor.name] = descriptor
        for (kw in descriptor.keywords) {
            val key = kw.lowercase().trim()
            if (key.isNotBlank()) {
                keywordIndex.getOrPut(key) { mutableListOf() }.add(descriptor)
            }
        }
    }

    /**
     * Register multiple tool descriptors at once.
     */
    fun registerAll(tools: Iterable<ToolDescriptor>) {
        for (tool in tools) {
            register(tool)
        }
    }

    /**
     * Remove a tool by name.
     * @return true if the tool was removed, false if not found
     */
    fun unregister(name: String): Boolean {
        val removed = descriptors.remove(name) ?: return false
        // Clean up keyword index
        for (kw in removed.keywords) {
            keywordIndex[kw.lowercase()]?.remove(removed)
        }
        return true
    }

    /** Remove all registered tools and clear the index. */
    fun clear() {
        descriptors.clear()
        keywordIndex.clear()
    }

    // ── Lookup ──────────────────────────────────────────────────────────

    /**
     * Find a single tool descriptor by its exact name.
     */
    fun getByName(name: String): ToolDescriptor? = descriptors[name]

    /**
     * Find all tools whose keywords contain the given query string.
     * The query is checked as **substring match** against each keyword.
     *
     * This is similar to OpenClaw's availability-based filtering — tools
     * whose keywords don't match are excluded.
     */
    fun findByKeyword(query: String): List<ToolDescriptor> {
        val lower = query.lowercase().trim()
        if (lower.isBlank()) return emptyList()
        return descriptors.values.filter { descriptor ->
            descriptor.keywords.any { kw -> lower.contains(kw.lowercase()) }
        }
    }

    /**
     * Find the **best matching** tool for the given input query.
     *
     * The best match is the one whose keyword appears earliest in the input.
     * This replicates OpenClaw's planner behavior where the most specific
     * match wins.
     *
     * @return the best-matching tool, or null if no keywords match
     */
    fun findBest(query: String): ToolDescriptor? {
        val lower = query.lowercase().trim()
        if (lower.isBlank()) return null

        return descriptors.values
            .mapNotNull { descriptor ->
                val bestKeyword = descriptor.keywords
                    .map { it.lowercase() }
                    .mapNotNull { kw ->
                        val idx = lower.indexOf(kw)
                        if (idx >= 0) idx to kw.length else null
                    }
                    .minByOrNull { it.first }
                if (bestKeyword != null) {
                    descriptor to bestKeyword
                } else null
            }
            .minByOrNull { it.second.first }  // earliest occurrence wins
            ?.first
    }

    /**
     * Find the **best matching** tool and extract parameters from the input.
     *
     * For example, with a tool whose keyword is "set volume", the input
     * "set volume to 50" would match and extract `{level: "50"}`.
     *
     * @return Pair of (tool, extracted params) or null
     */
    fun findBestWithParams(query: String): Pair<ToolDescriptor, Map<String, String>>? {
        val lower = query.lowercase().trim()
        if (lower.isBlank()) return null

        val best = findBest(query) ?: return null

        // Extract any numeric values after matched keywords as parameters
        val params = mutableMapOf<String, String>()

        // Look for "to <number>" or "<number>%" patterns
        val numberMatch = Regex("""(?:to|at|setting|set|as)\s*(\d+\.?\d*)""", RegexOption.IGNORE_CASE)
            .find(query)
        numberMatch?.let { match ->
            params["level"] = match.groupValues[1]
            params["value"] = match.groupValues[1]
        }

        return best to params
    }

    /**
     * List all tools in a specific category.
     */
    fun listByCategory(category: ToolCategory): List<ToolDescriptor> =
        descriptors.values.filter { it.category == category }

    /**
     * List all available tools (isAvailable() == true).
     */
    fun listAvailable(): List<ToolDescriptor> =
        descriptors.values.filter { it.isAvailable() }

    /**
     * Returns the complete set of registered tools (unsorted).
     */
    fun getAll(): List<ToolDescriptor> = descriptors.values.toList()

    /**
     * Returns all tool names sorted by sortKey then name.
     */
    fun getSortedNames(): List<String> =
        descriptors.values
            .sortedWith(compareBy<ToolDescriptor> { it.sortKey }.thenBy { it.name })
            .map { it.name }

    /**
     * Get all available commands as a flat list of ShellCommand for execution.
     */
    fun getAllCommands(): List<ShellCommand> {
        val shellTools = descriptors.values.filterIsInstance<ShellToolDescriptor>()
        return shellTools.flatMap { it.commands }
    }

    // ── Merging ─────────────────────────────────────────────────────────

    /**
     * Merge another ToolRegistry's tools into this one.
     * Tools from `other` override tools with the same name in this registry.
     */
    fun merge(other: ToolRegistry) {
        for (tool in other.getAll()) {
            register(tool)
        }
    }

    /**
     * Create a snapshot of the current registry state.
     */
    fun snapshot(): ToolRegistrySnapshot {
        return ToolRegistrySnapshot(
            toolCount = size,
            categories = ToolCategory.entries.map { cat ->
                cat to descriptors.values.count { it.category == cat }
            }.toMap(),
            names = getSortedNames()
        )
    }
}

/**
 * Immutable snapshot of a [ToolRegistry]'s state for debugging or UI display.
 */
data class ToolRegistrySnapshot(
    val toolCount: Int,
    val categories: Map<ToolCategory, Int>,
    val names: List<String>
)
