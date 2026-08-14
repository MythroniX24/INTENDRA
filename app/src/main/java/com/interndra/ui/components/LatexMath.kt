package com.interndra.ui.components

/**
 * LatexMath — a lightweight, offline LaTeX → Unicode converter.
 *
 * It is NOT a full TeX renderer. It maps the common symbols INTENDRA sees in
 * AI answers (fractions, roots, powers, subscripts, Greek letters, calculus
 * operators, matrices/vectors, sets, logic) onto their closest Unicode
 * equivalent so math renders visually instead of as raw `\frac{...}` text.
 *
 * Malformed or unknown LaTeX is returned mostly unchanged — it must never
 * crash the chat.
 */
object LatexMath {

    private val commands = linkedMapOf(
        "\\infty" to "∞", "\\pi" to "π", "\\theta" to "θ", "\\lambda" to "λ",
        "\\mu" to "μ", "\\sigma" to "σ", "\\Sigma" to "Σ", "\\alpha" to "α",
        "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\Delta" to "Δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\phi" to "φ", "\\varphi" to "φ",
        "\\omega" to "ω", "\\Omega" to "Ω", "\\rho" to "ρ", "\\tau" to "τ",
        "\\eta" to "η", "\\xi" to "ξ", "\\zeta" to "ζ", "\\psi" to "ψ", "\\Psi" to "Ψ",
        "\\chi" to "χ", "\\kappa" to "κ", "\\nu" to "ν", "\\iota" to "ι",
        "\\int" to "∫", "\\iint" to "∬", "\\oint" to "∮", "\\sum" to "∑",
        "\\prod" to "∏", "\\lim" to "lim", "\\liminf" to "lim inf",
        "\\limsup" to "lim sup", "\\nabla" to "∇", "\\partial" to "∂",
        "\\times" to "×", "\\cdot" to "·", "\\pm" to "±", "\\mp" to "∓",
        "\\div" to "÷", "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
        "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
        "\\propto" to "∝", "\\sim" to "∼", "\\simeq" to "≃", "\\ll" to "≪",
        "\\gg" to "≫", "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←",
        "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\Leftrightarrow" to "⇔",
        "\\mapsto" to "↦", "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂",
        "\\subseteq" to "⊆", "\\supset" to "⊃", "\\supseteq" to "⊇",
        "\\cup" to "∪", "\\cap" to "∩", "\\emptyset" to "∅", "\\varnothing" to "∅",
        "\\forall" to "∀", "\\exists" to "∃", "\\neg" to "¬", "\\land" to "∧",
        "\\lor" to "∨", "\\wedge" to "∧", "\\vee" to "∨", "\\oplus" to "⊕",
        "\\otimes" to "⊗", "\\perp" to "⊥", "\\parallel" to "∥", "\\angle" to "∠",
        "\\degree" to "°", "\\circ" to "∘", "\\therefore" to "∴", "\\because" to "∵",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\dots" to "…", "\\quad" to "  ",
        "\\qquad" to "    ", "\\," to " ", "\\;" to " ", "\\!" to "", "\\ " to " ",
        "\\% " to "%", "\\langle" to "⟨", "\\rangle" to "⟩", "\\lfloor" to "⌊",
        "\\rfloor" to "⌋", "\\lceil" to "⌈", "\\rceil" to "⌉", "\\hbar" to "ℏ",
        "\\Re" to "ℜ", "\\Im" to "ℑ", "\\aleph" to "ℵ", "\\prime" to "′",
        "\\star" to "⋆", "\\ast" to "∗", "\\bullet" to "•", "\\dagger" to "†",
        "\\ddagger" to "‡", "\\checkmark" to "✓", "\\pm " to "±",
        "\\surd" to "√", "\\bar" to "", "\\hat" to "", "\\vec" to "→",
        "\\text{" to "text{", "\\mathrm{" to "", "\\mathbf{" to "", "\\mathit{" to ""
    )

