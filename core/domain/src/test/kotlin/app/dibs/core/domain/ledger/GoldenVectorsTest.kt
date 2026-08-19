// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import app.dibs.core.domain.model.ExpenseId
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.model.MemberId
import app.dibs.core.domain.model.SettlementId
import app.dibs.core.domain.money.CurrencyCode
import app.dibs.core.domain.money.Money
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Golden vectors freezing the on-the-wire identity of the ledger.
 *
 * Generated for format `dibs-op-v2` (regenerated once pre-release under the
 * documented D31 exception, adding category/receiptRef/splitMode). From the
 * first tagged release on, these constants are the contract with every device
 * that has ever written an op and must never be regenerated to make a failing
 * build pass: a mismatch means the canonical encoding drifted, which silently
 * splits every synced group's history.
 */
class GoldenVectorsTest : FunSpec({

    test("golden: given the fixed op then its canonical encoding matches the committed constant byte for byte") {
        withClue(ENCODING_CHANGED_MESSAGE) {
            OpFactory.canonicalEncoding(
                groupId = GoldenFixtures.group,
                deviceId = GoldenFixtures.device,
                lamport = GoldenFixtures.LAMPORT,
                parents = GoldenFixtures.parents,
                wallClockMillis = GoldenFixtures.WALL_CLOCK,
                payload = GoldenFixtures.payload,
            ) shouldBe GoldenFixtures.EXPECTED_CANONICAL_ENCODING
        }
    }

    test("golden: given the fixed op then its op id matches the committed hex constant") {
        withClue(ENCODING_CHANGED_MESSAGE) {
            OpFactory.computeId(
                groupId = GoldenFixtures.group,
                deviceId = GoldenFixtures.device,
                lamport = GoldenFixtures.LAMPORT,
                parents = GoldenFixtures.parents,
                wallClockMillis = GoldenFixtures.WALL_CLOCK,
                payload = GoldenFixtures.payload,
            ).value shouldBe GoldenFixtures.EXPECTED_OP_ID
        }
    }

    test("golden: given the fixed 3b ledger with group metadata then the folded state matches the committed constant") {
        withClue(ENCODING_CHANGED_MESSAGE) {
            val log = OpLog.empty().merge(GoldenFixtures.metaLedgerOps())
            log.project(GoldenFixtures.group).canonicalBytes().toString(Charsets.UTF_8) shouldBe
                GoldenFixtures.EXPECTED_META_FOLD_CANONICAL_STATE
        }
    }

    test("golden: given the fixed identity ledger then the folded state matches the committed constant") {
        withClue(ENCODING_CHANGED_MESSAGE) {
            val log = OpLog.empty().merge(GoldenFixtures.identityLedgerOps())
            log.project(GoldenFixtures.group).canonicalBytes().toString(Charsets.UTF_8) shouldBe
                GoldenFixtures.EXPECTED_IDENTITY_FOLD_CANONICAL_STATE
        }
    }

    test("golden: given the fixed ledger then the folded state's canonical bytes match the committed constant") {
        withClue(ENCODING_CHANGED_MESSAGE) {
            val log = OpLog.empty().merge(GoldenFixtures.ledgerOps())
            log.project(GoldenFixtures.group).canonicalBytes().toString(Charsets.UTF_8) shouldBe
                GoldenFixtures.EXPECTED_FOLD_CANONICAL_STATE
        }
    }
}) {
    companion object {
        const val ENCODING_CHANGED_MESSAGE =
            "Op id encoding changed. This breaks sync with all previously released versions. " +
                "If intentional, bump the canonical encoding version and write a migration."
    }
}

/**
 * Inputs chosen to exercise every encoding rule: unicode in strings (byte-length
 * prefixes), parents and money maps given in reversed order (canonical sorting),
 * a three-decimal currency, a null note, a cleared note (presence marker), and
 * one quarantined op (quarantine rendering).
 */
