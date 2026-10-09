package com.repoforge.data.ci

enum class LogLineKind { NORMAL, GROUP, COMMAND, ERROR, WARNING, NOTICE, DEBUG }

/** A run of characters with an ANSI colour (palette index 0–255) and/or bold. */
data class LogSpan(val start: Int, val end: Int, val color: Int?, val bold: Boolean)

data class LogLine(
    /** 1-based line number in the original log. */
    val number: Int,
    val text: String,
    val kind: LogLineKind,
    val spans: List<LogSpan> = emptyList(),
    /** For a [LogLineKind.GROUP] line, its own group id; otherwise the group it sits in, if any. */
    val group: Int? = null,
)

data class ParsedLog(
    val lines: List<LogLine>,
    /** Lines dropped from the start because the log was too long. */
    val droppedLines: Int,
    /** Group ids that contain an error, so they can start expanded. */
    val groupsWithErrors: Set<Int>,
) {
    val firstError: Int? get() = lines.indexOfFirst { it.kind == LogLineKind.ERROR }.takeIf { it >= 0 }
}

/**
 * Turns raw CI logs into displayable lines. Understands GitHub/Gitea workflow commands
 * (`##[group]`, `::error::`…), GitLab's collapsible `section_start`/`section_end` markers, the
 * timestamps GitHub prefixes to every line, carriage-return progress output and ANSI colours.
 */
object CiLogParser {

    private val timestamp = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z ?")
    private val gitlabSection = Regex("section_(start|end):\\d+:[^\\r\\u001B\\s]+(\\r)?(\\u001B\\[0K)?")
    private val ansi = Regex("\\u001B\\[([0-9;?]*)([A-Za-z])")
    // Downloaded GitHub logs write "##[group]", "##[error]"… but plain "[command]".
    private val githubCommand = Regex("^(?:##)?\\[(group|endgroup|error|warning|notice|command|debug)\\](.*)$")
    private val workflowCommand = Regex("^::(group|endgroup|error|warning|notice|debug)( [^:]*)?::(.*)$")

    fun parse(raw: String, maxLines: Int = 50_000): ParsedLog {
        val rawLines = raw.removePrefix("\uFEFF").split('\n')
        val out = ArrayList<LogLine>(minOf(rawLines.size, maxLines))
        val groupsWithErrors = mutableSetOf<Int>()
        var currentGroup: Int? = null
        var nextGroup = 0
        rawLines.forEachIndexed { index, original ->
            if (index == rawLines.lastIndex && original.isEmpty()) return@forEachIndexed
            var line = original.removeSuffix("\r")
            var kind = LogLineKind.NORMAL

            // GitLab sections: start opens a group whose header is the rest of the line.
            var startsSection = false
            gitlabSection.findAll(line).forEach { match ->
                if (match.groupValues[1] == "start") startsSection = true else currentGroup = null
            }
            line = gitlabSection.replace(line, "")

            // Progress output rewrites the line with \r; only the final state matters.
            if ('\r' in line) line = line.substringAfterLast('\r')
            line = timestamp.replaceFirst(line, "")

            val plain = ansi.replace(line, "")
            val github = githubCommand.find(plain)
            val workflow = if (github == null) workflowCommand.find(plain) else null
            val command = github?.groupValues?.get(1) ?: workflow?.groupValues?.get(1)
            if (command != null) {
                line = github?.groupValues?.get(2) ?: workflow!!.groupValues[3]
                kind = when (command) {
                    "group" -> LogLineKind.GROUP
                    "endgroup" -> {
                        currentGroup = null
                        return@forEachIndexed
                    }
                    "error" -> LogLineKind.ERROR
                    "warning" -> LogLineKind.WARNING
                    "notice" -> LogLineKind.NOTICE
                    "command" -> LogLineKind.COMMAND
                    else -> LogLineKind.DEBUG
                }
            } else if (startsSection) {
                kind = LogLineKind.GROUP
            }

            val (text, spans) = colours(line)
            val group: Int?
            if (kind == LogLineKind.GROUP) {
                group = nextGroup++
                currentGroup = group
            } else {
                group = currentGroup
                if (kind == LogLineKind.ERROR && group != null) groupsWithErrors += group
            }
            out += LogLine(index + 1, text, kind, spans, group)
        }
        val dropped = (out.size - maxLines).coerceAtLeast(0)
        return ParsedLog(if (dropped > 0) out.subList(dropped, out.size).toList() else out, dropped, groupsWithErrors)
    }

    /** Strips escape sequences, keeping SGR colours as spans over the remaining text. */
    fun colours(line: String): Pair<String, List<LogSpan>> {
        if ('\u001B' !in line) return line to emptyList()
        val text = StringBuilder()
        val spans = mutableListOf<LogSpan>()
        var color: Int? = null
        var bold = false
        var spanStart = 0
        fun close() {
            if (text.length > spanStart && (color != null || bold)) spans += LogSpan(spanStart, text.length, color, bold)
            spanStart = text.length
        }
        var last = 0
        for (match in ansi.findAll(line)) {
            text.append(line, last, match.range.first)
            last = match.range.last + 1
            if (match.groupValues[2] != "m") continue // cursor/erase sequences: drop
            close()
            val params = match.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
            var i = 0
            while (i < params.size) {
                when (val p = params[i]) {
                    0 -> { color = null; bold = false }
                    1 -> bold = true
                    22 -> bold = false
                    in 30..37 -> color = p - 30
                    in 90..97 -> color = p - 90 + 8
                    39 -> color = null
                    38 -> {
                        // 38;5;n (256 colours) or 38;2;r;g;b (true colour, not kept).
                        if (params.getOrNull(i + 1) == 5) { color = params.getOrNull(i + 2); i += 2 }
                        else if (params.getOrNull(i + 1) == 2) i += 4
                    }
                    48 -> i += if (params.getOrNull(i + 1) == 5) 2 else if (params.getOrNull(i + 1) == 2) 4 else 0
                }
                i++
            }
        }
        text.append(line, last, line.length)
        close()
        return text.toString() to spans
    }
}
