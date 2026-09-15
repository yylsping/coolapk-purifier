package io.github.yylsping.coolapkpurifier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Root of the bundled target manifest. Parsing is fail-closed: any malformed
 * profile, duplicate versionCode or unsupported schema makes the whole
 * manifest unusable rather than silently dropping entries.
 */
final class TargetManifest {
    static final int SCHEMA = 1;

    /** Rejecting parse failure; never recoverable into a partial manifest. */
    static final class ManifestException extends Exception {
        ManifestException(String message) {
            super(message);
        }

        ManifestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final int schema;
    final List<TargetProfile> profiles;

    private TargetManifest(int schema, List<TargetProfile> profiles) {
        this.schema = schema;
        this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    static TargetManifest parse(byte[] jsonBytes) throws ManifestException {
        if (jsonBytes == null || jsonBytes.length == 0) {
            throw new ManifestException("empty manifest");
        }
        final JSONObject root;
        try {
            root = new JSONObject(new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (JSONException malformed) {
            throw new ManifestException("malformed manifest json", malformed);
        }
        int schema = root.optInt("schema", -1);
        if (schema != SCHEMA) {
            throw new ManifestException("unsupported manifest schema=" + schema);
        }
        JSONArray rawProfiles = root.optJSONArray("profiles");
        if (rawProfiles == null) {
            throw new ManifestException("manifest profiles missing");
        }
        List<TargetProfile> profiles = new ArrayList<>();
        Set<Long> seenVersions = new HashSet<>();
        for (int i = 0; i < rawProfiles.length(); i++) {
            JSONObject raw = rawProfiles.optJSONObject(i);
            if (raw == null) {
                throw new ManifestException("profile[" + i + "] is not an object");
            }
            TargetProfile profile = TargetProfile.parse(raw);
            if (!seenVersions.add(profile.versionCode)) {
                throw new ManifestException("duplicate profile versionCode="
                        + profile.versionCode);
            }
            profiles.add(profile);
        }
        return new TargetManifest(schema, profiles);
    }

    /** Exact validated profile only; never a nearest/other-status match. */
    TargetProfile validatedProfileFor(long versionCode) {
        for (TargetProfile profile : profiles) {
            if (profile.versionCode == versionCode
                    && profile.status == TargetProfile.Status.VALIDATED) {
                return profile;
            }
        }
        return null;
    }

    /** Diagnostic status string for a versionCode: validated/draft/missing. */
    String profileStatus(long versionCode) {
        for (TargetProfile profile : profiles) {
            if (profile.versionCode == versionCode) {
                return profile.status.jsonValue;
            }
        }
        return "missing";
    }
}
