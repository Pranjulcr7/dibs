// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.money.Money
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
@Suppress("TooManyFunctions") // one small encoder per canonical-format element
object OpFactory {

    // v2 (pre-release exception D31): ExpenseFields gained category/receiptRef/
    // splitMode; ExpenseEdit gained category; AmountsEdit gained splitMode.
    private const val FORMAT_VERSION = "dibs-op-v2"

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
        val canonical = canonicalEncoding(groupId, deviceId, lamport, parents, wallClockMillis, payload)
        val hash = ByteArray(HASH_LENGTH_BYTES)
        Blake3.initHash().update(canonical.toByteArray(Charsets.UTF_8)).doFinalize(hash)
        return OpId(hash.toLowercaseHex())
    }

    /**
     * The exact byte layout op ids are hashed over. Internal so the golden-vector
     * test can pin it byte for byte — any drift here silently changes every op id
     * and breaks sync with released versions, so it must fail a test, not a user.
     */
    @Suppress("LongParameterList") // the six fields of an Op are one cohesive record
    internal fun canonicalEncoding(
        groupId: GroupId,
        deviceId: DeviceId,
        lamport: Long,
        parents: Set<OpId>,
        wallClockMillis: Long,
        payload: OpPayload,
    ): String = buildString {
        str(FORMAT_VERSION)
        str(groupId.value)
        str(deviceId.value)
        num(lamport)
        num(wallClockMillis)
        list(parents.map { it.value }.sorted()) { str(it) }
        payload(payload)
    }

    private const val HASH_LENGTH_BYTES = 32
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Hand-rolled instead of commons-codec's `Hex`: Android's boot classpath
     * carries an ancient `org.apache.commons.codec.binary.Hex` (inside
     * `org.apache.http.legacy.jar`) that shadows the bundled library on real
     * devices and lacks `encodeHexString` — a `NoSuchMethodError` the on-device
     * test caught. `digest.Blake3` has no framework copy, so it stays.
     */
    private fun ByteArray.toLowercaseHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return out.toString()
    }

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
        optStr(fields.category)
        optStr(fields.receiptRef)
        optStr(fields.splitMode)
    }

    // One exhaustive when per payload type; splitting would hide the format.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun StringBuilder.payload(payload: OpPayload) {
        when (payload) {
            is OpPayload.CreateGroup -> {
                str("create_group")
                str(payload.name)
                optStr(payload.emoji)
                if (payload.colorArgb == null) append("n;") else num(payload.colorArgb)
                str(payload.defaultCurrency.value)
                str(if (payload.adHoc) "1" else "0")
            }
            is OpPayload.EditGroup -> {
                str("edit_group")
                optStr(payload.name)
                // Presence markers, as for NoteEdit: "untouched" and "cleared"
                // must never encode alike.
                if (payload.emoji == null) {
                    append("n;")
                } else {
                    append("p;")
                    optStr(payload.emoji.value)
                }
                if (payload.colorArgb == null) {
                    append("n;")
                } else {
                    append("p;")
                    if (payload.colorArgb.value == null) append("n;") else num(payload.colorArgb.value)
                }
                if (payload.defaultCurrency == null) append("n;") else str(payload.defaultCurrency.value)
            }
            is OpPayload.ReactivateMember -> {
                str("reactivate_member")
                str(payload.memberId.value)
            }
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
                    optStr(amounts.splitMode)
                }
                if (payload.edit.category == null) {
                    append("n;")
                } else {
                    append("p;")
                    optStr(payload.edit.category.value)
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
            is OpPayload.CreateInvite -> {
                str("create_invite")
                str(payload.inviteId.value)
                str(payload.inviterKey.hex)
                num(payload.maxUses.toLong())
                num(payload.expiresAtMillis)
                str(payload.signature)
            }
            is OpPayload.RevokeInvite -> {
                str("revoke_invite")
                str(payload.inviteId.value)
                str(payload.revokerKey.hex)
                str(payload.signature)
            }
            is OpPayload.ClaimMember -> {
                str("claim_member")
                str(payload.memberId.value)
                optStr(payload.displayName)
                optStr(payload.inviteId?.value)
                str(payload.claimantKey.hex)
                str(payload.signature)
            }
            is OpPayload.ApproveMember -> {
                str("approve_member")
                str(payload.memberId.value)
                str(payload.approverKey.hex)
                str(payload.signature)
            }
            is OpPayload.RemoveMember -> {
                str("remove_member")
                str(payload.memberId.value)
                str(payload.removerKey.hex)
                str(payload.signature)
            }
            is OpPayload.SetJoinPolicy -> {
                str("set_join_policy")
                str(if (payload.requiresApproval) "1" else "0")
                str(payload.signerKey.hex)
                str(payload.signature)
            }
            is OpPayload.RecordSettlement -> {
                str("record_settlement")
                str(payload.settlementId.value)
                str(payload.from.value)
                str(payload.to.value)
                num(payload.amount.minorUnits)
                str(payload.currency.value)
                optStr(payload.kind)
            }
        }
    }
}
