package br.com.assistentefinanceiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFingerprintTest {
    @Test
    fun identicalNotificationProducesSameFingerprint() {
        val first = fingerprint(
            body = "PIX recebido em 29/08/2026 às 12:38 no valor de R$ 58,00.",
            postedAt = 1_788_009_480_000,
        )
        val repeated = fingerprint(
            body = "PIX recebido em 29/08/2026 às 12:38 no valor de R$ 58,00.",
            postedAt = 1_788_009_480_000,
        )

        assertEquals(first, repeated)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun changedContentProducesDifferentFingerprint() {
        val first = fingerprint(
            body = "PIX recebido em 29/08/2026 às 12:38 no valor de R$ 58,00.",
            postedAt = 1_788_009_480_000,
        )
        val changed = fingerprint(
            body = "PIX recebido em 29/08/2026 às 12:38 no valor de R$ 59,00.",
            postedAt = 1_788_009_480_000,
        )

        assertNotEquals(first, changed)
    }

    @Test
    fun anotherPostingTimeProducesDifferentFingerprint() {
        val first = fingerprint(
            body = "Compra aprovada",
            postedAt = 1_788_009_480_000,
        )
        val later = fingerprint(
            body = "Compra aprovada",
            postedAt = 1_788_009_481_000,
        )

        assertNotEquals(first, later)
    }

    private fun fingerprint(body: String, postedAt: Long): String =
        NotificationFingerprint.create(
            packageName = "com.santander.app",
            title = "Santander",
            body = body,
            postedAt = postedAt,
        )
}
