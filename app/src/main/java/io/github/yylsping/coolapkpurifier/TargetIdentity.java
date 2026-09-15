package io.github.yylsping.coolapkpurifier;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Stable target-code identity.
 *
 * <p>The stable token is derived from the package name, versionCode, the
 * installed base/split APK structure (sizes, not paths) and the signer
 * certificate digest; this changes when the installed target code changes and
 * survives process restarts. Reading the protected APK contents from inside
 * Coolapk is deliberately avoided because the packer kills such reader
 * threads. versionName is retained for logs only.
 *
 * <p>Signer identity is the SHA-256 over the raw signer certificate DER bytes
 * (see {@link #signerDigest} for the exact single/multi signer contract). When
 * the platform cannot provide signing data the digest is the explicit marker
 * {@link #SIGNER_UNAVAILABLE}, never a hash of empty input.
 */
final class TargetIdentity {
    static final String SIGNER_UNAVAILABLE = "unavailable";

    final String packageName;
    final String apkPath;
    final long apkSize;
    final String signingHash;
    final String token;

    // versionCode participates in the token; versionName is log-only.
    final long versionCode;
    final String versionName;

    private TargetIdentity(String packageName, String apkPath, long apkSize,
                           String signingHash, String token,
                           long versionCode, String versionName) {
        this.packageName = packageName;
        this.apkPath = apkPath;
        this.apkSize = apkSize;
        this.signingHash = signingHash;
        this.token = token;
        this.versionCode = versionCode;
        this.versionName = versionName;
    }

    static TargetIdentity compute(Context appContext) {
        long versionCode = -1L;
        String versionName = "unknown";
        String signingHash = SIGNER_UNAVAILABLE;
        try {
            PackageManager pm = appContext.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(CoolapkModule.TARGET_PACKAGE,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            versionCode = packageInfo.getLongVersionCode();
            if (packageInfo.versionName != null) {
                versionName = packageInfo.versionName;
            }
            signingHash = signerDigest(signingCertificateDer(packageInfo));
        } catch (Throwable ignored) {
        }

        ApplicationInfo info = appContext.getApplicationInfo();
        String apkPath = info == null ? "" : String.valueOf(info.sourceDir);
        File apk = new File(apkPath);
        long size = apk.isFile() ? apk.length() : -1L;

        long[] splitSizes = null;
        if (info != null && info.splitSourceDirs != null) {
            splitSizes = new long[info.splitSourceDirs.length];
            for (int i = 0; i < info.splitSourceDirs.length; i++) {
                File splitFile = new File(info.splitSourceDirs[i]);
                splitSizes[i] = splitFile.isFile() ? splitFile.length() : -1L;
            }
        }

        // The random /data/app/... install path is intentionally excluded.
        return new TargetIdentity(
                CoolapkModule.TARGET_PACKAGE,
                apkPath,
                size,
                signingHash,
                StableIdentityKey.compute(CoolapkModule.TARGET_PACKAGE, versionCode, size,
                        splitSizes, signingHash),
                versionCode,
                versionName);
    }

    boolean sameTarget(TargetIdentity other) {
        return other != null
                && token != null && token.equals(other.token)
                && packageName.equals(other.packageName)
                && versionCode == other.versionCode
                && apkSize == other.apkSize;
    }

    String describe() {
        return "identity=" + shortToken()
                + " pkg=" + packageName
                + " apk=" + apkPath
                + " size=" + apkSize
                + " signer=" + (signingHash == null ? "null"
                : SIGNER_UNAVAILABLE.equals(signingHash) ? SIGNER_UNAVAILABLE
                : signingHash.substring(0, Math.min(12, signingHash.length())))
                + " version=" + versionName + "(" + versionCode + ")";
    }

    String shortToken() {
        return token == null ? "null" : token.substring(0, Math.min(16, token.length()));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("package", packageName);
        json.put("apkPath", apkPath);
        json.put("apkSize", apkSize);
        json.put("signingHash", String.valueOf(signingHash));
        json.put("token", String.valueOf(token));
        json.put("versionCode", versionCode);
        json.put("versionName", String.valueOf(versionName));
        return json;
    }

    static TargetIdentity fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new TargetIdentity(
                json.optString("package", ""),
                json.optString("apkPath", ""),
                json.optLong("apkSize", -1L),
                json.optString("signingHash", SIGNER_UNAVAILABLE),
                json.optString("token", ""),
                json.optLong("versionCode", -1L),
                json.optString("versionName", "unknown"));
    }

    /**
     * Raw signer certificate DER bytes, or null when the platform cannot
     * provide signing data. Reads signingInfo, populated only with
     * GET_SIGNING_CERTIFICATES (minSdk 28 makes the legacy signatures field
     * unnecessary).
     */
    private static byte[][] signingCertificateDer(PackageInfo packageInfo) {
        if (packageInfo == null) {
            return null;
        }
        try {
            SigningInfo signingInfo = packageInfo.signingInfo;
            if (signingInfo != null) {
                Signature[] signers = signingInfo.getApkContentsSigners();
                if (signers != null && signers.length > 0) {
                    return toDer(signers);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[][] toDer(Signature[] signatures) {
        List<byte[]> certs = new ArrayList<>(signatures.length);
        for (Signature signature : signatures) {
            if (signature == null) {
                continue;
            }
            try {
                byte[] der = signature.toByteArray();
                if (der != null && der.length > 0) {
                    certs.add(der);
                }
            } catch (Throwable ignored) {
            }
        }
        return certs.isEmpty() ? null : certs.toArray(new byte[0][]);
    }

    /**
     * Final signer digest contract:
     *
     * <p>single signer = {@code sha256:} + hex(SHA-256(raw DER bytes)) —
     * the raw DER is hashed exactly once, never re-hashed via its hex form.
     *
     * <p>multiple signers = each certificate's raw DER is hashed individually,
     * the digest BYTES are sorted in unsigned lexicographic order, concatenated
     * with a 4-byte big-endian length prefix per digest, and the combination
     * is hashed once more. Null/empty certificates are ignored; when nothing
     * usable remains the result is the explicit {@link #SIGNER_UNAVAILABLE}
     * marker, never a hash of empty input.
     */
    static String signerDigest(byte[][] derCerts) {
        if (derCerts == null || derCerts.length == 0) {
            return SIGNER_UNAVAILABLE;
        }
        List<byte[]> digests = new ArrayList<>(derCerts.length);
        for (byte[] der : derCerts) {
            if (der == null || der.length == 0) {
                continue;
            }
            byte[] digest = sha256Bytes(der);
            if (digest == null) {
                return SIGNER_UNAVAILABLE;
            }
            digests.add(digest);
        }
        if (digests.isEmpty()) {
            return SIGNER_UNAVAILABLE;
        }
        if (digests.size() == 1) {
            return "sha256:" + hex(digests.get(0));
        }
        digests.sort(TargetIdentity::compareUnsigned);
        try {
            MessageDigest combined = MessageDigest.getInstance("SHA-256");
            for (byte[] digest : digests) {
                combined.update(new byte[]{
                        (byte) (digest.length >>> 24), (byte) (digest.length >>> 16),
                        (byte) (digest.length >>> 8), (byte) digest.length});
                combined.update(digest);
            }
            return "sha256:" + hex(combined.digest());
        } catch (Throwable ignored) {
            return SIGNER_UNAVAILABLE;
        }
    }

    /** Unsigned lexicographic comparison, the canonical multi-signer order. */
    private static int compareUnsigned(byte[] left, byte[] right) {
        int shared = Math.min(left.length, right.length);
        for (int i = 0; i < shared; i++) {
            int diff = (left[i] & 0xff) - (right[i] & 0xff);
            if (diff != 0) {
                return diff;
            }
        }
        return left.length - right.length;
    }

    private static byte[] sha256Bytes(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String hex(byte[] value) {
        StringBuilder sb = new StringBuilder(value.length * 2);
        for (byte b : value) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
