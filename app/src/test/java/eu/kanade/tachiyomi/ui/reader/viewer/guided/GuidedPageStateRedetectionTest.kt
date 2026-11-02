package eu.kanade.tachiyomi.ui.reader.viewer.guided

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuidedPageStateRedetectionTest {
    private val left = GuidedRegion(NormalizedRect(0.05f, 0.1f, 0.4f, 0.35f), 0.9f)
    private val right = GuidedRegion(NormalizedRect(0.55f, 0.1f, 0.9f, 0.35f), 0.9f)

    @Test
    fun `selection follows the same bubble when detections change order`() {
        val state = GuidedPageState()
        state.setRegions(listOf(left, right))
        state.toggleRegion(1)
        state.markLoading()

        assertEquals(GuidedPageState.Step.Focus(right), state.setRegions(listOf(right, left)))
        assertEquals(0, state.currentIndex)
        assertEquals(GuidedPageState.Step.Focus(left), state.forward())
    }

    @Test
    fun `lost selection returns to whole page instead of invalid index`() {
        val state = GuidedPageState()
        state.setRegions(listOf(left, right))
        state.toggleRegion(1)
        state.markLoading()

        assertEquals(GuidedPageState.Step.ShowWholePage, state.setRegions(listOf(left)))
        assertEquals(-1, state.currentIndex)
        assertEquals(GuidedPageState.Step.Focus(left), state.forward())
    }

    @Test
    fun `re-detection keeps selection despite small bounding box movement`() {
        val state = GuidedPageState()
        val shiftedRight = GuidedRegion(NormalizedRect(0.56f, 0.11f, 0.91f, 0.36f), 0.87f)
        state.setRegions(listOf(left, right))
        state.toggleRegion(1)
        state.markLoading()

        assertEquals(GuidedPageState.Step.Focus(shiftedRight), state.setRegions(listOf(left, shiftedRight)))
        assertEquals(1, state.currentIndex)
    }

    @Test
    fun `re-detection to zero bubbles clears selection and retains normal fallback`() {
        val state = GuidedPageState()
        state.setRegions(listOf(left, right))
        state.toggleRegion(1)
        state.markLoading()

        assertEquals(GuidedPageState.Step.ShowWholePage, state.setRegions(emptyList()))
        assertEquals(-1, state.currentIndex)
        assertEquals(
            GuidedPageState.Step.LeavePage(GuidedPageState.Direction.FORWARD),
            state.forward(),
        )
    }

    @Test
    fun `pending next navigation is not lost on re-detection`() {
        val state = GuidedPageState()
        state.setRegions(listOf(left, right))
        state.toggleRegion(0)
        state.markLoading()
        assertEquals(GuidedPageState.Step.Wait, state.forward())

        assertEquals(GuidedPageState.Step.Focus(right), state.setRegions(listOf(left, right)))
        assertEquals(1, state.currentIndex)
    }
}
