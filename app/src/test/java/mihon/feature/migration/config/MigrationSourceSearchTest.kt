package mihon.feature.migration.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MigrationSourceSearchTest {

    @Test
    fun `empty search shows all sources`() {
        matchesMigrationSourceSearch("Mangadex", "en", 123L, "") shouldBe true
        matchesMigrationSourceSearch("Mangadex", "en", 123L, "  ") shouldBe true
    }

    @Test
    fun `name matching ignores case and surrounding whitespace`() {
        matchesMigrationSourceSearch("MangaDex", "en", 123L, "  dex ") shouldBe true
        matchesMigrationSourceSearch("MangaDex", "en", 123L, "mangadEX") shouldBe true
        matchesMigrationSourceSearch("MangaDex", "en", 123L, "no match") shouldBe false
    }

    @Test
    fun `language matching supports locale identifiers`() {
        matchesMigrationSourceSearch("Example", "pt-BR", 123L, "br") shouldBe true
        matchesMigrationSourceSearch("Example", "ja", 123L, "JA") shouldBe true
    }

    @Test
    fun `source id is searchable`() {
        matchesMigrationSourceSearch("Example", "en", 123456L, "345") shouldBe true
        matchesMigrationSourceSearch("Example", "en", 123456L, "999") shouldBe false
    }
}
