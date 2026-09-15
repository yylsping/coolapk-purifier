package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.pm.ApplicationInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class DexKitNativeLoaderTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private final List<String> events = new ArrayList<>();

    @Test
    public void frameworkNativeLoadsWithoutCreatingHostPrivateFilesAndMemoizes()
            throws Exception {
        byte[] bytes = elf("arm64-v8a", 1);
        File apk = apk("arm64-v8a", bytes);
        File nativeDir = temporary.newFolder("framework-native");
        File library = new File(nativeDir, "libdexkit.so");
        Files.write(library.toPath(), bytes);
        File untouchedHostDir = temporary.newFolder("host-code-cache");
        AtomicInteger calls = new AtomicInteger();
        HostDataMutationGuard guard = new HostDataMutationGuard();
        DexKitNativeLoader.Engine engine = new DexKitNativeLoader.Engine(path -> {
            assertEquals(library.getAbsolutePath(), path);
            calls.incrementAndGet();
        });

        for (int i = 0; i < 3; i++) {
            engine.ensureLoaded(() -> location(apk, nativeDir),
                    new String[]{"arm64-v8a"}, true, events::add);
        }

        assertEquals(1, calls.get());
        assertArrayEquals(bytes, Files.readAllBytes(library.toPath()));
        assertEquals(0, untouchedHostDir.list().length);
        assertEquals(0, guard.snapshot().hostPrivateWrites);
        assertEvent("result=success source=frameworkNative");
    }

    @Test
    public void frameworkNativeLoadFailureIsStickyAndFailsClosed() throws Exception {
        byte[] bytes = elf("arm64-v8a", 2);
        File apk = apk("arm64-v8a", bytes);
        File nativeDir = temporary.newFolder("framework-load-failure");
        File library = new File(nativeDir, "libdexkit.so");
        Files.write(library.toPath(), bytes);
        AtomicInteger calls = new AtomicInteger();
        DexKitNativeLoader.Engine engine = engine(path -> {
            calls.incrementAndGet();
            throw new UnsatisfiedLinkError("namespace denied");
        });

        DexKitNativeLoader.LoadFailure first = assertThrows(
                DexKitNativeLoader.LoadFailure.class,
                () -> load(engine, location(apk, nativeDir), "arm64-v8a"));
        DexKitNativeLoader.LoadFailure second = assertThrows(
                DexKitNativeLoader.LoadFailure.class,
                () -> load(engine, location(apk, nativeDir), "arm64-v8a"));

        assertSame(first, second);
        assertEquals(1, calls.get());
        assertEquals("systemLoad", first.stage);
        assertArrayEquals(bytes, Files.readAllBytes(library.toPath()));
        assertEvent("fallback=disabled hostPrivateWriteCount=0");
    }

    @Test
    public void missingFrameworkNativeDirectoryFailsWithoutFallback() throws Exception {
        File apk = apk("arm64-v8a", elf("arm64-v8a", 3));
        DexKitNativeLoader.LoadFailure failure = assertThrows(
                DexKitNativeLoader.LoadFailure.class,
                () -> load(engine(path -> fail("must not load")),
                        location(apk, null), "arm64-v8a"));
        assertEquals("frameworkNative", failure.stage);
        assertTrue(failure.getMessage().contains("nativeLibraryDir unavailable"));
        assertEvent("hostPrivateWriteCount=0");
    }

    @Test
    public void missingFrameworkNativeFileFailsWithoutCreatingIt() throws Exception {
        File apk = apk("arm64-v8a", elf("arm64-v8a", 4));
        File nativeDir = temporary.newFolder("framework-missing-file");
        DexKitNativeLoader.LoadFailure failure = assertThrows(
                DexKitNativeLoader.LoadFailure.class,
                () -> load(engine(path -> fail("must not load")),
                        location(apk, nativeDir), "arm64-v8a"));
        assertEquals("frameworkNative", failure.stage);
        assertFalse(new File(nativeDir, "libdexkit.so").exists());
        assertEquals(0, nativeDir.list().length);
    }

    @Test
    public void corruptFrameworkNativeIsRejectedBeforeSystemLoadAndUntouched()
            throws Exception {
        byte[] packaged = elf("arm64-v8a", 5);
        byte[] corrupt = elf("arm64-v8a", 99);
        File apk = apk("arm64-v8a", packaged);
        File nativeDir = temporary.newFolder("framework-corrupt");
        File library = new File(nativeDir, "libdexkit.so");
        Files.write(library.toPath(), corrupt);
        DexKitNativeLoader.Engine engine = engine(path -> fail("must reject before dlopen"));

        DexKitNativeLoader.LoadFailure failure = assertThrows(
                DexKitNativeLoader.LoadFailure.class,
                () -> load(engine, location(apk, nativeDir), "arm64-v8a"));

        assertEquals("verifyFrameworkNative", failure.stage);
        assertTrue(failure.getMessage().contains("SHA-256 mismatch"));
        assertArrayEquals(corrupt, Files.readAllBytes(library.toPath()));
    }

    @Test
    public void legacyHostNativeArtifactsAreNeverDeleted() throws Exception {
        File hostFiles = temporary.newFolder("host-files");
        File legacy = new File(hostFiles, "libdexkit-11.so");
        File interrupted = new File(hostFiles, "libdexkit-11.so.tmp");
        Files.write(legacy.toPath(), new byte[]{1});
        Files.write(interrupted.toPath(), new byte[]{2});
        File apk = apk("arm64-v8a", elf("arm64-v8a", 6));

        assertThrows(DexKitNativeLoader.LoadFailure.class,
                () -> load(engine(path -> fail()), location(apk, null), "arm64-v8a"));

        assertArrayEquals(new byte[]{1}, Files.readAllBytes(legacy.toPath()));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(interrupted.toPath()));
    }

    @Test
    public void missingPrimaryUsesPackagedSecondaryOfSameProcessBitness() throws Exception {
        byte[] bytes = elf("arm64-v8a", 7);
        File apk = apk("arm64-v8a", bytes);
        File nativeDir = nativeDir(bytes, "secondary");
        load(engine(path -> assertTrue(path.endsWith("libdexkit.so"))),
                location(apk, nativeDir), "x86_64", "arm64-v8a");
        assertEvent("selectedAbi=arm64-v8a");
    }

    @Test
    public void only32BitEntryCannotBeFallbackFor64BitProcess() throws Exception {
        File apk = apk("armeabi-v7a", elf("armeabi-v7a", 1));
        DexKitNativeLoader.LoadFailure failure = assertThrows(
                DexKitNativeLoader.LoadFailure.class,
                () -> load(engine(path -> fail("must not load wrong bitness")),
                        location(apk, null), "arm64-v8a", "armeabi-v7a"));
        assertEquals("abiSelect", failure.stage);
        assertTrue(failure.getMessage().contains("missing compatible"));
        assertEvent("availableAbis=[armeabi-v7a]");
    }

    @Test
    public void process32BitSelects32BitEntry() throws Exception {
        byte[] bytes = elf("armeabi-v7a", 8);
        File apk = apk("armeabi-v7a", bytes);
        File nativeDir = nativeDir(bytes, "native32");
        engine(path -> assertTrue(path.endsWith("libdexkit.so"))).ensureLoaded(
                () -> location(apk, nativeDir),
                new String[]{"arm64-v8a", "armeabi-v7a"}, false, events::add);
        assertEvent("process64=false");
        assertEvent("selectedAbi=armeabi-v7a");
    }

    @Test
    public void mislabeledPackagedElfFailsBeforeSystemLoad() throws Exception {
        File apk = apk("arm64-v8a", elf("armeabi-v7a", 1));
        DexKitNativeLoader.LoadFailure failure = assertThrows(
                DexKitNativeLoader.LoadFailure.class,
                () -> load(engine(path -> fail("wrong ELF must not reach system load")),
                        location(apk, null), "arm64-v8a"));
        assertTrue(failure.getMessage().contains("wrong ELF class/machine"));
    }

    @Test
    public void frameworkInfoSupportsSplitApkWithoutHostPackageManager() throws Exception {
        File empty = temporary.newFile("empty-base.apk");
        try (ZipOutputStream ignored = new ZipOutputStream(new FileOutputStream(empty))) { }
        byte[] bytes = elf("arm64-v8a", 9);
        File split = apk("arm64-v8a", bytes);
        File nativeDir = nativeDir(bytes, "split-native");
        ApplicationInfo info = new ApplicationInfo();
        info.sourceDir = empty.getAbsolutePath();
        info.splitSourceDirs = new String[]{split.getAbsolutePath()};
        info.nativeLibraryDir = nativeDir.getAbsolutePath();

        load(engine(path -> { }), DexKitNativeLoader.Location.fromFramework(info, 11),
                "arm64-v8a");
        assertEvent("moduleApk=available count=2");
    }

    @Test
    public void unavailableFrameworkLocationFailsOnceWithRootCause() {
        AtomicInteger queries = new AtomicInteger();
        DexKitNativeLoader.Engine engine = engine(path -> fail());
        DexKitNativeLoader.LocationProvider provider = () -> {
            queries.incrementAndGet();
            throw new IOException("module lookup failed", new SecurityException("access denied"));
        };
        DexKitNativeLoader.LoadFailure first = null;
        for (int i = 0; i < 3; i++) {
            DexKitNativeLoader.LoadFailure failure = assertThrows(
                    DexKitNativeLoader.LoadFailure.class,
                    () -> engine.ensureLoaded(provider, new String[]{"arm64-v8a"},
                            true, events::add));
            if (first == null) {
                first = failure;
            } else {
                assertSame(first, failure);
            }
            assertEquals("moduleLocation", failure.stage);
            assertTrue(failure.getMessage().contains(
                    "rootCauseType=java.lang.SecurityException"));
        }
        assertEquals(1, queries.get());
    }

    private DexKitNativeLoader.Engine engine(Consumer<String> load) {
        return new DexKitNativeLoader.Engine(load::accept);
    }

    private void load(DexKitNativeLoader.Engine engine,
                      DexKitNativeLoader.Location location, String... abis) {
        engine.ensureLoaded(() -> location, abis, true, events::add);
    }

    private DexKitNativeLoader.Location location(File apk, File nativeDir) {
        return new DexKitNativeLoader.Location(Collections.singletonList(apk), nativeDir, 11);
    }

    private File nativeDir(byte[] bytes, String name) throws Exception {
        File result = temporary.newFolder(name);
        Files.write(new File(result, "libdexkit.so").toPath(), bytes);
        return result;
    }

    private File apk(String abi, byte[] bytes) throws Exception {
        File result = temporary.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(result))) {
            zip.putNextEntry(new ZipEntry("lib/" + abi + "/libdexkit.so"));
            zip.write(bytes);
            zip.closeEntry();
        }
        return result;
    }

    private static byte[] elf(String abi, int payload) {
        byte[] data = new byte[64];
        data[0] = 0x7f;
        data[1] = 'E';
        data[2] = 'L';
        data[3] = 'F';
        data[4] = (byte) (DexKitNativeLoader.compatible(abi, true) ? 2 : 1);
        data[5] = 1;
        data[18] = (byte) ("arm64-v8a".equals(abi) ? 183
                : "x86_64".equals(abi) ? 62 : "x86".equals(abi) ? 3 : 40);
        data[63] = (byte) payload;
        return data;
    }

    private void assertEvent(String fragment) {
        assertTrue("missing " + fragment + " in " + events,
                events.stream().anyMatch(event -> event.contains(fragment)));
    }
}