object GoldenFixtures {
    val group = GroupId("11111111-1111-4111-8111-111111111111")
    val device = DeviceId("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
    val alice = MemberId("0a000000-0000-4000-8000-000000000001")
    val bob = MemberId("0b000000-0000-4000-8000-000000000002")
    val carol = MemberId("0c000000-0000-4000-8000-000000000003")

    const val LAMPORT = 42L
    const val WALL_CLOCK = 1_755_000_000_000L

    // Deliberately in descending order; the encoding must sort them.
    val parents = linkedSetOf(OpId("b".repeat(64)), OpId("a".repeat(64)))

    val payload = OpPayload.AddExpense(
        expenseId = ExpenseId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
        expense = ExpenseFields(
            description = "Golden dinner ✓",
            currency = CurrencyCode("KWD"),
            total = Money(10_000),
            paidBy = mapOf(alice to Money(10_000)),
            // Deliberately not in member-id order; the encoding must sort it.
            owedBy = linkedMapOf(bob to Money(5_000), alice to Money(5_000)),
            dateEpochDay = 20_000,
            note = null,
            category = "food",
            receiptRef = null,
            splitMode = "equal",
        ),
    )

    /** A fixed causal chain covering every payload kind plus one quarantined op. */
    fun ledgerOps(): List<Op> {
        val ops = mutableListOf<Op>()
        fun add(payload: OpPayload): Op {
            val op = OpFactory.create(
                groupId = group,
                deviceId = device,
                lamport = ops.size + 1L,
                parents = ops.lastOrNull()?.let { setOf(it.id) } ?: emptySet(),
                wallClockMillis = 1_000L + ops.size,
                payload = payload,
            )
            ops += op
            return op
        }

        val e1 = ExpenseId("e1e1e1e1-0000-4000-8000-000000000000")
        add(OpPayload.AddMember(alice, "Alice"))
        add(OpPayload.AddMember(bob, "Bob"))
        add(OpPayload.RenameMember(bob, "Bobby"))
        add(
            OpPayload.AddExpense(
                e1,
                ExpenseFields(
                    description = "Dinner",
                    currency = CurrencyCode("USD"),
                    total = Money(1_000),
                    paidBy = mapOf(alice to Money(1_000)),
                    owedBy = mapOf(alice to Money(500), bob to Money(500)),
                    dateEpochDay = 20_000,
                    note = "first",
                ),
            ),
        )
        add(OpPayload.EditExpense(e1, ExpenseEdit(description = "Dinner at Luigi's", note = NoteEdit(null))))
        add(OpPayload.EditExpense(e1, ExpenseEdit(category = CategoryEdit("dining"))))
        add(OpPayload.DeleteExpense(e1))
        add(OpPayload.RestoreExpense(e1))
        add(OpPayload.RecordSettlement(SettlementId("51515151-0000-4000-8000-000000000000"), bob, alice, Money(250), CurrencyCode("USD")))
        // Unknown member: must fold into a quarantine entry, deterministically.
        add(OpPayload.RecordSettlement(SettlementId("52525252-0000-4000-8000-000000000000"), carol, alice, Money(100), CurrencyCode("USD")))
        return ops
    }

    /** Fixed chain for the 3b payloads: create/edit group metadata, deactivate/reactivate. */
    fun metaLedgerOps(): List<Op> {
        val ops = mutableListOf<Op>()
        fun add(payload: OpPayload): Op {
            val op = OpFactory.create(
                groupId = group,
                deviceId = device,
                lamport = ops.size + 1L,
                parents = ops.lastOrNull()?.let { setOf(it.id) } ?: emptySet(),
                wallClockMillis = 2_000L + ops.size,
                payload = payload,
            )
            ops += op
            return op
        }
        add(OpPayload.CreateGroup("Lisbon trip", emoji = "🌊", colorArgb = 0xFF8B5000, defaultCurrency = CurrencyCode("EUR")))
        add(OpPayload.EditGroup(name = "Lisbon 2026", emoji = EmojiEdit(null)))
        add(OpPayload.AddMember(alice, "Alice"))
        add(OpPayload.DeactivateMember(alice))
        add(OpPayload.ReactivateMember(alice))
        add(OpPayload.EditGroup(colorArgb = ColorEdit(null), defaultCurrency = CurrencyCode("USD")))
        add(OpPayload.SetArchived(archived = true))
        return ops
    }

    /**
     * Fixed chain for the SPEC §5.9 identity ops: bootstrap claim, invite,
     * approval mode, claim-with-approval, approve, revoke, a post-revoke claim
     * (quarantined), and a removal. Ed25519 signing is deterministic (RFC 8032),
     * so signatures — and therefore these constants — are stable.
     */
    fun identityLedgerOps(): List<Op> {
        val creator = SignedOps.keyPair(1)
        val joiner = SignedOps.keyPair(2)
        val late = SignedOps.keyPair(3)
        val invite = app.dibs.core.domain.model.InviteId("11110000-0000-4000-8000-000000000001")
        val jo = MemberId("0e000000-0000-4000-8000-000000000005")
        val sy = MemberId("0f000000-0000-4000-8000-000000000006")

        val ops = mutableListOf<Op>()
        fun add(payload: OpPayload): Op {
            val op = OpFactory.create(
                groupId = group,
                deviceId = device,
                lamport = ops.size + 1L,
                parents = ops.lastOrNull()?.let { setOf(it.id) } ?: emptySet(),
                wallClockMillis = 3_000L + ops.size,
                payload = payload,
            )
            ops += op
            return op
        }
        add(OpPayload.AddMember(alice, "Alice"))
        add(SignedOps.claimMember(group, alice, creator))
        add(SignedOps.setJoinPolicy(group, requiresApproval = true, creator))
        add(SignedOps.createInvite(group, invite, creator, maxUses = 2, expiresAtMillis = 5_000L))
        add(SignedOps.claimMember(group, jo, joiner, displayName = "Jo", inviteId = invite))
        add(SignedOps.approveMember(group, jo, creator))
        add(SignedOps.revokeInvite(group, invite, creator))
        // Post-revoke claim: must quarantine as InviteRevoked, byte-identically.
        add(SignedOps.claimMember(group, sy, late, displayName = "Sy", inviteId = invite))
        add(SignedOps.removeMember(group, jo, creator))
        return ops
    }

    val EXPECTED_IDENTITY_FOLD_CANONICAL_STATE: String = listOf(
        "state|11111111-1111-4111-8111-111111111111",
        "jp|true",
        "m|0a000000-0000-4000-8000-000000000001|5:Alice|true|h:5:Alice,9725eb6add7267a9aaff7b97928c1875ad6b7ad105e3443f0c87df477eb51f7f,dddddddd-dddd-4ddd-8ddd-dddddddddddd,1|k:0cebfee033dd07bd811cc23eef015c00ce2d68cb2aebf3c7f21a88cfc798edac",
        "m|0e000000-0000-4000-8000-000000000005|2:Jo|false|h:2:Jo,33ce7f18a8a0eaaa04decd96c8390c7b4f4e0e2ddfb8380fddc68415d21bcfbf,dddddddd-dddd-4ddd-8ddd-dddddddddddd,5|k:09960c390dde56fbfcdfa3783aa9ca783ef4349e72c29b59157494d17c2e775d|via:11110000-0000-4000-8000-000000000001|removed",
        "iv|11110000-0000-4000-8000-000000000001|0cebfee033dd07bd811cc23eef015c00ce2d68cb2aebf3c7f21a88cfc798edac|0a000000-0000-4000-8000-000000000001|2|5000|true|1",
        "q|0f61af963ee0581e74d95d73bf55023aa87c2685c11397999f0aea2a6b615fb1|InviteRevoked(inviteId=InviteId(value=11110000-0000-4000-8000-000000000001))",
    ).joinToString("\n", postfix = "\n")

    const val EXPECTED_OP_ID = "4060f81fbc6b546f1860d4d1472d9a8ee267992f8a3640fc448670e1092803ff"

    val EXPECTED_META_FOLD_CANONICAL_STATE: String = listOf(
        "state|11111111-1111-4111-8111-111111111111",
        "g|11:Lisbon 2026|null|null|USD|false",
        "ar|true",
        "m|0a000000-0000-4000-8000-000000000001|5:Alice|true|h:5:Alice,9bc4a93d6429947d598208ecc6eb6d990afe2bff3dd57fd3e51c8bc9c5d3354d,dddddddd-dddd-4ddd-8ddd-dddddddddddd,3",
    ).joinToString("\n", postfix = "\n")

    const val EXPECTED_CANONICAL_ENCODING =
        "s10:dibs-op-v2;s36:11111111-1111-4111-8111-111111111111;s36:dddddddd-dddd-4ddd-8ddd-dddddddddddd;i42;i1755000000000;l2:s64:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;s64:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb;;s11:add_expense;s36:eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee;s17:Golden dinner ✓;s3:KWD;i10000;l1:s36:0a000000-0000-4000-8000-000000000001;i10000;;l2:s36:0a000000-0000-4000-8000-000000000001;i5000;s36:0b000000-0000-4000-8000-000000000002;i5000;;i20000;n;s4:food;n;s5:equal;"

    val EXPECTED_FOLD_CANONICAL_STATE: String = listOf(
        "state|11111111-1111-4111-8111-111111111111",
        "m|0a000000-0000-4000-8000-000000000001|5:Alice|true|h:5:Alice,abb55bb3eeeda42eb53e5a6ef11c9c621f8a62d0e6f8fa66b94b566100263ad3,dddddddd-dddd-4ddd-8ddd-dddddddddddd,1",
        "m|0b000000-0000-4000-8000-000000000002|5:Bobby|true|h:3:Bob,5abd42f02366780e6efc0e403cd365ef158ee823f0c4d1aad432e4a1849f37c8,dddddddd-dddd-4ddd-8ddd-dddddddddddd,2|h:5:Bobby,1a4494b66932cb1d262455e0ea54535bb37f1ac314971becf84ef676341f4f5b,dddddddd-dddd-4ddd-8ddd-dddddddddddd,3",
        "e|e1e1e1e1-0000-4000-8000-000000000000|false|17:Dinner at Luigi's|USD|1000|20000|null|dining|null|null|p:0a000000-0000-4000-8000-000000000001,1000|o:0a000000-0000-4000-8000-000000000001,500|o:0b000000-0000-4000-8000-000000000002,500|h:CREATED,2037143d9b23ddd9bb974e67cd427644c5086e718654781ff1f70458a33eaeca,dddddddd-dddd-4ddd-8ddd-dddddddddddd,4,1003|h:EDITED,2883506efb71c7569c8cf1b323b2562d881d93a13609651f86c5e36df3d3397b,dddddddd-dddd-4ddd-8ddd-dddddddddddd,5,1004|h:EDITED,33c4de3f7e89caa4f2b84aa56d7978df30440cfc7a8056a342366ab8e9ff6d63,dddddddd-dddd-4ddd-8ddd-dddddddddddd,6,1005|h:DELETED,3496d47cb71ed00c70d0ce0436fc989e95ca1acc3f498eed9f3634817d82e170,dddddddd-dddd-4ddd-8ddd-dddddddddddd,7,1006|h:RESTORED,d86c360a3c813f0a2353f2ab9f388dff11971c62d1c64d3767d62edf6ef00a49,dddddddd-dddd-4ddd-8ddd-dddddddddddd,8,1007",
        "s|51515151-0000-4000-8000-000000000000|0b000000-0000-4000-8000-000000000002|0a000000-0000-4000-8000-000000000001|250|USD|null",
        "q|4f6882322916442ee779e14f13b980c54fd38f5c91adaddfefa4be51ae9f01d7|UnknownMember(memberId=MemberId(value=0c000000-0000-4000-8000-000000000003))",
    ).joinToString("\n", postfix = "\n")
}
