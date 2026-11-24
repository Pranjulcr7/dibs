// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.receipt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The scan-review → editor hand-off (FR-R5): amounts cross as integer minor units, never text or floats. */
class ReceiptHandoffTest {

    private val draft = ReceiptDraft(
        items = listOf(
            ReceiptItem("Latte", quantity = 2, totalMinor = 900),
            ReceiptItem("Coupon", totalMinor = -150),
        ),
        taxMinor = 61,
        tipMinor = null,
        totalMinor = 811,
    )

    @Test
    fun `given a reviewed draft then the hand-off carries every item, tax, tip, and the receipt reference`() {
        val handoff = ReceiptHandoff.of(draft, receiptRef = "abc123.jpg")

        assertEquals(
            listOf(ReceiptHandoff.Item("Latte", 2, 900), ReceiptHandoff.Item("Coupon", 1, -150)),
            handoff.items,
        )
        assertEquals(61L, handoff.taxMinor)
        assertNull(handoff.tipMinor)
        assertEquals("abc123.jpg", handoff.receiptRef)
    }

    @Test
    fun `given an encoded hand-off then decoding returns it exactly, discounts and absent fields included`() {
        val handoff = ReceiptHandoff.of(draft, receiptRef = null)

        assertEquals(handoff, ReceiptHandoff.decode(handoff.encode()))
    }

    @Test
    fun `given a hand-off from a newer version with an unknown field then it still decodes`() {
        val text = """{"items":[{"label":"Tea","quantity":1,"amountMinor":300}],"futureField":true}"""

        assertEquals(ReceiptHandoff(listOf(ReceiptHandoff.Item("Tea", 1, 300))), ReceiptHandoff.decode(text))
    }

    @Test
    fun `given garbage or a wrong shape then decoding returns null instead of throwing`() {
        // A nav argument is untrusted input: a malformed one must degrade to an
        // empty editor, never crash the screen it opens.
        assertNull(ReceiptHandoff.decode("not json"))
        assertNull(ReceiptHandoff.decode("""{"items":"nope"}"""))
        assertNull(ReceiptHandoff.decode("""{"taxMinor":5}"""))
    }
}
