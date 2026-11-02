package eu.kanade.tachiyomi.ui.reader.viewer.guided

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidedDetectionPostprocessorTest {
    private val ltr = GuidedReadingDirection.LEFT_TO_RIGHT
    private val rtl = GuidedReadingDirection.RIGHT_TO_LEFT

    @Test
    fun `drops nonfinite, empty, inverted, out of range and low confidence boxes`() {
        val valid = bubble(0.1f, 0.1f, 0.3f, 0.3f)
        val invalid = listOf(
            bubble(Float.NaN, 0f, 0.2f, 0.3f),
            bubble(0.1f, Float.POSITIVE_INFINITY, 0.2f, 0.3f),
            bubble(0.3f, 0.1f, 0.2f, 0.3f),
            bubble(0.1f, 0.1f, 0.1f, 0.3f),
            bubble(-0.1f, 0.1f, 0.2f, 0.3f),
            bubble(0.1f, 0.1f, 1.1f, 0.3f),
            bubble(0.1f, 0.1f, 0.3f, 0.3f, 0.44f),
            bubble(0.1f, 0.1f, 0.3f, 0.3f, Float.NaN),
            bubble(0.1f, 0.1f, 0.3f, 0.3f, 1.2f),
        )

        assertEquals(1, GuidedDetectionPostprocessor.process(invalid + valid, ltr).size)
    }

    @Test
    fun `prefers higher confidence when model returns duplicate bubbles`() {
        val lower = bubble(0.11f, 0.10f, 0.4f, 0.4f, 0.65f)
        val higher = bubble(0.10f, 0.10f, 0.4f, 0.4f, 0.95f)

        val result = GuidedDetectionPostprocessor.process(listOf(lower, higher), ltr)

        assertEquals(1, result.size)
        assertEquals(0.95f, result.single().confidence)
    }

    @Test
    fun `does not merge neighboring bubbles just because their padding overlaps`() {
        val first = bubble(0.10f, 0.10f, 0.30f, 0.30f)
        val second = bubble(0.32f, 0.10f, 0.52f, 0.30f)

        assertEquals(2, GuidedDetectionPostprocessor.process(listOf(second, first), ltr).size)
    }

    @Test
    fun `orders western speech bubbles left to right and rows top to bottom`() {
        val a = bubble(0.1f, 0.1f, 0.3f, 0.3f)
        val b = bubble(0.7f, 0.1f, 0.9f, 0.3f)
        val c = bubble(0.1f, 0.7f, 0.3f, 0.9f)
        val result = GuidedDetectionPostprocessor.process(listOf(c, b, a), ltr)
        assertEquals(listOf(a, b, c).map { it.confidence }, result.map { it.confidence })
        assertTrue(result[0].bounds.centerX < result[1].bounds.centerX)
        assertTrue(result[1].bounds.centerY < result[2].bounds.centerY)
    }

    @Test
    fun `orders manga bubbles right to left`() {
        val left = bubble(0.1f, 0.1f, 0.3f, 0.3f, 0.85f)
        val right = bubble(0.7f, 0.1f, 0.9f, 0.3f, 0.95f)

        val result = GuidedDetectionPostprocessor.process(listOf(left, right), rtl)

        assertEquals(listOf(0.95f, 0.85f), result.map { it.confidence })
    }

    @Test
    fun `padding is clipped to the page boundaries`() {
        val edge = bubble(0f, 0f, 0.2f, 0.2f)
        val box = GuidedDetectionPostprocessor.process(listOf(edge), ltr).single().bounds

        assertEquals(0f, box.left)
        assertEquals(0f, box.top)
        assertTrue(box.right <= 1f && box.bottom <= 1f)
        assertTrue(box.width > edge.bounds.width)
    }

    @Test
    fun `empty model output has an empty fallback`() {
        assertTrue(GuidedDetectionPostprocessor.process(emptyList(), rtl).isEmpty())
    }

    private fun bubble(
        left: Float, top: Float, right: Float, bottom: Float, confidence: Float = 0.9f,
    ) = GuidedRegion(NormalizedRect(left, top, right, bottom), confidence)
}
