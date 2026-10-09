package com.repoforge.data.forge

import com.repoforge.data.model.ChangeType
import com.repoforge.data.model.FileDiff

/** Parsing for unified diffs, as returned raw by Bitbucket and Gitea and per file by GitLab. */
object Diffs {

    enum class LineType { HUNK, CONTEXT, ADDED, REMOVED, NOTE }

    data class Line(val type: LineType, val text: String, val oldNumber: Int?, val newNumber: Int?)

    private val hunkHeader = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /** Splits a multi-file `git diff` into per-file diffs. */
    fun parseGitDiff(text: String): List<FileDiff> {
        val files = mutableListOf<FileDiff>()
        val blocks = text.split(Regex("(?m)^(?=diff --git )")).filter { it.startsWith("diff --git ") }
        for (block in blocks) {
            val lines = block.lines()
            val header = lines.first()
            // "diff --git a/old b/new": paths may contain spaces, so prefer the ---/+++ and rename lines.
            var oldPath = header.substringAfter(" a/").substringBefore(" b/")
            var newPath = header.substringAfterLast(" b/")
            var change = ChangeType.MODIFIED
            var binary = false
            val hunkStart = lines.indexOfFirst { it.startsWith("@@") }
            val meta = if (hunkStart == -1) lines else lines.subList(0, hunkStart)
            for (line in meta) {
                when {
                    line.startsWith("new file mode") -> change = ChangeType.ADDED
                    line.startsWith("deleted file mode") -> change = ChangeType.DELETED
                    line.startsWith("rename from ") -> { oldPath = line.removePrefix("rename from "); change = ChangeType.RENAMED }
                    line.startsWith("rename to ") -> newPath = line.removePrefix("rename to ")
                    line.startsWith("--- a/") -> oldPath = line.removePrefix("--- a/")
                    line.startsWith("+++ b/") -> newPath = line.removePrefix("+++ b/")
                    line.startsWith("Binary files") || line == "GIT binary patch" -> binary = true
                }
            }
            val patch = if (hunkStart == -1 || binary) null else lines.subList(hunkStart, lines.size).joinToString("\n").trimEnd('\n')
            val (added, removed) = countChanges(patch)
            files += FileDiff(
                path = if (change == ChangeType.DELETED) oldPath else newPath,
                oldPath = oldPath.takeIf { change == ChangeType.RENAMED },
                change = change,
                additions = added,
                deletions = removed,
                patch = patch,
            )
        }
        return files
    }

    fun countChanges(patch: String?): Pair<Int, Int> {
        if (patch == null) return 0 to 0
        var added = 0
        var removed = 0
        patch.lineSequence().forEach {
            when {
                it.startsWith("+++") || it.startsWith("---") -> Unit
                it.startsWith("+") -> added++
                it.startsWith("-") -> removed++
            }
        }
        return added to removed
    }

    /** Turns a patch's hunks into display lines with old/new line numbers. */
    fun lines(patch: String): List<Line> {
        val result = mutableListOf<Line>()
        var oldNo = 0
        var newNo = 0
        for (raw in patch.lines()) {
            val hunk = hunkHeader.find(raw)
            when {
                hunk != null -> {
                    oldNo = hunk.groupValues[1].toInt()
                    newNo = hunk.groupValues[2].toInt()
                    result += Line(LineType.HUNK, raw, null, null)
                }
                raw.startsWith("+") -> result += Line(LineType.ADDED, raw.substring(1), null, newNo++)
                raw.startsWith("-") -> result += Line(LineType.REMOVED, raw.substring(1), oldNo++, null)
                raw.startsWith("\\") -> result += Line(LineType.NOTE, raw.removePrefix("\\ "), null, null)
                else -> result += Line(LineType.CONTEXT, raw.removePrefix(" "), oldNo++, newNo++)
            }
        }
        if (result.lastOrNull()?.let { it.type == LineType.CONTEXT && it.text.isEmpty() } == true) result.removeAt(result.lastIndex)
        return result
    }
}
