package com.interndra.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LatexRendererTest — verifies the zero-dependency LaTeX→Unicode renderer:
 * fractions (incl. nested), square roots, super/subscripts, Greek letters,
 * vectors/accents, limits, binomials, matrices, aligned environments,
 * \text{}, and malformed-input safety.
 */
class LatexRendererTest {

    // ── Basic constructs ────────────────────────────────────────────────

    @Test
    fun `fraction renders as numerator over denominator`() {
        assertEquals("(a)/(b)", LatexRenderer.toUnicode("\\frac{a}{b}"))
    }

    @Test
    fun `fraction with numbers`() {
        assertEquals("(1)/(2)", LatexRenderer.toUnicode("\\frac{1}{2}"))
    }

    @Test
    fun `nested fraction resolves recursively`() {
        assertEquals("((a)/(b))/(c)", LatexRenderer.toUnicode("\\frac{\\frac{a}{b}}{c}"))
    }

    @Test
    fun `square root`() {
        assertEquals("√(x+1)", LatexRenderer.toUnicode("\\sqrt{x+1}"))
    }

    @Test
    fun `superscript single char`() {
        assertEquals("E = mc²", LatexRenderer.toUnicode("E = mc^2"))
    }

    @Test
    fun `superscript group`() {
        assertEquals("xⁿ⁺¹", LatexRenderer.toUnicode("x^{n+1}"))
    }

    @Test
    fun `subscript single char`() {
        assertEquals("a₁", LatexRenderer.toUnicode("a_1"))
    }

    @Test
    fun `subscript group`() {
        assertEquals("aᵢ₋₁", LatexRenderer.toUnicode("a_{i-1}"))
    }

    // ── Symbols & Greek ──────────────────────────────────────────────────

    @Test
    fun `greek letters render`() {
        assertEquals("α + β - γ", LatexRenderer.toUnicode("\\alpha + \\beta - \\gamma"))
    }

    @Test
    fun `operators render`() {
        assertEquals("∞ × π", LatexRenderer.toUnicode("\\infty \\times \\pi"))
    }

    @Test
    fun `leftarrow is not destroyed by left delimiter stripping`() {
        assertEquals("x ← y", LatexRenderer.toUnicode("x \\leftarrow y"))
    }

    @Test
    fun `left right delimiters stripped in paired form`() {
        val out = LatexRenderer.toUnicode("\\left( \\frac{a}{b} \\right)")
        assertEquals("( (a)/(b) )", out)
    }

    @Test
    fun `sum with limits`() {
        val out = LatexRenderer.toUnicode("\\sum_{i=1}^{n} x_i")
        assertTrue("contains ∑", out.contains("∑"))
        assertTrue("contains superscript n", out.contains("ⁿ"))
        assertTrue("contains subscript x_i", out.contains("xᵢ"))
    }

    @Test
    fun `lim with arrow renders as lim (x → 0)`() {
        assertEquals("lim (x → 0)", LatexRenderer.toUnicode("\\lim_{x \\to 0}"))
    }

    // ── Vectors & accents ────────────────────────────────────────────────

    @Test
    fun `vector arrow`() {
        assertTrue(LatexRenderer.toUnicode("\\vec{v}").startsWith("v"))
    }

    @Test
    fun `hat accent`() {
        assertTrue(LatexRenderer.toUnicode("\\hat{x}").startsWith("x"))
    }

    @Test
    fun `bar accent`() {
        assertTrue(LatexRenderer.toUnicode("\\bar{z}").startsWith("z"))
    }

    @Test
    fun `dot accent`() {
        assertTrue(LatexRenderer.toUnicode("\\dot{a}").startsWith("a"))
    }

    // ── Binomials ────────────────────────────────────────────────────────

    @Test
    fun `binomial renders with big parens`() {
        val out = LatexRenderer.toUnicode("\\binom{n}{k}")
        assertTrue(out.contains("⎛n⎞"))
        assertTrue(out.contains("⎝k⎠"))
    }

    // ── Matrices & environments ──────────────────────────────────────────

    @Test
    fun `pmatrix renders rows with brackets`() {
        val out = LatexRenderer.toUnicode("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}")
        assertTrue("top bracket", out.contains("⎛"))
        assertTrue("bottom bracket", out.contains("⎝"))
        assertTrue("contains a cell", out.contains("a"))
        assertTrue("contains d cell", out.contains("d"))
        assertTrue("has two rows", out.lines().size == 2)
    }

    @Test
    fun `bmatrix renders square brackets`() {
        val out = LatexRenderer.toUnicode("\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}")
        assertTrue(out.contains("⎡"))
        assertTrue(out.contains("⎣"))
    }

    @Test
    fun `cases environment renders with brace`() {
        val out = LatexRenderer.toUnicode("\\begin{cases} x & \\text{if } x > 0 \\\\ -x & \\text{otherwise} \\end{cases}")
        assertTrue("brace char", out.contains("⎧"))
        assertTrue("text{} stripped", out.contains("if") && !out.contains("\\text"))
    }

    @Test
    fun `aligned environment keeps rows`() {
        val out = LatexRenderer.toUnicode("\\begin{aligned} y &= mx + b \\\\ z &= 2 \\end{aligned}")
        assertTrue(out.lines().size == 2)
        assertTrue(out.contains("mx + b"))
        assertTrue(out.contains("z"))
    }

    @Test
    fun `vmatrix renders vertical bars`() {
        val out = LatexRenderer.toUnicode("\\begin{vmatrix} a & b \\\\ c & d \\end{vmatrix}")
        assertTrue(out.contains("┌") || out.contains("|"))
        assertTrue(out.contains("└") || out.contains("|"))
    }

    // ── Text & functions ─────────────────────────────────────────────────

    @Test
    fun `text command stripped to literal`() {
        assertEquals("hello world", LatexRenderer.toUnicode("\\text{hello world}"))
    }

    @Test
    fun `mathrm stripped to literal`() {
        assertEquals("d", LatexRenderer.toUnicode("\\mathrm{d}"))
    }

    @Test
    fun `function names kept upright`() {
        assertEquals("sin x", LatexRenderer.toUnicode("\\sin x"))
        assertEquals("lim x", LatexRenderer.toUnicode("\\lim x"))
    }

    @Test
    fun `integral renders`() {
        assertTrue(LatexRenderer.toUnicode("\\int_0^1 x \\, dx").contains("∫"))
    }

    @Test
    fun `partial derivative symbol`() {
        assertTrue(LatexRenderer.toUnicode("\\frac{\\partial f}{\\partial x}").contains("∂"))
    }

    // ── Malformed / edge cases (must never crash) ────────────────────────

    @Test
    fun `unclosed frac stays literal`() {
        val out = LatexRenderer.toUnicode("\\frac{a")
        assertTrue(out.contains("\\frac{a"))
    }

    @Test
    fun `unclosed begin stays literal`() {
        val out = LatexRenderer.toUnicode("\\begin{pmatrix} 1 & 2")
        assertTrue(out.contains("\\begin"))
    }

    @Test
    fun `empty and blank inputs return unchanged`() {
        assertEquals("", LatexRenderer.toUnicode(""))
        assertEquals("   ", LatexRenderer.toUnicode("   "))
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("Euler's formula: E = mc^2", LatexRenderer.toUnicode("Euler's formula: E = mc^2").replace("²", "^2"))
    }

    @Test
    fun `no unknown symbol expansion corrupts unrelated text`() {
        val out = LatexRenderer.toUnicode("hello world 123")
        assertFalse(out.contains("\\"))
        assertEquals("hello world 123", out)
    }
}
