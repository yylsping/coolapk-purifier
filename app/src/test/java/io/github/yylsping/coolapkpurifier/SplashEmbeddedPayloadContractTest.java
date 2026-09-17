package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embedded splash finish-signal contract, pinned against the captured host
 * evidence fixture (16.6.1 SplashAdFragment). The fixture is parsed
 * structurally (flat JSON object) rather than substring-matched; the source
 * scan ties module constants back to the parsed values.
 */
public final class SplashEmbeddedPayloadContractTest {
    private static final String FIXTURE = "host-splash-finish-contract-16.6.1.json";

    @Test
    public void hostContractFixturePinsNativePayload() throws IOException {
        Map<String, String> fixture = parseFlatJsonObject(readFixture());

        assertEquals("com.coolapk.market", fixture.get("hostPackage"));
        assertEquals("2608212", fixture.get("hostVersionCode"));
        assertEquals("16.6.1", fixture.get("hostVersionName"));
        assertEquals("SplashAd", fixture.get("resultKey"));
        assertEquals("FINISH_REASON", fixture.get("reasonKey"));
        assertEquals("sdk_should_go_main", fixture.get("normalExitReason"));
        assertFalse("fixture must not contain module-specific markers",
                fixture.containsValue("purifier_ui_dismiss"));
    }

    @Test
    public void moduleConstantsMatchHostContractFixture() throws IOException {
        Map<String, String> fixture = parseFlatJsonObject(readFixture());
        String source = readMainSource("SplashEmbeddedHooks.java");

        assertTrue("result key must match the host fixture",
                source.contains("FRAGMENT_RESULT_KEY = \"" + fixture.get("resultKey") + "\""));
        assertTrue("extra key must match the host fixture",
                source.contains("EXTRA_FINISH_REASON = \"" + fixture.get("reasonKey") + "\""));
        assertTrue("reason must be the host-native countdown-exit value",
                source.contains("DISMISS_REASON = \"" + fixture.get("normalExitReason") + "\""));
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

    /**
     * Minimal parser for a flat JSON object with string or number values —
     * just enough structure for the contract fixture, no dependency.
     */
    static Map<String, String> parseFlatJsonObject(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        int[] pos = {skipWhitespace(json, 0)};
        expect(json, pos, '{');
        if (peek(json, pos) == '}') {
            pos[0]++;
            return out;
        }
        while (true) {
            String key = parseString(json, pos);
            expect(json, pos, ':');
            String value;
            if (peek(json, pos) == '"') {
                value = parseString(json, pos);
            } else {
                value = parseNumber(json, pos);
            }
            out.put(key, value);
            char next = peek(json, pos);
            if (next == ',') {
                pos[0]++;
                continue;
            }
            expect(json, pos, '}');
            return out;
        }
    }

    private static int skipWhitespace(String json, int pos) {
        while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static char peek(String json, int[] pos) {
        pos[0] = skipWhitespace(json, pos[0]);
        if (pos[0] >= json.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        return json.charAt(pos[0]);
    }

    private static void expect(String json, int[] pos, char expected) {
        char actual = peek(json, pos);
        if (actual != expected) {
            throw new IllegalArgumentException(
                    "expected '" + expected + "' but found '" + actual + "'");
        }
        pos[0]++;
    }

    private static String parseString(String json, int[] pos) {
        expect(json, pos, '"');
        StringBuilder sb = new StringBuilder();
        while (pos[0] < json.length()) {
            char c = json.charAt(pos[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos[0] >= json.length()) {
                    break;
                }
                char escaped = json.charAt(pos[0]++);
                switch (escaped) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        sb.append((char) Integer.parseInt(
                                json.substring(pos[0], pos[0] + 4), 16));
                        pos[0] += 4;
                        break;
                    default:
                        sb.append(escaped);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string in JSON");
    }

    private static String parseNumber(String json, int[] pos) {
        int start = pos[0];
        while (pos[0] < json.length()) {
            char c = json.charAt(pos[0]);
            if (!Character.isDigit(c) && c != '-' && c != '+' && c != '.' && c != 'e'
                    && c != 'E') {
                break;
            }
            pos[0]++;
        }
        if (start == pos[0]) {
            throw new IllegalArgumentException("expected number at " + start);
        }
        return json.substring(start, pos[0]);
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
