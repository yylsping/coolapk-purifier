package io.github.yylsping.coolapkpurifier;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.ExceptionMode;

/** Native settings model insertion plus an Activity-owned, dismissible config page. */
final class SettingsHooks {
    static final String EXIT_MESSAGE = "请重启软件以动态适配更改选项";
    private final XposedModule module;
    private final HookLedger ledger;
    private final ModuleLog log;
    private final PurifierConfig config;
    private final int coolapkMajor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OwnedSettingsPages<Activity, Dialog> pages = new OwnedSettingsPages<>();
    private final Map<ClassLoader, HookHandle> entryHooks = new HashMap<>();
    private final PageInjectionRetry<Activity> injectionRetry;
    private volatile boolean lifecycleCallbacksInstalled;
    private int settingsIcon;

    SettingsHooks(XposedModule module, HookLedger ledger, ModuleLog log,
                  PurifierConfig config, int coolapkMajor) {
        this.module = module;
        this.ledger = ledger;
        this.log = log;
        this.config = config;
        this.coolapkMajor = coolapkMajor;
        injectionRetry = new PageInjectionRetry<>(
                (task, delay) -> mainHandler.postDelayed(task, delay),
                mainHandler::removeCallbacks, this::ensureNativeEntry,
                activity -> log.info("settings native entry unavailable; no overlay fallback"));
    }

