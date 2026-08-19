// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.model

import kotlinx.serialization.Serializable

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
@Serializable
@JvmInline
value class MemberId(val value: String) : Comparable<MemberId> {
    init {
        require(value.isNotBlank()) { "MemberId must not be blank" }
    }

    override fun compareTo(other: MemberId): Int = value.compareTo(other.value)
}

/** Stable identifier of an expense; see [MemberId] for why ids are the only ordering key. */
@Serializable
@JvmInline
value class ExpenseId(val value: String) : Comparable<ExpenseId> {
    init {
        require(value.isNotBlank()) { "ExpenseId must not be blank" }
    }

    override fun compareTo(other: ExpenseId): Int = value.compareTo(other.value)
}

/** Stable identifier of a group; see [MemberId] for why ids are the only ordering key. */
@Serializable
@JvmInline
value class GroupId(val value: String) : Comparable<GroupId> {
    init {
        require(value.isNotBlank()) { "GroupId must not be blank" }
    }

    override fun compareTo(other: GroupId): Int = value.compareTo(other.value)
}

/** Stable identifier of an invite; see [MemberId] for why ids are the only ordering key. */
@Serializable
@JvmInline
value class InviteId(val value: String) : Comparable<InviteId> {
    init {
        require(value.isNotBlank()) { "InviteId must not be blank" }
    }

    override fun compareTo(other: InviteId): Int = value.compareTo(other.value)
}

/** Stable identifier of a settlement payment; see [MemberId] for why ids are the only ordering key. */
@Serializable
@JvmInline
value class SettlementId(val value: String) : Comparable<SettlementId> {
    init {
        require(value.isNotBlank()) { "SettlementId must not be blank" }
    }

    override fun compareTo(other: SettlementId): Int = value.compareTo(other.value)
}
