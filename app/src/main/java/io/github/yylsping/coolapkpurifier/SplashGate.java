package io.github.yylsping.coolapkpurifier;

import android.app.Activity;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First-launch splash protection. Loose namespace/simple-name matching is
 * allowed only before MainActivity is seen. Resolved descriptors always win.
 *
 * <p>Coolapk ships several splash-family activities (brand splash, ad splash,
 * fullscreen ad) that do not share one onCreate hierarchy, so the resolved set
 * and the legacy fallback names cover all of them.
 */
final class SplashGate {
    private static final String SPLASH_PACKAGE = "com.coolapk.market.view.splash";

    /**
     * Historically observed splash-family activity names across supported
     * Coolapk versions. 16.5.1 uses SplashAdActivity for the ad splash while
     * older builds used FullScreenAdActivity.
     */
    private static final Set<String> LEGACY_SPLASH_NAMES = Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "com.coolapk.market.view.splash.SplashActivity",
                    "com.coolapk.market.view.splash.SplashAdActivity",
                    "com.coolapk.market.view.splash.FullScreenAdActivity")));

    private final Set<Class<?>> resolvedSplashClasses = ConcurrentHashMap.newKeySet();
    private volatile boolean mainActivitySeen;
    private volatile boolean firstActivitySeen;

    void addResolvedSplashClass(Class<?> type) {
        if (type != null) {
            resolvedSplashClasses.add(type);
        }
    }

    int resolvedSplashClassCount() {
        return resolvedSplashClasses.size();
    }

    void markFirstActivity() {
        firstActivitySeen = true;
    }

    boolean isFirstActivitySeen() {
        return firstActivitySeen;
    }

    void markMainActivity() {
        mainActivitySeen = true;
    }

    boolean isStartupPhase() {
        return !mainActivitySeen;
    }

    boolean isMainActivitySeen() {
        return mainActivitySeen;
    }

    boolean isResolvedSplash(Activity activity) {
        Class<?> runtimeClass = activity.getClass();
        for (Class<?> resolved : resolvedSplashClasses) {
            if (resolved.isAssignableFrom(runtimeClass)) {
                return true;
            }
        }
        return false;
    }

    boolean isFallbackSplashCandidate(Activity activity) {
        if (!isStartupPhase()) {
            return false;
        }
        return SPLASH_PACKAGE.equals(packageName(activity.getClass()))
                && isSplashSimpleName(simpleName(activity.getClass()));
    }

    boolean isLegacySplash(Activity activity) {
        return LEGACY_SPLASH_NAMES.contains(activity.getClass().getName());
    }

    boolean isLegacySplashName(String className) {
        return LEGACY_SPLASH_NAMES.contains(className);
    }

    boolean shouldFinishSplash(Activity activity) {
        return isResolvedSplash(activity)
                || isFallbackSplashCandidate(activity)
                || isLegacySplash(activity);
    }

    private static boolean isSplashSimpleName(String simpleName) {
        return simpleName != null
                && (simpleName.contains("Splash") || simpleName.contains("FullScreenAd"));
    }

    private static String packageName(Class<?> type) {
        Package pkg = type.getPackage();
        if (pkg != null) {
            return pkg.getName();
        }
        String name = type.getName();
        int lastDot = name.lastIndexOf('.');
        return lastDot < 0 ? name : name.substring(0, lastDot);
    }

    private static String simpleName(Class<?> type) {
        String name = type.getName();
        int lastDot = name.lastIndexOf('.');
        return lastDot < 0 ? name : name.substring(lastDot + 1);
    }
}
