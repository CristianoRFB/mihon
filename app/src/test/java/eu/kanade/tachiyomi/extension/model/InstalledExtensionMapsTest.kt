package eu.kanade.tachiyomi.extension.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InstalledExtensionMapsTest {

    private fun empty() = InstalledExtensionMaps<String, String>()

    @Test
    fun `new state starts empty`() {
        val state = empty()

        state.loaded shouldBe emptyMap()
        state.notLoaded shouldBe emptyMap()
    }

    @Test
    fun `trusting a package moves it into loaded without losing other packages`() {
        val initial = empty()
            .withNotLoaded("a", "untrusted-a")
            .withNotLoaded("b", "untrusted-b")

        val trusted = initial.withLoaded("a", "loaded-a")

        trusted.loaded shouldBe mapOf("a" to "loaded-a")
        trusted.notLoaded shouldBe mapOf("b" to "untrusted-b")
        initial.notLoaded.keys shouldBe setOf("a", "b")
    }

    @Test
    fun `revoking trust moves a loaded package into not loaded`() {
        val state = empty().withLoaded("a", "loaded-a").withNotLoaded("a", "untrusted-a")

        state.loaded shouldBe emptyMap()
        state.notLoaded shouldBe mapOf("a" to "untrusted-a")
    }

    @Test
    fun `replacing an extension updates its classification without duplicates`() {
        val state = empty()
            .withLoaded("a", "v1")
            .withNotLoaded("a", "blocked-v2")
            .withLoaded("a", "v2")

        state.loaded shouldBe mapOf("a" to "v2")
        state.notLoaded shouldBe emptyMap()
    }

    @Test
    fun `installing into the same group replaces the package entry`() {
        val state = empty().withLoaded("a", "v1").withLoaded("a", "v2")

        state.loaded shouldBe mapOf("a" to "v2")
        state.loaded.size shouldBe 1
    }

    @Test
    fun `removing a package clears either classification`() {
        val state = empty()
            .withLoaded("a", "loaded-a")
            .withNotLoaded("b", "blocked-b")
            .without("a")
            .without("b")

        state shouldBe empty()
    }

    @Test
    fun `removing an unknown package is harmless`() {
        val initial = empty().withLoaded("a", "loaded-a")

        initial.without("missing") shouldBe initial
    }

    @Test
    fun `many consecutive trust changes never drop unrelated extensions`() {
        val names = (1..100).map { "extension-$it" }
        var state = names.fold(empty()) { snapshot, name ->
            snapshot.withNotLoaded(name, "pending-$name")
        }

        names.forEachIndexed { index, name ->
            state = state.withLoaded(name, "active-$name")

            val installed = state.loaded.keys + state.notLoaded.keys
            installed.size shouldBe names.size
            installed.toSet() shouldBe names.toSet()
            state.loaded.size shouldBe index + 1
            state.notLoaded.size shouldBe names.size - index - 1
        }
    }

    @Test
    fun `each snapshot remains unchanged when another is published`() {
        val first = empty().withNotLoaded("a", "untrusted")
        val second = first.withLoaded("a", "trusted")
        val third = second.without("a")

        first.notLoaded shouldBe mapOf("a" to "untrusted")
        second.loaded shouldBe mapOf("a" to "trusted")
        third shouldBe empty()
    }
}
