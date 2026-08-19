// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.receipt

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RuleBasedLayoutParserTest : FunSpec({

    // Fixture helpers: a 600px-wide receipt; labels on the left, prices
    // right-aligned at x=600, one row every 30px.
    var y = 0
    fun label(text: String, top: Int) = TextBox(text, 20, top, 20 + text.length * 10, top + 20)
    fun price(text: String, top: Int) = TextBox(text, 600 - text.length * 10, top, 600, top + 20)
    fun row(name: String, amount: String): List<TextBox> {
        val top = y
        y += 30
        return listOf(label(name, top), price(amount, top))
    }
    beforeTest { y = 0 }

    test("given a clean receipt then items, subtotal, tax, and total parse and the gate passes") {
        val boxes = row("Latte", "4.50") + row("Bagel", "3.25") +
            row("Subtotal", "7.75") + row("Tax", "0.62") + row("Total", "8.37")

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.items shouldBe listOf(ReceiptItem("Latte", 1, 450), ReceiptItem("Bagel", 1, 325))
        draft.subtotalMinor shouldBe 775
        draft.taxMinor shouldBe 62
        draft.totalMinor shouldBe 837
        draft.gatePassed.shouldNotBeNull().shouldBeTrue()
        draft.flags shouldBe emptySet<ReceiptFlag>()
    }

    test("given quantity prefixes then quantity is extracted and stripped from the label") {
        val boxes = row("2 x Coffee", "9.00") + row("QTY 3 Donut", "4.50") + row("Total", "13.50")

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.items shouldBe listOf(ReceiptItem("Coffee", 2, 900), ReceiptItem("Donut", 3, 450))
        draft.gatePassed.shouldNotBeNull().shouldBeTrue()
    }

    test("given a quantity column of bare digits then it is never mistaken for a price column") {
        // "2" sits mid-row; only "9.00"-style right-aligned decimals are prices.
        val boxes = listOf(
            label("Coffee", 0),
            TextBox("2", 400, 0, 410, 20),
            price("9.00", 0),
            label("Total", 30),
            price("9.00", 30),
        )

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.items shouldBe listOf(ReceiptItem("Coffee 2", 1, 900))
    }

    test("edge 39 — given items that do not sum to the printed total then the gate flags it") {
        val boxes = row("Latte", "4.50") + row("Bagel", "3.25") + row("Total", "9.99")

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.gatePassed.shouldNotBeNull().shouldBeFalse()
        draft.flags shouldContain ReceiptFlag.ARITHMETIC_MISMATCH
    }

    test("edge 40 — given OCR misreading one digit then the sum mismatch is caught by the gate") {
        // The paper said 8.37; the engine hallucinated 3.37.
        val boxes = row("Latte", "4.50") + row("Bagel", "3.25") + row("Tax", "0.62") + row("Total", "3.37")

        RuleBasedLayoutParser.parse(boxes).gatePassed.shouldNotBeNull().shouldBeFalse()
    }

    test("given a sum within two minor units of the total then the 0-point-02 gate still passes") {
        val boxes = row("Latte", "4.50") + row("Bagel", "3.25") + row("Total", "7.77")

        RuleBasedLayoutParser.parse(boxes).gatePassed.shouldNotBeNull().shouldBeTrue()
    }

    test("edge 41 — given a negative discount line then it parses as a negative item and the math works") {
        val boxes = row("Pizza", "12.00") + row("Coupon", "-2.00") + row("Total", "10.00")

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.items shouldContain ReceiptItem("Coupon", 1, -200)
        draft.gatePassed.shouldNotBeNull().shouldBeTrue()
    }

    test("given a parenthesized amount then it reads as negative") {
        RuleBasedLayoutParser.parsePrice("(2.00)") shouldBe -200L
        RuleBasedLayoutParser.parsePrice("2.00-") shouldBe -200L
        RuleBasedLayoutParser.parsePrice("$1,234.56") shouldBe 123456L
        RuleBasedLayoutParser.parsePrice("12") shouldBe null
        RuleBasedLayoutParser.parsePrice("12.345") shouldBe null
    }

    test("edge 43 — given a total-only receipt then it degrades to a single-amount draft with a flag") {
        val boxes = listOf(label("Corner Store", 0)) + row("Total", "23.10") + listOf(label("Thank you!", 90))

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.items shouldBe emptyList<ReceiptItem>()
        draft.totalMinor shouldBe 2310
        draft.flags shouldContain ReceiptFlag.TOTAL_ONLY
        draft.gatePassed.shouldBeNull()
    }

    test("edge 37 — given nearly no recognizable text then the draft says so instead of guessing") {
        val draft = RuleBasedLayoutParser.parse(listOf(label("~~~", 0)))
        draft.flags shouldContain ReceiptFlag.LOW_TEXT
        draft.items shouldBe emptyList<ReceiptItem>()
    }

    test("edge 36 — given an unclassifiable price row after the summary then it is flagged, not guessed") {
        val boxes = row("Latte", "4.50") + row("Total", "4.50") + row("Smudged thing", "1.00")

        RuleBasedLayoutParser.parse(boxes).flags shouldContain ReceiptFlag.UNPARSED_REGIONS
    }

    test("given cash and change rows then they are ignored, not treated as items") {
        val boxes = row("Latte", "4.50") + row("Total", "4.50") +
            row("Cash", "10.00") + row("Change", "5.50")

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.items shouldBe listOf(ReceiptItem("Latte", 1, 450))
        draft.totalMinor shouldBe 450
        draft.gatePassed.shouldNotBeNull().shouldBeTrue()
    }

    test("given multiple tax lines then they accumulate") {
        val boxes = row("Wine", "20.00") + row("GST", "1.00") + row("HST", "2.00") + row("Total", "23.00")

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.taxMinor shouldBe 300
        draft.gatePassed.shouldNotBeNull().shouldBeTrue()
    }

    test("given running totals then the last total wins") {
        val boxes = row("Latte", "4.50") + row("Total", "4.50") +
            row("Tip", "1.00") + row("Total", "5.50")

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.totalMinor shouldBe 550
        draft.gatePassed.shouldNotBeNull().shouldBeTrue()
    }

    test("given boxes arriving in scrambled order then rows still assemble by geometry") {
        val boxes = (row("Latte", "4.50") + row("Bagel", "3.25") + row("Total", "7.75")).shuffled(
            kotlin.random.Random(42),
        )

        val draft = RuleBasedLayoutParser.parse(boxes)
        draft.items shouldBe listOf(ReceiptItem("Latte", 1, 450), ReceiptItem("Bagel", 1, 325))
    }

    // -- multi-frame (FR-R7) ----------------------------------------------

    test("edge 35 — given two frames overlapping at the boundary then boundary items dedupe once") {
        val frame1 = ReceiptDraft(
            items = listOf(
                ReceiptItem("Latte", 1, 450),
                ReceiptItem("Bagel", 1, 325),
                ReceiptItem("Juice", 1, 300),
            ),
        )
        val frame2 = ReceiptDraft(
            items = listOf(
                ReceiptItem("Bagel", 1, 325),
                ReceiptItem("Juice", 1, 300),
                ReceiptItem("Soup", 1, 500),
            ),
            totalMinor = 1575,
        )

        val merged = RuleBasedLayoutParser.merge(listOf(frame1, frame2))
        merged.items.map { it.label } shouldBe listOf("Latte", "Bagel", "Juice", "Soup")
        merged.totalMinor shouldBe 1575
        merged.gatePassed.shouldNotBeNull().shouldBeTrue()
    }

    test("given two frames with a repeated identical item that is genuinely bought twice mid-frame then it survives") {
        // Overlap dedupe only trims the boundary run; two lattes inside one
        // frame are two lattes.
        val frame1 = ReceiptDraft(items = listOf(ReceiptItem("Latte", 1, 450), ReceiptItem("Latte", 1, 450)))
        val frame2 = ReceiptDraft(items = listOf(ReceiptItem("Soup", 1, 500)), totalMinor = 1400)

        val merged = RuleBasedLayoutParser.merge(listOf(frame1, frame2))
        merged.items.size shouldBe 3
        merged.gatePassed.shouldNotBeNull().shouldBeTrue()
    }

    test("given the summary was readable only on the last frame then merge takes it from there") {
        val frame1 = ReceiptDraft(items = listOf(ReceiptItem("Latte", 1, 450)))
        val frame2 = ReceiptDraft(
            items = emptyList(),
            subtotalMinor = 450,
            taxMinor = 36,
            totalMinor = 486,
            flags = setOf(ReceiptFlag.TOTAL_ONLY),
        )

        val merged = RuleBasedLayoutParser.merge(listOf(frame1, frame2))
        merged.items.size shouldBe 1
        merged.totalMinor shouldBe 486
        merged.flags shouldBe emptySet<ReceiptFlag>()
        merged.gatePassed.shouldNotBeNull().shouldBeTrue()
    }

    // -- fingerprint (edge 47) --------------------------------------------

    test("edge 47 — given the same parsed content then fingerprints collide and different content does not") {
        val a = ReceiptDraft(items = listOf(ReceiptItem("Latte", 1, 450)), totalMinor = 450)
        val b = ReceiptDraft(items = listOf(ReceiptItem("  LATTE ", 1, 450)), totalMinor = 450)
        val c = ReceiptDraft(items = listOf(ReceiptItem("Latte", 1, 451)), totalMinor = 451)

        ReceiptFingerprint.of(a) shouldBe ReceiptFingerprint.of(b)
        ReceiptFingerprint.of(a) shouldNotBe ReceiptFingerprint.of(c)
    }
})
