// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.model

/**
 * Stable identifier of a group member.
 *
 * Why `Comparable`: member UUIDs are the deterministic tiebreak for every
 * algorithm in the domain — leftover cents, settlement ordering, result row
 * ordering. Sorting by this id instead of insertion order is what makes two
 * devices compute byte-identical results (CLAUDE.md invariant 6). Store the
 * canonical lowercase UUID string; comparison is plain lexicographic so it never
 * depends on locale or platform.
 */
@JvmInline
value class MemberId(val value: String) : Comparable<MemberId> {
    init {
        require(value.isNotBlank()) { "MemberId must not be blank" }
    }

    override fun compareTo(other: MemberId): Int = value.compareTo(other.value)
}

/** Stable identifier of an expense; see [MemberId] for why ids are the only ordering key. */
@JvmInline
value class ExpenseId(val value: String) {
    init {
        require(value.isNotBlank()) { "ExpenseId must not be blank" }
    }
}

/** Stable identifier of a group; see [MemberId] for why ids are the only ordering key. */
@JvmInline
value class GroupId(val value: String) {
    init {
        require(value.isNotBlank()) { "GroupId must not be blank" }
    }
}
