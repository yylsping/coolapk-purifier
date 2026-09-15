package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** BUG-B: signer identity must hash raw DER bytes and survive ordering. */
public final class TargetIdentityTest {
    /** SHA-256 of the raw bytes 0x01 0x02 0x03 (known vector). */
    private static final String DER_010203_SHA256 =
            "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";

    @Test
    public void singleSignerDigestsRawDerBytes() {
        String digest = TargetIdentity.signerDigest(new byte[][]{{1, 2, 3}});
        // Final contract: single signer = hex(SHA-256(raw DER)), hashed once.
        assertEquals("sha256:" + DER_010203_SHA256, digest);
        // It must never be the rejected double hash sha256(hex(sha256(der))).
        assertNotEquals("sha256:" + StableIdentityKey.sha256(DER_010203_SHA256), digest);
        // And it must never be the old "hash of the ISO-8859-1→UTF-8 re-encode"
        // (use high bytes: low bytes are identical under both interpretations).
        byte[] highBytes = {(byte) 0xCA, (byte) 0xFE};
        assertNotEquals("sha256:" + StableIdentityKey.sha256(new String(highBytes,
                        java.nio.charset.StandardCharsets.ISO_8859_1)),
                TargetIdentity.signerDigest(new byte[][]{highBytes}));
    }

    @Test
    public void multiSignerDigestIsOrderIndependent() {
        byte[] a = {1, 2, 3};
        byte[] b = {4, 5, 6, 7};
        byte[] c = {(byte) 0xCA, (byte) 0xFE};
        assertEquals(
                TargetIdentity.signerDigest(new byte[][]{a, b, c}),
                TargetIdentity.signerDigest(new byte[][]{c, a, b}));
        assertEquals(
                TargetIdentity.signerDigest(new byte[][]{a, b, c}),
                TargetIdentity.signerDigest(new byte[][]{b, c, a}));
    }

    @Test
    public void multiSignerDiffersFromAnySingleSigner() {
        byte[] a = {1, 2, 3};
        byte[] b = {4, 5, 6};
        String combined = TargetIdentity.signerDigest(new byte[][]{a, b});
        assertNotEquals(TargetIdentity.signerDigest(new byte[][]{a}), combined);
        assertNotEquals(TargetIdentity.signerDigest(new byte[][]{b}), combined);
    }

    @Test
    public void multiSignerDigestIsDeterministicAndLengthPrefixed() {
        byte[] a = {9, 9, 9};
        byte[] b = {7, 7};
        String first = TargetIdentity.signerDigest(new byte[][]{a, b});
        String second = TargetIdentity.signerDigest(new byte[][]{a, b});
        assertEquals(first, second);
        assertTrue(first.startsWith("sha256:"));
        // A signer set change must change the combined digest.
        assertNotEquals(first, TargetIdentity.signerDigest(new byte[][]{a, b, new byte[]{1}}));
    }

    @Test
    public void nullCertificatesAreIgnoredWhenOthersExist() {
        byte[] a = {1, 2, 3};
        assertEquals(TargetIdentity.signerDigest(new byte[][]{a}),
                TargetIdentity.signerDigest(new byte[][]{a, null, new byte[0]}));
    }

    @Test
    public void unavailableSignerIsExplicitMarkerNotEmptyHash() {
        assertEquals(TargetIdentity.SIGNER_UNAVAILABLE, TargetIdentity.signerDigest(null));
        assertEquals(TargetIdentity.SIGNER_UNAVAILABLE,
                TargetIdentity.signerDigest(new byte[0][]));
        assertEquals(TargetIdentity.SIGNER_UNAVAILABLE,
                TargetIdentity.signerDigest(new byte[][]{null}));
        assertEquals(TargetIdentity.SIGNER_UNAVAILABLE,
                TargetIdentity.signerDigest(new byte[][]{new byte[0]}));
        // The marker must not collide with any sha256: digest form.
        assertTrue(!TargetIdentity.SIGNER_UNAVAILABLE.startsWith("sha256:"));
    }

    @Test
    public void jsonRoundTripPreservesIdentityFields() throws Exception {
        org.json.JSONObject json = new org.json.JSONObject();
        json.put("package", "com.coolapk.market");
        json.put("apkPath", "/data/app/~~xyz/base.apk");
        json.put("apkSize", 99_841_375L);
        json.put("signingHash", "sha256:abc");
        json.put("token", "tok");
        json.put("versionCode", 2608212L);
        json.put("versionName", "16.6.1");
        TargetIdentity identity = TargetIdentity.fromJson(json);
        org.json.JSONObject again = identity.toJson();
        TargetIdentity restored = TargetIdentity.fromJson(again);
        assertTrue(identity.sameTarget(restored));
    }
}
