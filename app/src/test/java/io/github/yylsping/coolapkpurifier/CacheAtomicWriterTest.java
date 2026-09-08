package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CacheAtomicWriterTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void replaceFailureLeavesOldDestinationIntact() throws Exception {
        File destination = folder.newFile("cache.json");
        byte[] oldBytes = "old-cache".getBytes(StandardCharsets.UTF_8);
        Files.write(destination.toPath(), oldBytes);

        boolean written = CacheAtomicWriter.write(
                destination,
                "new-cache".getBytes(StandardCharsets.UTF_8),
                (temp, dest) -> false);

        assertFalse(written);
        assertArrayEquals(oldBytes, Files.readAllBytes(destination.toPath()));
    }

    @Test
    public void successfulReplaceUsesPayloadFromCompleteTempFile() throws Exception {
        File destination = folder.newFile("cache.json");
        byte[] payload = "{\"schema\":1}".getBytes(StandardCharsets.UTF_8);

        boolean written = CacheAtomicWriter.write(
                destination,
                payload,
                (temp, dest) -> {
                    try {
                        Files.copy(temp.toPath(), dest.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        return true;
                    } catch (Exception ignored) {
                        return false;
                    }
                });

        assertTrue(written);
        assertArrayEquals(payload, Files.readAllBytes(destination.toPath()));
    }
}
