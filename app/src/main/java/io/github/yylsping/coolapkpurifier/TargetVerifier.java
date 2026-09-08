package io.github.yylsping.coolapkpurifier;

import android.app.Activity;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

final class TargetVerifier {
    private TargetVerifier() {
    }

    /** Returns a failure reason or null when the cached target is usable. */
    static String verify(ResolvedTarget target, ClassLoader loader) {
        if (target == null) {
            return "null target";
        }
        if (target.key == null || target.key.isEmpty()) {
            return "empty key";
        }
        try {
            Class<?> type = DescriptorUtils.classForName(target.classDescriptor, loader);
            if (type == null) {
                return "class not loadable";
            }
            // Splash targets may legitimately carry an empty method descriptor;
            // the real onCreate is located at install time via findOnCreate.
            if (target.methodDescriptor == null || target.methodDescriptor.isEmpty()) {
                return TargetResolver.isSplashKey(target.key)
                        && Activity.class.isAssignableFrom(type)
                        ? null : "empty method descriptor";
            }
            Method method = DescriptorUtils.methodForDescriptor(target.methodDescriptor, loader);
            if (method == null) {
                return "method not loadable";
            }
            switch (keyKind(target.key)) {
                case TargetResolver.KEY_FEED:
                    return isFeedShape(method) && !Modifier.isAbstract(method.getModifiers())
                            ? null : "feed shape mismatch";
                case TargetResolver.KEY_SPLASH_BASE:
                    return Activity.class.isAssignableFrom(type)
                            && "onCreate".equals(method.getName())
                            && method.getParameterTypes().length == 1
                            && method.getParameterTypes()[0] == Bundle.class
                            ? null : "splash shape mismatch";
                default:
                    return method.getParameterTypes().length == 0
                            && method.getReturnType() == String.class
                            ? null : "getter shape mismatch";
            }
        } catch (Throwable throwable) {
            return String.valueOf(throwable);
        }
    }

    /** Normalizes feed#2 / splash_base#2 style keys to their base key. */
    private static String keyKind(String key) {
        if (TargetResolver.isFeedKey(key)) {
            return TargetResolver.KEY_FEED;
        }
        if (TargetResolver.isSplashKey(key)) {
            return TargetResolver.KEY_SPLASH_BASE;
        }
        return key;
    }

    static boolean isFeedShape(Method method) {
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && List.class.isAssignableFrom(params[0])
                && params[1] == boolean.class
                && List.class.isAssignableFrom(method.getReturnType());
    }

    /**
     * Returns the most-derived onCreate(Bundle) declared by the Coolapk
     * splash hierarchy. Framework/AndroidX Activity.onCreate is deliberately
     * excluded so we never hook every Activity in the process.
     */
    static Method findOnCreate(Class<?> type) {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            if (!cursor.getName().startsWith("com.coolapk.market.")) {
                break;
            }
            try {
                Method method = cursor.getDeclaredMethod("onCreate", Bundle.class);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }
}
