package io.mosip.liveness.android;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.HexFormat;

/**
 * Vendor-signed model manifest (spec §13 {@code manifest {modelId, version,
 * sha256, signature, minAppVersion}} — "verify signature + hash → atomic
 * swap"). The canonical payload covers every other field, so any tamper —
 * model bytes (via the embedded sha256), version label, or the min-app gate —
 * invalidates the signature and the store refuses the update fail-closed.
 *
 * <p>Without this, a stored sha256 proves nothing: whoever can rewrite the
 * model file can rewrite its recorded hash too. The vendor signature is what
 * anchors the digest.</p>
 *
 * @param modelId       vendor model identifier
 * @param version       model version label (recorded in evidence)
 * @param sha256Hex     hex digest of the exact model payload this manifest authorises
 * @param minAppVersion minimum app version the model requires (blank = no floor)
 * @param signatureHex  SHA256withRSA signature over {@link #canonicalPayload()}
 */
public record SignedModelManifest(
        String modelId,
        String version,
        String sha256Hex,
        String minAppVersion,
        String signatureHex) {

    /** Canonical payload the vendor signs and the store re-derives. */
    public String canonicalPayload() {
        return String.join("|", modelId, version, sha256Hex, minAppVersion == null ? "" : minAppVersion);
    }

    /**
     * Sign a manifest (vendor / offline-sideload side, spec §13 both paths).
     *
     * @throws IllegalArgumentException on null/blank required fields
     */
    public static SignedModelManifest sign(String modelId, String version, String sha256Hex,
                                           String minAppVersion, PrivateKey vendorKey) {
        if (isBlank(modelId) || isBlank(version) || isBlank(sha256Hex)) {
            throw new IllegalArgumentException("modelId, version and sha256 are required");
        }
        if (vendorKey == null) {
            throw new IllegalArgumentException("vendorKey is required");
        }
        String payload = String.join("|", modelId, version, sha256Hex,
                minAppVersion == null ? "" : minAppVersion);
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(vendorKey);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return new SignedModelManifest(modelId, version, sha256Hex,
                    minAppVersion == null ? "" : minAppVersion, HexFormat.of().formatHex(sig.sign()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("manifest signing failed", e);
        }
    }

    /**
     * Verify the signature under the vendor key. Fail closed: null key,
     * missing required fields, or malformed signature hex all return false —
     * never throw.
     */
    public boolean verifySignature(PublicKey vendorKey) {
        if (vendorKey == null || isBlank(modelId) || isBlank(version)
                || isBlank(sha256Hex) || minAppVersion == null || signatureHex == null) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(vendorKey);
            sig.update(canonicalPayload().getBytes(StandardCharsets.UTF_8));
            return sig.verify(HexFormat.of().parseHex(signatureHex));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
