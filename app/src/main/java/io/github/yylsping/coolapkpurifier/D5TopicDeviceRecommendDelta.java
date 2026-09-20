package io.github.yylsping.coolapkpurifier;

import android.annotation.SuppressLint;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

/**
 * D5 topic/device recommend card, conservative two-layer design:
 *
 * <pre>
 * FeedBottomViewHolder compose assembler  -> observe only, ALWAYS proceeds
 * FeedNewTargetRowUI terminal composable  -> the single suppression point
 * </pre>
 *
 * The host assembler, theme wrapper, getTargetRow() read and every upstream
 * data/analytics path run exactly as unmodified; only the terminal card
 * composable emits nothing when the feature is enabled. Whole-assembler skip
 * is gone: original_skipped no longer exists, and entered/executed counts
 * must always be equal.
 */
final class D5TopicDeviceRecommendDelta {
    static final String ASSEMBLER_HOOK_ID = "coolapk-d5-topic-device-recommend";
    static final String UI_HOOK_ID = "coolapk-d5-topic-device-recommend-ui";
    private static final int SUMMARY_EVERY = 32;

    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final FeatureExposureLedger exposureLedger;

    private final AtomicInteger targetEnteredCount = new AtomicInteger();
    private final AtomicInteger originalExecutedCount = new AtomicInteger();
    private final AtomicInteger targetUiObservedCount = new AtomicInteger();
    private final AtomicInteger targetUiSuppressedCount = new AtomicInteger();
    private final AtomicBoolean assemblerEnteredLogged = new AtomicBoolean();
    private final AtomicBoolean targetUiSuppressedLogged = new AtomicBoolean();

    // HookHandles must remain strongly reachable for the process lifetime.
    private volatile HookHandle assemblerHandle;
    private volatile HookHandle uiHandle;

    D5TopicDeviceRecommendDelta(XposedModule module, ModuleLog log, FeatureGate gate) {
        this(module, log, gate, null);
    }

    D5TopicDeviceRecommendDelta(XposedModule module, ModuleLog log, FeatureGate gate,
                                FeatureExposureLedger exposureLedger) {
        this.module = module;
        this.log = log;
        this.gate = gate;
        this.exposureLedger = exposureLedger;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized InstallResult install(TopicDeviceTargetSpec assemblerSpec,
                                       TopicDeviceUiTargetSpec uiSpec,
                                       ClassLoader loader) {
        if (assemblerHandle != null || uiHandle != null) {
            return InstallResult.ALREADY_INSTALLED;
        }
        if (assemblerSpec == null || uiSpec == null) {
            log.info("d5_hook_registered=false reason=target_missing"
                    + " assemblerSpec=" + (assemblerSpec != null)
                    + " uiSpec=" + (uiSpec != null));
            return InstallResult.TARGET_MISSING;
        }
        String assemblerDescriptor = assemblerSpec.descriptor();
        String uiDescriptor = uiSpec.descriptor();
        Method assembler;
        Method ui;
        try {
            assembler = findMethod(assemblerSpec, loader);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d5_hook_registered=false reason=target_missing layer=assembler"
                    + " descriptor=" + assemblerDescriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        }
        if (!ExactMethodVerifier.isExactTarget(assemblerSpec, assembler)) {
            log.info("d5_hook_registered=false reason=contract_mismatch layer=assembler"
                    + " descriptor=" + assemblerDescriptor);
            return InstallResult.CONTRACT_MISMATCH;
        }
        try {
            ui = findMethod(uiSpec, loader);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d5_hook_registered=false reason=target_missing layer=ui"
                    + " descriptor=" + uiDescriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        }
        if (!ExactMethodVerifier.isExactTarget(uiSpec, ui)) {
            log.info("d5_hook_registered=false reason=contract_mismatch layer=ui"
                    + " descriptor=" + uiDescriptor);
            return InstallResult.CONTRACT_MISMATCH;
        }
        try {
            assembler.setAccessible(true);
            ui.setAccessible(true);
            assemblerHandle = module.hook(assembler)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(ASSEMBLER_HOOK_ID)
                    .intercept(chain -> onAssemblerEnter(chain, assemblerDescriptor));
            uiHandle = module.hook(ui)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(UI_HOOK_ID)
                    .intercept(chain -> onTargetUi(chain, gateEnabled(), uiDescriptor));
            log.info("d5_hook_registered=true source=manifest_exact layer=assembler"
                    + " mode=observe_only descriptor=" + assemblerDescriptor);
            log.info("d5_hook_registered=true source=manifest_exact layer=ui"
                    + " mode=ui_terminal descriptor=" + uiDescriptor);
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(
                        PurifierConfig.Feature.TOPIC_DEVICE_RECOMMEND);
            }
            return InstallResult.INSTALLED;
        } catch (Throwable throwable) {
            // both-or-nothing: a half-installed pair must never survive an
            // installation failure.
            rollbackHandles();
            log.error("d5_hook_registered=false reason=installation_failure"
                    + " descriptor=" + assemblerDescriptor, throwable);
            return InstallResult.INSTALL_FAILED;
        }
    }

