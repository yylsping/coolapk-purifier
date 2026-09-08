package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class StableIdentityKeyTest {
    @Test
    public void reinstallWithDifferentPathProducesSameKey() {
        String first = StableIdentityKey.compute(
                "com.coolapk.market", 99_841_375L,
                new long[]{1_000L, 2_000L}, "cert-a");
        String second = StableIdentityKey.compute(
                "com.coolapk.market", 99_841_375L,
                new long[]{1_000L, 2_000L}, "cert-a");
        assertEquals(first, second);
    }

    @Test
    public void differentCodeSizeProducesDifferentKey() {
        String first = StableIdentityKey.compute(
                "com.coolapk.market", 99_841_375L, new long[0], "cert-a");
        String second = StableIdentityKey.compute(
                "com.coolapk.market", 101_688_539L, new long[0], "cert-a");
        assertNotEquals(first, second);
    }

    @Test
    public void versionNumbersAreNotPartOfTheKey() {
        assertEquals(
                StableIdentityKey.compute("pkg", 123L, null, "cert"),
                StableIdentityKey.compute("pkg", 123L, null, "cert"));
    }
}
