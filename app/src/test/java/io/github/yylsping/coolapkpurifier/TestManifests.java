package io.github.yylsping.coolapkpurifier;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Loads the real bundled target manifest for contract regression tests. */
final class TestManifests {
    static final long COOLAPK_16_6_1 = 2_608_212L;
    static final long COOLAPK_16_6_2 = 2_609_151L;

    private TestManifests() {
    }

    static TargetManifest manifest() {
        try {
            return TargetManifest.parse(rawBytes());
        } catch (TargetManifest.ManifestException failure) {
            throw new AssertionError("bundled manifest must parse", failure);
        }
    }

    static byte[] rawBytes() {
        try (InputStream in = TestManifests.class.getClassLoader()
                .getResourceAsStream("coolapk_target_manifest.json")) {
            if (in == null) {
                throw new AssertionError("bundled manifest not on test classpath");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    static TargetProfile profile() {
        TargetProfile profile = manifest().validatedProfileFor(COOLAPK_16_6_1);
        if (profile == null) {
            throw new AssertionError("bundled manifest must validate 16.6.1");
        }
        return profile;
    }
}
