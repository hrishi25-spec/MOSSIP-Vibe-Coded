package io.mosip.liveness.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The secret that keys the config audit chain's hashes.
 *
 * <p>The chain is tamper-*detecting* on its own: anyone who can edit
 * the database can also read the stored hashes and recompute them,
 * because the unkeyed {@code SHA-256} of the canonical form is
 * public knowledge. Keying the hash with a secret the database does
 * not hold closes that gap — an attacker with write access to the
 * database but not to the application's environment cannot recompute
 * a single link, so any rebuild they attempt fails verification
 * outright. The secret arrives via the
 * {@code mosip.security.audit-hmac-secret} property, which is meant
 * to come from the environment ({@code MOSIP_AUDIT_HMAC_SECRET}) so
 * it lives on the app server, not in the database or the repo.</p>
 *
 * <h2>Documented fallback when the secret is unset</h2>
 *
 * <p>A blank secret falls back to the original unkeyed {@code SHA-256}.
 * That keeps a fresh deployment booting with no configuration and
 * keeps chains written before this key existed verifiable — but it is
 * strictly weaker, so the fallback is <em>visible</em>, not silent:
 * {@code GET /api/v1/config/audit/verify} reports the active mode in
 * its {@code hmac} field, and an operator who sees {@code false}
 * knows the chain is tamper-detecting only.</p>
 *
 * <p>The mode is chosen by configuration alone, and verification uses
 * the same key as writing. A chain written keyed and verified with a
 * different (or absent) secret reports every entry as broken — loudly,
 * at the first entry — rather than quietly accepting a downgrade. The
 * remedy is to restore the secret, not to ignore the alarm.</p>
 *
 * <p>A secret shorter than {@value #MIN_SECRET_LENGTH} characters is
 * rejected at startup. A guessable key is no key at all: it would
 * produce keyed-looking hashes that an attacker recomputes just as
 * easily, so failing fast beats shipping a false sense of security.</p>
 */
@Component
public class AuditChainKey {

    /** Shortest accepted secret — long enough to be unguessable. */
    static final int MIN_SECRET_LENGTH = 16;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** {@code null} in fallback mode (no secret configured). */
    private final byte[] secret;

    public AuditChainKey(
            @Value("${mosip.security.audit-hmac-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            this.secret = null;
            return;
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "mosip.security.audit-hmac-secret must be at least "
                            + MIN_SECRET_LENGTH + " characters when set — a "
                            + "shorter secret is guessable, and a guessable "
                            + "key is no key at all");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether entries are HMAC-keyed ({@code false} = SHA-256 fallback). */
    public boolean isKeyed() {
        return secret != null;
    }

    /**
     * Hashes a canonical entry string.
     *
     * @param canonical the stable form produced by
     *        {@link AuditChain#canonical(AuditLog)}
     * @return the 64-character hex {@code entry_hash}
     */
    public String hash(String canonical) {
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        try {
            if (secret == null) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(bytes));
            }
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Both algorithms are required by the JCS, so this cannot happen.
            throw new IllegalStateException("SHA-256 / HmacSHA256 are required"
                    + " by the JCS but unavailable", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("the audit HMAC key was rejected", e);
        }
    }
}
