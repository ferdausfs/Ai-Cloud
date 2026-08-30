package dev.repochat.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSegmentParserTest {

    @Test
    fun plainProse_singleSegment() {
        val segs = parseMessageSegments("Hello **world**")
        assertEquals(1, segs.size)
        assertEquals(MessageSegment.Prose("Hello **world**"), segs[0])
    }

    @Test
    fun fencedKotlin_splitsProseAndCode() {
        val raw = """
            Here is a fix:

            ```kotlin
            fun main() {
              println("hi")
            }
            ```

            **Done.**
        """.trimIndent()
        val segs = parseMessageSegments(raw)
        assertEquals(3, segs.size)
        assertTrue(segs[0] is MessageSegment.Prose)
        assertTrue((segs[0] as MessageSegment.Prose).markdown.contains("Here is a fix"))
        val code = segs[1] as MessageSegment.Code
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("fun main()"))
        assertTrue(!code.code.contains("```"))
        assertTrue(segs[2] is MessageSegment.Prose)
        assertTrue((segs[2] as MessageSegment.Prose).markdown.contains("**Done.**"))
    }

    @Test
    fun fenceWithoutLanguage() {
        val segs = parseMessageSegments("```\nplain\n```")
        assertEquals(1, segs.size)
        val code = segs[0] as MessageSegment.Code
        assertEquals(null, code.language)
        assertEquals("plain", code.code)
    }

    @Test
    fun unclosedFence_consumesRestAsCode() {
        val segs = parseMessageSegments("intro\n```js\nconst x = 1;\nstill code")
        assertEquals(2, segs.size)
        assertEquals("intro", (segs[0] as MessageSegment.Prose).markdown)
        val code = segs[1] as MessageSegment.Code
        assertEquals("js", code.language)
        assertTrue(code.code.contains("still code"))
    }

    @Test
    fun splitProseBlocks_headingsListsBold() {
        val blocks = splitProseBlocks(
            """
            # Title
            - one
            - two
            1. first
            Some **bold** and `code`.
            """.trimIndent(),
        )
        assertTrue(blocks.any { it.toString().contains("Title") })
        assertTrue(blocks.size >= 4)
    }

    @Test
    fun annotateInline_stripsMarkers() {
        val a = annotateInlineMarkdown(
            text = "say **hi** and `x` plus [link](https://example.com)",
            baseColor = androidx.compose.ui.graphics.Color.Black,
            linkColor = androidx.compose.ui.graphics.Color.Blue,
            inlineCodeBg = androidx.compose.ui.graphics.Color.LightGray,
            inlineCodeFg = androidx.compose.ui.graphics.Color.DarkGray,
        )
        assertEquals("say hi and x plus link", a.text)
        // LinkAnnotation.Url is attached; plain text no longer contains markdown markers.
        assertTrue(!a.text.contains("**"))
        assertTrue(!a.text.contains("`"))
        assertTrue(a.text.contains("link"))
    }

    @Test
    fun preferLogCodeBlock_detectsCiDump() {
        val log = """
            Attempt 1/5 — CI failed. Reading logs and fixing…

            ---- log (tail) ----
            e: Unresolved reference: Foo
            FAILED
        """.trimIndent()
        assertTrue(preferLogCodeBlock(log))
        assertTrue(!preferLogCodeBlock("Just a short reply."))
    }
}
