package io.github.yylsping.coolapkpurifier;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Loads DexKit only from the framework-owned installed native directory. */
final class DexKitNativeLoader {
    static final String FAILURE_REASON = "DEXKIT_NATIVE_LOAD_FAILED";

    interface LocationProvider {
        Location get() throws Exception;
    }

    interface LibraryLoader {
        void load(String path);
    }

    static final class LoadFailure extends IllegalStateException {
        final String stage;

        LoadFailure(String stage, Throwable cause) {
            super(FAILURE_REASON + " stage=" + stage + " " + describeFailure(cause), cause);
            this.stage = stage;
        }
    }

    static final class Location {
        final List<File> apks;
        final File nativeDirectory;
        final long versionCode;

        Location(List<File> apks, File nativeDirectory, long versionCode) {
            this.apks = new ArrayList<>(apks);
            this.nativeDirectory = nativeDirectory;
            this.versionCode = versionCode;
        }

        static Location fromFramework(ApplicationInfo info, long versionCode) throws IOException {
            if (info == null || info.sourceDir == null || info.sourceDir.isEmpty()) {
                throw new IOException("framework module APK path unavailable");
            }
            List<File> apks = new ArrayList<>();
            apks.add(new File(info.sourceDir));
            if (info.splitSourceDirs != null) {
                for (String path : info.splitSourceDirs) {
                    if (path != null && !path.isEmpty()) {
                        apks.add(new File(path));
                    }
                }
            }
            return new Location(apks, info.nativeLibraryDir == null
                    ? null : new File(info.nativeLibraryDir), versionCode);
        }
    }

    private static final Engine PROCESS_LOADER = new Engine(System::load);
    private static LocationProvider moduleLocation;

    static synchronized void configure(LocationProvider provider) {
        if (moduleLocation == null) {
            moduleLocation = provider;
        }
    }

    static void ensureLoaded(ModuleLog log, BootstrapTrace trace) {
        final LocationProvider captured;
        synchronized (DexKitNativeLoader.class) {
            captured = moduleLocation;
        }
        boolean process64 = Process.is64Bit();
        PROCESS_LOADER.ensureLoaded(() -> {
            if (captured == null) {
                throw new IOException("framework module location provider unavailable");
            }
            return captured.get();
        }, process64 ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS,
                process64, detail -> {
                    log.info("dexkitNative " + detail);
                    if (trace != null) {
                        trace.mark("dexkitNative", detail);
                    }
                });
    }

    /** One success or terminal failure per process; testable without Android JNI. */
    static final class Engine {
        private final LibraryLoader loader;
        private boolean loaded;
        private LoadFailure failure;
        private String stage = "moduleLocation";

        Engine(LibraryLoader loader) {
            this.loader = loader;
        }

        synchronized void ensureLoaded(LocationProvider provider, String[] supported,
                                       boolean process64, Consumer<String> log) {
            if (loaded) {
                return;
            }
            if (failure != null) {
                throw failure;
            }
            try {
                mark(log, "moduleLocation", "source=framework");
                Location location = provider.get();
                if (location == null || location.apks.isEmpty()) {
                    throw new IOException("framework module APK path unavailable");
                }
                log.accept("moduleApk=available count=" + location.apks.size()
                        + " versionCode=" + location.versionCode);
                Candidate candidate = select(location, supported, process64, log);
                if (location.nativeDirectory == null) {
                    mark(log, "frameworkNative", "available=false reason=nativeDirectoryMissing");
                    throw new IOException("framework nativeLibraryDir unavailable");
                }
                File nativeFile = new File(location.nativeDirectory, "libdexkit.so");
                if (!nativeFile.isFile()) {
                    mark(log, "frameworkNative", "available=false file=libdexkit.so");
                    throw new IOException("framework native libdexkit.so unavailable");
                }
                mark(log, "verifyFrameworkNative", "abi=" + candidate.abi
                        + " file=libdexkit.so");
                candidate.verify(nativeFile);
                mark(log, "systemLoad", "source=frameworkNative file=libdexkit.so");
                loader.load(nativeFile.getAbsolutePath());
                loaded = true;
                log.accept("stage=systemLoad result=success source=frameworkNative");
            } catch (Exception | LinkageError error) {
                failure = new LoadFailure(stage, error);
                log.accept("stage=" + stage
                        + " result=failure classification=NATIVE_BOOTSTRAP_FAILED"
                        + " fallback=disabled hostPrivateWriteCount=0 "
                        + describeFailure(error));
                throw failure;
            }
        }

