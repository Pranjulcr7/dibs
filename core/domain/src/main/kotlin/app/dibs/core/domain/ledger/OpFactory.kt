// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.Blake3

/**
 * Creates ops with their content-addressed ids.
 *
 * The id is BLAKE3 over a **hand-rolled canonical byte encoding** of the whole
 * op — header and payload. Two deliberate choices, both recorded in
 * DECISIONS.md:
 *
 * - *Hand-rolled, not a serialization library:* kotlinx-JSON writes properties
 *   in declaration order, so an innocent refactor could silently change every
 *   op id and split every group's history. The canonical format below is
 *   versioned, explicit about field order, sorts every map and set, and
 *   length-prefixes strings so no delimiter injection is possible.
 * - *Whole op, not payload alone as SPEC §5.5 loosely words it:* an identical
 *   payload can legitimately recur ("rename it back"); if only the payload were
 *   hashed the second op would collide with the first and vanish under set
 *   union.
 */
object OpFactory {

    private const val FORMAT_VERSION = "dibs-op-v1"

    @Suppress("LongParameterList") // the six fields of an Op are one cohesive record
    fun create(
        groupId: GroupId,
        deviceId: DeviceId,
        lamport: Long,
        parents: Set<OpId>,
        wallClockMillis: Long,
        payload: OpPayload,
    ): Op {
        val id = computeId(groupId, deviceId, lamport, parents, wallClockMillis, payload)
        return Op(id, groupId, lamport, deviceId, parents, wallClockMillis, payload)
    }

    @Suppress("LongParameterList") // the six fields of an Op are one cohesive record
    fun computeId(
        groupId: GroupId,
        deviceId: DeviceId,
        lamport: Long,
        parents: Set<OpId>,
        wallClockMillis: Long,
        payload: OpPayload,
    ): OpId {
        val canonical = buildString {
            str(FORMAT_VERSION)
            str(groupId.value)
            str(deviceId.value)
            num(lamport)
            num(wallClockMillis)
            list(parents.map { it.value }.sorted()) { str(it) }
            payload(payload)
        }
        val hash = ByteArray(HASH_LENGTH_BYTES)
        Blake3.initHash().update(canonical.toByteArray(Charsets.UTF_8)).doFinalize(hash)
        return OpId(Hex.encodeHexString(hash))
    }

    private const val HASH_LENGTH_BYTES = 32

    // -- canonical encoding -------------------------------------------------
    // Strings are length-prefixed by UTF-8 byte count so no content can smuggle
    // a delimiter; maps and sets are always sorted by key; every payload type
    // has a fixed tag and field order. Changing any of this is a breaking
    // ledger-format change and requires a new FORMAT_VERSION.

    private fun StringBuilder.str(value: String) {
        append('s').append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value).append(';')
    }

    private fun StringBuilder.optStr(value: String?) {
        if (value == null) append("n;") else str(value)
    }

    private fun StringBuilder.num(value: Long) {
        append('i').append(value).append(';')
    }

    private fun <T> StringBuilder.list(values: List<T>, encode: StringBuilder.(T) -> Unit) {
        append('l').append(values.size).append(':')
        values.forEach { encode(it) }
        append(';')
    }

    private fun StringBuilder.moneyMap(map: Map<MemberId, Money>) {
        list(map.entries.sortedBy { it.key }) { (member, amount) ->
            str(member.value)
            num(amount.minorUnits)
        }
    }

    private fun StringBuilder.expenseFields(fields: ExpenseFields) {
        str(fields.description)
        str(fields.currency.value)
        num(fields.total.minorUnits)
        moneyMap(fields.paidBy)
        moneyMap(fields.owedBy)
        num(fields.dateEpochDay)
        optStr(fields.note)
    }

    @Suppress("LongMethod") // one exhaustive when over every payload type; splitting would hide the format
    private fun StringBuilder.payload(payload: OpPayload) {
        when (payload) {
            is OpPayload.AddMember -> {
                str("add_member")
                str(payload.memberId.value)
                str(payload.name)
            }
            is OpPayload.RenameMember -> {
                str("rename_member")
                str(payload.memberId.value)
                str(payload.newName)
            }
            is OpPayload.DeactivateMember -> {
                str("deactivate_member")
                str(payload.memberId.value)
            }
            is OpPayload.AddExpense -> {
                str("add_expense")
                str(payload.expenseId.value)
                expenseFields(payload.expense)
            }
            is OpPayload.EditExpense -> {
                str("edit_expense")
                str(payload.expenseId.value)
                optStr(payload.edit.description)
                // "note untouched" and "note cleared" must not encode alike: a
                // presence marker precedes the (possibly null) new value.
                if (payload.edit.note == null) {
                    append("n;")
                } else {
                    append("p;")
                    optStr(payload.edit.note.value)
                }
                if (payload.edit.dateEpochDay == null) append("n;") else num(payload.edit.dateEpochDay)
                val amounts = payload.edit.amounts
                if (amounts == null) {
                    append("n;")
                } else {
                    str(amounts.currency.value)
                    num(amounts.total.minorUnits)
                    moneyMap(amounts.paidBy)
                    moneyMap(amounts.owedBy)
                }
            }
            is OpPayload.DeleteExpense -> {
                str("delete_expense")
                str(payload.expenseId.value)
            }
            is OpPayload.RestoreExpense -> {
                str("restore_expense")
                str(payload.expenseId.value)
            }
            is OpPayload.RecordSettlement -> {
                str("record_settlement")
                str(payload.settlementId.value)
                str(payload.from.value)
                str(payload.to.value)
                num(payload.amount.minorUnits)
                str(payload.currency.value)
            }
        }
    }
}
