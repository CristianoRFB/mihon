package eu.kanade.tachiyomi.ui.updates

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UpdatesClearCutoffTest {

    @Test
    fun `new updates after the cutoff appear`() {
        isUpdateNewerThanCleanup(2_001L, 2_000L) shouldBe true
        isUpdateNewerThanCleanup(9_000L, 2_000L) shouldBe true
    }

    @Test
    fun `old updates and entries at the cutoff are hidden`() {
        isUpdateNewerThanCleanup(1_999L, 2_000L) shouldBe false
        isUpdateNewerThanCleanup(2_000L, 2_000L) shouldBe false
    }

    @Test
    fun `initial preference preserves all existing timestamped updates`() {
        isUpdateNewerThanCleanup(1_000L, 0L) shouldBe true
        isUpdateNewerThanCleanup(0L, 0L) shouldBe true
        isUpdateNewerThanCleanup(-1L, 0L) shouldBe true
    }

    @Test
    fun `cutoff is now unless an existing fetch has a future timestamp`() {
        updateClearCutoff(2_000L, 1_900L) shouldBe 2_000L
        updateClearCutoff(2_000L, 2_100L) shouldBe 2_100L
        updateClearCutoff(2_000L, null) shouldBe 2_000L
    }
}