    private val superscripts = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'i' to 'ⁱ', 'n' to 'ⁿ'
    )

    private val subscripts = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'i' to 'ᵢ', 'j' to 'ⱼ', 'n' to 'ₙ', 'x' to 'ₓ'
    )

    /** Convert a LaTeX expression to the closest readable Unicode. */
    fun toUnicode(latex: String): String {
        if (latex.isBlank()) return latex
        var s = latex.trim()
        s = s.replace("\\left", "").replace("\\right", "")
        s = s.replace("\\displaystyle", "").replace("\\textstyle", "")
        s = s.replace("\\dfrac", "\\frac").replace("\\tfrac", "\\frac")
        s = s.replace("\\begin{pmatrix}", "(").replace("\\end{pmatrix}", ")")
            .replace("\\begin{bmatrix}", "[").replace("\\end{bmatrix}", "]")
            .replace("\\begin{matrix}", "").replace("\\end{matrix}", "")
            .replace("\\begin{cases}", "{").replace("\\end{cases}", "")
            .replace("\\\\", " ; ")
        s = s.replace("{", "⦃").replace("}", "⦄") // temporary placeholders

        // \frac{a}{b} → (a/b)
        s = Regex("\\\\frac\\s*⦃([^⦄]*)⦄\\s*⦃([^⦄]*)⦄").replace(s) { m ->
            "(${m.groupValues[1]}/${m.groupValues[2]})"
        }

        // \sqrt[n]{x} and \sqrt{x}
        s = Regex("\\\\sqrt\\s*\\[([^\\]]*)]\\s*⦃([^⦄]*)⦄").replace(s) { m ->
            "√[${m.groupValues[1]}](${m.groupValues[2]})"
        }
        s = Regex("\\\\sqrt\\s*⦃([^⦄]*)⦄").replace(s) { m -> "√(${m.groupValues[1]})" }
        s = s.replace("\\sqrt", "√")

        // \text{...} → literal
        s = Regex("\\\\text\\s*⦃([^⦄]*)⦄").replace(s) { m -> m.groupValues[1] }

        // Superscripts: ^{...} and ^x
        s = Regex("\\^\\s*⦃([^⦄]*)⦄").replace(s) { m -> toSuperscript(m.groupValues[1]) }
        s = Regex("\\^([A-Za-z0-9+\\-=()])").replace(s) { m -> superscripts[m.groupValues[1][0]]?.toString() ?: m.value }

        // Subscripts: _{...} and _x
        s = Regex("_\\s*⦃([^⦄]*)⦄").replace(s) { m -> toSubscript(m.groupValues[1]) }
        s = Regex("_([A-Za-z0-9+\\-=()])").replace(s) { m -> subscripts[m.groupValues[1][0]]?.toString() ?: m.value }

        // \vec{v}, \hat{x}, \bar{y} → accented
        s = Regex("\\\\vec\\s*⦃([^⦄]*)⦄").replace(s) { m -> m.groupValues[1] + "⃗" }
        s = Regex("\\\\vec\\s*([A-Za-z])").replace(s) { m -> m.groupValues[1] + "⃗" }
        s = Regex("\\\\hat\\s*⦃([^⦄]*)⦄").replace(s) { m -> m.groupValues[1] + "̂" }
        s = Regex("\\\\hat\\s*([A-Za-z])").replace(s) { m -> m.groupValues[1] + "̂" }
        s = Regex("\\\\bar\\s*⦃([^⦄]*)⦄").replace(s) { m -> m.groupValues[1] + "̄" }

        // Replace remaining named commands
        for ((cmd, uni) in commands) {
            s = s.replace(cmd, uni)
        }

        // Restore braces (any placeholders that survived are literal braces)
        s = s.replace("⦃", "{").replace("⦄", "}")
        // Remove leftover command backslashes for unknown commands
        s = s.replace("\\", "").trim()
        return s.ifBlank { latex }
    }

    private fun toSuperscript(t: String): String =
        t.map { superscripts[it] ?: it }.joinToString("")

    private fun toSubscript(t: String): String =
        t.map { subscripts[it] ?: it }.joinToString("")
}
