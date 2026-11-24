// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.sync

import app.dibs.core.domain.crypto.Identity
import app.dibs.core.domain.crypto.IdentityKey
import app.dibs.core.domain.crypto.IdentityKeyPair
import app.dibs.core.domain.ledger.DeviceId
import app.dibs.core.domain.ledger.Op
import app.dibs.core.domain.ledger.OpId
import app.dibs.core.domain.ledger.OpPayload
import app.dibs.core.domain.model.GroupId
import app.dibs.core.domain.sync.VectorClock
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.apache.commons.codec.digest.Blake3
import java.security.GeneralSecurityException

/**
 * The `dibs-sync-v1` bundle: how a group's ops travel between devices with no
 * server involved (FR-Y1, D41).
 *
 * Layout: `[4-byte header length][header JSON][64-byte signature][ciphertext]`.
 *
 * - The **header** (format version, group, sender device and identity key,
 *   sender vector clock, op count) is plaintext *and* the AEAD associated
 *   data — a receiver can route and version-check before decrypting, but any
 *   tampering with it fails authentication wholesale (edge 27).
 * - The **body** is XChaCha20-Poly1305 under the group key. Each op's payload
 *   is its own JSON string inside the body, so an op type from a newer minor
 *   version skips with a count instead of sinking the bundle (edge 28);
 *   a *major* version bump is a hard, specific error.
 * - The **signature** is Ed25519 by the sender's identity key over
 *   BLAKE3(header ‖ ciphertext) — AUDIT T1's sender authentication. Whether
 *   that key belongs to a claimed member of the group is the caller's check:
 *   it needs fold state, which a codec must not.
 */
object SyncBundles {

    const val FORMAT_MAJOR = 1
    const val FORMAT_MINOR = 0
    private const val MAGIC = "dibs-sync"
    private const val SIGNATURE_BYTES = 64
    private const val LENGTH_PREFIX_BYTES = 4
    private const val MAX_HEADER_BYTES = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /** Everything [decode] can say. Nothing is ever partially applied. */
    sealed interface Result {
        /**
         * [skippedUnknownOps] counts ops whose payload this app version cannot
         * read (edge 28) — surface as a warning, never a silent drop.
         */
        data class Decoded(
            val groupId: GroupId,
            val senderDevice: DeviceId,
            val senderKey: IdentityKey,
            val senderClock: VectorClock,
            val ops: List<Op>,
            val skippedUnknownOps: Int,
        ) : Result

        /** Not a Dibs bundle at all — wrong magic, truncated, or garbled framing. */
        data object NotABundle : Result

        /** Written by an app generation we cannot read; tell the user to update (edge 28). */
        data class MajorVersionMismatch(val theirs: Int) : Result

        /** AEAD failed: wrong group key, or any tampered byte (edge 27). */
        data object WrongKeyOrTampered : Result

        /** The Ed25519 sender signature does not verify (AUDIT T1). */
        data object BadSignature : Result
    }

    @Serializable
    private data class Header(
        val magic: String,
        val major: Int,
        val minor: Int,
        val groupId: String,
        val senderDevice: String,
        val senderKeyHex: String,
        val clock: Map<String, Long>,
        val opCount: Int,
    )

    @Serializable
    private data class BundleOp(
        val id: String,
        val lamport: Long,
        val deviceId: String,
        val parents: List<String>,
        val wallClockMillis: Long,
        /** The payload as its own JSON document — the unit of forward-compatible skipping. */
        val payloadJson: String,
    )

    @Serializable
    private data class Body(val ops: List<BundleOp>)

    fun encode(
        groupId: GroupId,
        ops: List<Op>,
        groupKey: ByteArray,
        senderDevice: DeviceId,
        sender: IdentityKeyPair,
    ): ByteArray = encode(groupId, ops, groupKey, senderDevice, sender) { json.encodeToString<OpPayload>(it.payload) }

    /**
     * [payloadJson] is the seam that lets tests write a bundle as a *newer*
     * app version would — with a payload type this version has never heard
     * of — so edge 28's skip-with-a-count is exercised for real, not assumed.
     */
    @Suppress("LongParameterList") // the public encode's five inputs plus the test seam
    internal fun encode(
        groupId: GroupId,
        ops: List<Op>,
        groupKey: ByteArray,
        senderDevice: DeviceId,
        sender: IdentityKeyPair,
        payloadJson: (Op) -> String,
    ): ByteArray {
        val header = Header(
            magic = MAGIC,
            major = FORMAT_MAJOR,
            minor = FORMAT_MINOR,
            groupId = groupId.value,
            senderDevice = senderDevice.value,
            senderKeyHex = sender.publicKey.hex,
            clock = VectorClock.of(ops).perDevice.mapKeys { it.key.value },
            opCount = ops.size,
        )
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        val body = Body(
            ops.map { op ->
                BundleOp(
                    id = op.id.value,
                    lamport = op.lamport,
                    deviceId = op.deviceId.value,
                    parents = op.parents.map { it.value }.sorted(),
                    wallClockMillis = op.wallClockMillis,
                    payloadJson = payloadJson(op),
                )
            },
        )
        val ciphertext = XChaCha20Poly1305(groupKey)
            .encrypt(json.encodeToString(body).toByteArray(Charsets.UTF_8), headerBytes)
        val signature = Identity.sign(sender, digest(headerBytes, ciphertext))
        check(signature.size == SIGNATURE_BYTES) { "unexpected signature length ${signature.size}" }

        val out = ByteArray(LENGTH_PREFIX_BYTES + headerBytes.size + SIGNATURE_BYTES + ciphertext.size)
        writeLength(out, headerBytes.size)
        headerBytes.copyInto(out, LENGTH_PREFIX_BYTES)
        signature.copyInto(out, LENGTH_PREFIX_BYTES + headerBytes.size)
        ciphertext.copyInto(out, LENGTH_PREFIX_BYTES + headerBytes.size + SIGNATURE_BYTES)
        return out
    }

