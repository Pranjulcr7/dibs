// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.receipt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Feeds ground-truth boxes from the `model/` corpus (SROIE/CORD — see
 * model/README.md) straight into [RuleBasedLayoutParser] and reports rates.
 * This measures *layout rules only* — OCR is bypassed on purpose — and the
 * corpus is scanned Southeast Asian receipts, which do NOT predict
 * performance on photographed Western receipts. It exists to find rule bugs,
 * not to claim accuracy.
 *
 * Skipped unless `DIBS_RECEIPT_CORPUS` points at an exported corpus
 * directory. There is deliberately no pass/fail rate threshold: the numbers
 * go to PROGRESS.md, and regressions are caught by the named unit tests.
 */
class CorpusEvaluationTest {

    @Serializable
    private data class CorpusBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    @Serializable
    private data class CorpusReceipt(val boxes: List<CorpusBox>, val total: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `corpus report — parse rates over the layout corpus`() {
        val root = System.getenv("DIBS_RECEIPT_CORPUS")?.let(::File)
        assumeTrue(root != null && root.isDirectory, "DIBS_RECEIPT_CORPUS not set; skipping corpus report")

        checkNotNull(root).listFiles { file: File -> file.isDirectory }?.forEach(::report)
    }

    @Suppress("CyclomaticComplexMethod") // a tally loop; splitting it would obscure the report
    private fun report(datasetDir: File) {
        var receipts = 0
        var withItems = 0
        var withTotal = 0
        var gatePassed = 0
        var gateChecked = 0
        var totalMatchesLabel = 0
        var labeledTotals = 0

        datasetDir.listFiles { file: File -> file.extension == "json" }?.sorted()?.forEach { file ->
            val receipt = json.decodeFromString<CorpusReceipt>(file.readText())
            val draft = RuleBasedLayoutParser.parse(
                receipt.boxes.map { TextBox(it.text, it.left, it.top, it.right, it.bottom) },
            )
            receipts++
            if (draft.items.isNotEmpty()) withItems++
            if (draft.totalMinor != null) withTotal++
            if (draft.gatePassed != null) {
                gateChecked++
                if (draft.gatePassed == true) gatePassed++
            }
            val labeled = receipt.total?.let { parseLabeledTotal(it) }
            if (labeled != null) {
                labeledTotals++
                if (draft.totalMinor == labeled) totalMatchesLabel++
            }
        }

        fun pct(n: Int, of: Int) = if (of == 0) "n/a" else "%.1f%%".format(100.0 * n / of)
        println(
            """
            |corpus=${datasetDir.name} receipts=$receipts
            |  items found:        ${pct(withItems, receipts)}
            |  total found:        ${pct(withTotal, receipts)}
            |  gate checked:       ${pct(gateChecked, receipts)}
            |  gate passed:        ${pct(gatePassed, gateChecked)} (of checked)
            |  total == labeled:   ${pct(totalMatchesLabel, labeledTotals)} (of $labeledTotals labeled)
            """.trimMargin(),
        )
    }

    /** CORD labels totals like "25,000" (IDR, no decimals) or "8.37"; same rules as the parser. */
    private fun parseLabeledTotal(raw: String): Long? {
        val cleaned = raw.trim().replace(" ", "")
        return RuleBasedLayoutParser.parsePrice(cleaned)
            ?: RuleBasedLayoutParser.parseIntegerPrice(cleaned)
            ?: cleaned.replace(",", "").replace(".", "").toLongOrNull()
    }
}
