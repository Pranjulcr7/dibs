// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.importer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SplitwiseCsvTest : FunSpec({

    val header = "Date,Description,Category,Cost,Currency,Alice,Bob,Carol"

    test("given a Splitwise export then members come from the header and rows reconstruct payer and shares") {
        val csv = """
            $header
            2026-05-01,Dinner,Food,30.00,USD,20.00,-10.00,-10.00
        """.trimIndent()

        val result = SplitwiseCsv.parse(csv).shouldBeInstanceOf<SplitwiseCsv.Result.Success>()
        result.memberNames shouldBe listOf("Alice", "Bob", "Carol")
        val row = result.rows.single().shouldBeInstanceOf<SplitwiseCsv.Row.Expense>()
        row.description shouldBe "Dinner"
        row.currency shouldBe "USD"
        row.totalMinor shouldBe 3000L
        // Alice's net +20 on a 30 expense: she paid 30, her own share is 10.
        row.paidByMinor shouldBe mapOf("Alice" to 3000L)
        row.owedByMinor shouldBe mapOf("Alice" to 1000L, "Bob" to 1000L, "Carol" to 1000L)
        row.dateEpochDay shouldBe java.time.LocalDate.of(2026, 5, 1).toEpochDay()
        row.category shouldBe "Food"
    }

    test("given multiple positive columns then payers share the residual owed by largest remainder, sums staying exact") {
        val csv = """
            $header
            2026-05-02,Airbnb,Travel,90.00,USD,40.00,20.00,-60.00
        """.trimIndent()

        val row = (SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success)
            .rows.single().shouldBeInstanceOf<SplitwiseCsv.Row.Expense>()
        row.owedByMinor.values.sum() shouldBe 9000L
        row.paidByMinor.values.sum() shouldBe 9000L
        // Carol owes her full 60; Alice and Bob split the remaining 30 of owed
        // 2:1 by credit; paid = owed + credit.
        row.owedByMinor["Carol"] shouldBe 6000L
        (row.paidByMinor["Alice"]!! - row.owedByMinor["Alice"]!!) shouldBe 4000L
        (row.paidByMinor["Bob"]!! - row.owedByMinor["Bob"]!!) shouldBe 2000L
    }

    test("given a Payment row then it converts to a settlement, not an expense") {
        val csv = """
            $header
            2026-05-03,Bob paid Alice,Payment,10.00,USD,10.00,-10.00,0.00
        """.trimIndent()

        val row = (SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success).rows.single()
        val settlement = row.shouldBeInstanceOf<SplitwiseCsv.Row.Settlement>()
        settlement.fromName shouldBe "Alice"
        settlement.toName shouldBe "Bob"
        settlement.amountMinor shouldBe 1000L
    }

    test("given a Total balance trailer then it is skipped and used as a checksum") {
        val csv = """
            $header
            2026-05-01,Dinner,Food,30.00,USD,20.00,-10.00,-10.00
            ,Total balance,,,USD,20.00,-10.00,-10.00
        """.trimIndent()

        val result = SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success
        result.rows.size shouldBe 1
        result.balanceChecksumMatches shouldBe true
    }

    test("given a checksum mismatch then the import still parses but flags it") {
        val csv = """
            $header
            2026-05-01,Dinner,Food,30.00,USD,20.00,-10.00,-10.00
            ,Total balance,,,USD,99.00,-10.00,-10.00
        """.trimIndent()

        (SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success).balanceChecksumMatches shouldBe false
    }

    test("given comma decimals then the separator is detected from the file, never mis-read as grouping") {
        val csv = """
            $header
            2026-05-01,Dinner,Food,"30,50",EUR,"20,50","-10,25","-10,25"
        """.trimIndent()

        val row = (SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success)
            .rows.single().shouldBeInstanceOf<SplitwiseCsv.Row.Expense>()
        row.totalMinor shouldBe 3050L
        row.owedByMinor["Bob"] shouldBe 1025L
    }

    test("given a non-English header then parsing fails with the re-export advice, never mis-maps") {
        val csv = """
            Datum,Beschreibung,Kategorie,Kosten,Währung,Alice,Bob
            2026-05-01,Abendessen,Essen,30.00,EUR,15.00,-15.00
        """.trimIndent()

        SplitwiseCsv.parse(csv).shouldBeInstanceOf<SplitwiseCsv.Result.UnrecognizedHeader>()
    }

    test("given malformed rows then each is skipped with a line number and reason while good rows survive") {
        val csv = """
            $header
            2026-05-01,Dinner,Food,30.00,USD,20.00,-10.00,-10.00
            not-a-date,Broken,Food,30.00,USD,20.00,-10.00,-10.00
            2026-05-02,Bad numbers,Food,abc,USD,20.00,-10.00,-10.00
            2026-05-03,Unbalanced,Food,30.00,USD,20.00,-5.00,-10.00
        """.trimIndent()

        val result = SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success
        result.rows.size shouldBe 1
        result.skipped.map { it.lineNumber } shouldBe listOf(3, 4, 5)
        result.skipped.forEach { it.reason.isNotBlank() shouldBe true }
    }

    test("given quoted fields with embedded commas and quotes then RFC-4180 parsing holds") {
        val csv = header + "\n" +
            "2026-05-01,\"Dinner, drinks \"\"and more\"\"\",Food,30.00,USD,20.00,-10.00,-10.00"

        val row = (SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success)
            .rows.single().shouldBeInstanceOf<SplitwiseCsv.Row.Expense>()
        row.description shouldBe "Dinner, drinks \"and more\""
    }

    test("given a multi-currency file then totals are reported per currency, never summed across") {
        val csv = """
            $header
            2026-05-01,Dinner,Food,30.00,USD,20.00,-10.00,-10.00
            2026-05-02,Tapas,Food,20.00,EUR,10.00,-10.00,0.00
        """.trimIndent()

        val result = SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success
        result.currencyTotalsMinor shouldBe mapOf("USD" to 3000L, "EUR" to 2000L)
    }

    test("given the same row content then the derived expense id is stable per file, so re-imports dedupe") {
        val csv = """
            $header
            2026-05-01,Dinner,Food,30.00,USD,20.00,-10.00,-10.00
        """.trimIndent()

        val first = (SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success)
            .rows.single() as SplitwiseCsv.Row.Expense
        val second = (SplitwiseCsv.parse(csv) as SplitwiseCsv.Result.Success)
            .rows.single() as SplitwiseCsv.Row.Expense
        first.stableId shouldBe second.stableId

        val otherFile = csv.replace("Dinner", "Dinner2")
        val third = (SplitwiseCsv.parse(otherFile) as SplitwiseCsv.Result.Success)
            .rows.single() as SplitwiseCsv.Row.Expense
        (third.stableId == first.stableId) shouldBe false
    }

    test("given an empty file or missing member columns then parsing fails cleanly") {
        SplitwiseCsv.parse("").shouldBeInstanceOf<SplitwiseCsv.Result.UnrecognizedHeader>()
        SplitwiseCsv.parse("Date,Description,Category,Cost,Currency")
            .shouldBeInstanceOf<SplitwiseCsv.Result.UnrecognizedHeader>()
    }
})
