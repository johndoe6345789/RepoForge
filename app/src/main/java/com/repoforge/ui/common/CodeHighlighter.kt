package com.repoforge.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import dev.snipme.highlights.model.SyntaxThemes

/** Syntax highlighting for the file viewer, split per line so long files stay lazy. */
object CodeHighlighter {

    /** Files larger than this are shown without highlighting to keep opening them fast. */
    private const val MAX_CHARS = 200_000

    fun theme(dark: Boolean): SyntaxTheme = SyntaxThemes.atom(darkMode = dark)

    fun languageFor(fileName: String): SyntaxLanguage? {
        val lower = fileName.lowercase()
        if (lower == "dockerfile" || lower == "makefile") return SyntaxLanguage.SHELL
        return when (lower.substringAfterLast('.', "")) {
            "kt", "kts" -> SyntaxLanguage.KOTLIN
            "java", "groovy", "gradle" -> SyntaxLanguage.JAVA
            "js", "mjs", "cjs", "jsx" -> SyntaxLanguage.JAVASCRIPT
            "ts", "tsx", "mts", "cts" -> SyntaxLanguage.TYPESCRIPT
            "py", "pyi" -> SyntaxLanguage.PYTHON
            "go" -> SyntaxLanguage.GO
            "rs" -> SyntaxLanguage.RUST
            "c", "h" -> SyntaxLanguage.C
            "cpp", "cc", "cxx", "hpp", "hh", "hxx", "m", "mm" -> SyntaxLanguage.CPP
            "cs" -> SyntaxLanguage.CSHARP
            "swift" -> SyntaxLanguage.SWIFT
            "rb" -> SyntaxLanguage.RUBY
            "php" -> SyntaxLanguage.PHP
            "sh", "bash", "zsh", "fish" -> SyntaxLanguage.SHELL
            "dart" -> SyntaxLanguage.DART
            "pl", "pm" -> SyntaxLanguage.PERL
            "coffee" -> SyntaxLanguage.COFFEESCRIPT
            // Strings, numbers and comments still get colour in structured text formats.
            "json", "yaml", "yml", "toml", "xml", "html", "css", "scss", "sql", "properties", "ini", "cfg" -> SyntaxLanguage.DEFAULT
            else -> null
        }
    }

    /** Highlights [text] and returns one styled string per line, or null when not worth highlighting. */
    fun highlightLines(text: String, fileName: String, dark: Boolean): List<AnnotatedString>? {
        val language = languageFor(fileName) ?: return null
        if (text.length > MAX_CHARS) return null
        val lines = text.removeSuffix("\n").split('\n')
        val starts = IntArray(lines.size)
        var offset = 0
        lines.forEachIndexed { i, line -> starts[i] = offset; offset += line.length + 1 }

        val builders = lines.map { AnnotatedString.Builder(it) }
        val highlights = Highlights.Builder().code(text).language(language).theme(theme(dark)).build().getHighlights()
        for (highlight in highlights) {
            val style = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(0xFF000000 or highlight.rgb.toLong()))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            val start = highlight.location.start
            val end = highlight.location.end
            var line = lineOf(starts, start)
            while (line < lines.size && starts[line] < end) {
                val from = (start - starts[line]).coerceAtLeast(0)
                val to = (end - starts[line]).coerceAtMost(lines[line].length)
                if (from < to) builders[line].addStyle(style, from, to)
                line++
            }
        }
        return builders.map { it.toAnnotatedString() }
    }

    private fun lineOf(starts: IntArray, offset: Int): Int {
        val index = starts.binarySearch(offset)
        return if (index >= 0) index else (-index - 2).coerceAtLeast(0)
    }
}
