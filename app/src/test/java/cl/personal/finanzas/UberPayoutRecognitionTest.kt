package cl.personal.finanzas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UberPayoutRecognitionTest {
    private fun movement(
        description: String, amount: Long, account: String = "Banco de Chile",
        category: String = "Importado"
    ): Movement = Movement(
        account = account,
        description = description,
        amountClp = amount,
        date = "2026-11-16",
        category = category
    )

    @Test fun recognizesPositiveBankDepositFromRegisteredUberCompany() {
        val entry = movement("ABONO PAGO PROVEEDORES UBER PAGOS CHILE SPA", 67_890L)
        assertTrue(UberPayoutRecognition.matches(entry))
        val categorized = UberPayoutRecognition.classify(entry)
        assertEquals("Trabajo", categorized.category)
        assertEquals(entry.description, categorized.description)
        assertEquals(entry.amountClp, categorized.amountClp)
        assertEquals(entry.account, categorized.account)
    }

    @Test fun paymentAmountsCanVaryBetweenWeeks() {
        assertTrue(UberPayoutRecognition.matches(
            movement("Transferencia Uber Pagos Chile SpA", 21_700L)
        ))
        assertTrue(UberPayoutRecognition.matches(
            movement("UBER PAGOS CHILE SPA", 105_000L)
        ))
    }

    @Test fun rejectsNegativeUberTripPurchases() {
        assertFalse(UberPayoutRecognition.matches(
            movement("PAYU *UBER TRIP", -2_300L)
        ))
        assertFalse(UberPayoutRecognition.matches(
            movement("CARGO UBER PAGOS CHILE SPA", -67_890L)
        ))
    }

    @Test fun doesNotInferUberFromRidesOrGenericProviderPayments() {
        assertFalse(UberPayoutRecognition.matches(
            movement("PAGO PROVEEDORES", 67_890L)
        ))
        assertFalse(UberPayoutRecognition.matches(
            movement("PAYU UBER TRIP", 67_890L)
        ))
        assertFalse(UberPayoutRecognition.matches(
            movement("Transferencia UBER DRIVER", 67_890L)
        ))
    }

    @Test fun neverClassifiesAnotherBankAccountAsUberPayout() {
        assertFalse(UberPayoutRecognition.matches(
            movement("UBER PAGOS CHILE SPA", 67_890L, "MACH")
        ))
    }

    @Test fun preservesUserChosenCategories() {
        val m = movement("UBER PAGOS CHILE SPA", 67_890L, category = "Ingresos extras")
        assertEquals(m.category, UberPayoutRecognition.classify(m).category)
    }

    @Test fun displaysFriendlyNameWithoutOverwritingOriginalOrManualAlias() {
        val m = movement("UBER PAGOS CHILE SPA", 67_890L)
        assertEquals("Uber · Ganancias",
            UberPayoutRecognition.displayName(m, null))
        assertEquals("Mi pago de Uber",
            UberPayoutRecognition.displayName(m, "Mi pago de Uber"))
        assertEquals("UBER PAGOS CHILE SPA", m.description)
    }
}
