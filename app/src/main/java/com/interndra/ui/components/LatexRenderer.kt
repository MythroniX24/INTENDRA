package com.interndra.ui.components

/**
 * Zero-dependency LaTeX → Unicode renderer for chat math.
 *
 * Handles the constructs AI replies actually use: fractions (incl. nested),
 * square roots, super/subscripts, Greek letters, operators, vectors/accents
 * (\vec, \hat, \bar, \dot…), limits, binomials, matrices (pmatrix, bmatrix,
 * vmatrix, Bmatrix, cases) and aligned environments, \text{}/\mathrm{} and
 * common function names (sin, cos, lim, log…).
 *
 * Anything unrecognized is left untouched — malformed LaTeX never crashes,
 * it simply renders literally. Runs fully offline, no dependencies.
 */
object LatexRenderer {

    private val symbols = mapOf(
        "\\times" to "×", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
        "\\cdot" to "·", "\\ast" to "∗", "\\circ" to "∘",
        "\\le" to "≤", "\\ge" to "≥", "\\neq" to "≠", "\\ne" to "≠",
        "\\approx" to "≈", "\\equiv" to "≡", "\\propto" to "∝",
        "\\sim" to "∼", "\\simeq" to "≃", "\\cong" to "≅", "\\approxeq" to "≊",
        "\\ll" to "≪", "\\gg" to "≫", "\\prec" to "≺", "\\succ" to "≻",
        "\\preceq" to "⪯", "\\succeq" to "⪰",
        "\\infty" to "∞", "\\hbar" to "ℏ", "\\ell" to "ℓ", "\\imath" to "ı",
        "\\jmath" to "ȷ", "\\Re" to "ℜ", "\\Im" to "ℑ", "\\wp" to "℘",
        "\\aleph" to "ℵ", "\\angle" to "∠", "\\triangle" to "△", "\\square" to "□",
        "\\perp" to "⊥", "\\parallel" to "∥", "\\mid" to "∣", "\\land" to "∧",
        "\\lor" to "∨", "\\neg" to "¬", "\\oplus" to "⊕", "\\otimes" to "⊗",
        "\\degree" to "°", "\\nabla" to "∇", "\\partial" to "∂",
        "\\rightarrow" to "→", "\\leftarrow" to "←", "\\leftrightarrow" to "↔",
        "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\Leftrightarrow" to "⇔",
        "\\mapsto" to "↦", "\\to" to "→", "\\gets" to "←", "\\uparrow" to "↑",
        "\\downarrow" to "↓", "\\updownarrow" to "↕", "\\nearrow" to "↗",
        "\\searrow" to "↘", "\\longrightarrow" to "⟶", "\\Longrightarrow" to "⟹",
        "\\in" to "∈", "\\notin" to "∉", "\\ni" to "∋",
        "\\subset" to "⊂", "\\supset" to "⊃", "\\subseteq" to "⊆", "\\supseteq" to "⊇",
        "\\cup" to "∪", "\\cap" to "∩", "\\setminus" to "∖",
        "\\forall" to "∀", "\\exists" to "∃", "\\nexists" to "∄",
        "\\emptyset" to "∅", "\\varnothing" to "∅",
        "\\sum" to "∑", "\\prod" to "∏", "\\coprod" to "∐",
        "\\int" to "∫", "\\iint" to "∬", "\\iiint" to "∭", "\\oint" to "∮",
        "\\bigcup" to "⋃", "\\bigcap" to "⋂", "\\bigoplus" to "⊕", "\\bigotimes" to "⊗",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
        "\\omicron" to "ο", "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ",
        "\\varrho" to "ϱ", "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ",
        "\\upsilon" to "υ", "\\phi" to "φ", "\\varphi" to "φ", "\\chi" to "χ",
        "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
        "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\dots" to "…", "\\ldots" to "…", "\\cdots" to "⋯", "\\vdots" to "⋮",
        "\\ddots" to "⋱"
    )

    private val funcNames = listOf(
        "lim", "log", "ln", "lg", "exp", "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "det", "dim", "ker",
        "min", "max", "sup", "inf", "arg", "deg", "mod", "gcd", "lcm", "Pr", "erf"
    )

    private val envRe = Regex("""\\begin\{([a-zA-Z*]+)\}(.*?)\\end\{\1\}""", RegexOption.DOT_MATCHES_ALL)

