package io.github.yylsping.coolapkpurifier;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;

/** Projects the main bar into the host's own liquid-glass renderer. No shader/layout replacement. */
final class BottomNavigationHooks {
    static final String RENDER_HOOK_ID = "coolapk-bottom-navigation-render";
    static final String MODEL_HOOK_ID = "coolapk-bottom-navigation-model";
    static final String DATA_HOOK_ID = "coolapk-bottom-navigation-data";
    static final String SETTINGS_HOOK_ID = "coolapk-bottom-navigation-settings-shortcut";
    static final String SELECTION_HOOK_ID = "coolapk-bottom-navigation-selection";
    static final String LAYOUT_HOOK_ID = "coolapk-bottom-navigation-layout";
    static final String CONTENT_HOOK_ID = "coolapk-bottom-navigation-content";
    static final String PANEL_HOOK_ID = "coolapk-bottom-navigation-panel";
    private final List<HookHandle> handles = new ArrayList<>();
    private final BottomNavigationOwnedLists ownedLists = new BottomNavigationOwnedLists();
    private final ThreadLocal<Boolean> hiddenSelection = new ThreadLocal<>();
    private final AtomicBoolean observed = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean installed;
    private WeakReference<Object> previousCallback = new WeakReference<>(null);
    private WeakReference<Object> previousProxy = new WeakReference<>(null);

