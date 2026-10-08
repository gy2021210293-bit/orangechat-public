/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolsTest {

    @Test
    fun smallFileDefaultCallReturnsFullContentWithoutTruncation() {
        val text = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n"
        val page = paginateWorkspaceText(
            path = "/workspace/small.txt",
            text = text,
        )

        assertEquals("/workspace/small.txt", page.path)
        assertEquals(text, page.text)
        assertEquals(1, page.startLine)
        assertEquals(0, page.startColumn)
        assertEquals(5, page.endLine)
        assertEquals(5, page.totalLines)
        assertFalse(page.truncated)
        assertNull(page.nextStartLine)
        assertNull(page.nextStartColumn)

        val json = page.toJsonObject()
        assertEquals("/workspace/small.txt", json["path"]?.jsonPrimitive?.content)
        assertEquals(text, json["text"]?.jsonPrimitive?.content)
        assertEquals(1, json["start_line"]?.jsonPrimitive?.intOrNull)
        assertEquals(0, json["start_column"]?.jsonPrimitive?.intOrNull)
        assertEquals(5, json["end_line"]?.jsonPrimitive?.intOrNull)
        assertEquals(5, json["total_lines"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, json["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertNull(json["next_start_line"])
        assertNull(json["next_start_column"])
    }

    @Test
    fun fileExceeding200LinesReturnsFirstPageByDefaultWithCursor() {
        val lines = (1..300).map { "Line $it" }
        val text = lines.joinToString("\n") + "\n"

        val page = paginateWorkspaceText(
            path = "/workspace/300lines.txt",
            text = text,
        )

        assertEquals(1, page.startLine)
        assertEquals(0, page.startColumn)
        assertEquals(200, page.endLine)
        assertEquals(300, page.totalLines)
        assertTrue(page.truncated)
        assertEquals(201, page.nextStartLine)
        assertEquals(0, page.nextStartColumn)
        assertEquals(lines.take(200).joinToString("\n") + "\n", page.text)
    }

    @Test
    fun readingSecondPageWithCursorHasNoOverlapOrOmission() {
        val lines = (1..300).map { "Line $it" }
        val text = lines.joinToString("\n") + "\n"

        val page1 = paginateWorkspaceText(
            path = "/workspace/300lines.txt",
            text = text,
        )
        val page2 = paginateWorkspaceText(
            path = "/workspace/300lines.txt",
            text = text,
            startLine = page1.nextStartLine!!,
            startColumn = page1.nextStartColumn!!,
        )

        assertEquals(201, page2.startLine)
        assertEquals(0, page2.startColumn)
        assertEquals(300, page2.endLine)
        assertEquals(300, page2.totalLines)
        assertFalse(page2.truncated)
        assertNull(page2.nextStartLine)
        assertNull(page2.nextStartColumn)

        assertEquals(text, page1.text + page2.text)
    }

    @Test
    fun lineCount500AllowedAnd501Rejected() {
        val text = (1..600).joinToString("\n") { "line $it" } + "\n"

        val page500 = paginateWorkspaceText(
            path = "/workspace/lines.txt",
            text = text,
            lineCount = 500,
        )
        assertEquals(500, page500.endLine)
        assertTrue(page500.truncated)

        assertThrows(IllegalArgumentException::class.java) {
            paginateWorkspaceText(
                path = "/workspace/lines.txt",
                text = text,
                lineCount = 501,
            )
        }
    }

    @Test
    fun stopsAt24KiBBudgetAndEnsuresJsonWithin32KiB() {
        // 1000 lines of 100 chars = ~100 KB
        val line = "a".repeat(99) + "\n"
        val text = line.repeat(1000)

        val page = paginateWorkspaceText(
            path = "/workspace/large.txt",
            text = text,
        )

        val textBytes = page.text.toByteArray(Charsets.UTF_8).size
        val jsonBytes = page.toJsonString().toByteArray(Charsets.UTF_8).size

        assertTrue("Text bytes ($textBytes) must be <= 24 KiB", textBytes <= MAX_WORKSPACE_PAGE_TEXT_BYTES)
        assertTrue("JSON bytes ($jsonBytes) must be <= 32 KiB", jsonBytes <= MAX_WORKSPACE_PAGE_JSON_BYTES)
        assertTrue(page.truncated)
        assertEquals(page.endLine + 1, page.nextStartLine)
        assertEquals(0, page.nextStartColumn)
    }

    @Test
    fun multiByteUnicodeAndEmojiNotCorrupted() {
        val emojiPattern = "🍊👨‍👩‍👧‍👦你好世界✨🔥\n"
        val text = emojiPattern.repeat(1000)

        val pages = mutableListOf<WorkspaceTextPage>()
        var startLine = 1
        var startColumn = 0

        while (true) {
            val page = paginateWorkspaceText(
                path = "/workspace/emoji.txt",
                text = text,
                startLine = startLine,
                startColumn = startColumn,
            )
            pages.add(page)

            // Validate that string does not have broken surrogate pairs
            val textStr = page.text
            if (textStr.isNotEmpty()) {
                assertFalse("Text must not start with an orphaned low surrogate", Character.isLowSurrogate(textStr.first()))
                assertFalse("Text must not end with an orphaned high surrogate", Character.isHighSurrogate(textStr.last()))
            }
            // Validate UTF-8 roundtrip
            assertEquals(textStr, textStr.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))

            if (!page.truncated) break
            startLine = page.nextStartLine!!
            startColumn = page.nextStartColumn!!
        }

        val reconstructed = pages.joinToString("") { it.text }
        assertEquals(text, reconstructed)
    }

    @Test
    fun windowsCrlfLineEndingsHandledCorrectly() {
        val text = "Line 1\r\nLine 2\r\nLine 3\r\n"
        val page = paginateWorkspaceText(
            path = "/workspace/crlf.txt",
            text = text,
        )

        assertEquals(3, page.totalLines)
        assertEquals(3, page.endLine)
        assertFalse(page.truncated)
        assertEquals(text, page.text)
        assertTrue(page.text.contains("\r\n"))
    }

    @Test
    fun emptyFileReturnsEmptyContentWithoutTruncation() {
        val page = paginateWorkspaceText(
            path = "/workspace/empty.txt",
            text = "",
        )

        assertEquals("", page.text)
        assertEquals(1, page.startLine)
        assertEquals(0, page.startColumn)
        assertEquals(0, page.endLine)
        assertEquals(0, page.totalLines)
        assertFalse(page.truncated)
        assertNull(page.nextStartLine)
        assertNull(page.nextStartColumn)
    }

    @Test
    fun totalLinesSemanticConsistencyWithOrWithoutTrailingNewline() {
        val withTrailing = "a\nb\nc\n"
        val withoutTrailing = "a\nb\nc"

        val page1 = paginateWorkspaceText("/workspace/file1.txt", withTrailing)
        val page2 = paginateWorkspaceText("/workspace/file2.txt", withoutTrailing)

        assertEquals(3, page1.totalLines)
        assertEquals(3, page2.totalLines)
        assertEquals("a\nb\nc\n", page1.text)
        assertEquals("a\nb\nc", page2.text)
    }

    @Test
    fun singleLineExceeding24KiBReturnsNextStartColumnOnSameLine() {
        val text = "x".repeat(60000)
        val page = paginateWorkspaceText(
            path = "/workspace/superlong.txt",
            text = text,
        )

        assertEquals(1, page.startLine)
        assertEquals(0, page.startColumn)
        assertEquals(1, page.endLine)
        assertEquals(1, page.totalLines)
        assertTrue(page.truncated)
        assertEquals(1, page.nextStartLine)
        assertTrue(page.nextStartColumn!! > 0)
        assertEquals(page.nextStartColumn, page.text.length)
        assertTrue(page.text.toByteArray(Charsets.UTF_8).size <= MAX_WORKSPACE_PAGE_TEXT_BYTES)
    }

    @Test
    fun resumingSuperLongSingleLineReconstructsWithoutLossOrDuplication() {
        val text = "x".repeat(60000)

        val pages = mutableListOf<WorkspaceTextPage>()
        var startLine = 1
        var startColumn = 0

        while (true) {
            val page = paginateWorkspaceText(
                path = "/workspace/superlong.txt",
                text = text,
                startLine = startLine,
                startColumn = startColumn,
            )
            pages.add(page)

            if (!page.truncated) break
            startLine = page.nextStartLine!!
            startColumn = page.nextStartColumn!!
        }

        val reconstructed = pages.joinToString("") { it.text }
        assertEquals(text, reconstructed)
        assertEquals(3, pages.size) // 24576 + 24576 + 10848 = 60000
    }

    @Test
    fun startLineExceedingTotalLinesReturnsEmptyResultSafely() {
        val text = "Line 1\nLine 2\nLine 3\n"
        val page = paginateWorkspaceText(
            path = "/workspace/file.txt",
            text = text,
            startLine = 10,
        )

        assertEquals("", page.text)
        assertEquals(10, page.startLine)
        assertEquals(0, page.startColumn)
        assertEquals(3, page.endLine)
        assertEquals(3, page.totalLines)
        assertFalse(page.truncated)
    }

    @Test
    fun invalidParametersReturnExplicitErrors() {
        val text = "Line 1\nLine 2\n"

        // start_line < 1
        assertThrows(IllegalArgumentException::class.java) {
            paginateWorkspaceText("/path", text, startLine = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            paginateWorkspaceText("/path", text, startLine = -1)
        }

        // start_column < 0
        assertThrows(IllegalArgumentException::class.java) {
            paginateWorkspaceText("/path", text, startColumn = -1)
        }

        // line_count < 1 or > 500
        assertThrows(IllegalArgumentException::class.java) {
            paginateWorkspaceText("/path", text, lineCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            paginateWorkspaceText("/path", text, lineCount = -5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            paginateWorkspaceText("/path", text, lineCount = 501)
        }

        // start_column exceeds line length
        assertThrows(IllegalArgumentException::class.java) {
            paginateWorkspaceText("/path", text, startLine = 1, startColumn = 100)
        }
    }

    @Test
    fun imagePathsKeepOriginalImageHandling() {
        assertTrue("/workspace/image.png".isImagePath())
        assertTrue("/workspace/photo.JPG".isImagePath())
        assertTrue("/workspace/anim.gif".isImagePath())
        assertTrue("/workspace/vector.svg".isImagePath())
        assertFalse("/workspace/doc.txt".isImagePath())
        assertFalse("/workspace/code.kt".isImagePath())
        assertFalse("/workspace/data.json".isImagePath())
    }

    @Test
    fun worstCaseEscapedJsonStaysUnder32KiB() {
        // Double quotes, backslashes, and control characters expand when escaped to JSON
        val evilLine = "\"\\\"\u0000\u0001\u0002\t\r\n".repeat(1000)
        val evilText = evilLine.repeat(50)

        val pages = mutableListOf<WorkspaceTextPage>()
        var startLine = 1
        var startColumn = 0

        while (true) {
            val page = paginateWorkspaceText(
                path = "/workspace/worst_case.txt",
                text = evilText,
                startLine = startLine,
                startColumn = startColumn,
            )
            pages.add(page)

            val jsonBytes = page.toJsonString().toByteArray(Charsets.UTF_8).size
            val textBytes = page.text.toByteArray(Charsets.UTF_8).size

            assertTrue("Worst-case JSON bytes ($jsonBytes) must be <= 32 KiB", jsonBytes <= MAX_WORKSPACE_PAGE_JSON_BYTES)
            assertTrue("Worst-case Text bytes ($textBytes) must be <= 24 KiB", textBytes <= MAX_WORKSPACE_PAGE_TEXT_BYTES)

            if (!page.truncated) break
            startLine = page.nextStartLine!!
            startColumn = page.nextStartColumn!!
        }

        val reconstructed = pages.joinToString("") { it.text }
        assertEquals(evilText, reconstructed)
    }

    @Test
    fun exactBudgetBoundaryLfRetainsNewlineAndReconstructs() {
        // 24576 chars of 'x' + '\n' = 24577 bytes (exceeds 24 KiB by 1 byte due to newline)
        val text = "x".repeat(24576) + "\nY"

        val page1 = paginateWorkspaceText(
            path = "/workspace/boundary_lf.txt",
            text = text,
        )

        assertTrue(page1.truncated)
        assertEquals(1, page1.startLine)
        assertEquals(0, page1.startColumn)
        assertEquals(1, page1.endLine)
        // Must stay on line 1 so that the delimiter is not lost!
        assertEquals(1, page1.nextStartLine)
        assertEquals(24575, page1.nextStartColumn)
        assertEquals("x".repeat(24575), page1.text)

        val page2 = paginateWorkspaceText(
            path = "/workspace/boundary_lf.txt",
            text = text,
            startLine = page1.nextStartLine!!,
            startColumn = page1.nextStartColumn!!,
        )

        assertFalse(page2.truncated)
        assertEquals(1, page2.startLine)
        assertEquals(24575, page2.startColumn)
        assertEquals(2, page2.endLine)
        assertEquals("x\nY", page2.text)

        val reconstructed = page1.text + page2.text
        assertEquals(text, reconstructed)
    }

    @Test
    fun exactBudgetBoundaryCrlfRetainsNewlineAndReconstructs() {
        // 24575 chars of 'x' + "\r\n" = 24577 bytes (exceeds 24 KiB by 1 byte due to CRLF)
        val text = "x".repeat(24575) + "\r\nY"

        val page1 = paginateWorkspaceText(
            path = "/workspace/boundary_crlf.txt",
            text = text,
        )

        assertTrue(page1.truncated)
        assertEquals(1, page1.startLine)
        assertEquals(0, page1.startColumn)
        assertEquals(1, page1.endLine)
        // Must stay on line 1 so that the delimiter is not lost!
        assertEquals(1, page1.nextStartLine)
        assertEquals(24574, page1.nextStartColumn)
        assertEquals("x".repeat(24574), page1.text)

        val page2 = paginateWorkspaceText(
            path = "/workspace/boundary_crlf.txt",
            text = text,
            startLine = page1.nextStartLine!!,
            startColumn = page1.nextStartColumn!!,
        )

        assertFalse(page2.truncated)
        assertEquals(1, page2.startLine)
        assertEquals(24574, page2.startColumn)
        assertEquals(2, page2.endLine)
        assertEquals("x\r\nY", page2.text)

        val reconstructed = page1.text + page2.text
        assertEquals(text, reconstructed)
    }

    @Test
    fun exactBudgetBoundaryExact24KiBLineFitsWithDelimiter() {
        // 24575 chars of 'x' + '\n' = 24576 bytes (exactly 24 KiB)
        val textLf = "x".repeat(24575) + "\n"
        val pageLf = paginateWorkspaceText("/workspace/exact_lf.txt", textLf)
        assertFalse(pageLf.truncated)
        assertEquals(textLf, pageLf.text)

        // 24574 chars of 'x' + "\r\n" = 24576 bytes (exactly 24 KiB)
        val textCrlf = "x".repeat(24574) + "\r\n"
        val pageCrlf = paginateWorkspaceText("/workspace/exact_crlf.txt", textCrlf)
        assertFalse(pageCrlf.truncated)
        assertEquals(textCrlf, pageCrlf.text)
    }
}
