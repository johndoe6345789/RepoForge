package com.repoforge.data.git

import com.repoforge.data.git.ConflictMarkers.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConflictMarkersTest {

    private val file = """
        |fun a() = 1
        |<<<<<<< HEAD
        |fun b() = 2
        |=======
        |fun b() = 3
        |>>>>>>> main
        |fun c() = 4
        |<<<<<<< HEAD
        |=======
        |fun d() = 5
        |>>>>>>> main
        |""".trimMargin()

    @Test
    fun `parses text and hunks`() {
        val segments = ConflictMarkers.parse(file)!!
        assertEquals(
            listOf(
                Segment.Text("fun a() = 1\n"),
                Segment.Hunk("fun b() = 2\n", "fun b() = 3\n", null),
                Segment.Text("fun c() = 4\n"),
                Segment.Hunk("", "fun d() = 5\n", null),
            ),
            segments,
        )
    }

    @Test
    fun `assembles resolutions and keeps line breaks`() {
        val segments = ConflictMarkers.parse(file)!!
        assertEquals(
            "fun a() = 1\nfun b() = 2 + 3\nfun c() = 4\nfun d() = 5\n",
            ConflictMarkers.assemble(segments, listOf("fun b() = 2 + 3", "fun d() = 5\n")),
        )
        // An empty resolution drops the hunk entirely.
        assertEquals("fun a() = 1\nfun c() = 4\n", ConflictMarkers.assemble(segments, listOf("", "")))
    }

    @Test
    fun `diff3 markers capture the ancestor`() {
        val diff3 = "<<<<<<< ours\na\n||||||| base\no\n=======\nb\n>>>>>>> theirs\n"
        assertEquals(listOf(Segment.Hunk("a\n", "b\n", "o\n")), ConflictMarkers.parse(diff3))
    }

    @Test
    fun `unterminated hunks are rejected`() {
        assertNull(ConflictMarkers.parse("<<<<<<< HEAD\na\n=======\nb\n"))
    }
}
