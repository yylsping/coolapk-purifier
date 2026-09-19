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
 * D6 auto-comment prompt, conservative two-layer design:
 *
 * <pre>
 * controller callback (full-visible setup)  -> observe only, ALWAYS proceeds
 * terminal prompt UI action (host lambda)   -> the single suppression point
 * </pre>
 *
 * The host coroutine, scroll listener, debounce and gates therefore run
 * exactly as unmodified; only the final "show quick-comment bar" action is
 * suppressed when the feature is enabled. Whole-callback skip is gone:
 * original_skipped no longer exists, and controller entered/executed counts
 * must always be equal.
 */
final class D6AutoCommentDelta {
    static final String CONTROLLER_HOOK_ID = "coolapk-d6-auto-comment";
    static final String PROMPT_HOOK_ID = "coolapk-d6-auto-comment-prompt";
    private static final int SUMMARY_EVERY = 32;

    private final XposedModule module;
    private final ModuleLog log;
    private final FeatureGate gate;
    private final FeatureExposureLedger exposureLedger;

    private final AtomicInteger controllerEnteredCount = new AtomicInteger();
    private final AtomicInteger originalExecutedCount = new AtomicInteger();
    private final AtomicInteger promptObservedCount = new AtomicInteger();
    private final AtomicInteger promptSuppressedCount = new AtomicInteger();
    private final AtomicBoolean controllerEnteredLogged = new AtomicBoolean();
    private final AtomicBoolean promptSuppressedLogged = new AtomicBoolean();

    // HookHandles must remain strongly reachable for the process lifetime.
    private volatile HookHandle controllerHandle;
    private volatile HookHandle promptHandle;

    private volatile Object unitInstance;

    D6AutoCommentDelta(XposedModule module, ModuleLog log, FeatureGate gate) {
        this(module, log, gate, null);
    }

    D6AutoCommentDelta(XposedModule module, ModuleLog log, FeatureGate gate,
                       FeatureExposureLedger exposureLedger) {
        this.module = module;
        this.log = log;
        this.gate = gate;
        this.exposureLedger = exposureLedger;
    }

    @SuppressLint("NewApi") // libxposed Executable hooks run only inside the API-31 host.
    synchronized InstallResult install(AutoCommentTargetSpec controllerSpec,
                                       AutoCommentPromptTargetSpec promptSpec,
                                       ClassLoader loader) {
        if (controllerHandle != null || promptHandle != null) {
            return InstallResult.ALREADY_INSTALLED;
        }
        if (controllerSpec == null || promptSpec == null) {
            log.info("d6_hook_registered=false reason=target_missing"
                    + " controllerSpec=" + (controllerSpec != null)
                    + " promptSpec=" + (promptSpec != null));
            return InstallResult.TARGET_MISSING;
        }
        String controllerDescriptor = controllerSpec.descriptor();
        String promptDescriptor = promptSpec.descriptor();
        Method controller;
        Method prompt;
        try {
            controller = findMethod(controllerSpec, loader);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d6_hook_registered=false reason=target_missing layer=controller"
                    + " descriptor=" + controllerDescriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        }
        if (!ExactMethodVerifier.isExactTarget(controllerSpec, controller)) {
            log.info("d6_hook_registered=false reason=contract_mismatch layer=controller"
                    + " descriptor=" + controllerDescriptor);
            return InstallResult.CONTRACT_MISMATCH;
        }
        try {
            prompt = findMethod(promptSpec, loader);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            log.info("d6_hook_registered=false reason=target_missing layer=prompt"
                    + " descriptor=" + promptDescriptor + " error=" + missing);
            return InstallResult.TARGET_MISSING;
        }
        if (!ExactMethodVerifier.isExactTarget(promptSpec, prompt)) {
            log.info("d6_hook_registered=false reason=contract_mismatch layer=prompt"
                    + " descriptor=" + promptDescriptor);
            return InstallResult.CONTRACT_MISMATCH;
        }
        try {
            unitInstance = resolveUnitInstance(prompt.getDeclaringClass().getClassLoader());
            if (unitInstance == null) {
                log.info("d6_unit_instance_resolved=false layer=prompt"
                        + " descriptor=" + promptDescriptor);
            }
            controller.setAccessible(true);
            prompt.setAccessible(true);
            controllerHandle = module.hook(controller)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(CONTROLLER_HOOK_ID)
                    .intercept(chain -> onControllerEnter(chain, controllerDescriptor));
            promptHandle = module.hook(prompt)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(PROMPT_HOOK_ID)
                    .intercept(chain -> onPromptAction(chain, gateEnabled(), promptDescriptor));
            log.info("d6_hook_registered=true source=manifest_exact layer=controller"
                    + " mode=observe_only descriptor=" + controllerDescriptor);
            log.info("d6_hook_registered=true source=manifest_exact layer=prompt"
                    + " mode=ui_terminal descriptor=" + promptDescriptor);
            if (exposureLedger != null) {
                exposureLedger.recordHookInstalled(PurifierConfig.Feature.AUTO_COMMENT);
            }
            return InstallResult.INSTALLED;
        } catch (Throwable throwable) {
            // both-or-nothing: a half-installed pair must never survive an
            // installation failure.
            rollbackHandles();
            log.error("d6_hook_registered=false reason=installation_failure"
                    + " descriptor=" + controllerDescriptor, throwable);
            return InstallResult.INSTALL_FAILED;
        }
    }

