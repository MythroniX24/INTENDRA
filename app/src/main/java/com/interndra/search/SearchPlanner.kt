package com.interndra.search

import java.util.Locale

/**
 * SearchPlanner — the autonomous decision engine.
 *
 * For every incoming user message it decides:
 *  - whether a web search is required at all,
 *  - how confident it is (0..1),
 *  - what the optimized query/queries should be,
 *  - whether full webpages need to be read,
 *  - which providers to prefer (given what's configured).
 *
 * The model NEVER asks the user "do you want me to search?" — this planner
 * decides silently. It is intentionally keyword/signal-based (fast, no extra
 * LLM round-trip) and conservative about spending API calls.
 */
class SearchPlanner {

    /**
     * Signals that strongly imply the user wants FRESH, time-sensitive info
     * that the model's static knowledge cannot reliably answer.
     */
    private val FRESHNESS_SIGNALS = listOf(
        "latest", "newest", "recent", "breaking", "today", "yesterday",
        "this week", "this month", "news", "headlines", "current", "live",
        "update", "updated", "release", "released", "new version", "version of",
        "changelog", "what's new", "whats new", "weather", "forecast",
        "temperature", "stock", "share price", "price of", "crypto", "bitcoin",
        "ethereum", "price today", "rate", "results", "score", "winner",
        "election", "vote", "announced", "launched", "launch", "beta",
        "download now", "how much does", "how much is", "install guide for",
        "docs for", "api for", "error ", "crash", "not working", "fixed in",
        "patch", "hotfix", "deadline", "schedule", "schedule of", "fixture"
    )

    /** Entity-lookup signals — "who/what/where/when is X" or named facts. */
    private val ENTITY_SIGNALS = listOf(
        "who is", "who are", "what is", "what are", "where is", "where are",
        "when was", "when did", "when is", "tell me about", "facts about",
        "history of", "biography of", "capital of", "population of",
        "founded", "founder of", "ceo of", "president of", "prime minister of",
        "mayor of", "facts about", "how old is", "how big is", "how far",
        "how many people", "highest", "tallest", "largest", "smallest",
        "first ", "oldest", "newest ", "wikipedia", "officially", "meaning of",
        "definition of", "difference between", "vs ", " vs", "compare",
        "best ", "top 10", "top 5", "ranked", "ranking", "list of"
    )

    /** Tech/documentation signals — the user wants external, current technical info. */
    private val TECH_SIGNALS = listOf(
        "how to install", "how to use", "how do i", "tutorial", "guide",
        "documentation", "docs", "api reference", "reference",
        "github", "repository", "repo", "npm", "pip install", "maven",
        "gradle", "dependency", "library", "framework", "sdk", "sdk version",
        "android studio", "androidx", "jetpack compose", "kotlin", "releases",
        "release notes", "supported", "compatibility", "requirements",
        "download link", "official website", "official site", "setup",
        "config", "configure", "integration", "migration", "upgrade",
        "deprecated", "end of life", "eol", "roadmap", "status", "status page",
        "registry", "pypi", "crates.io", "maven central", "changelog"
    )

    /** Explicit "go look this up" phrases — unambiguous search intent. */
    private val EXPLICIT_SEARCH_SIGNALS = listOf(
        "search the web", "search for", "search online", "look up", "google it",
        "google search", "find out", "find information", "find info",
        "check online", "web search", "research this", "research about",
        "lookup", "find the latest", "verify", "fact check", "is this true",
        "is that true", "confirm", "validate", "prove", "debunk", "rumor",
        "current price", "live price", "real-time", "real time"
    )

