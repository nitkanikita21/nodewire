package dev.nitka.nodewire.script.lexer

import dev.nitka.nodewire.ui.render.HlKind
import dev.nitka.nodewire.ui.render.HlSpan

/**
 * A **non-compiling**, client-safe, single-pass tokenizer that classifies script
 * source into coloured [HlSpan]s for the in-world editor. Carries no dependency on
 * the Kotlin compiler (or the optional `:scripting` addon), so it runs on any client.
 *
 * It is intentionally lighter than a real Kotlin lexer — it recognizes just enough
 * to colour keywords, the script DSL words, string/number literals and line
 * comments. Highlighting is **unconditional**: unlike [HeaderLexer] there is no
 * brace/paren gating, every token is classified wherever it appears.
 *
 * Reuses [HeaderLexer]'s scanning idioms (`isIdentStart`/`isIdentPart`,
 * `boundaryBefore`, escape-skip in string scan). Never throws.
 */
object ScriptHighlighter {

    private val KEYWORDS: Set<String> = setOf(
        "val", "var", "if", "else", "for", "while", "return",
        "fun", "when", "is", "in", "true", "false", "null",
    )

    private val DSL: Set<String> = setOf(
        "input", "output", "state", "tick", "eval", "log", "chat", "Redstone",
    )

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

    /**
     * Tokenize [src] into ordered, non-overlapping [HlSpan]s. Only non-[HlKind.PLAIN]
     * tokens are emitted — the gaps between spans are implicitly plain text, which the
     * renderer paints in its default colour.
     */
    fun highlight(src: String): List<HlSpan> {
        val spans = ArrayList<HlSpan>()
        var i = 0
        val n = src.length

        // word boundary before position p (start of source counts as a boundary).
        fun boundaryBefore(p: Int): Boolean = p == 0 || !isIdentPart(src[p - 1])

        while (i < n) {
            val c = src[i]

            // --- line comment: swallows the rest of the line ---
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                val start = i
                i += 2
                while (i < n && src[i] != '\n') i++
                spans += HlSpan(start, i, HlKind.COMMENT)
                continue
            }

            // --- string literal (incl. escapes) ---
            if (c == '"') {
                val start = i
                i++
                while (i < n && src[i] != '"') {
                    if (src[i] == '\\') i++ // skip escaped char
                    i++
                }
                if (i < n) i++ // closing quote
                spans += HlSpan(start, i, HlKind.STRING)
                continue
            }

            // --- number literal ---
            if (c.isDigit()) {
                val start = i
                i++
                while (i < n && (src[i].isLetterOrDigit() || src[i] == '.' || src[i] == '_')) i++
                spans += HlSpan(start, i, HlKind.NUMBER)
                continue
            }

            // --- identifier: keyword / DSL / plain ---
            if (isIdentStart(c) && boundaryBefore(i)) {
                val start = i
                var j = i + 1
                while (j < n && isIdentPart(src[j])) j++
                val word = src.substring(start, j)
                val kind = when (word) {
                    in KEYWORDS -> HlKind.KEYWORD
                    in DSL -> HlKind.DSL
                    else -> null
                }
                if (kind != null) spans += HlSpan(start, j, kind)
                i = j
                continue
            }

            i++
        }

        return spans
    }
}
