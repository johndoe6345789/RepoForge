package com.repoforge.data.git

/** Splits a file with git conflict markers into plain text and conflict hunks, and back. */
object ConflictMarkers {

    sealed interface Segment {
        data class Text(val text: String) : Segment

        /** One `<<<<<<< … ======= … >>>>>>>` block. [ancestor] is set for diff3-style markers. */
        data class Hunk(val head: String, val base: String, val ancestor: String?) : Segment
    }

    /** Returns the segments, or null when the markers are malformed (e.g. a block never closes). */
    fun parse(text: String): List<Segment>? {
        val segments = mutableListOf<Segment>()
        val plain = StringBuilder()
        var head: StringBuilder? = null
        var ancestor: StringBuilder? = null
        var base: StringBuilder? = null
        var part = 0 // 0 = outside, 1 = head, 2 = ancestor, 3 = base
        for (line in splitKeepingNewlines(text)) {
            val bare = line.trimEnd('\n', '\r')
            when {
                part == 0 && bare.startsWith("<<<<<<<") -> {
                    if (plain.isNotEmpty()) segments += Segment.Text(plain.toString())
                    plain.clear()
                    head = StringBuilder(); ancestor = null; base = null; part = 1
                }
                part == 1 && bare.startsWith("|||||||") -> { ancestor = StringBuilder(); part = 2 }
                (part == 1 || part == 2) && bare == "=======" -> { base = StringBuilder(); part = 3 }
                part == 3 && bare.startsWith(">>>>>>>") -> {
                    segments += Segment.Hunk(head.toString(), base.toString(), ancestor?.toString())
                    part = 0
                }
                part == 1 -> head!!.append(line)
                part == 2 -> ancestor!!.append(line)
                part == 3 -> base!!.append(line)
                else -> plain.append(line)
            }
        }
        if (part != 0) return null
        if (plain.isNotEmpty()) segments += Segment.Text(plain.toString())
        return segments
    }

    fun hunks(segments: List<Segment>): List<Segment.Hunk> = segments.filterIsInstance<Segment.Hunk>()

    /** Rebuilds the file, replacing hunk `i` with `resolutions[i]`. */
    fun assemble(segments: List<Segment>, resolutions: List<String>): String {
        require(resolutions.size == hunks(segments).size) { "Expected ${hunks(segments).size} resolutions, got ${resolutions.size}" }
        var next = 0
        return buildString {
            for (segment in segments) {
                when (segment) {
                    is Segment.Text -> append(segment.text)
                    is Segment.Hunk -> {
                        val resolution = resolutions[next++]
                        append(resolution)
                        // Keep the next line from being glued onto the resolution's last line.
                        val hunkEndsLine = segment.head.endsWith("\n") || segment.base.endsWith("\n")
                        if (hunkEndsLine && resolution.isNotEmpty() && !resolution.endsWith("\n")) append('\n')
                    }
                }
            }
        }
    }

    private fun splitKeepingNewlines(text: String): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = text.indexOf('\n', start)
            if (end == -1) {
                lines += text.substring(start)
                break
            }
            lines += text.substring(start, end + 1)
            start = end + 1
        }
        return lines
    }
}
