package io.github.yylsping.coolapkpurifier;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * LSPosed does not add a scoped module APK to the target app's native
 * library search path. DexKit also deliberately leaves library loading to
 * the caller, so extract our packaged libdexkit.so into the target app's
 * files dir and load it by absolute path.
 */
final class DexKitNativeLoader {
    private static final String MODULE_PACKAGE = "io.github.yylsping.coolapkpurifier";
    private static volatile boolean loaded;

    static synchronized void ensureLoaded(Context appContext) {
        if (loaded) {
            return;
        }
        File library = null;
        try {
            ApplicationInfo moduleInfo = appContext.getPackageManager()
                    .getApplicationInfo(MODULE_PACKAGE, 0);
            String apkPath = moduleInfo == null ? null : moduleInfo.sourceDir;
            if (apkPath == null) {
                throw new IllegalStateException("module apk path unavailable");
            }
            PackageInfo modulePackage = appContext.getPackageManager()
                    .getPackageInfo(MODULE_PACKAGE, 0);
            long moduleVersion = modulePackage == null ? 0L : modulePackage.getLongVersionCode();
            String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
            String entryName = "lib/" + abi + "/libdexkit.so";
            library = new File(appContext.getFilesDir(), "libdexkit-" + moduleVersion + ".so");

            if (!library.isFile() || library.length() <= 0) {
                extract(apkPath, entryName, library);
            }
            System.load(library.getAbsolutePath());
            loaded = true;
        } catch (Throwable first) {
            // Older 32-bit devices may expose a different primary ABI.
            try {
                if (library != null && library.isFile()) {
                    System.load(library.getAbsolutePath());
                    loaded = true;
                    return;
                }
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("unable to load dexkit native library", first);
        }
    }

    private static void extract(String apkPath, String entryName, File output) throws Exception {
        File temp = new File(output.getParentFile(), output.getName() + ".tmp");
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("missing zip entry " + entryName);
            }
            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
        if (!temp.renameTo(output)) {
            try (InputStream in = new java.io.FileInputStream(temp);
                 FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
    }
}