    /** Reads only the plaintext header — for routing and version checks before any crypto. */
    fun peekGroupId(bytes: ByteArray): GroupId? = readHeader(bytes)?.let { GroupId(it.groupId) }

    // Reject-early gates: every caught exception IS the typed result — wrong
    // key, tampering, and garbage each map to a specific Result, never a crash.
    @Suppress("ReturnCount", "SwallowedException")
    fun decode(bytes: ByteArray, groupKey: ByteArray): Result {
        val header = readHeader(bytes) ?: return Result.NotABundle
        if (header.major != FORMAT_MAJOR) return Result.MajorVersionMismatch(header.major)

        val headerLength = readLength(bytes)
        val headerBytes = bytes.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + headerLength)
        val signatureStart = LENGTH_PREFIX_BYTES + headerLength
        val bodyStart = signatureStart + SIGNATURE_BYTES
        if (bytes.size <= bodyStart) return Result.NotABundle
        val signature = bytes.copyOfRange(signatureStart, bodyStart)
        val ciphertext = bytes.copyOfRange(bodyStart, bytes.size)

        val senderKey = IdentityKey(header.senderKeyHex)
        if (!Identity.verify(senderKey, digest(headerBytes, ciphertext), signature)) {
            return Result.BadSignature
        }

        val plaintext = try {
            XChaCha20Poly1305(groupKey).decrypt(ciphertext, headerBytes)
        } catch (e: GeneralSecurityException) {
            // Wrong key and tampering are deliberately indistinguishable (edge 27).
            return Result.WrongKeyOrTampered
        }

        val body = try {
            json.decodeFromString<Body>(plaintext.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            return Result.NotABundle
        }
        var skipped = 0
        val groupId = GroupId(header.groupId)
        val ops = body.ops.mapNotNull { op ->
            val payload = decodePayloadOrNull(op.payloadJson)
            if (payload == null) {
                skipped += 1
                null
            } else {
                Op(
                    id = OpId(op.id),
                    groupId = groupId,
                    lamport = op.lamport,
                    deviceId = DeviceId(op.deviceId),
                    parents = op.parents.map(::OpId).toSet(),
                    wallClockMillis = op.wallClockMillis,
                    payload = payload,
                )
            }
        }
        return Result.Decoded(
            groupId = groupId,
            senderDevice = DeviceId(header.senderDevice),
            senderKey = senderKey,
            senderClock = VectorClock(header.clock.mapKeys { DeviceId(it.key) }),
            ops = ops,
            skippedUnknownOps = skipped,
        )
    }

    /**
     * An unreadable payload is a *newer minor version's op*, not corruption —
     * the AEAD already proved integrity — so it skips with a count (edge 28).
     */
    @Suppress("SwallowedException")
    private fun decodePayloadOrNull(payloadJson: String): OpPayload? = try {
        json.decodeFromString<OpPayload>(payloadJson)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    @Suppress("SwallowedException") // malformed header == not a bundle; the null IS the answer
    private fun readHeader(bytes: ByteArray): Header? {
        if (bytes.size < LENGTH_PREFIX_BYTES) return null
        val length = readLength(bytes)
        if (length <= 0 || length > MAX_HEADER_BYTES || bytes.size < LENGTH_PREFIX_BYTES + length) return null
        return try {
            val header = json.decodeFromString<Header>(
                bytes.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + length).toString(Charsets.UTF_8),
            )
            if (header.magic == MAGIC) header else null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun digest(headerBytes: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ByteArray(DIGEST_BYTES)
        Blake3.initHash().update(headerBytes).update(ciphertext).doFinalize(out)
        return out
    }

    private fun writeLength(target: ByteArray, value: Int) {
        target[0] = (value ushr 24).toByte()
        target[1] = (value ushr 16).toByte()
        target[2] = (value ushr 8).toByte()
        target[3] = value.toByte()
    }

    private fun readLength(bytes: ByteArray): Int = (bytes[0].toInt() and 0xFF shl 24) or
        (bytes[1].toInt() and 0xFF shl 16) or
        (bytes[2].toInt() and 0xFF shl 8) or
        (bytes[3].toInt() and 0xFF)

    private const val DIGEST_BYTES = 32
}
