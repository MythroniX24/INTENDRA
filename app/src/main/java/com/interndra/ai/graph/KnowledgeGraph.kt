package com.interndra.ai.graph

import android.util.Log
import com.interndra.data.model.KnowledgeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * KnowledgeGraph — lightweight in-memory concept graph.
 *
 * Builds entity → entity relationships from knowledge vault entries.
 * No external graph DB required — pure Kotlin data structures.
 *
 * Nodes: concepts / entities extracted from entry titles and tags.
 * Edges: co-occurrence within the same entry = similarity weight.
 *
 * Used for: "what else is related to X?", knowledge map UI, context expansion.
 */
class KnowledgeGraph {

    companion object {
        private const val TAG = "KnowledgeGraph"
    }

    data class Node(
        val id: String,           // normalized concept string
        val label: String,        // display label
        val entryIds: MutableSet<Long> = mutableSetOf(),
        var weight: Int = 1       // how many entries reference this concept
    )

    data class Edge(
        val from: String,
        val to: String,
        var weight: Int = 1       // co-occurrence count
    )

    data class GraphSnapshot(
        val nodes: List<Node>,
        val edges: List<Edge>,
        val nodeCount: Int,
        val edgeCount: Int
    )

    private val nodes = mutableMapOf<String, Node>()
    private val edges = mutableMapOf<String, Edge>()  // "from::to" → Edge

    private val _snapshot = MutableStateFlow(GraphSnapshot(emptyList(), emptyList(), 0, 0))
    val snapshot: StateFlow<GraphSnapshot> = _snapshot.asStateFlow()

    // ── Build from knowledge entries ──────────────────────────────────────
    suspend fun rebuild(entries: List<KnowledgeEntry>) = withContext(Dispatchers.Default) {
        nodes.clear()
        edges.clear()

        entries.forEach { entry ->
            val concepts = extractConcepts(entry)
            concepts.forEach { concept ->
                val node = nodes.getOrPut(concept) { Node(concept, concept.replaceFirstChar { it.uppercase() }) }
                node.entryIds.add(entry.id)
                node.weight++
            }
            // Create edges for all pairs of concepts in same entry
            val list = concepts.toList()
            for (i in list.indices) {
                for (j in i + 1 until list.size) {
                    val key = "${list[i]}::${list[j]}"
                    val edge = edges.getOrPut(key) { Edge(list[i], list[j]) }
                    edge.weight++
                }
            }
        }

        publishSnapshot()
        Log.d(TAG, "Graph rebuilt: ${nodes.size} nodes, ${edges.size} edges from ${entries.size} entries")
    }

    // ── Query related concepts ────────────────────────────────────────────
    fun getRelated(concept: String, maxResults: Int = 10): List<Node> {
        val key = normalize(concept)
        val connectedKeys = edges.values
            .filter { it.from == key || it.to == key }
            .sortedByDescending { it.weight }
            .flatMap { listOf(if (it.from == key) it.to else it.from) }
            .take(maxResults)
        return connectedKeys.mapNotNull { nodes[it] }
    }

    // ── Search nodes ──────────────────────────────────────────────────────
    fun searchNodes(query: String): List<Node> {
        val q = query.lowercase()
        return nodes.values
            .filter { it.label.lowercase().contains(q) }
            .sortedByDescending { it.weight }
    }

    // ── Concept extractor ─────────────────────────────────────────────────
    private fun extractConcepts(entry: KnowledgeEntry): Set<String> {
        val concepts = mutableSetOf<String>()
        // Title words (all noun-like words > 3 chars)
        entry.title.split(Regex("\\W+"))
            .filter { it.length > 3 }
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .forEach { concepts.add(it) }
        // Tags (always add as concepts)
        entry.tags.split(",")
            .map { normalize(it.trim()) }
            .filter { it.isNotBlank() }
            .forEach { concepts.add(it) }
        return concepts
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "").trim()

    private fun publishSnapshot() {
        _snapshot.value = GraphSnapshot(
            nodes     = nodes.values.sortedByDescending { it.weight },
            edges     = edges.values.sortedByDescending { it.weight },
            nodeCount = nodes.size,
            edgeCount = edges.size
        )
    }

    // ── Stats ─────────────────────────────────────────────────────────────
    fun topConcepts(n: Int = 20): List<Node> =
        nodes.values.sortedByDescending { it.weight }.take(n)

    fun isEmpty(): Boolean = nodes.isEmpty()
    fun clear() { nodes.clear(); edges.clear(); publishSnapshot() }
}
