package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.gesture.UnistrokeRecognizer
import com.nibhaus.gesture.UnistrokeRecognizer.Pt
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class UnistrokeRecognizerTest {

    private fun densify(vertices: List<Pt>, perEdge: Int = 12): List<Pt> {
        val out = ArrayList<Pt>()
        for (i in 1 until vertices.size) {
            val a = vertices[i - 1]; val b = vertices[i]
            for (s in 0 until perEdge) {
                val t = s / perEdge.toFloat()
                out.add(Pt(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
            }
        }
        out.add(vertices.last())
        return out
    }

    private fun circle(cx: Float, cy: Float, r: Float, n: Int = 40, rot: Double = 0.0) =
        (0 until n).map {
            val a = rot + 2 * Math.PI * it / n
            Pt((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
        }

    @Test fun recognizesCircle() {
        assertThat(UnistrokeRecognizer.recognize(circle(100f, 100f, 80f))?.name).isEqualTo("circle")
    }

    @Test fun recognizesCircle_atDifferentScaleAndPosition() {
        // Scale + translation invariant.
        assertThat(UnistrokeRecognizer.recognize(circle(900f, 40f, 15f))?.name).isEqualTo("circle")
    }

    @Test fun recognizesBox() {
        val box = densify(listOf(Pt(0f, 0f), Pt(200f, 0f), Pt(200f, 200f), Pt(0f, 200f), Pt(0f, 0f)))
        assertThat(UnistrokeRecognizer.recognize(box)?.name).isEqualTo("box")
    }

    @Test fun recognizesCheck() {
        val check = densify(listOf(Pt(0f, 60f), Pt(40f, 130f), Pt(130f, 0f)), perEdge = 20)
        assertThat(UnistrokeRecognizer.recognize(check)?.name).isEqualTo("check")
    }

    @Test fun recognizesStar() {
        val outer = (0 until 5).map {
            val a = Math.toRadians(-90.0 + it * 72.0)
            Pt((50 + 50 * cos(a)).toFloat(), (50 + 50 * sin(a)).toFloat())
        }
        val order = intArrayOf(0, 2, 4, 1, 3, 0)
        val star = densify(order.map { outer[it] }, perEdge = 14)
        assertThat(UnistrokeRecognizer.recognize(star)?.name).isEqualTo("star")
    }

    @Test fun recognizesBox_startedFromAnotherCornerAndReversed() {
        // The point-cloud recognizer is start-point + direction invariant — this same box drawn
        // from the opposite corner the "wrong" way round is where the old $1 recognizer struggled.
        val box = densify(listOf(Pt(200f, 200f), Pt(0f, 200f), Pt(0f, 0f), Pt(200f, 0f), Pt(200f, 200f)))
        assertThat(UnistrokeRecognizer.recognize(box)?.name).isEqualTo("box")
    }

    @Test fun recognizesCircle_drawnCounterClockwise() {
        assertThat(UnistrokeRecognizer.recognize(circle(100f, 100f, 80f, rot = 1.3).reversed())?.name)
            .isEqualTo("circle")
    }

    @Test fun rejectsTooFewPoints() {
        assertThat(UnistrokeRecognizer.recognize(listOf(Pt(0f, 0f), Pt(1f, 1f)))).isNull()
    }

    @Test fun straightLine_isNotAFalsePositive() {
        // A plain straight stroke is none of the vocabulary → no command (strict score + margin).
        val line = densify(listOf(Pt(0f, 0f), Pt(200f, 120f)), perEdge = 30)
        assertThat(UnistrokeRecognizer.recognize(line)).isNull()
    }
}
