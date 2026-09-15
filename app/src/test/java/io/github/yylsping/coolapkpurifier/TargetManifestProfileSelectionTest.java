package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;

import org.junit.Test;

/** Manifest §8.2: exact profile selection, never nearest-version guessing. */
public final class TargetManifestProfileSelectionTest {
    private static TargetManifestRepository bundledRepository() {
        TargetManifestRepository repository =
                TargetManifestRepository.load(TestManifests::rawBytes, null);
        assertNotNull(repository);
        return repository;
    }

    @Test
    public void exactHitForCoolapk1661() {
        TargetProfile profile = bundledRepository().validatedProfileFor(2_608_212L);
        assertNotNull(profile);
        assertEquals(2_608_212L, profile.versionCode);
        assertEquals(TargetProfile.Status.VALIDATED, profile.status);
    }

    @Test
    public void adjacentVersionCodesDoNotMatch() {
        TargetManifestRepository repository = bundledRepository();
        // No nearest-version fallback in either direction.
        assertNull(repository.validatedProfileFor(2_608_211L));
        assertNull(repository.validatedProfileFor(2_608_213L));
    }

    @Test
    public void unknownVersionCodesDoNotMatch() {
        TargetManifestRepository repository = bundledRepository();
        assertNull(repository.validatedProfileFor(0L));
        assertNull(repository.validatedProfileFor(1L));
        assertNull(repository.validatedProfileFor(Long.MAX_VALUE));
    }

    @Test
    public void profileStatusIsDiagnostic() {
        TargetManifestRepository repository = bundledRepository();
        assertEquals("validated", repository.profileStatus(2_608_212L));
        assertEquals("missing", repository.profileStatus(9_999_999L));
    }

    @Test
    public void unreadableSourceFailsClosed() {
        assertNull(TargetManifestRepository.load(() -> {
            throw new IOException("asset gone");
        }, null));
    }

    @Test
    public void malformedSourceFailsClosed() {
        assertNull(TargetManifestRepository.load(
                () -> "definitely not json".getBytes(), null));
    }

    @Test
    public void nullBytesFailClosed() {
        assertNull(TargetManifestRepository.load(() -> null, null));
    }
}