    private void rollbackHandles() {
        HookHandle prompt = promptHandle;
        promptHandle = null;
        if (prompt != null) {
            try {
                prompt.unhook();
            } catch (Throwable failure) {
                log.error("d6 prompt hook rollback failed", failure);
            }
        }
        HookHandle controller = controllerHandle;
        controllerHandle = null;
        if (controller != null) {
            try {
                controller.unhook();
            } catch (Throwable failure) {
                log.error("d6 controller hook rollback failed", failure);
            }
        }
    }

    private static Method findMethod(StaticAssemblerTargetSpec spec, ClassLoader loader)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?>[] parameters = new Class<?>[spec.parameterTypes.size()];
        Class<?> owner = Class.forName(spec.ownerClass, false, loader);
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = Class.forName(spec.parameterTypes.get(i), false, loader);
        }
        return owner.getDeclaredMethod(spec.methodName, parameters);
    }

    private boolean gateEnabled() {
        return gate != null && gate.isEffectiveEnabled(PurifierConfig.Feature.AUTO_COMMENT);
    }

    /**
     * Controller callback policy: observe only. The original always runs;
     * entered/executed counts are the proof that nothing is ever skipped. The
     * first-entry line is logged AFTER proceed() returns, so its executed
     * count is direct evidence that the original ran to completion.
     */
    Object onControllerEnter(Chain chain, String descriptor) throws Throwable {
        int entered = controllerEnteredCount.incrementAndGet();
        if (exposureLedger != null) {
            exposureLedger.recordEntered(PurifierConfig.Feature.AUTO_COMMENT);
        }
        Object result = chain.proceed();
        int executed = originalExecutedCount.incrementAndGet();
        if (controllerEnteredLogged.compareAndSet(false, true)) {
            log.info("d6_controller_entered=true count=" + entered
                    + " original_executed_count=" + executed
                    + " descriptor=" + descriptor);
        }
        return result;
    }

    /**
     * Terminal prompt UI action. Enabled: the host prompt action is not
     * executed and the Unit result is answered directly. Disabled: the
     * original runs untouched (fail open).
     */
    Object onPromptAction(Chain chain, boolean enabled, String descriptor) throws Throwable {
        int observed = promptObservedCount.incrementAndGet();
        if (exposureLedger != null) {
            exposureLedger.recordSampleSeen(PurifierConfig.Feature.AUTO_COMMENT);
        }
        if (!enabled) {
            return chain.proceed();
        }
        int suppressed = promptSuppressedCount.incrementAndGet();
        if (exposureLedger != null) {
            exposureLedger.recordModified(PurifierConfig.Feature.AUTO_COMMENT);
        }
        if (promptSuppressedLogged.compareAndSet(false, true)) {
            log.info("d6_prompt_ui_suppressed=true count=" + suppressed
                    + " descriptor=" + descriptor);
        }
        if (observed % SUMMARY_EVERY == 0) {
            log.info(summaryLine());
        }
        return promptSuppressedResult();
    }

    Object promptSuppressedResult() {
        // install() resolves Unit.INSTANCE against the host class loader. The
        // fallback keeps the answer non-null even if that resolution failed.
        Object unit = unitInstance;
        return unit != null ? unit : resolveUnitInstance(getClass().getClassLoader());
    }

    static Object resolveUnitInstance(ClassLoader loader) {
        try {
            return Class.forName("kotlin.Unit", false, loader).getField("INSTANCE").get(null);
        } catch (Throwable failure) {
            return null;
        }
    }

    String summaryLine() {
        return "d6_prompt_summary"
                + " d6_controller_entered_count=" + controllerEnteredCount.get()
                + " d6_original_executed_count=" + originalExecutedCount.get()
                + " d6_prompt_ui_observed_count=" + promptObservedCount.get()
                + " d6_prompt_ui_suppressed_count=" + promptSuppressedCount.get();
    }

    // Test-only visibility into the both-or-nothing install invariant.
    synchronized boolean anyHookInstalled() {
        return controllerHandle != null || promptHandle != null;
    }
}
