package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Embedded splash finish-signal contract, pinned against the captured host
 * evidence fixture (16.6.1 SplashAdFragment): the module must emit the
 * host-native payload and must not carry any module-specific marker. The
 * fixture is the regression anchor; the source scan ties module constants
 * back to it.
 */
public final class SplashEmbeddedPayloadContractTest {
    private static final String FIXTURE = "host-splash-finish-contract-16.6.1.json";

    @Test
    public void hostContractFixturePinsNativePayload() throws IOException {
        String fixture = readFixture();

        assertTrue(fixture.contains("\"hostVersionCode\": 2608212"));
        assertTrue(fixture.contains("\"resultKey\": \"SplashAd\""));
        assertTrue(fixture.contains("\"reasonKey\": \"FINISH_REASON\""));
        assertTrue(fixture.contains("\"normalExitReason\": \"sdk_should_go_main\""));
        assertFalse("fixture must not contain module-specific markers",
                fixture.contains("purifier_ui_dismiss"));
    }

    @Test
    public void moduleConstantsMatchHostContractFixture() throws IOException {
        String fixture = readFixture();
        String source = readMainSource("SplashEmbeddedHooks.java");

        assertTrue("result key must match the host fixture",
                source.contains("FRAGMENT_RESULT_KEY = \"SplashAd\"")
                        && fixture.contains("\"resultKey\": \"SplashAd\""));
        assertTrue("extra key must match the host fixture",
                source.contains("EXTRA_FINISH_REASON = \"FINISH_REASON\"")
                        && fixture.contains("\"reasonKey\": \"FINISH_REASON\""));
        assertTrue("reason must be the host-native countdown-exit value",
                source.contains("DISMISS_REASON = \"sdk_should_go_main\"")
                        && fixture.contains("\"normalExitReason\": \"sdk_should_go_main\""));
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

    private static String readFixture() throws IOException {
        InputStream in = SplashEmbeddedPayloadContractTest.class
                .getClassLoader().getResourceAsStream(FIXTURE);
        assertNotNull("missing test fixture " + FIXTURE, in);
        byte[] data;
        try (InputStream closeable = in) {
            data = readAll(closeable);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String readMainSource(String name) throws IOException {
        File file = new File("src/main/java/io/github/yylsping/coolapkpurifier", name);
        assertTrue("missing source " + file, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