    InstallResult install(XposedModule module, ModuleLog log, HookLedger ledger,
                          BottomNavigationTargetSpec spec, ClassLoader loader,
                          int hiddenMask, Consumer<Activity> openSettings) {
        if (hiddenMask == 0) return InstallResult.DISABLED;
        if (spec == null) return InstallResult.TARGET_MISSING;
        if (installed) return InstallResult.ALREADY_INSTALLED;
        boolean verified = false;
        try {
            BottomNavigationSelection selection = new BottomNavigationSelection(hiddenMask);
            Runtime target = verify(spec, loader);
            verified = true;
            handles.add(module.hook(target.modelConstructor).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(MODEL_HOOK_ID).intercept(chain -> {
                        Object result = chain.proceed();
                        if (installed) {
                            Object model = chain.getThisObject();
                            rememberData(model, target);
                            if (selection.hidden(BottomNavigationSelection.Item.HOME)) {
                                Object flow = target.currentItemGetter.invoke(model);
                                target.setValue.invoke(flow, selection.firstPage());
                                log.info("bottomNavigation initialPage=" + selection.firstPage());
                            }
                        }
                        return result;
                    }));
            handles.add(module.hook(target.refreshData).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(DATA_HOOK_ID).intercept(chain -> {
                        Object result = chain.proceed();
                        if (installed) rememberData(chain.getThisObject(), target);
                        return result;
                    }));
            handles.add(module.hook(target.renderer).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(RENDER_HOOK_ID).intercept(chain -> onRender(chain, selection, target, log)));
            handles.add(module.hook(target.glassRenderer).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(SELECTION_HOOK_ID).intercept(chain -> {
                        if (target.legacy) return onLegacyGlass(chain);
                        if (!installed || (hiddenSelection.get() == null && !(chain.getArg(2) instanceof MainSlots))) return chain.proceed();
                        Object[] args = new Object[16];
                        for (int i = 0; i < args.length; i++) args[i] = chain.getArg(i);
                        boolean hidden = args[2] instanceof MainSlots ? ((MainSlots) args[2]).selectionHidden
                                : Boolean.TRUE.equals(hiddenSelection.get());
                        if (!(args[2] instanceof MainSlots)) args[2] = new MainSlots((List<?>) args[2], hidden);
                        args[0] = new MainPairs((List<?>) args[0], hidden, target.contentVisible.getLong(args[8]));
                        if (hidden) {
                            Object style = args[6];
                            Object optics = target.styleOptics.get(style);
                            Object[] coefficients = new Object[20];
                            for (int i = 0; i < coefficients.length; i++) coefficients[i] = target.opticalFields[i].getFloat(optics);
                            // Only the selection lens is neutralized; container optics stay native.
                            for (int index : new int[]{6, 7, 11, 12, 13, 14, 15, 17, 18, 19}) coefficients[index] = 0f;
                            Object colors = target.neutralSelectionColors(args[7]);
                            args[6] = target.styleConstructor.newInstance(target.opticalConstructor.newInstance(coefficients), colors);
                            args[7] = colors;
                            args[1] = -1;
                        }
                        // The same changed() calls must consume slots on every composition,
                        // including transitions between visible and hidden active pages.
                        args[14] = ((Integer) args[14]) & ~(0xE | 0x70 | 0x380 | 0x380000 | 0x1C00000);
                        return chain.proceed(args);
                    }));
            handles.add(module.hook(target.slotsRenderer).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(CONTENT_HOOK_ID).intercept(chain -> {
                        if (!installed || !(chain.getArg(1) instanceof MainPairs)) return chain.proceed();
                        MainPairs pairs = (MainPairs) chain.getArg(1);
                        Object[] args = new Object[18];
                        for (int i = 0; i < args.length; i++) args[i] = chain.getArg(i);
                        if (target.legacy) {
                            if (pairs.selectionHidden) {
                                args[9] = -1;
                                args[10] = pairs.nativeConfig;
                                args[11] = target.unspecifiedColor;
                            }
                            args[16] = ((Integer) args[16]) & ~(0x70 | 0x70000000);
                            args[17] = ((Integer) args[17]) & ~(0xE | 0x70);
                            return chain.proceed(args);
                        }
                        if (pairs.selectionHidden) args[10] = pairs.visibleColor;
                        // Publish uses a separate accent-color argument and stays green.
                        args[16] = ((Integer) args[16]) & ~0x70;
                        args[17] = ((Integer) args[17]) & ~0xE;
                        return chain.proceed(args);
                    }));
            if (target.legacy) {
                handles.add(module.hook(target.panelRenderer).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId(PANEL_HOOK_ID).intercept(chain -> {
                            if (!installed || !(chain.getArg(2) instanceof MainSlots)) return chain.proceed();
                            MainSlots slots = (MainSlots) chain.getArg(2);
                            Object[] args = new Object[13];
                            for (int i = 0; i < args.length; i++) args[i] = chain.getArg(i);
                            if ((((Integer) args[12]) & 64) != 0 || args[6] == null) {
                                args[6] = target.styleColors.get(args[5]);
                            }
                            args[12] = ((Integer) args[12]) & ~64;
                            if (slots.selectionHidden) {
                                Object style = args[5];
                                Object optics = target.styleOptics.get(style);
                                Object[] coefficients = new Object[10];
                                for (int i = 0; i < 10; i++) coefficients[i] = target.opticalFields[i].getFloat(optics);
                                // Container blur/lens and press geometry remain native.
                                for (int i : new int[]{0, 5, 6, 7, 8, 9}) coefficients[i] = 0f;
                                Object colors = target.neutralSelectionColors(args[6]);
                                args[5] = target.styleConstructor.newInstance(
                                        target.opticalConstructor.newInstance(coefficients), colors);
                                args[6] = colors;
                            }
                            args[11] = ((Integer) args[11]) & ~(0x380 | 0x70000 | 0x380000);
                            return chain.proceed(args);
                        }));
            }
            handles.add(module.hook(target.layoutCalculator).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId(LAYOUT_HOOK_ID).intercept(chain -> {
                        Object nativeLayout = chain.proceed();
                        if (!installed || !(chain.getArg(5) instanceof MainSlots)) return nativeLayout;
                        int count = (Integer) chain.getArg(3);
                        Integer post = (Integer) chain.getArg(4);
                        if (post == null || count == 5 || post < 0 || post >= count) return nativeLayout;
                        try {
                            Object bar = chain.getArg(0);
                            float density = (Float) target.densityGetter.invoke(chain.getArg(2));
                            float[] original = new float[7];
                            for (int i = 0; i < original.length; i++) original[i] = target.layoutFields[i].getFloat(nativeLayout);
                            BottomNavigationGeometry geometry = new BottomNavigationGeometry(original[2], original[1],
                                    original[5], target.barFields[3].getFloat(bar) * density,
                                    target.barFields[4].getFloat(bar) * density, count, post);
                            return target.layoutConstructor.newInstance(original[0], original[1], original[2], original[3],
                                    geometry.tabWidth, original[5], original[6], geometry.lefts, geometry.widths, chain.getArg(5));
                        } catch (Throwable failure) {
                            if (failed.compareAndSet(false, true)) log.error("bottomNavigation layout repair skipped", failure);
                            return nativeLayout;
                        }
                    }));
            if (selection.hidden(BottomNavigationSelection.Item.MINE)) {
                handles.add(module.hook(target.headerConstructor).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId(SETTINGS_HOOK_ID).intercept(chain -> {
                            Object result = chain.proceed();
                            if (installed) {
                                View header = (View) chain.getThisObject();
                                Context context = header.getContext();
                                for (int i = 0; i < 10 && context instanceof ContextWrapper
                                        && !(context instanceof Activity); i++) {
                                    context = ((ContextWrapper) context).getBaseContext();
                                }
                                if (context instanceof Activity && context.getClass().getName().equals(
                                        "com.coolapk.market.view.main.MainActivity")) {
                                    Activity activity = (Activity) context;
                                    View avatar = (View) target.avatarContainer.get(header);
                                    avatar.setOnLongClickListener(view -> { openSettings.accept(activity); return true; });
                                    log.info("bottomNavigation settingsShortcut=avatarLongPress");
                                }
                            }
                            return result;
                        }));
            }
            installed = true;
            ledger.record(HookLedger.Layer.BUSINESS, "bottomNavigation", MODEL_HOOK_ID,
                    target.modelConstructor.toGenericString());
            ledger.record(HookLedger.Layer.BUSINESS, "bottomNavigation", DATA_HOOK_ID,
                    target.refreshData.toGenericString());
            ledger.record(HookLedger.Layer.BUSINESS, "bottomNavigation", RENDER_HOOK_ID,
                    target.renderer.toGenericString());
            ledger.record(HookLedger.Layer.BUSINESS, "bottomNavigation", SELECTION_HOOK_ID,
                    target.glassRenderer.toGenericString());
            ledger.record(HookLedger.Layer.BUSINESS, "bottomNavigation", LAYOUT_HOOK_ID,
                    target.layoutCalculator.toGenericString());
            ledger.record(HookLedger.Layer.BUSINESS, "bottomNavigation", CONTENT_HOOK_ID,
                    target.slotsRenderer.toGenericString());
            if (target.legacy) ledger.record(HookLedger.Layer.BUSINESS, "bottomNavigation", PANEL_HOOK_ID,
                    target.panelRenderer.toGenericString());
            if (selection.hidden(BottomNavigationSelection.Item.MINE)) {
                ledger.record(HookLedger.Layer.BUSINESS, "bottomNavigation", SETTINGS_HOOK_ID,
                        target.headerConstructor.toGenericString());
            }
            log.info("bottomNavigation install=INSTALLED mask=" + hiddenMask
                    + " visibleSourceIndices=" + selection.sources + " logicalPagesPreserved=true");
            return InstallResult.INSTALLED;
        } catch (Throwable failure) {
            installed = false;
            String[] ids = spec.legacy()
                    ? new String[]{MODEL_HOOK_ID, DATA_HOOK_ID, RENDER_HOOK_ID, SELECTION_HOOK_ID,
                            CONTENT_HOOK_ID, PANEL_HOOK_ID, LAYOUT_HOOK_ID, SETTINGS_HOOK_ID}
                    : new String[]{MODEL_HOOK_ID, DATA_HOOK_ID, RENDER_HOOK_ID, SELECTION_HOOK_ID,
                            CONTENT_HOOK_ID, LAYOUT_HOOK_ID, SETTINGS_HOOK_ID};
            for (int i = 0; i < handles.size(); i++) {
                ledger.record(HookLedger.Layer.BUSINESS, "bottomNavigation", ids[i], "partial install");
                try {
                    handles.get(i).unhook();
                    ledger.retire(ids[i], "installRollback");
                } catch (Throwable cleanup) {
                    log.error("bottomNavigation partial hook cleanup failed; interceptor inert", cleanup);
                }
            }
            log.error("bottomNavigation install failed; native bar preserved", failure);
            return verified ? InstallResult.INSTALL_FAILED : InstallResult.CONTRACT_MISMATCH;
        }
    }

