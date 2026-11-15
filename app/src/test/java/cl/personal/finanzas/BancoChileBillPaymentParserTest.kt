package cl.personal.finanzas

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class BancoChileBillPaymentParserTest {
    private val today = LocalDate.now().minusDays(1)
    private val spanishDate = today.format(
        DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-CL"))
    )

    private fun mail(
        total: String = "20.500",
        account: String = "****9999",
        amountA: String = "12.500",
        amountB: String = "8.000",
        includeDate: Boolean = true
    ): String = """
        Comprobante de pago de cuentas
        Adjuntamos comprobante de pago de las cuentas pagadas.
        Detalle cuenta(s) pagada(s)
        Empresa Servicio de prueba Uno
        ID 1234567
        Monto $amountA

        Empresa Servicio de prueba Dos
        ID 8765432
        Monto $amountB

        Detalle del pago
        Medio de pago Cuenta Corriente $account
        Total $total
        Código de autorización FICTICIO-ABC
        ${if (includeDate) "Fecha y Hora $spanishDate 18:51" else ""}
    """.trimIndent()

    @Test fun twoBillsProduceOneChargeWithExactSumAndTime() {
        val candidate = BancoChileBillPaymentParser.parse(mail(), "sample-source-hash", "9999")
        assertNotNull(candidate)
        assertEquals(today.toString(), candidate!!.operationDate)
        assertEquals(-20_500L, candidate.amountClp)
        assertEquals("Gastos comunes", candidate.category)
        assertTrue(candidate.description.startsWith("Pago de cuentas: "))
        assertTrue(candidate.description.contains("Servicio de prueba Uno"))
        assertTrue(candidate.description.contains("Servicio de prueba Dos"))
        assertTrue(candidate.note.contains("Hora operación: 18:51"))
    }

    @Test fun wrongAccountDoesNotProduceCandidate() {
        assertNull(BancoChileBillPaymentParser.parse(mail(), "source", "1234"))
    }

    @Test fun totalMustEqualAllBills() {
        assertNull(BancoChileBillPaymentParser.parse(mail(total = "25.000"), "source", "9999"))
    }

    @Test fun missingDateOrReceiptBodyDoesNotProduceCandidate() {
        assertNull(BancoChileBillPaymentParser.parse(mail(includeDate = false), "source", "9999"))
        assertNull(BancoChileBillPaymentParser.parse("Compra normal", "source", "9999"))
    }

    @Test fun duplicateBillWillNotBeRegisteredTwiceWithoutWarning() {
        val candidate = BancoChileBillPaymentParser.parse(mail(), "source", "9999")
        assertNotNull(candidate)
        val movements = listOf(Movement(account = "Banco de Chile", description = "Banco",
            amountClp = -20_500L, date = today.toString()))
        assertTrue(ChileBankEmailParser.possibleDuplicate(candidate!!, movements))
    }
}
