package io.github.yylsping.coolapkpurifier;

import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Single entry point that owns the bundled target manifest: read, schema
 * check and exact-versionCode profile selection. Only {@code validated}
 * profiles are handed to the production install path; unknown versions get
 * an explicit unsupported answer — never a nearest-version fallback and
 * never the previous version's descriptors.
 */
final class TargetManifestRepository {
    static final String ASSET_NAME = "assets/coolapk_target_manifest.json";

    /** Supplies the raw manifest bytes; module-APK backed in production. */
    interface ManifestSource {
        byte[] read() throws Exception;
    }

    private final TargetManifest manifest;

    private TargetManifestRepository(TargetManifest manifest) {
        this.manifest = manifest;
    }

    static TargetManifestRepository load(ManifestSource source, ModuleLog log) {
        try {
            TargetManifest manifest = TargetManifest.parse(source.read());
            if (log != null) {
                log.info("manifest loaded schema=" + manifest.schema
                        + " profiles=" + manifest.profiles.size());
            }
            return new TargetManifestRepository(manifest);
        } catch (Throwable failure) {
            if (log != null) {
                log.info("manifest load failed reason=" + failure.getMessage()
                        + " failClosed=true");
            }
            return null;
        }
    }

    /** Reads the bundled asset from the module base/split APK set. */
    static ManifestSource moduleApkSource(ApplicationInfo moduleInfo) {
        return () -> {
            List<File> apks = new ArrayList<>();
            if (moduleInfo == null || moduleInfo.sourceDir == null
                    || moduleInfo.sourceDir.isEmpty()) {
                throw new java.io.IOException("framework module APK path unavailable");
            }
            apks.add(new File(moduleInfo.sourceDir));
            if (moduleInfo.splitSourceDirs != null) {
                for (String path : moduleInfo.splitSourceDirs) {
                    if (path != null && !path.isEmpty()) {
                        apks.add(new File(path));
                    }
                }
            }
            for (File apk : apks) {
                try (ZipFile zip = new ZipFile(apk)) {
                    ZipEntry entry = zip.getEntry(ASSET_NAME);
                    if (entry == null) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        return out.toByteArray();
                    }
                }
            }
            throw new java.io.IOException("manifest asset missing in module APKs");
        };
    }

    int schema() {
        return manifest.schema;
    }

    /**
     * The validated profile for exactly this host versionCode, or null when
     * none exists. Null is the fail-closed answer: callers must treat it as
     * UNSUPPORTED, not as "try something else".
     */
    TargetProfile validatedProfileFor(long versionCode) {
        return manifest.validatedProfileFor(versionCode);
    }

    /** Diagnostic status for logs: validated / draft / missing. */
    String profileStatus(long versionCode) {
        return manifest.profileStatus(versionCode);
    }
}