    private Object onRender(Chain chain, BottomNavigationSelection selection,
                            Runtime target, ModuleLog log) throws Throwable {
        Object value = chain.getArg(0);
        if (!installed || !(value instanceof List<?>)) return chain.proceed();
        if (value instanceof ProjectedItems) {
            return proceedProjected(chain, null, ((ProjectedItems) value).selectionHidden);
        }
        List<?> source = (List<?>) value;
        Object[] args = null;
        boolean selectionHidden = false;
        try {
            boolean eligible = source.size() == 5 && ownedLists.contains(source);
            for (Object item : source) eligible &= target.itemClass.isInstance(item);
            Object config = chain.getArg(1);
            eligible &= target.configClass.isInstance(config);
            if (eligible) {
                int selected = (Integer) chain.getArg(2);
                Object callback = chain.getArg(3);
                int visual = selection.visualIndex(selected);
                selectionHidden = visual < 0;
                args = new Object[target.renderer.getParameterCount()];
                for (int i = 0; i < args.length; i++) args[i] = chain.getArg(i);
                args[0] = new ProjectedItems(source, selection, selectionHidden);
                args[1] = target.projectConfig(config, selection);
                args[2] = visual;
                args[3] = callbackProxy(callback, selection, target);
                Object post = args[5];
                int postVisual = selection.visualIndex(2);
                args[5] = post == null || postVisual < 0 ? null
                        : target.postConstructor.newInstance(postVisual, target.postCallback.get(post));
                // Let Compose compare the projected arguments, including its saved restart inputs.
                int affected = 0xE | 0x70 | 0x380 | 0x1C00 | 0x70000;
                int changedIndex = target.legacy ? 7 : 12;
                args[changedIndex] = ((Integer) args[changedIndex]) & ~affected;
            }
        } catch (Throwable failure) {
            if (failed.compareAndSet(false, true)) log.error("bottomNavigation projection skipped", failure);
            args = null;
        }
        if (args == null) return chain.proceed();
        if (observed.compareAndSet(false, true)) {
            log.info("bottomNavigation rendererEntered=true visibleSourceIndices=" + selection.sources
                    + " logicalDataSize=" + source.size() + " nativeLiquidGlass=true");
        }
        return proceedProjected(chain, args, selectionHidden);
    }

