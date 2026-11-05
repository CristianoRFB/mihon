package mihon.feature.migration.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MigrationDestinationEligibilityTest {

    private val languages = setOf("en", "pt-BR")
    private val disabled = setOf(13L, 99L)

    @Test
    fun `enabled source in enabled language can be selected`() {
        isEligibleMigrationDestination(12L, "en", languages, disabled) shouldBe true
        isEligibleMigrationDestination(14L, "pt-BR", languages, disabled) shouldBe true
    }

    @Test
    fun `disabled source is excluded even if its language is enabled`() {
        isEligibleMigrationDestination(13L, "en", languages, disabled) shouldBe false
        isEligibleMigrationDestination(99L, "pt-BR", languages, disabled) shouldBe false
    }

    @Test
    fun `disabled language is excluded even if the source is enabled`() {
        isEligibleMigrationDestination(12L, "ja", languages, disabled) shouldBe false
        isEligibleMigrationDestination(12L, "pt", languages, disabled) shouldBe false
    }

    @Test
    fun `both disabled conditions remain excluded`() {
        isEligibleMigrationDestination(13L, "ja", languages, disabled) shouldBe false
    }

    @Test
    fun `reenabling a source makes it eligible without changing saved migration choices`() {
        isEligibleMigrationDestination(13L, "en", languages, disabled) shouldBe false
        isEligibleMigrationDestination(13L, "en", languages, emptySet()) shouldBe true
    }
}
