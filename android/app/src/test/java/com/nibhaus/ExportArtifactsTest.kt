package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.PageEntity
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import com.nibhaus.export.ExportArtifacts
import com.nibhaus.export.PageGeometry
import com.nibhaus.export.PageStyle
import com.nibhaus.export.Ruling
import org.junit.Test

class ExportArtifactsTest {

    private fun stroke(uuid: String, color: Int) = StrokeEntity(
        uuid = uuid, pageId = "P1", color = color, startedAt = 0, endedAt = 1,
        pointsJson = "", syncState = SyncState.PENDING,
    )

    @Test fun `svg has a polyline with the points and the rgb color`() {
        val s = stroke("s1", 0xFF112233.toInt())
        val pts = listOf(Point(1f, 2f, 1f, 0), Point(3f, 4f, 1f, 1))

        val svg = ExportArtifacts.renderSvg(listOf(s), { pts })

        assertThat(svg).contains("<svg")
        assertThat(svg).contains("viewBox=")
        assertThat(svg).contains("points=\"1,2 3,4\"")
        assertThat(svg).contains("stroke=\"#112233\"")  // alpha dropped
        // True physical size: the <svg> root carries width/height in mm (viewBox stays in Ncode units).
        val head = svg.substringBefore('>')
        assertThat(head).contains("mm\"")
        assertThat(head).contains("viewBox=")
    }

    @Test fun `full-page svg uses the sheet's physical size and keeps ink absolute`() {
        val s = stroke("s1", 0)
        val pts = listOf(Point(10f, 10f, 1f, 0), Point(20f, 20f, 1f, 1))

        val svg = ExportArtifacts.renderSvg(listOf(s), { pts }, PageGeometry.forBook(438))

        val head = svg.substringBefore('>')
        assertThat(head).contains("width=\"138mm\"")  // whole page face (measured), not the ink bbox
        assertThat(head).contains("height=\"205mm\"")
        assertThat(svg).contains("points=\"10,10 20,20\"")  // ink kept at its absolute page position
    }

    @Test fun `full-page svg carries the calibrated ruling and page number`() {
        val s = stroke("s1", 0)
        val pts = listOf(Point(10f, 10f, 1f, 0), Point(20f, 20f, 1f, 1))
        val geo = PageGeometry.forBook(438)!!                // PROFESSIONAL, incl. traced anchors
        val ruling = Ruling(topMm = 14f, bottomMm = 8.5f, lineCount = 27, sideMm = 9f)

        val svg = ExportArtifacts.renderSvg(listOf(s), { pts }, geo, ruling = ruling, pageNumber = 36)

        assertThat(Regex("<line").findAll(svg).count()).isEqualTo(27)  // every printed rule (LINED)
        assertThat(svg).contains(">036</text>")                        // 3-digit printed page number
    }

    @Test fun `footer-only pages keep just the bottom rule in the svg`() {
        val s = stroke("s1", 0)
        val pts = listOf(Point(10f, 10f, 1f, 0), Point(20f, 20f, 1f, 1))
        val svg = ExportArtifacts.renderSvg(
            listOf(s), { pts }, PageGeometry.forBook(438),
            ruling = Ruling(topMm = 14f, bottomMm = 8.5f, lineCount = 27, sideMm = 9f),
            pageNumber = 36, pageStyle = PageStyle.FOOTER_ONLY,
        )
        assertThat(Regex("<line").findAll(svg).count()).isEqualTo(1)
    }

    @Test fun `unknown notebook has no geometry so the svg stays ink-cropped`() {
        assertThat(PageGeometry.forBook(999)).isNull()
        assertThat(PageGeometry.forBook(null)).isNull()
    }

    @Test fun `svg simplification drops collinear points but keeps endpoints and corners`() {
        val line = listOf(
            Point(0f, 0f, 1f, 0), Point(1f, 1f, 1f, 1), Point(2f, 2f, 1f, 2),
            Point(3f, 3f, 1f, 3), Point(4f, 4f, 1f, 4),
        )
        assertThat(ExportArtifacts.simplify(line, 0.15f))
            .containsExactly(line.first(), line.last()).inOrder()      // collinear → just the ends
        val bent = listOf(Point(0f, 0f, 1f, 0), Point(2f, 5f, 1f, 1), Point(4f, 0f, 1f, 2))
        assertThat(ExportArtifacts.simplify(bent, 0.15f)).hasSize(3)    // a real corner is kept
        assertThat(ExportArtifacts.simplify(line.take(2), 0.15f)).hasSize(2) // tiny strokes untouched
    }

    @Test fun `simplify handles degenerate and empty input without error`() {
        val same = List(5) { Point(3f, 3f, 1f, it.toLong()) } // zero-length segments (perp len == 0)
        assertThat(ExportArtifacts.simplify(same, 0.15f)).containsExactly(same.first(), same.last()).inOrder()
        assertThat(ExportArtifacts.simplify(emptyList(), 0.15f)).isEmpty()
    }

    @Test fun `single-point strokes are skipped but still produce valid svg`() {
        val svg = ExportArtifacts.renderSvg(listOf(stroke("s1", 0)), { listOf(Point(0f, 0f, 1f, 0)) })
        assertThat(svg).contains("<svg")
        assertThat(svg).doesNotContain("<polyline")
    }

