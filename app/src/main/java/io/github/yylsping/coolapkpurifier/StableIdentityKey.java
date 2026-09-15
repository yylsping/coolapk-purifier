package io.github.yylsping.coolapkpurifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Stable target-code identity independent of the random APK install path.
 * The same target build reinstalled at a different path gets the same key,
 * while a different versionCode, split set or signer never shares a key even
 * when the base APK size happens to match.
 */
final class StableIdentityKey {
    private StableIdentityKey() {
    }

    static String compute(String packageName, long versionCode, long baseApkSize,
                          long[] splitSizes, String signerDigest) {
        StringBuilder source = new StringBuilder();
        source.append("pkg=").append(packageName);
        source.append("|versionCode=").append(versionCode);
        source.append("|baseSize=").append(baseApkSize);
        if (splitSizes != null) {
            for (long size : splitSizes) {
                source.append("|split=").append(size);
            }
        }
        source.append("|signer=").append(signerDigest == null ? "" : signerDigest);
        return sha256(source.toString());
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