        private Candidate select(Location location, String[] supported, boolean process64,
                                 Consumer<String> log) throws Exception {
            mark(log, "abiSelect", "supportedAbis=" + Arrays.toString(supported)
                    + " process64=" + process64);
            Set<String> available = new LinkedHashSet<>();
            for (File apk : location.apks) {
                try (ZipFile zip = new ZipFile(apk)) {
                    zip.stream().map(ZipEntry::getName)
                            .filter(name -> name.startsWith("lib/")
                                    && name.endsWith("/libdexkit.so"))
                            .forEach(name -> available.add(name.substring(4,
                                    name.length() - "/libdexkit.so".length())));
                }
            }
            log.accept("stage=abiSelect availableAbis=" + available);
            for (String abi : supported == null ? new String[0] : supported) {
                if (!compatible(abi, process64) || !available.contains(abi)) {
                    continue;
                }
                for (File apk : location.apks) {
                    try (ZipFile zip = new ZipFile(apk)) {
                        ZipEntry entry = zip.getEntry("lib/" + abi + "/libdexkit.so");
                        if (entry != null) {
                            Candidate result = new Candidate(apk, abi, entry, zip);
                            log.accept("stage=abiSelect selectedAbi=" + abi
                                    + " apkSha256=" + result.apkHash
                                    + " entry=" + entry.getName()
                                    + " size=" + result.size + " crc=" + result.crc);
                            return result;
                        }
                    }
                }
            }
            throw new IOException("missing compatible DexKit zip entry; supportedAbis="
                    + Arrays.toString(supported) + " availableAbis=" + available
                    + " process64=" + process64);
        }

        private void mark(Consumer<String> log, String next, String detail) {
            stage = next;
            log.accept("stage=" + next + " " + detail);
        }
    }

    static boolean compatible(String abi, boolean process64) {
        return process64 ? ("arm64-v8a".equals(abi) || "x86_64".equals(abi))
                : ("armeabi-v7a".equals(abi) || "armeabi".equals(abi)
                        || "x86".equals(abi));
    }

    static final class Candidate {
        final File apk;
        final String abi;
        final String entryName;
        final String apkHash;
        final String libraryHash;
        final long size;
        final long crc;

        Candidate(File apk, String abi, ZipEntry entry, ZipFile zip) throws Exception {
            this.apk = apk;
            this.abi = abi;
            entryName = entry.getName();
            size = entry.getSize();
            crc = entry.getCrc();
            try (InputStream input = new FileInputStream(apk)) {
                apkHash = sha256(input);
            }
            try (InputStream input = zip.getInputStream(entry)) {
                libraryHash = sha256(input);
            }
            try (InputStream input = zip.getInputStream(entry)) {
                verifyElf(input, abi);
            }
            if (size < 20) {
                throw new IOException("invalid packaged native size=" + size);
            }
        }

        void verify(File file) throws Exception {
            if (!file.isFile() || file.length() != size) {
                throw new IOException("native file size mismatch expected=" + size
                        + " actual=" + file.length());
            }
            try (InputStream input = new FileInputStream(file)) {
                if (!libraryHash.equals(sha256(input))) {
                    throw new IOException("native file SHA-256 mismatch");
                }
            }
            try (InputStream input = new FileInputStream(file)) {
                verifyElf(input, abi);
            }
        }
    }

    static void verifyElf(InputStream input, String abi) throws IOException {
        byte[] header = new byte[20];
        int count = 0;
        while (count < header.length) {
            int read = input.read(header, count, header.length - count);
            if (read < 0) {
                throw new IOException("truncated ELF header");
            }
            count += read;
        }
        int expectedClass = compatible(abi, true) ? 2 : 1;
        int expectedMachine = "arm64-v8a".equals(abi) ? 183
                : ("x86_64".equals(abi) ? 62 : ("x86".equals(abi) ? 3 : 40));
        int machine = (header[18] & 255) | ((header[19] & 255) << 8);
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L'
                || header[3] != 'F' || header[4] != expectedClass
                || header[5] != 1 || machine != expectedMachine) {
            throw new IOException("wrong ELF class/machine for abi=" + abi
                    + " class=" + header[4] + " machine=" + machine);
        }
    }

    static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 255));
        }
        return result.toString();
    }

    static String describeFailure(Throwable error) {
        Throwable root = error;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (root.getCause() != null && seen.add(root)
                && !seen.contains(root.getCause())) {
            root = root.getCause();
        }
        return "failureType=" + error.getClass().getName()
                + " failureMessage=" + oneLine(error.getMessage())
                + " rootCauseType=" + root.getClass().getName()
                + " rootCauseMessage=" + oneLine(root.getMessage());
    }

    private static String oneLine(String value) {
        return value == null ? "<null>" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private DexKitNativeLoader() { }
}