    @Test fun `inkml has a trace per stroke with x y pressure time`() {
        val s = stroke("s1", 0)
        val pts = listOf(Point(1f, 2f, 0.5f, 10), Point(3f, 4f, 0.8f, 20))

        val xml = ExportArtifacts.renderInkML(listOf(s), { pts })

        assertThat(xml).contains("<ink xmlns=\"http://www.w3.org/2003/InkML\">")
        assertThat(xml).contains("<channel name=\"F\"") // pressure channel
        assertThat(xml).contains("<annotation type=\"mmPerUnit\">2.36</annotation>") // self-describing scale
        assertThat(xml).contains("<trace>1 2 0.5 10, 3 4 0.8 20</trace>")
    }

    @Test fun `inkml carries notebook provenance annotations when provided`() {
        val xml = ExportArtifacts.renderInkML(
            listOf(stroke("s1", 0)), { listOf(Point(1f, 2f, 0.5f, 10), Point(3f, 4f, 0.5f, 20)) },
            notebookType = "Professional Notebook", label = "Work",
        )
        assertThat(xml).contains("<annotation type=\"notebookType\">Professional Notebook</annotation>")
        assertThat(xml).contains("<annotation type=\"label\">Work</annotation>")
    }

    @Test fun `content hash is stable and changes only with content`() {
        val a = listOf(stroke("s1", 1), stroke("s2", 2))
        assertThat(ExportArtifacts.contentHash(a)).isEqualTo(ExportArtifacts.contentHash(a.reversed()))
        assertThat(ExportArtifacts.contentHash(a)).isNotEqualTo(ExportArtifacts.contentHash(listOf(stroke("s1", 1))))
    }

    @Test fun `content hash changes when a transcript is added`() {
        val a = listOf(stroke("s1", 1))
        assertThat(ExportArtifacts.contentHash(a, transcript = null))
            .isNotEqualTo(ExportArtifacts.contentHash(a, transcript = "hello"))
    }

    @Test fun `markdown has frontmatter, the png embed and the transcript`() {
        val page = PageEntity(
            id = "P1", notebookId = "nb", addressKey = "3.27.603.1",
            section = 3, owner = 27, book = 603, page = 7, firstSeenAt = 0, lastInkAt = 99,
        )
        val md = ExportArtifacts.renderMarkdown("P1", page, "Buy milk")

        assertThat(md).startsWith("---\n")
        assertThat(md).contains("pageId: P1")
        assertThat(md).contains("ncode: 3.27.603.1")
        assertThat(md).contains("page: 7")
        assertThat(md).contains("![[P1.png]]")
        assertThat(md).contains("[vector](P1.svg)")        // links the vector + online-ink artifacts
        assertThat(md).contains("[online ink](P1.inkml)")
        assertThat(md).contains("Buy milk")
    }

    @Test fun `markdown frontmatter includes type, label and tags when provided`() {
        val page = PageEntity(
            id = "P1", notebookId = "nb", addressKey = "3.27.438.7",
            section = 3, owner = 27, book = 438, page = 7, firstSeenAt = 0, lastInkAt = 9,
        )
        val md = ExportArtifacts.renderMarkdown(
            "P1", page, "hi", "PNB_Work_Pg007.png",
            notebookType = "Professional Notebook", label = "Work", tags = listOf("meeting", "q3"),
        )
        assertThat(md).contains("label: Work")
        assertThat(md).contains("type: Professional Notebook")
        assertThat(md).contains("tags: [meeting, q3]")
        assertThat(md).contains("![[PNB_Work_Pg007.png]]")
    }

    @Test fun `markdown shows a placeholder when not yet transcribed`() {
        val md = ExportArtifacts.renderMarkdown("P1", null, null)
        assertThat(md).contains("*(not yet transcribed)*")
        assertThat(md).contains("![[P1.png]]")
    }

    @Test fun `sidecar carries the required metadata fields`() {
        val page = PageEntity(
            id = "P1", notebookId = "nb", addressKey = "3.27.603.1",
            section = 3, owner = 27, book = 603, page = 1, firstSeenAt = 0, lastInkAt = 9,
        )
        val js = ExportArtifacts.sidecarJson(
            "P1", page, penId = "AA:BB", strokeCount = 2, contentHash = "abc", exportedAt = 42,
            formats = listOf("svg", "inkml", "png", "pdf", "md", "json"),
        )

        assertThat(js).contains("\"pageId\": \"P1\"")
        assertThat(js).contains("\"notebookNcodeId\": \"3.27.603.1\"")
        assertThat(js).contains("\"penId\": \"AA:BB\"")
        assertThat(js).contains("\"captureTimestamp\": 9")
        assertThat(js).contains("\"strokeCount\": 2")
        assertThat(js).contains("\"pdf\"")  // formats lists every artifact actually written
    }

    @Test fun `sidecar records notebook type, label and physical scale`() {
        val js = ExportArtifacts.sidecarJson(
            "P1", null, penId = "AA", strokeCount = 1, contentHash = "h", exportedAt = 1,
            notebookType = "professional", notebookLabel = "Work", mmPerUnit = 2.32f,
        )
        assertThat(js).contains("\"notebookType\": \"professional\"")
        assertThat(js).contains("\"notebookLabel\": \"Work\"")
        assertThat(js).contains("\"mmPerUnit\": 2.32")
    }
}