    /** Domains where the model's static knowledge is fine — never search. */
    private val NO_SEARCH_SIGNALS = listOf(
        // Coding help (model knows it)
        "write code", "write a function", "implement", "refactor", "debug this",
        "fix my code", "what's wrong with my code", "explain this code",
        "generate a script", "create a python", "make an app",
        // Math & logic (offline reasoning)
        "solve", "calculate", "equation", "what is 2+2", "derivative of",
        "integral of", "prove that", "sum of", "math", "mathematics",
        // Conversation / local device context
        "my phone", "my device", "my battery", "my storage", "my files",
        "my downloads", "my whatsapp", "my messages", "open app", "launch app",
        "settings", "turn on wifi", "turn off bluetooth", "set alarm",
        "take screenshot", "show me my", "open camera", "close app",
        // Local project / terminal context
        "terminal", "termux", "shizuku", "adb", "run this command",
        "execute this", "install app", "uninstall", "check battery",
        "battery status", "storage space", "ram usage", "cpu usage",
        "network info", "wifi info", "device info", "model number",
        "android version of my device", "list files", "ls ", "cd ",
        // Writing & creativity (model is great at these)
        "write a story", "write an essay", "write a poem", "write an email",
        "draft", "translate", "translation", "summarize my", "rewrite",
        // General knowledge / explanations the model already handles
        "explain", "what does", "how does", "why is", "why do", "what is a",
        "define", "meaning", "synonym", "antonym", "grammar", "spelling",
        // Hard sciences — static model knowledge per spec (never search)
        "physics", "chemistry", "biology", "quantum mechanics", "relativity",
        "photosynthesis", "cell division", "chemical reaction", "newton's law",
        "osmosis", "mitosis", "meiosis", "electromagnetic", "thermodynamics",
        "anatomy", "physiology", "genetics", "evolution"
    )

    /**
     * Decide whether/how to search for [input].
     *
     * @param settings snapshot of provider settings (whether search is enabled,
     *        which providers have keys). The planner never hardcodes the choice —
     *        it reads the runtime configuration.
     */
    fun plan(
        input: String,
        searchEnabled: Boolean = true,
        braveEnabled: Boolean = true,
        braveKeyConfigured: Boolean = false,
        geminiKeyConfigured: Boolean = false,
        preferBrave: Boolean = false
    ): SearchPlan {
        if (!searchEnabled) return SearchPlan.none("search disabled in settings")
        val text = input.trim()
        if (text.length < 4) return SearchPlan.none("input too short")
        if (text.length > 600) return SearchPlan.none("input too long")

        val lower = text.lowercase(Locale.ROOT)

        // ── 1. Explicit search requests: always search ────────────────────
        if (EXPLICIT_SEARCH_SIGNALS.any { lower.contains(it) }) {
            return buildPlan(text, lower, confidence = 0.98f,
                reason = "explicit search request",
                braveEnabled = braveEnabled, braveKeyConfigured = braveKeyConfigured,
                geminiKeyConfigured = geminiKeyConfigured, preferBrave = preferBrave)
        }

        // ── 2. Freshness-critical: news, prices, versions, live data ──────
        // Checked BEFORE the no-search filter so queries like
        // "what is the latest android version" are never suppressed.
        val freshnessHits = FRESHNESS_SIGNALS.count { lower.contains(it) }
        if (freshnessHits > 0) {
            return buildPlan(text, lower, confidence = minOf(0.9f, 0.55f + freshnessHits * 0.15f),
                reason = "freshness-critical query ($freshnessHits signals)",
                freshnessCritical = true,
                readPages = true,
                braveEnabled = braveEnabled, braveKeyConfigured = braveKeyConfigured,
                geminiKeyConfigured = geminiKeyConfigured, preferBrave = preferBrave)
        }

        // ── 3. Entity lookups & tech docs ────────────────────────────────
        val entityHits = ENTITY_SIGNALS.count { lower.contains(it) }
        val techHits = TECH_SIGNALS.count { lower.contains(it) }
        val totalHits = entityHits + techHits

        if (totalHits >= 2) {
            return buildPlan(text, lower, confidence = minOf(0.85f, 0.5f + totalHits * 0.1f),
                reason = "entity/tech lookup ($entityHits entity, $techHits tech signals)",
                readPages = techHits > 0,
                braveEnabled = braveEnabled, braveKeyConfigured = braveKeyConfigured,
                geminiKeyConfigured = geminiKeyConfigured, preferBrave = preferBrave)
        }
        if (entityHits == 1 || techHits == 1) {
            // Single weak signal: search but with modest confidence.
            return buildPlan(text, lower, confidence = 0.5f,
                reason = "single lookup signal",
                braveEnabled = braveEnabled, braveKeyConfigured = braveKeyConfigured,
                geminiKeyConfigured = geminiKeyConfigured, preferBrave = preferBrave)
        }

        // ── 4. Never-search signals: local device/code/math/conversation ──
        // Only applies when no positive signal matched above.
        // "explain how to install docker" already matched tech (step 3), so
        // the generic "explain" word here won't suppress it.
        if (NO_SEARCH_SIGNALS.any { lower.contains(it) }) {
            return SearchPlan.none("offline/local topic — model knowledge sufficient")
        }

        // ── 5. Unknown facts / low confidence topics ──────────────────────
        // A question with a proper question word we didn't classify as
        // offline is likely a factual lookup. Greetings ("hi how are you")
        // are excluded — they're not factual queries.
        val greetings = listOf("hi how are you", "how are you", "how are you doing", "what's up", "wassup")
        if (greetings.any { lower.contains(it) }) {
            return SearchPlan.none("greeting — conversation only")
        }
        val questionWords = listOf("who", "what", "where", "when", "why", "how", "which", "whose")
        if (questionWords.any { lower.startsWith("$it ") || lower.contains(" $it ") }) {
            return buildPlan(text, lower, confidence = 0.55f,
                reason = "question-word query",
                braveEnabled = braveEnabled, braveKeyConfigured = braveKeyConfigured,
                geminiKeyConfigured = geminiKeyConfigured, preferBrave = preferBrave)
        }

        return SearchPlan.none("no search signals detected")
    }

