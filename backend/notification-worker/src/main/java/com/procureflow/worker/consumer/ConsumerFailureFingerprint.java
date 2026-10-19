package com.procureflow.worker.consumer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Stable, bounded diagnostic fingerprint. It is the only representation of a failed message that is
 * persisted, so an attacker cannot inflate the diagnostic table with whitespace variants of a known
 * canonical event and no raw body is ever stored.
 *
 * <p>Two explicitly documented scopes exist:
 * <ul>
 *   <li><strong>Event scoped</strong> (a canonical outbox event with this company/event exists): the
 *       hash is the SHA-256 of {@code p4c:v1:event:<companyId>:<eventId>:<code>}. It depends only on
 *       canonical database values, so differently formatted or reordered variants of the same
 *       message map to the same row and increment one attempt counter.</li>
 *   <li><strong>Raw scoped</strong> (unparseable/oversized input or no canonical event): the hash is
 *       the SHA-256 of {@code p4c:v1:raw:<code>:<sha256-hex-of-the-received-bytes>} and the
 *       diagnostic's company/event columns stay NULL, because the diagnostic's foreign key requires
 *       an existing canonical event. An identical body redelivery reuses the row; a byte-different
 *       variant creates a new row. That is the documented limitation of unknown input: the hash
 *       alone cannot reconstruct a message for replay.</li>
 * </ul>
 */
public final class ConsumerFailureFingerprint {
    private ConsumerFailureFingerprint() {
    }

    /** Fingerprint for a message whose canonical event is known. */
    public static String forEvent(UUID companyId, UUID eventId, ConsumerErrorCode code) {
        return sha256Hex(("p4c:v1:event:" + companyId + ":" + eventId + ":" + code.code())
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Fingerprint for input that cannot be tied to an existing canonical event. */
    public static String forRawBody(byte[] body, ConsumerErrorCode code) {
        MessageDigest digest = digest();
        digest.update(("p4c:v1:raw:" + code.code() + ":").getBytes(StandardCharsets.UTF_8));
        digest.update(sha256Hex(body).getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256Hex(byte[] value) {
        return HexFormat.of().formatHex(digest().digest(value));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