    private Object proceedProjected(Chain chain, Object[] args, boolean hidden) throws Throwable {
        Boolean previous = hiddenSelection.get();
        hiddenSelection.set(hidden);
        try { return args == null ? chain.proceed() : chain.proceed(args); }
        finally {
            if (previous == null) hiddenSelection.remove(); else hiddenSelection.set(previous);
        }
    }

    private Object onLegacyGlass(Chain chain) throws Throwable {
        if (!installed || (hiddenSelection.get() == null && !(chain.getArg(2) instanceof MainSlots))) {
            return chain.proceed();
        }
        Object[] args = new Object[12];
        for (int i = 0; i < args.length; i++) args[i] = chain.getArg(i);
        boolean hidden = args[2] instanceof MainSlots ? ((MainSlots) args[2]).selectionHidden
                : Boolean.TRUE.equals(hiddenSelection.get());
        if (!(args[2] instanceof MainSlots)) args[2] = new MainSlots((List<?>) args[2], hidden);
        args[0] = new MainPairs((List<?>) args[0], hidden, args[6]);
        if (hidden) args[1] = -1;
        args[11] = ((Integer) args[11]) & ~(0xE | 0x70 | 0x380);
        return chain.proceed(args);
    }

    private void rememberData(Object model, Runtime target) throws ReflectiveOperationException {
        Object flow = target.dataListGetter.invoke(model);
        Object value = target.getValue.invoke(flow);
        if (value instanceof List<?>) ownedLists.remember((List<?>) value);
    }

