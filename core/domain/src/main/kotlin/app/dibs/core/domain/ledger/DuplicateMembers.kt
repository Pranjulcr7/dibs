// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.MemberId

/**
 * Detects members that *look* like the same person (SPEC §6.3 case 26: the same
 * person joining on two devices produces two records).
 *
 * Why detection only: merging identities destroys information and cannot be
 * undone if wrong, so it must be an explicit user action — this function feeds
 * the "are these the same person?" prompt and nothing merges silently, ever.
 */
object DuplicateMembers {

    /** Candidate pairs, each and the list itself deterministically ordered. */
    fun candidates(state: GroupState): List<Pair<MemberId, MemberId>> = state.members.values
        .groupBy { it.name.trim().lowercase() }
        .values
        .filter { it.size > 1 }
        .flatMap { sameName ->
            val ids = sameName.map { it.id }.sorted()
            ids.flatMapIndexed { index, first ->
                ids.drop(index + 1).map { second -> first to second }
            }
        }
        .sortedWith(compareBy({ it.first }, { it.second }))
}
