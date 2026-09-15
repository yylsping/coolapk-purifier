package io.github.yylsping.coolapkpurifier;

import android.content.Context;
import android.os.Parcelable;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Strict semantic resolver for Coolapk's embedded MainActivity splash decision. */
final class SplashDecisionResolver {
    static final String SOURCE = "splash_decision_post_result_v1";
    static final String MAIN = "com.coolapk.market.view.main.MainActivity";
    static final String FRAGMENT = "com.coolapk.market.view.splash.SplashAdFragment";
    static final List<String> DECISION_MARKERS = Arrays.asList(
            "[shouldShowAd] start",
            "[shouldShowAd] splash type args: ",
            "TENCENT_AD_ERROR_TIMESTAMP",
            "SPLASH_AD_LAST_SHOW",
            "[shouldShowAd] everything is fine, show splash");

    static ResolvedTarget resolve(DexKitBridge bridge, ClassLoader loader, ModuleLog log) {
        try {
            return resolveStrict(bridge, loader, log);
        } catch (Throwable failure) {
            log.error("splash decision resolution failed; other resolvers continue", failure);
            return null;
        }
    }

    private static ResolvedTarget resolveStrict(DexKitBridge bridge, ClassLoader loader,
                                                ModuleLog log) {
        MethodMatcher matcher = MethodMatcher.create().returnType("boolean").paramCount(3)
                .declaredClass(ClassMatcher.create().source(
                        "FullScreenAdUtils.kt", StringMatchType.Equals, false));
        for (String marker : DECISION_MARKERS) {
            matcher.addUsingString(marker, StringMatchType.Equals);
        }
        List<ResolvedTarget> decisions = new ArrayList<>();
        for (MethodData data : bridge.findMethod(FindMethod.create().matcher(matcher))) {
            boolean mainCaller = false;
            boolean onlyBusinessCallers = true;
            for (MethodData caller : data.getCallers()) {
                String owner = caller.getDeclaredClassName();
                mainCaller |= MAIN.equals(owner) && "onCreate".equals(caller.getName())
                        && caller.getParamTypeNames().equals(
                        java.util.Collections.singletonList("android.os.Bundle"));
                onlyBusinessCallers &= MAIN.equals(owner)
                        || "com.coolapk.market.CoolMarketApplication".equals(owner);
            }
            boolean clock = data.getInvokes().stream().anyMatch(method ->
                    "java.lang.System".equals(method.getDeclaredClassName())
                            && "currentTimeMillis".equals(method.getName()));
            boolean firstInstall = data.getUsingFields().stream().anyMatch(field ->
                    "Landroid/content/pm/PackageInfo;->firstInstallTime:J"
                            .equals(field.getField().getDescriptor()));
            if (!mainCaller || !onlyBusinessCallers || !clock || !firstInstall) {
                continue;
            }
            ResolvedTarget target = new ResolvedTarget(
                    TargetResolver.KEY_SPLASH_DECISION,
                    SOURCE,
                    "L" + data.getDeclaredClassName().replace('.', '/') + ";",
                    data.getDescriptor());
            if (verify(target, loader)) {
                decisions.add(target);
            }
        }
        ResolvedTarget decision = decisions.size() == 1 ? decisions.get(0) : null;
        log.info("splash decision candidates=" + decisions.size()
                + " unique=" + (decision != null) + " failClosed=" + (decision == null));
        return decision;
    }

    static boolean decisionShape(Method method) {
        if (method == null) {
            return false;
        }
        int flags = method.getModifiers();
        Class<?>[] parameters = method.getParameterTypes();
        return Modifier.isPublic(flags)
                && Modifier.isStatic(flags)
                && Modifier.isFinal(flags)
                && !Modifier.isNative(flags)
                && !Modifier.isAbstract(flags)
                && Modifier.isFinal(method.getDeclaringClass().getModifiers())
                && method.getReturnType() == boolean.class
                && parameters.length == 3
                && parameters[0] == Context.class
                && parameters[2] == String.class
                && sourceShape(parameters[1]);
    }

    static boolean sourceShape(Class<?> source) {
        try {
            return Modifier.isFinal(source.getModifiers())
                    && Parcelable.class.isAssignableFrom(source)
                    && Modifier.isPublic(source.getDeclaredConstructor(
                    String.class, String.class, String.class).getModifiers());
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    static boolean verify(ResolvedTarget target, ClassLoader loader) {
        try {
            if (target == null
                    || !TargetResolver.KEY_SPLASH_DECISION.equals(target.key)
                    || !SOURCE.equals(target.source)) {
                return false;
            }
            Method method = DescriptorUtils.methodForDescriptor(target.methodDescriptor, loader);
            return method != null
                    && decisionShape(method)
                    && org.luckypray.dexkit.util.DexSignUtil.getDescriptor(method)
                    .equals(target.methodDescriptor)
                    && DescriptorUtils.classDescriptorOf(method.getDeclaringClass())
                    .equals(target.classDescriptor);
        } catch (Throwable failure) {
            return false;
        }
    }

    static boolean hasEmbeddedHost(ClassLoader loader) {
        try {
            Class.forName(FRAGMENT, false, loader);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (LinkageError | SecurityException unresolved) {
            // Uncertain capability cannot certify that Activity-only coverage is complete.
            return true;
        }
    }

    private SplashDecisionResolver() {
    }
}