    private Object callbackProxy(Object callback, BottomNavigationSelection selection, Runtime target) {
        Object cached = previousProxy.get();
        if (callback == previousCallback.get() && cached != null) return cached;
        Object proxy = Proxy.newProxyInstance(target.function.getClassLoader(),
                new Class<?>[]{target.function}, (self, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("equals".equals(method.getName())) return self == args[0];
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(self);
                        return "CoolapkPurifierBottomNavigation";
                    }
                    int visual = (Integer) args[0];
                    return target.invoke.invoke(callback, selection.sourceIndex(visual));
                });
        previousCallback = new WeakReference<>(callback);
        previousProxy = new WeakReference<>(proxy);
        return proxy;
    }

    static final class ProjectedItems extends AbstractList<Object> {
        final List<?> source;
        final BottomNavigationSelection selection;
        final boolean selectionHidden;
        ProjectedItems(List<?> source, BottomNavigationSelection selection) {
            this(source, selection, false);
        }
        ProjectedItems(List<?> source, BottomNavigationSelection selection, boolean selectionHidden) {
            this.source = source;
            this.selection = selection;
            this.selectionHidden = selectionHidden;
        }
        @Override public Object get(int index) { return source.get(selection.sourceIndex(index)); }
        @Override public int size() { return selection.sources.size(); }
    }

    static final class MainSlots extends AbstractList<Object> {
        final List<?> source;
        final boolean selectionHidden;
        MainSlots(List<?> source, boolean selectionHidden) { this.source = source; this.selectionHidden = selectionHidden; }
        @Override public Object get(int index) { return source.get(index); }
        @Override public int size() { return source.size(); }
    }

    static final class MainPairs extends AbstractList<Object> {
        final List<?> source;
        final boolean selectionHidden;
        final long visibleColor;
        final Object nativeConfig;
        MainPairs(List<?> source, boolean selectionHidden, long visibleColor) {
            this.source = source; this.selectionHidden = selectionHidden; this.visibleColor = visibleColor;
            this.nativeConfig = null;
        }
        MainPairs(List<?> source, boolean selectionHidden, Object nativeConfig) {
            this.source = source; this.selectionHidden = selectionHidden; this.visibleColor = 0;
            this.nativeConfig = nativeConfig;
        }
        @Override public Object get(int index) { return source.get(index); }
        @Override public int size() { return source.size(); }
    }

    static Runtime verify(BottomNavigationTargetSpec spec, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(spec.rendererOwner, false, loader);
        Class<?>[] params = new Class<?>[spec.rendererParams.size()];
        for (int i = 0; i < params.length; i++) params[i] = "int".equals(spec.rendererParams.get(i))
                ? int.class : Class.forName(spec.rendererParams.get(i), false, loader);
        Method renderer = owner.getDeclaredMethod(spec.rendererMethod, params);
        int changed = spec.legacy() ? 7 : 12;
        if ((params.length != 15 && params.length != 9) || renderer.getReturnType() != void.class
                || !Modifier.isStatic(renderer.getModifiers()) || params[0] != List.class
                || params[2] != int.class || params[changed] != int.class
                || params[changed + 1] != int.class
                || (!spec.legacy() && params[14] != int.class)) throw new NoSuchMethodException("renderer shape");
        Class<?> helper = Class.forName(spec.helperClass, false, loader);
        Field instance = helper.getDeclaredField(spec.instanceField);
        instance.setAccessible(true);
        Method listGetter = helper.getDeclaredMethod(spec.listGetter);
        listGetter.setAccessible(true);
        Object list = listGetter.invoke(instance.get(null));
        if (!(list instanceof List<?>)) throw new NoSuchFieldException("page order");
        List<String> names = new ArrayList<>();
        for (Object item : (List<?>) list) {
            if (!(item instanceof Enum<?>)) throw new NoSuchFieldException("page enum");
            names.add(((Enum<?>) item).name());
        }
        if (!names.equals(Arrays.asList("HOME", "MOBILE_BAR", "POST_BUTTON", "DISCOVERY", "CENTER"))) {
            throw new NoSuchFieldException("unexpected page order");
        }
        return new Runtime(spec, loader, renderer);
    }

    static final class Runtime {
        final Method renderer, glassRenderer, slotsRenderer, layoutCalculator, densityGetter,
                currentItemGetter, dataListGetter, refreshData, getValue, setValue, invoke, panelRenderer;
        final Constructor<?> modelConstructor, configConstructor, postConstructor, headerConstructor,
                layoutConstructor, opticalConstructor, styleConstructor, colorsConstructor,
                overrideConstructor, capabilityColorsConstructor, colorConstructor;
        final Class<?> configClass, itemClass, function;
        final Field accent, visible, refreshIcon, refreshIndex, postIndex, postCallback, avatarContainer;
        final Field[] barFields;
        final Field contentVisible;
        final Field[] layoutFields = new Field[7];
        final Field[] opticalFields;
        final Field styleOptics, styleColors, navigationHeight, colorContainer, colorOverrides,
                overrideBlur, overrideFallback, capabilityContainer;
        final boolean legacy;
        final long unspecifiedColor;
        Runtime(BottomNavigationTargetSpec spec, ClassLoader loader, Method renderer) throws ReflectiveOperationException {
            this.renderer = renderer;
            legacy = spec.legacy();
            barFields = new Field[legacy ? 7 : 8];
            opticalFields = new Field[legacy ? 10 : 20];
            Class<?> model = Class.forName(spec.viewModelClass, false, loader);
            configClass = Class.forName(spec.configClass, false, loader);
            itemClass = Class.forName(spec.itemClass, false, loader);
            Class<?> postClass = Class.forName(spec.postSlotClass, false, loader);
            function = Class.forName("kotlin.jvm.functions.Function1", false, loader);
            invoke = function.getMethod("invoke", Object.class);
            Class<?> function0 = Class.forName("kotlin.jvm.functions.Function0", false, loader);
            Class<?> color = Class.forName("androidx.compose.ui.graphics.Color", false, loader);
            modelConstructor = model.getDeclaredConstructor();
            currentItemGetter = model.getDeclaredMethod(spec.currentItemGetter);
            dataListGetter = model.getDeclaredMethod(spec.dataListGetter);
            refreshData = model.getDeclaredMethod(spec.refreshDataMethod);
            if (refreshData.getReturnType() != void.class || Modifier.isStatic(refreshData.getModifiers())) {
                throw new NoSuchMethodException("data producer shape");
            }
            Class<?> flow = currentItemGetter.getReturnType();
            if (dataListGetter.getReturnType() != flow) throw new NoSuchMethodException("flow type mismatch");
            getValue = flow.getMethod("getValue");
            setValue = flow.getMethod("setValue", Object.class);
            accent = field(configClass, "\u037f", color);
            visible = field(configClass, "\u0528", color);
            refreshIcon = field(configClass, "\u0529", String.class);
            refreshIndex = field(configClass, "\u052a", int.class);
            postIndex = field(configClass, "\u052b", int.class);
            field(postClass, "\u037f", int.class);
            postCallback = field(postClass, "\u0528", function0);
            navigationHeight = legacy ? field(configClass, "\u052c", int.class) : null;
            configConstructor = legacy
                    ? configClass.getDeclaredConstructor(color, color, String.class, int.class, int.class, int.class)
                    : configClass.getDeclaredConstructor(color, color, String.class, int.class, int.class);
            configConstructor.setAccessible(true);
            postConstructor = postClass.getDeclaredConstructor(int.class, function0);
            Class<?> header = Class.forName(spec.headerClass, false, loader);
            if (!View.class.isAssignableFrom(header)) throw new NoSuchMethodException("header View");
            headerConstructor = header.getDeclaredConstructor(Context.class, AttributeSet.class);
            avatarContainer = field(header, spec.avatarContainerField, FrameLayout.class);
            Class<?>[] top = renderer.getParameterTypes();
            Class<?> calculator = Class.forName(spec.layoutOwner, false, loader);
            Class<?>[] panelParams = new Class<?>[spec.panelRendererParams.size()];
            for (int i = 0; i < panelParams.length; i++) panelParams[i] = "int".equals(spec.panelRendererParams.get(i))
                    ? int.class : Class.forName(spec.panelRendererParams.get(i), false, loader);
            if (legacy && panelParams.length != 13) throw new NoSuchMethodException("legacy panel shape");
            panelRenderer = legacy ? calculator.getDeclaredMethod(spec.panelRendererMethod, panelParams) : null;
            if (legacy && (!Modifier.isStatic(panelRenderer.getModifiers()) || panelRenderer.getReturnType() != void.class
                    || panelParams[0] != function0 || panelParams[1] != function || panelParams[2] != List.class
                    || panelParams[3] != int.class || panelParams[4] != Integer.class
                    || panelParams[8] != top[4] || panelParams[10] != top[6]
                    || panelParams[11] != int.class || panelParams[12] != int.class)) {
                throw new NoSuchMethodException("legacy panel contract");
            }
            Class<?> barClass = legacy ? panelParams[7] : top[8];
            Class<?> contentColors = legacy ? null : top[10];
            String[] fields = {"\u037f", "\u0528", "\u0529", "\u052a", "\u052b", "\u052c", "\u052d", "\u052e"};
            for (int i = 0; i < barFields.length; i++) barFields[i] = field(barClass, fields[i], float.class);
            contentVisible = legacy ? null : field(contentColors, "\u037f", long.class);
            if (!legacy) field(contentColors, "\u0528", long.class);
            glassRenderer = legacy ? renderer.getDeclaringClass().getDeclaredMethod(spec.glassRendererMethod,
                    List.class, int.class, List.class, int.class, Integer.class, postClass,
                    configClass, barClass, top[4], function, top[6], int.class)
                    : renderer.getDeclaringClass().getDeclaredMethod(spec.glassRendererMethod,
                    List.class, int.class, List.class, int.class, Integer.class, postClass,
                    top[6], top[7], contentColors, barClass, top[4], top[9], function, top[11], int.class, int.class);
            if (!Modifier.isStatic(glassRenderer.getModifiers()) || glassRenderer.getReturnType() != void.class) {
                throw new NoSuchMethodException("glass renderer shape");
            }
            Class<?> density = Class.forName("androidx.compose.ui.unit.Density", false, loader);
            densityGetter = density.getMethod("getDensity");
            layoutCalculator = calculator.getDeclaredMethod(spec.layoutMethod, barClass, float.class,
                    density, int.class, Integer.class, List.class);
            if (!Modifier.isStatic(layoutCalculator.getModifiers())) throw new NoSuchMethodException("layout calculator");
            Class<?> layout = layoutCalculator.getReturnType();
            for (int i = 0; i < layoutFields.length; i++) layoutFields[i] = field(layout, fields[i], float.class);
            layoutConstructor = layout.getDeclaredConstructor(float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, List.class, List.class, List.class);
            layoutConstructor.setAccessible(true);
            Class<?> box = Class.forName("androidx.compose.foundation.layout.BoxScope", false, loader);
            slotsRenderer = legacy ? renderer.getDeclaringClass().getDeclaredMethod(spec.slotsMethod,
                    box, List.class, layout, int.class, Integer.class, postClass, float.class, boolean.class,
                    int.class, int.class, configClass, long.class, function0, boolean.class, function, top[6], int.class, int.class)
                    : renderer.getDeclaringClass().getDeclaredMethod(spec.slotsMethod,
                    box, List.class,
                    layout, int.class, Integer.class, postClass, float.class, boolean.class, boolean.class,
                    int.class, long.class, long.class, function0, boolean.class, function, top[11], int.class, int.class);
            if (!Modifier.isStatic(slotsRenderer.getModifiers()) || slotsRenderer.getReturnType() != void.class) {
                throw new NoSuchMethodException("slot renderer shape");
            }
            Class<?> styleClass = legacy ? panelParams[5] : top[6];
            Class<?> colorsClass = legacy ? panelParams[6] : top[7];
            styleColors = field(styleClass, "\u0528", colorsClass);
            styleOptics = styleClass.getDeclaredField("\u037f");
            styleOptics.setAccessible(true);
            Class<?> opticsClass = styleOptics.getType();
            String[] opticalNames = {"\u037f", "\u0528", "\u0529", "\u052a", "\u052b", "\u052c",
                    "\u052d", "\u052e", "\u052f", "\u0560", "\u0588", "\u058f", "\u05ef", "\u0620",
                    "\u0780", "\u0781", "\u0782", "\u0783", "\u0784", "\u0785"};
            for (int i = 0; i < opticalFields.length; i++) opticalFields[i] = field(opticsClass, opticalNames[i], float.class);
            Class<?>[] floats = new Class<?>[opticalFields.length];
            Arrays.fill(floats, float.class);
            opticalConstructor = opticsClass.getDeclaredConstructor(floats);
            opticalConstructor.setAccessible(true);
            styleConstructor = styleClass.getDeclaredConstructor(opticsClass, colorsClass);
            styleConstructor.setAccessible(true);
            colorContainer = field(colorsClass, "\u037f", long.class);
            field(colorsClass, "\u0528", long.class);
            if (!legacy) field(colorsClass, "\u052a", long.class);
            colorOverrides = colorsClass.getDeclaredField("\u0529");
            colorOverrides.setAccessible(true);
            Class<?> overrides = colorOverrides.getType();
            overrideBlur = overrides.getDeclaredField("\u037f");
            overrideBlur.setAccessible(true);
            Class<?> capabilityColors = overrideBlur.getType();
            overrideFallback = field(overrides, "\u0528", capabilityColors);
            capabilityContainer = field(capabilityColors, "\u037f", color);
            field(capabilityColors, "\u0528", color);
            if (!legacy) field(capabilityColors, "\u0529", color);
            overrideConstructor = overrides.getDeclaredConstructor(capabilityColors, capabilityColors);
            capabilityColorsConstructor = legacy ? capabilityColors.getDeclaredConstructor(color, color)
                    : capabilityColors.getDeclaredConstructor(color, color, color);
            capabilityColorsConstructor.setAccessible(true);
            colorConstructor = color.getDeclaredConstructor(long.class);
            colorConstructor.setAccessible(true);
            colorsConstructor = legacy ? colorsClass.getDeclaredConstructor(long.class, long.class, overrides)
                    : colorsClass.getDeclaredConstructor(long.class, long.class, overrides, long.class);
            colorsConstructor.setAccessible(true);
            if (legacy) {
                Object companion = color.getDeclaredField("Companion").get(null);
                unspecifiedColor = (Long) companion.getClass().getDeclaredMethod("getUnspecified-0d7_KjU").invoke(companion);
            } else unspecifiedColor = 0L;
            Class<?>[] parameters = renderer.getParameterTypes();
            if (parameters[1] != configClass || parameters[3] != function || parameters[5] != postClass) {
                throw new NoSuchMethodException("renderer model mismatch");
            }
        }
        Object projectConfig(Object config, BottomNavigationSelection selection) throws ReflectiveOperationException {
            Object[] args = legacy ? new Object[6] : new Object[5];
            args[0] = accent.get(config);
            args[1] = visible.get(config);
            args[2] = refreshIcon.get(config);
            args[3] = selection.visualIndex(refreshIndex.getInt(config));
            args[4] = selection.visualIndex(postIndex.getInt(config));
            if (legacy) args[5] = navigationHeight.getInt(config);
            return configConstructor.newInstance(args);
        }
        Object neutralSelectionColors(Object colors) throws ReflectiveOperationException {
            Object overrides = colorOverrides.get(colors);
            Object transparent = colorConstructor.newInstance(0L);
            Object blur = neutralCapabilityColors(overrideBlur.get(overrides), transparent);
            Object fallback = neutralCapabilityColors(overrideFallback.get(overrides), transparent);
            Object neutral = overrideConstructor.newInstance(blur, fallback);
            return legacy ? colorsConstructor.newInstance(colorContainer.getLong(colors), 0L, neutral)
                    : colorsConstructor.newInstance(colorContainer.getLong(colors), 0L, neutral, 0L);
        }
        private Object neutralCapabilityColors(Object colors, Object transparent) throws ReflectiveOperationException {
            if (colors == null) return null;
            return legacy ? capabilityColorsConstructor.newInstance(capabilityContainer.get(colors), transparent)
                    : capabilityColorsConstructor.newInstance(capabilityContainer.get(colors), transparent, transparent);
        }
        private static Field field(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
            Field field = owner.getDeclaredField(name);
            if (field.getType() != type || Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(name);
            field.setAccessible(true);
            return field;
        }
    }
}
