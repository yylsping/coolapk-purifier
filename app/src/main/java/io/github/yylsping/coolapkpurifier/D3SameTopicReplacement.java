package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * D3 same-topic insertion-event filtering. All adaptation data comes from the
 * validated manifest profile; the host event flow is preserved exactly.
 */
final class D3SameTopicReplacement {
    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final FeatureExposureLedger exposureLedger;
    private final AtomicBoolean enteredLogged = new AtomicBoolean();
    private final AtomicBoolean payloadLogged = new AtomicBoolean();
    private final AtomicBoolean hostReturnedLogged = new AtomicBoolean();
    private final AtomicBoolean changedLogged = new AtomicBoolean();
    private volatile HookHandle handle;

    D3SameTopicReplacement(XposedModule module, ModuleLog log, FeatureGate gate) {
        this(module, log, gate, null);
    }

    D3SameTopicReplacement(XposedModule module, ModuleLog log, FeatureGate gate,
                           FeatureExposureLedger exposureLedger) {
        this.module = module;
        this.log = log;
        this.gate = gate;
        this.exposureLedger = exposureLedger;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized InstallResult install(SameTopicTargetSpec spec, ClassLoader loader) {
        if (handle != null) {
            return InstallResult.ALREADY_INSTALLED;
        }
        if (spec == null) {
            log.info("d3_hook_registered=false reason=target_missing");
            return InstallResult.TARGET_MISSING;
        }
        String descriptor = spec.eventDescriptor();
        try {
            Class<?> owner = Class.forName(spec.ownerClass, false, loader);
            Method semantic = owner.getDeclaredMethod(spec.semanticMethod, Object.class);
            if (!isExactSemanticTarget(spec, semantic)) {
                log.info("d3_hook_registered=false reason=contract_mismatch"
                        + " detail=semantic descriptor=" + descriptor);
                return InstallResult.CONTRACT_MISMATCH;
            }
            Class<?> eventClass = Class.forName(spec.eventClass, false, loader);
            Method target = owner.getDeclaredMethod(spec.eventHandler, eventClass);
            if (!isExactEventTarget(spec, target, eventClass)) {
                log.info("d3_hook_registered=false reason=contract_mismatch"
                        + " detail=event descriptor=" + descriptor);
                return InstallResult.CONTRACT_MISMATCH;
            }
            Class<?> entityClass = Class.forName(spec.entityClass, false, loader);
            Method templateGetter = entityClass.getMethod(spec.entityTemplateGetter);
            Method anchorGetter = eventClass.getDeclaredMethod(spec.anchorGetter);
            Method cardGetter = eventClass.getDeclaredMethod(spec.cardGetter);
            Constructor<?> constructor = eventClass.getDeclaredConstructor(
                    String.class, List.class);
            if (templateGetter.getParameterCount() != 0
                    || templateGetter.getReturnType() != String.class
                    || anchorGetter.getParameterCount() != 0
                    || anchorGetter.getReturnType() != String.class
                    || cardGetter.getParameterCount() != 0
                    || cardGetter.getReturnType() != List.class) {
                log.info("d3_hook_registered=false reason=contract_mismatch"
                        + " detail=accessors descriptor=" + descriptor);
                return InstallResult.CONTRACT_MISMATCH;
            }
            templateGetter.setAccessible(true);
            anchorGetter.setAccessible(true);
            cardGetter.setAccessible(true);
            constructor.setAccessible(true);
            target.setAccessible(true);
            handle = module.hook(target)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-d3-same-topic-insert-event")
                    .intercept(chain -> {
                        if (exposureLedger != null) {
                            exposureLedger.recordEntered(
                                    PurifierConfig.Feature.SAME_TOPIC_FEED);
                        }
                        if (!gate.isEffectiveEnabled(
                                PurifierConfig.Feature.SAME_TOPIC_FEED)) {
                            return chain.proceed();
                        }
                        if (enteredLogged.compareAndSet(false, true)) {
                            log.info("d3_interceptor_entered=true descriptor=" + descriptor);
                        }
                        Object originalEvent = chain.getArg(0);
                        Object hostEvent = originalEvent;
                        int sourceSize = -1;
                        int removedCount = 0;
                        try {
                            if (eventClass.isInstance(originalEvent)) {
                                Object cards = cardGetter.invoke(originalEvent);
                                Object anchor = anchorGetter.invoke(originalEvent);
                                if (cards instanceof List<?> && anchor instanceof String) {
                                    List<?> source = (List<?>) cards;
                                    sourceSize = source.size();
                                    if (payloadLogged.compareAndSet(false, true)) {
                                        log.info("d3_host_event_payload_observed=true"
                                                + " descriptor=" + descriptor
                                                + " size=" + sourceSize
                                                + " anchorPresent=true");
                                    }
                                    List<?> filtered = filterSameTopicCards(spec,
                                            source, entityClass, templateGetter);
                                    if (filtered != source) {
                                        if (exposureLedger != null) {
                                            exposureLedger.recordSampleSeen(
                                                    PurifierConfig.Feature.SAME_TOPIC_FEED);
                                        }
                                        hostEvent = constructor.newInstance(anchor, filtered);
                                        removedCount = source.size() - filtered.size();
                                    }
                                }
                            }
                        } catch (Throwable throwable) {
                            hostEvent = originalEvent;
                            removedCount = 0;
                            log.error("d3 filter failed; preserving original event", throwable);
                        }
                        Object result = hostEvent == originalEvent
                                ? chain.proceed()
                                : chain.proceed(new Object[]{hostEvent});
                        if (hostEvent != originalEvent && exposureLedger != null) {
                            exposureLedger.recordModified(
                                    PurifierConfig.Feature.SAME_TOPIC_FEED);
                        }
                        if (hostReturnedLogged.compareAndSet(false, true)) {
                            log.info("d3_host_returned=true originalHandlerPreserved=true"
                                    + " descriptor=" + descriptor
                                    + " eventReplaced=" + (hostEvent != originalEvent));
                        }
                        if (removedCount > 0 && changedLogged.compareAndSet(false, true)) {
                            log.info("d3_filtered_list_changed=true removedCount="
                                    + removedCount + " sourceSize=" + sourceSize
                                    + " hostInsertExecuted=true template=" + spec.entityTemplate);
                        }
                        return result;
                    });
            log.info("d3_hook_registered=true source=manifest_exact"
                    + " semantic=" + spec.semanticDescriptor()
                    + " descriptor=" + descriptor
                    + " hostHandlerPreserved=true");
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.SAME_TOPIC_FEED);
            }
            return InstallResult.INSTALLED;
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d3_hook_registered=false reason=target_missing"
                    + " descriptor=" + descriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        } catch (Throwable throwable) {
            log.error("d3_hook_registered=false reason=installation_failure"
                    + " descriptor=" + descriptor, throwable);
            return InstallResult.INSTALL_FAILED;
        }
    }

    static boolean isExactSemanticTarget(SameTopicTargetSpec spec, Method method) {
        if (spec == null || method == null
                || !spec.ownerClass.equals(method.getDeclaringClass().getName())
                || !spec.semanticMethod.equals(method.getName())
                || !Modifier.isStatic(method.getModifiers())
                || Modifier.isAbstract(method.getModifiers())
                || method.getReturnType() != boolean.class
                || method.getParameterCount() != 1
                || method.getParameterTypes()[0] != Object.class) {
            return false;
        }
        Class<?> cursor = method.getDeclaringClass();
        while (cursor != null) {
            if (spec.ownerParentClass.equals(cursor.getName())) {
                return true;
            }
            cursor = cursor.getSuperclass();
        }
        return false;
    }

    static boolean isExactEventTarget(SameTopicTargetSpec spec, Method method,
                                      Class<?> eventClass) {
        return spec != null && method != null && eventClass != null
                && spec.ownerClass.equals(method.getDeclaringClass().getName())
                && spec.eventHandler.equals(method.getName())
                && spec.eventClass.equals(eventClass.getName())
                && !Modifier.isStatic(method.getModifiers())
                && method.getReturnType() == void.class
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == eventClass;
    }

    static List<?> filterSameTopicCards(SameTopicTargetSpec spec, List<?> source,
                                        Class<?> entityClass, Method templateGetter)
            throws ReflectiveOperationException {
        if (spec == null || source == null || entityClass == null || templateGetter == null) {
            return source;
        }
        ArrayList<Object> filtered = null;
        for (int index = 0; index < source.size(); index++) {
            Object item = source.get(index);
            Object value = item != null && entityClass.isInstance(item)
                    ? templateGetter.invoke(item) : null;
            if (spec.entityTemplate.equals(value)) {
                if (filtered == null) {
                    filtered = new ArrayList<>(Math.max(0, source.size() - 1));
                    filtered.addAll(source.subList(0, index));
                }
            } else if (filtered != null) {
                filtered.add(item);
            }
        }
        return filtered == null ? source : filtered;
    }
}