    private void rollbackHandles() {
        HookHandle ui = uiHandle;
        uiHandle = null;
        if (ui != null) {
            try {
                ui.unhook();
            } catch (Throwable failure) {
                log.error("d5 ui hook rollback failed", failure);
            }
        }
        HookHandle assembler = assemblerHandle;
        assemblerHandle = null;
        if (assembler != null) {
            try {
                assembler.unhook();
            } catch (Throwable failure) {
                log.error("d5 assembler hook rollback failed", failure);
            }
        }
    }

    private static Method findMethod(StaticAssemblerTargetSpec spec, ClassLoader loader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?>[] parameters = new Class<?>[spec.parameterTypes.size()];
        Class<?> owner = Class.forName(spec.ownerClass, false, loader);
        for (int i = 0; i < parameters.length; i++) {
            String name = spec.parameterTypes.get(i);
            parameters[i] = "int".equals(name) ? int.class
                    : Class.forName(name, false, loader);
        }
        return owner.getDeclaredMethod(spec.methodName, parameters);
    }

    private boolean gateEnabled() {
        return gate != null && gate.isEffectiveEnabled(
                PurifierConfig.Feature.TOPIC_DEVICE_RECOMMEND);
    }

    /**
     * Assembler policy: observe only. The original always runs; entered and
     * executed counts are the proof that nothing is ever skipped. The
     * first-entry line is logged AFTER proceed() returns, so its executed
     * count is direct evidence that the original ran to completion.
     */
    Object onAssemblerEnter(Chain chain, String descriptor) throws Throwable {
        int entered = targetEnteredCount.incrementAndGet();
        if (exposureLedger != null) {
            exposureLedger.recordEntered(PurifierConfig.Feature.TOPIC_DEVICE_RECOMMEND);
        }
        Object result = chain.proceed();
        int executed = originalExecutedCount.incrementAndGet();
        if (assemblerEnteredLogged.compareAndSet(false, true)) {
            log.info("d5_assembler_entered=true count=" + entered
                    + " original_executed_count=" + executed
                    + " descriptor=" + descriptor);
        }
        return result;
    }

    /**
     * Terminal card composable. Enabled: the composable emits nothing (a
     * no-op composable is legal from the caller's perspective) and the void
     * result is answered directly. Disabled: the original runs untouched
     * (fail open).
     */
    Object onTargetUi(Chain chain, boolean enabled, String descriptor) throws Throwable {
        int observed = targetUiObservedCount.incrementAndGet();
        if (exposureLedger != null) {
            exposureLedger.recordSampleSeen(PurifierConfig.Feature.TOPIC_DEVICE_RECOMMEND);
        }
        if (!enabled) {
            return chain.proceed();
        }
        int suppressed = targetUiSuppressedCount.incrementAndGet();
        if (exposureLedger != null) {
            exposureLedger.recordModified(PurifierConfig.Feature.TOPIC_DEVICE_RECOMMEND);
        }
        if (targetUiSuppressedLogged.compareAndSet(false, true)) {
            log.info("d5_target_ui_suppressed=true count=" + suppressed
                    + " descriptor=" + descriptor);
        }
        if (observed % SUMMARY_EVERY == 0) {
            log.info(summaryLine());
        }
        return suppressedResult();
    }

    /** The terminal composable returns void; the answer value is ignored. */
    static Object suppressedResult() {
        return null;
    }

    String summaryLine() {
        return "d5_target_ui_summary"
                + " d5_target_entered_count=" + targetEnteredCount.get()
                + " d5_original_executed_count=" + originalExecutedCount.get()
                + " d5_target_ui_observed_count=" + targetUiObservedCount.get()
                + " d5_target_ui_suppressed_count=" + targetUiSuppressedCount.get();
    }

    // Test-only visibility into the both-or-nothing install invariant.
    synchronized boolean anyHookInstalled() {
        return assemblerHandle != null || uiHandle != null;
    }
}
