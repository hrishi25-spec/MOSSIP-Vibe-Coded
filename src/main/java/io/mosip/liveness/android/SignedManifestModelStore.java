package io.mosip.liveness.android;

import java.security.PublicKey;
import java.util.Objects;
import java.util.Optional;

/**
 * Production model store for the Android glue (spec §13): activation requires
 * a {@link SignedModelManifest} whose vendor signature verifies and whose
 * embedded sha256 binds to the exact payload bytes — then an atomic swap that
 * keeps the previous version for {@link #rollback()} after a failed health
 * check. The plain {@link #activate(String, String, byte[])} path fails closed
 * (returns false): an unsigned activation is exactly the gap this store closes
 * ({@code ModelStore} promised "signature at the glue layer" but shipped no
 * implementation).
 *
 * <p>Callers audit refusal/rollback themselves ({@code MODEL_UPDATED(rollback)}
 * per the interface contract); this class only decides. Thread-safety: the
 * active model is a single volatile reference — swap is one write.</p>
 */
public final class SignedManifestModelStore implements ModelStore {

    private final PublicKey vendorKey;
    /** App version for the manifest's minAppVersion gate; null disables the gate. */
    private final String currentAppVersion;

    private volatile ActiveModel active;
    private volatile ActiveModel previous;

    public SignedManifestModelStore(PublicKey vendorKey) {
        this(vendorKey, null);
    }

    /** @param currentAppVersion when non-null, updates whose minAppVersion exceeds it are refused. */
    public SignedManifestModelStore(PublicKey vendorKey, String currentAppVersion) {
        this.vendorKey = Objects.requireNonNull(vendorKey, "vendorKey");
        this.currentAppVersion = currentAppVersion;
    }

    @Override
    public Optional<ActiveModel> activeModel() {
        return Optional.ofNullable(active);
    }

    /**
     * Unsigned activation is refused — use {@link #activate(SignedModelManifest,
     * byte[])}. Fail closed so a caller wired to the bare interface can never
     * install an unverified model.
     */
    @Override
    public boolean activate(String modelId, String version, byte[] payload) {
        return false;
    }

    /**
     * Spec §13 path: verify signature + hash → atomic swap → keep previous.
     * Every check runs before any state changes, so a refused update leaves
     * the currently active model untouched.
     */
    public boolean activate(SignedModelManifest manifest, byte[] payload) {
        if (manifest == null || payload == null || payload.length == 0) {
            return false;
        }
        if (!manifest.verifySignature(vendorKey)) {
            return false;
        }
        String digest = InMemoryModelStore.sha256Hex(payload);
        if (!digest.equals(manifest.sha256Hex())) {
            return false;
        }
        if (currentAppVersion != null && !minAppSatisfied(manifest.minAppVersion())) {
            return false;
        }
        previous = active;                          // keep previous version (rollback source)
        active = new ActiveModel(manifest.modelId(), manifest.version(), digest);   // atomic swap
        return true;
    }

    /**
     * Spec §13 rollback: restore the previous model after a failed health
     * check. No-op when there is no previous version.
     */
    public void rollback() {
        active = previous;
    }

    /** True when {@code current} >= {@code minimum}; malformed versions fail closed. */
    private boolean minAppSatisfied(String minimum) {
        if (minimum == null || minimum.isBlank()) {
            return true;                            // blank = no floor
        }
        String[] required = minimum.trim().split("\\.");
        String[] current = currentAppVersion.trim().split("\\.");
        for (int i = 0; i < required.length; i++) {
            int need = parseSegment(required[i]);
            if (need < 0) {
                return false;
            }
            int have = i < current.length ? parseSegment(current[i]) : 0;
            if (have < 0) {
                return false;
            }
            if (have != need) {
                return have > need;
            }
        }
        return true;
    }

    private static int parseSegment(String segment) {
        try {
            return Integer.parseInt(segment.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
