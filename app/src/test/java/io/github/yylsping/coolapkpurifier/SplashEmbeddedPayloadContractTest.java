package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Embedded splash finish-signal contract, pinned against the host's own
 * dismissal path (16.6.1 SplashAdFragment): the module must emit the
 * host-native payload and must not carry any module-specific marker.
 */
public final class SplashEmbeddedPayloadContractTest {
    @Test
    public void finishSignalUsesHostNativePayload() throws IOException {
        String source = readMainSource("SplashEmbeddedHooks.java");

        assertTrue("result key must match the host listener",
                source.contains("FRAGMENT_RESULT_KEY = \"SplashAd\""));
        assertTrue("extra key must match the host listener",
                source.contains("EXTRA_FINISH_REASON = \"FINISH_REASON\""));
        assertTrue("reason must be the host-native countdown-exit value",
                source.contains("DISMISS_REASON = \"sdk_should_go_main\""));
    }

    @Test
    public void mainSourcesCarryNoModuleSpecificMarker() throws IOException {
        File mainJava = new File("src/main/java");
        assertTrue("unit tests must run with the app module as working directory",
                mainJava.isDirectory());
        File[] files = mainJava.listFiles();
        java.util.ArrayDeque<File> pending = new java.util.ArrayDeque<>();
        if (files != null) {
            for (File file : files) {
                pending.add(file);
            }
        }
        while (!pending.isEmpty()) {
            File file = pending.remove();
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        pending.add(child);
                    }
                }
                continue;
            }
            if (!file.getName().endsWith(".java")) {
                continue;
            }
            String source = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            assertFalse("module-specific dismissal marker leaked into " + file.getName(),
                    source.contains("purifier_ui_dismiss"));
        }
    }

    private static String readMainSource(String name) throws IOException {
        File file = new File("src/main/java/io/github/yylsping/coolapkpurifier", name);
        assertTrue("missing source " + file, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
