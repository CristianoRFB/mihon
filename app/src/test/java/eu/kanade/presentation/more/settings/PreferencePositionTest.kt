package eu.kanade.presentation.more.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PreferencePositionTest {

    private fun item(title: String) = Preference.PreferenceItem.TextPreference(title = title)

    @Test
    fun `repeated titles have different child positions`() {
        val preferences = listOf(
            Preference.PreferenceGroup("Library", preferenceItems = listOf(item("Categories"), item("Categories"))),
        )

        preferences.preferencePosition(0, 0) shouldBe 1
        preferences.preferencePosition(0, 1) shouldBe 2
        preferences.preferencePosition(0, 0) shouldBe preferences.preferencePosition(0, 1).let { it - 1 }
    }

    @Test
    fun `group headers and spacers count towards next item`() {
        val preferences = listOf(
            Preference.PreferenceGroup("First", preferenceItems = listOf(item("One"), item("Two"))),
            item("Next"),
            Preference.PreferenceGroup("Last", preferenceItems = listOf(item("Three"))),
        )
        preferences.preferencePosition(0) shouldBe 0
        preferences.preferencePosition(0, 1) shouldBe 2
        preferences.preferencePosition(1) shouldBe 4
        preferences.preferencePosition(2) shouldBe 5
        preferences.preferencePosition(2, 0) shouldBe 6
    }

    @Test
    fun `invisible groups do not contribute list rows`() {
        val preferences = listOf(
            Preference.PreferenceGroup("Hidden", visible = false, preferenceItems = listOf(item("Not visible"))),
            item("Visible"),
        )
        preferences.preferencePosition(0) shouldBe -1
        preferences.preferencePosition(1) shouldBe 0
    }

    @Test
    fun `invalid preference indices are safely ignored`() {
        val preferences = listOf(item("Single"))
        preferences.preferencePosition(-1) shouldBe -1
        preferences.preferencePosition(0, 0) shouldBe -1
        preferences.preferencePosition(1) shouldBe -1
    }
}