    fun toUnicode(latex: String): String {
        if (latex.isBlank()) return latex
        var s = latex
            // Strip \left/\right delimiters ONLY in their paired forms so
            // commands like \leftarrow / \leftrightarrow survive.
            .replace("\\left(", "(").replace("\\right)", ")")
            .replace("\\left[", "[").replace("\\right]", "]")
            .replace("\\left\\{", "{").replace("\\right\\}", "}")
            .replace("\\left\\|", "‖").replace("\\right\\|", "‖")
            .replace("\\left\\langle", "⟨").replace("\\right\\rangle", "⟩")
            .replace("\\left.", "").replace("\\right.", "")
            .replace("\\,", " ").replace("\\;", " ").replace("\\:", " ").replace("\\!", "")
            .replace("\\ ", " ")
            // Longest match first: \qquad must be replaced before \quad,
            // otherwise \qquad degrades to "  q".
            .replace("\\qquad", "    ").replace("\\quad", "  ").replace("\\enspace", "  ")

        // \text{...} / \mathrm{...} / \mathbf{...} / \operatorname{...}
        s = s.replace(Regex("""\\(text|mathrm|mathbf|mathit|operatorname)\{([^{}]*)\}""")) { m ->
            m.groupValues[2]
        }
        s = s.replace(Regex("""\\(text|mathrm|mathbf|mathit|operatorname)\b"""), "")

        // Matrices / cases / aligned environments (before global \\ handling)
        s = convertEnvs(s)

        // Row separators & alignment outside environments
        s = s.replace("\\\\", "\n").replace("&", "  ")

        // Vectors & accents: \vec{v} → v⃗, \hat{x} → x̂ …
        s = s.replace(Regex("""\\(vec|hat|bar|dot|ddot|tilde|widehat|overline|underline)\{([^{}]*)\}""")) { m ->
            val accent = when (m.groupValues[1]) {
                "vec" -> "\u20D7"; "hat" -> "\u0302"; "bar" -> "\u0304"
                "dot" -> "\u0307"; "ddot" -> "\u0308"; "tilde" -> "\u0303"
                "widehat" -> "\u0302"; "overline" -> "\u0305"; else -> ""
            }
            m.groupValues[2] + accent
        }

        // Binomial: \binom{n}{k}
        s = s.replace(Regex("""\\binom\{([^{}]*)\}\{([^{}]*)\}""")) { m ->
            "⎛${m.groupValues[1].trim()}⎞\n⎝${m.groupValues[2].trim()}⎠"
        }

        // Limits: \lim_{x \to 0} → lim (x → 0)
        s = s.replace(Regex("""\\lim_\{(.*?)\}""")) { m ->
            "lim (${m.groupValues[1].trim()})"
        }
        // \underset{below}{main} / \overset{above}{main}
        s = s.replace(Regex("""\\underset\{([^{}]*)\}\{([^{}]*)\}""")) { m ->
            "${m.groupValues[2].trim()}₍${m.groupValues[1].trim()}₎"
        }
        s = s.replace(Regex("""\\overset\{([^{}]*)\}\{([^{}]*)\}""")) { m ->
            "${m.groupValues[2].trim()}⁽${m.groupValues[1].trim()}⁾"
        }

        // Function names stay upright: \sin x → sin x
        s = s.replace(Regex("""\\(${funcNames.joinToString("|")})(?![a-zA-Z])""")) { m ->
            m.groupValues[1]
        }

        // Fractions with one level of nesting, repeatedly until stable
        s = convertFracs(s)

        // Square root
        s = s.replace(Regex("""\\sqrt\{([^{}]*)\}""")) { m -> "√(${m.groupValues[1]})" }

        for ((k, v) in symbols) s = s.replace(k, v)

        s = s.replace("\\{", "{").replace("\\}", "}")

        // Superscripts: ^{...} groups first, then ^x single chars
        s = s.replace(Regex("""\^\{([^{}]*)\}""")) { m -> superscript(m.groupValues[1]) }
        s = s.replace(Regex("""\^([0-9a-zA-Z+\-()])""")) { m -> superscript(m.groupValues[1]) }
        // Subscripts: _{...} groups first, then _x single chars
        s = s.replace(Regex("""_\{([^{}]*)\}""")) { m -> subscript(m.groupValues[1]) }
        s = s.replace(Regex("""_([0-9a-zA-Z+\-()])""")) { m -> subscript(m.groupValues[1]) }

        return s.trim()
    }

    private fun convertEnvs(s: String): String {
        var out = s
        var guard = 0
        while (guard++ < 30) {
            // Replace the innermost environment first (no nested \begin inside)
            val target = envRe.findAll(out)
                .firstOrNull { !it.groupValues[2].contains("\\begin{") } ?: break
            out = out.substring(0, target.range.first) +
                renderEnv(target.groupValues[1], target.groupValues[2]) +
                out.substring(target.range.last + 1)
        }
        return out
    }

