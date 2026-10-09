package com.repoforge.data.ci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CiLogParserTest {

    @Test
    fun `github logs lose timestamps and become groups, commands and errors`() {
        // Downloaded logs start with a byte-order mark.
        val raw = "\uFEFF" + """
            |2026-10-09T14:02:51.1234567Z ##[group]Run ./gradlew test
            |2026-10-09T14:02:51.1234567Z ./gradlew test
            |2026-10-09T14:02:51.1234567Z shell: /usr/bin/bash -e {0}
            |2026-10-09T14:02:52.0000000Z ##[endgroup]
            |2026-10-09T14:03:00.0000000Z > Task :app:test FAILED
            |2026-10-09T14:03:01.0000000Z ##[error]Process completed with exit code 1.
            |2026-10-09T14:03:02.0000000Z ##[group]Post job cleanup.
            |2026-10-09T14:03:02.0000000Z [command]/usr/bin/git version
            |2026-10-09T14:03:02.0000000Z ##[warning]Node 16 is deprecated
            |""".trimMargin()
        val log = CiLogParser.parse(raw)
        assertEquals(
            listOf("Run ./gradlew test", "./gradlew test", "shell: /usr/bin/bash -e {0}", "> Task :app:test FAILED",
                "Process completed with exit code 1.", "Post job cleanup.", "/usr/bin/git version", "Node 16 is deprecated"),
            log.lines.map { it.text },
        )
        val kinds = log.lines.map { it.kind }
        assertEquals(
            listOf(LogLineKind.GROUP, LogLineKind.NORMAL, LogLineKind.NORMAL, LogLineKind.NORMAL, LogLineKind.ERROR,
                LogLineKind.GROUP, LogLineKind.COMMAND, LogLineKind.WARNING),
            kinds,
        )
        // Lines inside a group carry its id; ##[endgroup] closes it.
        assertEquals(listOf(0, 0, 0, null, null, 1, 1, 1), log.lines.map { it.group })
        assertEquals(4, log.firstError)
        assertEquals(listOf(1, 2, 3, 5, 6, 7, 8, 9), log.lines.map { it.number })
    }

    @Test
    fun `gitlab sections, carriage returns and ansi colours`() {
        val esc = "\u001B"
        val raw = "section_start:1697040000:prepare_script\r$esc[0K$esc[36;1mPreparing environment$esc[0;m\n" +
            "Downloading 10%\rDownloading 55%\rDownloading 100%\n" +
            "section_end:1697040001:prepare_script\r$esc[0K\n" +
            "$esc[31;1mERROR: Job failed: exit code 1$esc[0m\n"
        val log = CiLogParser.parse(raw)
        val texts = log.lines.map { it.text }
        assertEquals(listOf("Preparing environment", "Downloading 100%", "", "ERROR: Job failed: exit code 1"), texts)
        assertEquals(LogLineKind.GROUP, log.lines[0].kind)
        assertEquals(0, log.lines[1].group)
        assertNull("section_end closes the group", log.lines[2].group)
        val header = log.lines[0].spans.single()
        assertEquals(6, header.color)
        assertTrue(header.bold)
        assertEquals(LogSpan(0, 30, 1, true), log.lines[3].spans.single())
    }

    @Test
    fun `workflow commands from gitea runners and 256 colours`() {
        val raw = "::group::Install\nnpm ci\n::endgroup::\n::error file=a.kt,line=3::Unresolved reference\n\u001B[38;5;208morange\u001B[39m plain\n"
        val log = CiLogParser.parse(raw)
        assertEquals(listOf("Install", "npm ci", "Unresolved reference", "orange plain"), log.lines.map { it.text })
        assertEquals(listOf(LogLineKind.GROUP, LogLineKind.NORMAL, LogLineKind.ERROR, LogLineKind.NORMAL), log.lines.map { it.kind })
        assertEquals(LogSpan(0, 6, 208, false), log.lines[3].spans.single())
    }

    @Test
    fun `very long logs keep the end, where failures are`() {
        val raw = (1..120).joinToString("\n") { "line $it" }
        val log = CiLogParser.parse(raw, maxLines = 100)
        assertEquals(20, log.droppedLines)
        assertEquals("line 21", log.lines.first().text)
        assertEquals("line 120", log.lines.last().text)
    }

    @Test
    fun `errors inside groups mark the group`() {
        val log = CiLogParser.parse("##[group]Build\n##[error]boom\n##[endgroup]\n##[group]Other\nfine\n")
        assertEquals(setOf(0), log.groupsWithErrors)
    }
}
