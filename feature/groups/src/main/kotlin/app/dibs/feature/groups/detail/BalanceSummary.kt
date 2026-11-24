// SPDX-License-Identifier: Apache-2.0
package app.dibs.feature.groups.detail

import android.content.Context
import android.content.Intent
import app.dibs.core.domain.ledger.GroupState
import app.dibs.core.ui.money.MoneyFormatter
import app.dibs.feature.groups.R

/**
 * FR-S6 "Remind": who owes whom, as plain text for the share sheet. Dibs never
 * sends a message itself — it hands the user a summary to paste wherever they
 * already talk to the group, which also works for members who do not have the
 * app.
 *
 * The lines are the raw pairwise debts (FR-S1), not the simplified plan: a
 * reminder must only say things a person can check against the expenses they
 * remember.
 */
internal object BalanceSummary {

    fun text(context: Context, state: GroupState): String {
        val name = state.meta?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.groups_unnamed)
        val lines = state.pairwiseDebts().entries.sortedBy { it.key.value }.flatMap { (currency, debts) ->
            debts.map { debt ->
                context.getString(
                    R.string.remind_line,
                    state.memberName(debt.from),
                    state.memberName(debt.to),
                    MoneyFormatter.format(debt.amount, currency),
                )
            }
        }
        return if (lines.isEmpty()) {
            context.getString(R.string.remind_settled, name)
        } else {
            (listOf(context.getString(R.string.remind_header, name)) + lines).joinToString("\n")
        }
    }

    fun shareIntent(context: Context, state: GroupState): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text(context, state))
        }
        return Intent.createChooser(send, context.getString(R.string.remind_action))
    }
}
