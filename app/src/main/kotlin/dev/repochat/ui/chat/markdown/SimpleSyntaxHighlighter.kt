package dev.repochat.ui.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Tiny regex-based highlighter for chat code blocks. Covers common Kotlin /
 * Java / JS / Python tokens well enough to read — not a full language parser.
 */
object SimpleSyntaxHighlighter {

    data class Palette(
        val plain: Color,
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val type: Color,
    )

    private val KEYWORDS = setOf(
        // Kotlin / Java
        "package", "import", "class", "interface", "object", "fun", "val", "var",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "try", "catch", "finally", "throw", "in", "is", "as", "this", "super",
        "true", "false", "null", "override", "open", "abstract", "private",
        "public", "protected", "internal", "data", "sealed", "enum", "companion",
        "suspend", "inline", "reified", "typealias", "const", "lateinit",
        "by", "where", "out", "get", "set", "init", "constructor", "new",
        "extends", "implements", "static", "final", "void", "boolean", "int",
        "long", "float", "double", "char", "byte", "short", "switch", "case",
        "default", "synchronized", "volatile", "transient", "native", "throws",
        // JS / TS
        "function", "const", "let", "export", "from", "async", "await", "typeof",
        "instanceof", "yield", "of", "debugger", "with", "delete",
        // Python
        "def", "elif", "lambda", "pass", "raise", "with", "yield", "from",
        "None", "True", "False", "and", "or", "not", "global", "nonlocal",
        "assert", "del", "except",
        // Shell-ish
        "echo", "export", "source", "fi", "then", "esac",
    )

    private val TYPE_HINT = Regex("\\b[A-Z][A-Za-z0-9_]*\\b")
    private val NUMBER = Regex("\\b\\d+(\\.\\d+)?[fFlL]?\\b")
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Match [regex] only if it starts at [index] (portable alternative to matchAt). */
    private fun matchFrom(regex: Regex, input: String, index: Int): String? {
        if (index >= input.length) return null
        val m = regex.find(input, index) ?: return null
        return if (m.range.first == index) m.value else null
    }

    fun highlight(code: String, language: String?, palette: Palette): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString("")
        val lang = language?.lowercase().orEmpty()
        // Skip fancy coloring for pure logs / plain dumps.
        if (lang in setOf("text", "plain", "log", "logs", "output")) {
            return AnnotatedString(code, SpanStyle(color = palette.plain))
        }
        return buildAnnotatedString {
            var i = 0
            while (i < code.length) {
                // Line comment //
                if (code.startsWith("//", i)) {
                    val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    withStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)) {
                        append(code.substring(i, end))
                    }
                    i = end
                    continue
                }
                // Block comment /* */
                if (code.startsWith("/*", i)) {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                    withStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)) {
                        append(code.substring(i, end))
                    }
                    i = end
                    continue
                }
                // Hash comment (python/shell) — only at line start-ish
                if (code[i] == '#' && (i == 0 || code[i - 1] == '\n' || code[i - 1].isWhitespace())) {
                    val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    withStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)) {
                        append(code.substring(i, end))
                    }
                    i = end
                    continue
                }
                // Strings "..." or '...'
                if (code[i] == '"' || code[i] == '\'') {
                    val quote = code[i]
                    var j = i + 1
                    while (j < code.length) {
                        if (code[j] == '\\' && j + 1 < code.length) {
                            j += 2
                            continue
                        }
                        if (code[j] == quote) {
                            j++
                            break
                        }
                        if (code[j] == '\n' && quote == '\'') break // avoid eating whole file
                        j++
                    }
                    withStyle(SpanStyle(color = palette.string)) {
                        append(code.substring(i, j))
                    }
                    i = j
                    continue
                }
                // Triple quotes """
                if (code.startsWith("\"\"\"", i) || code.startsWith("'''", i)) {
                    val q = code.substring(i, i + 3)
                    val end = code.indexOf(q, i + 3).let { if (it < 0) code.length else it + 3 }
                    withStyle(SpanStyle(color = palette.string)) {
                        append(code.substring(i, end))
                    }
                    i = end
                    continue
                }
                // Number
                val num = matchFrom(NUMBER, code, i)
                if (num != null) {
                    withStyle(SpanStyle(color = palette.number)) {
                        append(num)
                    }
                    i += num.length
                    continue
                }
                // Identifier / keyword / type
                val id = matchFrom(IDENT, code, i)
                if (id != null) {
                    val style = when {
                        id in KEYWORDS -> SpanStyle(color = palette.keyword, fontWeight = FontWeight.SemiBold)
                        TYPE_HINT.matches(id) && id.length > 1 -> SpanStyle(color = palette.type)
                        else -> SpanStyle(color = palette.plain)
                    }
                    withStyle(style) { append(id) }
                    i += id.length
                    continue
                }
                withStyle(SpanStyle(color = palette.plain)) { append(code[i]) }
                i++
            }
        }
    }
}
