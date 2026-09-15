package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class StableIdentityKeyTest {
    @Test
    public void reinstallWithDifferentPathProducesSameKey() {
        String first = StableIdentityKey.compute(
                "com.coolapk.market", 2608212L, 99_841_375L,
                new long[]{1_000L, 2_000L}, "sha256:cert-a");
        String second = StableIdentityKey.compute(
                "com.coolapk.market", 2608212L, 99_841_375L,
                new long[]{1_000L, 2_000L}, "sha256:cert-a");
        assertEquals(first, second);
    }

    @Test
    public void differentCodeSizeProducesDifferentKey() {
        String first = StableIdentityKey.compute(
                "com.coolapk.market", 2608212L, 99_841_375L, new long[0], "sha256:cert-a");
        String second = StableIdentityKey.compute(
                "com.coolapk.market", 2608212L, 101_688_539L, new long[0], "sha256:cert-a");
        assertNotEquals(first, second);
    }

    @Test
    public void versionCodeIsPartOfTheKey() {
        assertNotEquals(
                StableIdentityKey.compute("pkg", 2608212L, 123L, null, "sha256:cert"),
                StableIdentityKey.compute("pkg", 2608213L, 123L, null, "sha256:cert"));
    }

    @Test
    public void sameSizeDifferentVersionIsIsolated() {
        // BUG-B: an equal base APK size must never let a different version
        // reuse the cache of another build.
        String first = StableIdentityKey.compute("pkg", 1L, 500L, null, "sha256:cert");
        String second = StableIdentityKey.compute("pkg", 2L, 500L, null, "sha256:cert");
        assertNotEquals(first, second);
    }

    @Test
    public void splitStructureIsPartOfTheKey() {
        String noSplits = StableIdentityKey.compute("pkg", 1L, 500L, null, "sha256:cert");
        String withSplits = StableIdentityKey.compute(
                "pkg", 1L, 500L, new long[]{10L}, "sha256:cert");
        String otherSplits = StableIdentityKey.compute(
                "pkg", 1L, 500L, new long[]{11L}, "sha256:cert");
        assertNotEquals(noSplits, withSplits);
        assertNotEquals(withSplits, otherSplits);
    }

    @Test
    public void signerDigestIsPartOfTheKey() {
        assertNotEquals(
                StableIdentityKey.compute("pkg", 1L, 500L, null, "sha256:a"),
                StableIdentityKey.compute("pkg", 1L, 500L, null, "sha256:b"));
        assertNotEquals(
                StableIdentityKey.compute("pkg", 1L, 500L, null, "sha256:a"),
                StableIdentityKey.compute("pkg", 1L, 500L, null,
                        TargetIdentity.SIGNER_UNAVAILABLE));
    }
}