    private fun renderEnv(env: String, content: String): String {
        val rows = content.split("\\\\").map { row -> row.split("&").map { it.trim() } }
        val eff = if (rows.lastOrNull()?.all { it.isBlank() } == true) rows.dropLast(1) else rows
        if (eff.isEmpty()) return content.trim()
        val colCount = eff.maxOfOrNull { it.size } ?: 0
        if (colCount == 0) return content.trim()
        val widths = IntArray(colCount) { c ->
            eff.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0
        }
        fun cells(row: List<String>): List<String> =
            (row + List(colCount - row.size) { "" }).mapIndexed { c, cell -> cell.padEnd(widths[c]) }
        fun line(row: List<String>, l: String, r: String): String =
            "$l ${cells(row).joinToString("  ")} $r"

        return when (env.removeSuffix("*")) {
            "pmatrix" -> eff.mapIndexed { i, r ->
                when {
                    eff.size == 1 -> line(r, "⎛", "⎞")
                    i == 0 -> line(r, "⎛", "⎞")
                    i == eff.size - 1 -> line(r, "⎝", "⎠")
                    else -> line(r, "⎜", "⎟")
                }
            }.joinToString("\n")
            "bmatrix" -> eff.mapIndexed { i, r ->
                when {
                    eff.size == 1 -> line(r, "⎡", "⎤")
                    i == 0 -> line(r, "⎡", "⎤")
                    i == eff.size - 1 -> line(r, "⎣", "⎦")
                    else -> line(r, "⎢", "⎥")
                }
            }.joinToString("\n")
            "vmatrix" -> eff.mapIndexed { i, r ->
                when {
                    eff.size == 1 -> line(r, "|", "|")
                    i == 0 -> line(r, "┌", "┐")
                    i == eff.size - 1 -> line(r, "└", "┘")
                    else -> line(r, "│", "│")
                }
            }.joinToString("\n")
            "Bmatrix" -> eff.mapIndexed { i, r ->
                when {
                    eff.size == 1 -> line(r, "⎧", "⎫")
                    i == 0 -> line(r, "⎧", "⎫")
                    i == eff.size - 1 -> line(r, "⎩", "⎭")
                    else -> line(r, "⎨", "⎬")
                }
            }.joinToString("\n")
            "cases" -> eff.mapIndexed { i, r ->
                val brace = when {
                    eff.size == 1 -> "⎧"
                    i == 0 -> "⎧"
                    i == eff.size - 1 -> "⎩"
                    else -> "⎨"
                }
                "$brace ${cells(r).joinToString("  ")}"
            }.joinToString("\n")
            "matrix", "aligned", "align", "split", "gathered", "array" ->
                eff.map { cells(it).joinToString("  ") }.joinToString("\n")
            else -> content.trim()
        }
    }

    private fun convertFracs(s: String): String {
        var out = s
        val re = Regex("""\\frac\{((?:[^{}]|\{[^{}]*\})*)\}\{((?:[^{}]|\{[^{}]*\})*)\}""")
        var guard = 0
        while (guard++ < 8) {
            val next = re.replace(out) { m -> "(${m.groupValues[1]})/(${m.groupValues[2]})" }
            if (next == out) break
            out = next
        }
        return out
    }

    private fun superscript(chars: String): String = buildString {
        for (c in chars) {
            when (c) {
                '0'->append('⁰'); '1'->append('¹'); '2'->append('²'); '3'->append('³')
                '4'->append('⁴'); '5'->append('⁵'); '6'->append('⁶'); '7'->append('⁷')
                '8'->append('⁸'); '9'->append('⁹'); '+'->append('⁺'); '-'->append('⁻')
                '('->append('⁽'); ')'->append('⁾'); '='->append('⁼'); 'n'->append('ⁿ')
                'a'->append('ᵃ'); 'b'->append('ᵇ'); 'c'->append('ᶜ'); 'd'->append('ᵈ')
                'e'->append('ᵉ'); 'f'->append('ᶠ'); 'g'->append('ᵍ'); 'h'->append('ʰ')
                'i'->append('ⁱ'); 'j'->append('ʲ'); 'k'->append('ᵏ'); 'l'->append('ˡ')
                'm'->append('ᵐ'); 'o'->append('ᵒ'); 'p'->append('ᵖ'); 'r'->append('ʳ')
                's'->append('ˢ'); 't'->append('ᵗ'); 'u'->append('ᵘ'); 'v'->append('ᵛ')
                'w'->append('ʷ'); 'x'->append('ˣ'); 'y'->append('ʸ'); 'z'->append('ᶻ')
                else -> append(c)
            }
        }
    }

    private fun subscript(chars: String): String = buildString {
        for (c in chars) {
            when (c) {
                '0'->append('₀'); '1'->append('₁'); '2'->append('₂'); '3'->append('₃')
                '4'->append('₄'); '5'->append('₅'); '6'->append('₆'); '7'->append('₇')
                '8'->append('₈'); '9'->append('₉'); '+'->append('₊'); '-'->append('₋')
                '('->append('₍'); ')'->append('₎'); '='->append('₌')
                'a'->append('ₐ'); 'e'->append('ₑ'); 'h'->append('ₕ'); 'i'->append('ᵢ')
                'j'->append('ⱼ'); 'k'->append('ₖ'); 'l'->append('ₗ'); 'm'->append('ₘ')
                'n'->append('ₙ'); 'o'->append('ₒ'); 'p'->append('ₚ'); 'r'->append('ᵣ')
                's'->append('ₛ'); 't'->append('ₜ'); 'u'->append('ᵤ'); 'v'->append('ᵥ')
                'x'->append('ₓ')
                else -> append(c)
            }
        }
    }
}
