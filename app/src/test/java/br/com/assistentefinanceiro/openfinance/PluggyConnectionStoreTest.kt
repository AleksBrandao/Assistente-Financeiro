package br.com.assistentefinanceiro.openfinance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluggyConnectionStoreTest {
    @Test
    fun normalizesHttpsBackendUrl() {
        assertEquals(
            "https://assistente-financeiro.vercel.app",
            PluggyConnectionStore.normalizeBackendUrl(
                " https://assistente-financeiro.vercel.app/ ",
            ),
        )
    }

    @Test
    fun rejectsNonHttpsBackendUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            PluggyConnectionStore.normalizeBackendUrl("http://localhost:3000")
        }
    }
}
