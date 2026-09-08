package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/** Literal-2.1.2 D3 experiment: exact same-topic insertion-event filtering only. */
final class D3SameTopicReplacement {
    static final long HOST_VERSION_CODE = 2_608_212L;
    static final String OWNER = "com.coolapk.market.view.cardlist.MainV8ListFragment";
    static final String OWNER_PARENT = "com.coolapk.market.view.cardlist.EntityListFragment";
    static final String SEMANTIC_METHOD = "\u0abd";
    static final String SEMANTIC_DESCRIPTOR =
            "Lcom/coolapk/market/view/cardlist/MainV8ListFragment;->"
                    + "\u0abd(Ljava/lang/Object;)Z";
    static final String EVENT_CLASS = "yl6";
    static final String EVENT_HANDLER = "onInsertRecommendListEvent";
    static final String EVENT_DESCRIPTOR =
            "Lcom/coolapk/market/view/cardlist/MainV8ListFragment;->"
                    + "onInsertRecommendListEvent(Lyl6;)V";
    static final String ANCHOR_GETTER = "\u037f";
    static final String CARD_GETTER = "\u0528";
    static final String ENTITY_CLASS = "com.coolapk.market.model.Entity";
    static final String TEMPLATE_GETTER = "getEntityTemplate";
    static final String TEMPLATE = "feedRecommendListCard";

    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final AtomicBoolean enteredLogged = new AtomicBoolean();
    private final AtomicBoolean payloadLogged = new AtomicBoolean();
    private final AtomicBoolean hostReturnedLogged = new AtomicBoolean();
    private final AtomicBoolean changedLogged = new AtomicBoolean();
    private volatile HookHandle handle;

    D3SameTopicReplacement(XposedModule module, ModuleLog log, FeatureGate gate) {
        this.module = module;
        this.log = log;
        this.gate = gate;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized void install(Context context, ClassLoader loader) {
        if (handle != null) return;
        try {
            long versionCode = hostVersionCode(context);
            if (versionCode != HOST_VERSION_CODE) {
                log.info("d3_hook_registered=false reason=host_version_mismatch"
                        + " expected=" + HOST_VERSION_CODE + " actual=" + versionCode);
                return;
            }
            Class<?> owner = Class.forName(OWNER, false, loader);
            Method semantic = owner.getDeclaredMethod(SEMANTIC_METHOD, Object.class);
            if (!isExactSemanticTarget(semantic)) {
                throw new NoSuchMethodException("same-topic semantic target mismatch");
            }
            Class<?> eventClass = Class.forName(EVENT_CLASS, false, loader);
            Method target = owner.getDeclaredMethod(EVENT_HANDLER, eventClass);
            if (!isExactEventTarget(target, eventClass)) {
                throw new NoSuchMethodException("same-topic event target mismatch");
            }
            Class<?> entityClass = Class.forName(ENTITY_CLASS, false, loader);
            Method templateGetter = entityClass.getMethod(TEMPLATE_GETTER);
            Method anchorGetter = eventClass.getDeclaredMethod(ANCHOR_GETTER);
            Method cardGetter = eventClass.getDeclaredMethod(CARD_GETTER);
            Constructor<?> constructor = eventClass.getDeclaredConstructor(
                    String.class, List.class);
            if (templateGetter.getParameterCount() != 0
                    || templateGetter.getReturnType() != String.class
                    || anchorGetter.getParameterCount() != 0
                    || anchorGetter.getReturnType() != String.class
                    || cardGetter.getParameterCount() != 0
                    || cardGetter.getReturnType() != List.class) {
                throw new NoSuchMethodException("same-topic accessor mismatch");
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
                        if (!gate.isEffectiveEnabled(
                                PurifierConfig.Feature.SAME_TOPIC_FEED)) {
                            return chain.proceed();
                        }
                        if (enteredLogged.compareAndSet(false, true)) {
                            log.info("d3_interceptor_entered=true descriptor="
                                    + EVENT_DESCRIPTOR);
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
                                                + " descriptor=" + EVENT_DESCRIPTOR
                                                + " size=" + sourceSize
                                                + " anchorPresent=true");
                                    }
                                    List<?> filtered = filterSameTopicCards(
                                            source, entityClass, templateGetter);
                                    if (filtered != source) {
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
                        if (hostReturnedLogged.compareAndSet(false, true)) {
                            log.info("d3_host_returned=true originalHandlerPreserved=true"
                                    + " descriptor=" + EVENT_DESCRIPTOR
                                    + " eventReplaced=" + (hostEvent != originalEvent));
                        }
                        if (removedCount > 0 && changedLogged.compareAndSet(false, true)) {
                            log.info("d3_filtered_list_changed=true removedCount="
                                    + removedCount + " sourceSize=" + sourceSize
                                    + " hostInsertExecuted=true template=" + TEMPLATE);
                        }
                        return result;
                    });
            log.info("d3_hook_registered=true source=version_pinned_exact"
                    + " versionCode=" + versionCode
                    + " semantic=" + SEMANTIC_DESCRIPTOR
                    + " descriptor=" + EVENT_DESCRIPTOR
                    + " hostHandlerPreserved=true");
        } catch (Throwable throwable) {
            log.error("d3_hook_registered=false reason=installation_failure"
                    + " descriptor=" + EVENT_DESCRIPTOR, throwable);
        }
    }

    static boolean isExactSemanticTarget(Method method) {
        if (method == null || !OWNER.equals(method.getDeclaringClass().getName())
                || !SEMANTIC_METHOD.equals(method.getName())
                || !Modifier.isStatic(method.getModifiers())
                || Modifier.isAbstract(method.getModifiers())
                || method.getReturnType() != boolean.class
                || method.getParameterCount() != 1
                || method.getParameterTypes()[0] != Object.class) return false;
        Class<?> cursor = method.getDeclaringClass();
        while (cursor != null) {
            if (OWNER_PARENT.equals(cursor.getName())) return true;
            cursor = cursor.getSuperclass();
        }
        return false;
    }

    static boolean isExactEventTarget(Method method, Class<?> eventClass) {
        return method != null && eventClass != null
                && OWNER.equals(method.getDeclaringClass().getName())
                && EVENT_HANDLER.equals(method.getName())
                && EVENT_CLASS.equals(eventClass.getName())
                && !Modifier.isStatic(method.getModifiers())
                && method.getReturnType() == void.class
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == eventClass;
    }

    static List<?> filterSameTopicCards(List<?> source, Class<?> entityClass,
                                        Method templateGetter)
            throws ReflectiveOperationException {
        if (source == null || entityClass == null || templateGetter == null) return source;
        ArrayList<Object> filtered = null;
        for (int index = 0; index < source.size(); index++) {
            Object item = source.get(index);
            Object value = item != null && entityClass.isInstance(item)
                    ? templateGetter.invoke(item) : null;
            if (TEMPLATE.equals(value)) {
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

    private static long hostVersionCode(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.getLongVersionCode();
        //noinspection deprecation
        return info.versionCode;
    }
}