    void install(Context context) {
        if (lifecycleCallbacksInstalled) return;
        try {
            Context candidate = context instanceof Application ? context
                    : context == null ? null : context.getApplicationContext();
            if (!(candidate instanceof Application)) return;
            settingsIcon = candidate.getResources().getIdentifier(
                    "ic_setting", "drawable", "com.coolapk.market");
            ((Application) candidate).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityPreCreated(Activity activity, Bundle state) {
                            ensureNativeEntry(activity);
                        }
                        @Override public void onActivityCreated(Activity activity, Bundle state) {
                            ensureNativeEntry(activity);
                        }
                        @Override public void onActivityStarted(Activity activity) { }
                        @Override public void onActivityResumed(Activity activity) {
                            if (!ensureNativeEntry(activity)) injectionRetry.start(activity);
                        }
                        @Override public void onActivityPaused(Activity activity) { injectionRetry.cancel(activity); }
                        @Override public void onActivityStopped(Activity activity) { }
                        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                        @Override public void onActivityDestroyed(Activity activity) {
                            injectionRetry.cancel(activity);
                            Dialog page = pages.removeOwner(activity);
                            if (page != null) page.dismiss();
                        }
                    });
            lifecycleCallbacksInstalled = true;
            log.info("settings lifecycle callbacks registered frameworkHooks=0");
        } catch (Throwable failure) {
            log.error("settings lifecycle callback registration failed", failure);
        }
    }

    boolean isLifecycleCallbacksInstalled() { return lifecycleCallbacksInstalled; }

    private synchronized boolean ensureNativeEntry(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return true;
        ClassLoader loader = activity.getClass().getClassLoader();
        if (entryHooks.containsKey(loader)) return true;
        try {
            Class<?> type = Class.forName(SettingsEntryInjector.FRAGMENT, false, loader);
            Method method = SettingsEntryInjector.findInitData(type);
            if (method == null) return false;
            HookHandle handle = module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("coolapk-settings-native-entry").intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            boolean inserted = SettingsEntryInjector.inject(chain.getThisObject(), settingsIcon, this::showConfigPage);
                            log.info("settings native entry inserted=" + inserted + " source=initData listOwned=true");
                        } catch (Throwable failure) {
                            log.error("settings model injection skipped; native settings preserved", failure);
                        }
                        return result;
                    });
            entryHooks.put(loader, handle);
            ledger.record(HookLedger.Layer.BUSINESS, "settings", "settings-native-initData-"
                    + Integer.toHexString(System.identityHashCode(loader)), method.toGenericString());
            log.info("settings business hook installed method=" + method + " frameworkHooks=0");
            return true;
        } catch (Throwable failure) {
            log.info("settings native entry not ready reason=" + failure.getClass().getSimpleName());
            return false;
        }
    }

    private void showConfigPage(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || pages.contains(activity)) return;
        // A real Dialog owns its back/cancel handling. The native settings
        // Activity, fragment, toolbar and LazyColumn are never replaced/hidden.
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        dialog.setOwnerActivity(activity);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);
        int background = resolveColor(activity, android.R.attr.colorBackground, 0xfff7f7fa);
        page.setBackgroundColor(background);
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(resolveColor(activity, android.R.attr.colorBackgroundFloating, Color.WHITE));
        TextView back = new TextView(activity);
        back.setText("‹");
        back.setContentDescription("返回酷安设置");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        back.setTextColor(resolveColor(activity, android.R.attr.textColorPrimary, Color.BLACK));
        back.setGravity(Gravity.CENTER);
        back.setFocusable(true);
        back.setOnClickListener(view -> dialog.dismiss());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)));
        TextView title = new TextView(activity);
        title.setText("酷安净化");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(resolveColor(activity, android.R.attr.textColorPrimary, Color.BLACK));
        toolbar.addView(title);
        page.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56)));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 28));
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            list.addView(createSwitchRow(activity, feature), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog.setContentView(page);
        if (!pages.open(activity, dialog)) return;
        dialog.setOnDismissListener(ignored -> {
            if (pages.close(activity, dialog)) {
                Toast.makeText(activity.getApplicationContext(), EXIT_MESSAGE, Toast.LENGTH_LONG).show();
                log.info("settings config dismissed returnToNative=true");
            }
        });
        try {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(background));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.setStatusBarColor(activity.getWindow().getStatusBarColor());
                window.setNavigationBarColor(activity.getWindow().getNavigationBarColor());
                // The host uses modern edge-to-edge bar controls; copying its
                // legacy flags loses contrast and can put our toolbar under it.
                boolean light = Color.red(background) * .299 + Color.green(background) * .587
                        + Color.blue(background) * .114 >= 128;
                window.getDecorView().setSystemUiVisibility(light
                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
                if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
                    int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    window.getInsetsController().setSystemBarsAppearance(light ? appearance : 0, appearance);
                }
            }
            log.info("settings config shown owner=" + activity.getClass().getName() + " navigation=dialog");
        } catch (Throwable failure) {
            pages.close(activity, dialog);
            dialog.dismiss();
            log.error("settings config page show failed", failure);
        }
    }

    @SuppressWarnings("deprecation")
    private View createSwitchRow(Context context, PurifierConfig.Feature feature) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 68));
        row.setPadding(dp(context, 18), dp(context, 8), dp(context, 12), dp(context, 8));

        GradientDrawable background = new GradientDrawable();
        background.setColor(resolveColor(context, android.R.attr.colorBackgroundFloating,
                Color.WHITE));
        background.setCornerRadius(dp(context, 8));
        row.setBackground(background);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(feature.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(resolveColor(context, android.R.attr.textColorPrimary, Color.BLACK));
        labels.addView(title);
        boolean supported = !feature.requiresCoolapk15 || coolapkMajor >= 15;
        if (!supported) {
            TextView summary = new TextView(context);
            summary.setText("仅支持酷安 15.x 及以上");
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            summary.setTextColor(resolveColor(context,
                    android.R.attr.textColorSecondary, 0xff888888));
            labels.addView(summary);
        }
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(context);
        toggle.setChecked(config.isEnabled(feature));
        toggle.setEnabled(supported);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!config.setEnabled(feature, checked)) {
                Toast.makeText(context.getApplicationContext(),
                        "配置保存失败", Toast.LENGTH_SHORT).show();
                button.setChecked(config.isEnabled(feature));
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setEnabled(supported);
        row.setAlpha(supported ? 1f : 0.45f);
        row.setOnClickListener(view -> {
            if (supported) {
                toggle.setChecked(!toggle.isChecked());
            }
        });
        return row;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int resolveColor(Context context, int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return context.getColor(value.resourceId);
                } catch (Throwable ignored) {
                }
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        }
        return fallback;
    }

}