    private fun buildPlan(
        text: String,
        lower: String,
        confidence: Float,
        reason: String,
        freshnessCritical: Boolean = false,
        readPages: Boolean = false,
        braveEnabled: Boolean,
        braveKeyConfigured: Boolean,
        geminiKeyConfigured: Boolean,
        preferBrave: Boolean
    ): SearchPlan {
        val queries = optimizeQueries(text, lower, freshnessCritical)
        val providers = pickProviders(
            braveEnabled, braveKeyConfigured, geminiKeyConfigured, preferBrave
        )
        return SearchPlan(
            shouldSearch = true,
            confidence = confidence,
            reason = reason,
            queries = queries,
            readPages = readPages,
            preferredProviders = providers,
            maxResults = if (freshnessCritical) 8 else 6,
            freshnessCritical = freshnessCritical
        )
    }

    /** Build 1–2 optimized queries from the user's text. */
    private fun optimizeQueries(text: String, lower: String, freshnessCritical: Boolean): List<String> {
        val stopWords = setOf(
            "please", "can you", "could you", "i want to know", "tell me",
            "give me", "show me", "need to know", "help me", "do you know",
            "i was wondering", "im wondering", "i am wondering", "the", "a", "an"
        )
        var query = text.trim()
        stopWords.forEach { stop ->
            if (query.lowercase(Locale.ROOT).startsWith("$stop ") ||
                query.lowercase(Locale.ROOT).contains(" $stop ")
            ) {
                // Remove the phrase (case-insensitive first occurrence)
                query = query.replace(Regex("(?i)\\b" + Regex.escape(stop.trim()) + "\\b"), " ")
                    .trim()
            }
        }
        // Collapse whitespace
        query = query.replace(Regex("\\s+"), " ").trim().take(160)

        val queries = mutableListOf(query)
        // Add a compact keyword fallback for freshness queries.
        if (freshnessCritical && query.length > 40) {
            val keywords = query
                .split(Regex("[^A-Za-z0-9.]+"))
                .filter { it.length > 2 && !stopWords.contains(it.lowercase(Locale.ROOT)) }
                .joinToString(" ")
                .take(80)
            if (keywords.length >= 10) queries.add(keywords)
        }
        return queries.distinct()
    }

    /** Decide provider preference based on settings. Gemini = primary by default. */
    private fun pickProviders(
        braveEnabled: Boolean,
        braveKeyConfigured: Boolean,
        geminiKeyConfigured: Boolean,
        preferBrave: Boolean
    ): List<SearchProviderId> {
        val list = mutableListOf<SearchProviderId>()
        when {
            preferBrave && braveEnabled && braveKeyConfigured -> {
                list.add(SearchProviderId.BRAVE)
                if (geminiKeyConfigured) list.add(SearchProviderId.GEMINI)
            }
            else -> {
                if (geminiKeyConfigured) list.add(SearchProviderId.GEMINI)
                if (braveEnabled && braveKeyConfigured) list.add(SearchProviderId.BRAVE)
            }
        }
        // DuckDuckGo is always the last-resort fallback.
        list.add(SearchProviderId.DUCKDUCKGO)
        return list.distinct()
    }
}
