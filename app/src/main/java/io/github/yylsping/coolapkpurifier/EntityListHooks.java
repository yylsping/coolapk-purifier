package io.github.yylsping.coolapkpurifier;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * Feed filter hooks. 2.0.1 covered every (List, boolean) -> List transformer
 * in both EntityAdHelper and EntityListFragment; this class keeps that shape:
 * every newly resolved business entry is hooked additively and existing hooks
 * are never removed, so partial resolutions cannot shrink coverage.
 */
final class EntityListHooks {
    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final EntityClassifier classifier = new EntityClassifier();
    private final EntityListFilter filter = new EntityListFilter(classifier);
    private final HookedFeedRegistry hooked = new HookedFeedRegistry();
    private final Map<Method, HookHandle> handles = new HashMap<>();
    private volatile boolean accessorsComplete;

    EntityListHooks(XposedModule module, ModuleLog log, FeatureGate gate) {
        this.module = module;
        this.log = log;
        this.gate = gate;
    }

    void updateAccessors(Map<String, ResolvedTarget> targets, ClassLoader loader) {
        EntityAccessors accessors = EntityAccessors.fromTargets(targets, loader);
        classifier.setAccessors(accessors);
        accessorsComplete = accessors.isComplete();
    }

    boolean isAccessorsComplete() {
        return accessorsComplete;
    }

    /** Live-anchor probe for coverage settling; sees installed hooks only. */
    boolean hasHookedInClass(String classDescriptor) {
        return hooked.hasHookedInClass(classDescriptor);
    }

    /**
     * Hooks one more list transformer. Returns how many hooks were added by
     * this call (0 when the method was already hooked or is unusable).
     */
    synchronized int install(Method method) {
        if (method == null || hooked.contains(method)) {
            return 0;
        }
        try {
            HookHandle handle = module.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-feed-filter")
                    .intercept(chain -> {
                        Object original = chain.proceed();
                        if (!(original instanceof List<?>)) {
                            return original;
                        }
                        if (!gate.isEffectiveEnabled(
                                PurifierConfig.Feature.FEED_SPONSOR)) {
                            return original;
                        }
                        try {
                            List<?> source = (List<?>) original;
                            List<?> filtered = filter.filter(source);
                            if (filtered != source) {
                                log.info("removed " + (source.size() - filtered.size())
                                        + " sponsored item(s) via " + method);
                            }
                            return filtered;
                        } catch (Throwable throwable) {
                            log.error("ad filtering failed; preserving original list", throwable);
                            return original;
                        }
                    });
            handles.put(method, handle);
            hooked.add(method);
            log.info("installed feed filter hook method=" + method);
            return 1;
        } catch (Throwable throwable) {
            log.error("feed filter hook install failed method=" + method, throwable);
            return 0;
        }
    }

    synchronized int installAll(Collection<Method> methods) {
        int installed = 0;
        if (methods == null) {
            return 0;
        }
        for (Method method : methods) {
            installed += install(method);
        }
        return installed;
    }

    synchronized int hookedMethodCount() {
        return hooked.size();
    }
}
