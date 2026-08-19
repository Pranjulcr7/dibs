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
 * These constants were generated once from the behavior shipped in M2 and are
 * the contract with every device that has ever written an op. They must never
 * be regenerated to make a failing build pass: a mismatch means the canonical
 * encoding drifted, which silently splits every synced group's history.
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
        "m|0a000000-0000-4000-8000-000000000001|5:Alice|true|h:5:Alice,4387b33ebc79446e9cbd36e2382025d77a4756cb74161b4ab9a656d8ca3b5b40,dddddddd-dddd-4ddd-8ddd-dddddddddddd,1|k:0cebfee033dd07bd811cc23eef015c00ce2d68cb2aebf3c7f21a88cfc798edac",
        "m|0e000000-0000-4000-8000-000000000005|2:Jo|false|h:2:Jo,491f1563a3d4ca9b2137cfd7ccbe662e93c00d51ec73aac8a64b209e8de1f75f,dddddddd-dddd-4ddd-8ddd-dddddddddddd,5|k:09960c390dde56fbfcdfa3783aa9ca783ef4349e72c29b59157494d17c2e775d|via:11110000-0000-4000-8000-000000000001|removed",
        "iv|11110000-0000-4000-8000-000000000001|0cebfee033dd07bd811cc23eef015c00ce2d68cb2aebf3c7f21a88cfc798edac|0a000000-0000-4000-8000-000000000001|2|5000|true|1",
        "q|69041eb8e6f438806f76c48c3ba1f4f51ccebbd4a024557d99b2f5ea2012f246|InviteRevoked(inviteId=InviteId(value=11110000-0000-4000-8000-000000000001))",
    ).joinToString("\n", postfix = "\n")

    const val EXPECTED_OP_ID = "2d2a78f69159b82496593382a25281b12f3e61da535beaccb9c7fc87a09e0a6f"

    val EXPECTED_META_FOLD_CANONICAL_STATE: String = listOf(
        "state|11111111-1111-4111-8111-111111111111",
        "g|11:Lisbon 2026|null|null|USD",
        "m|0a000000-0000-4000-8000-000000000001|5:Alice|true|h:5:Alice,001b7331edace9a0765812a6feb379ed584d224a765dca57a1cd9a3671953c9c,dddddddd-dddd-4ddd-8ddd-dddddddddddd,3",
    ).joinToString("\n", postfix = "\n")

    const val EXPECTED_CANONICAL_ENCODING =
        "s10:dibs-op-v1;s36:11111111-1111-4111-8111-111111111111;s36:dddddddd-dddd-4ddd-8ddd-dddddddddddd;i42;i1755000000000;l2:s64:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;s64:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb;;s11:add_expense;s36:eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee;s17:Golden dinner ✓;s3:KWD;i10000;l1:s36:0a000000-0000-4000-8000-000000000001;i10000;;l2:s36:0a000000-0000-4000-8000-000000000001;i5000;s36:0b000000-0000-4000-8000-000000000002;i5000;;i20000;n;"

    val EXPECTED_FOLD_CANONICAL_STATE: String = listOf(
        "state|11111111-1111-4111-8111-111111111111",
        "m|0a000000-0000-4000-8000-000000000001|5:Alice|true|h:5:Alice,6741633ec3498b46ddc70f209c68db8229206729de0a332062866f4788d51119,dddddddd-dddd-4ddd-8ddd-dddddddddddd,1",
        "m|0b000000-0000-4000-8000-000000000002|5:Bobby|true|h:3:Bob,9c8a569a288418cd72ace8ede54dedfc49564c49359bae7c0067f1f245ae434f,dddddddd-dddd-4ddd-8ddd-dddddddddddd,2|h:5:Bobby,ada2da1775401d08e6e9dad90c60edaf9be9d68b6cce0cf84e3e13686c676fff,dddddddd-dddd-4ddd-8ddd-dddddddddddd,3",
        "e|e1e1e1e1-0000-4000-8000-000000000000|false|17:Dinner at Luigi's|USD|1000|20000|null|p:0a000000-0000-4000-8000-000000000001,1000|o:0a000000-0000-4000-8000-000000000001,500|o:0b000000-0000-4000-8000-000000000002,500|h:CREATED,d5520e5d24d622434fc9ba42fb3456101c1eccbe44ebad997ccf31775723df7b,dddddddd-dddd-4ddd-8ddd-dddddddddddd,4|h:EDITED,4d0915958478349a846ee33727044b9a33579ee7cde2ec897b0a0c85ff07f9d8,dddddddd-dddd-4ddd-8ddd-dddddddddddd,5|h:DELETED,c02f0bd2df9ee449e7d366a967d2cf1b5567671ba3df1231257f68ab0061df92,dddddddd-dddd-4ddd-8ddd-dddddddddddd,6|h:RESTORED,303abd6986a47bff6132eb8d8a8e66f20d1403be92a4a383382fda64c79e71b0,dddddddd-dddd-4ddd-8ddd-dddddddddddd,7",
        "s|51515151-0000-4000-8000-000000000000|0b000000-0000-4000-8000-000000000002|0a000000-0000-4000-8000-000000000001|250|USD",
        "q|9993db56f52ab34f5ad7df6f585253f7777a925bda4f204dfb0f992b6099882b|UnknownMember(memberId=MemberId(value=0c000000-0000-4000-8000-000000000003))",
    ).joinToString("\n", postfix = "\n")
}
