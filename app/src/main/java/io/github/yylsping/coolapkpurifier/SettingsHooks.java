package io.github.yylsping.coolapkpurifier;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    static final String EXIT_MESSAGE = "设置已保存，请强制停止并重新打开酷安以应用更改";
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
    private volatile boolean bottomNavigationSupported;
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

    void setBottomNavigationSupported(boolean supported) { bottomNavigationSupported = supported; }

    private volatile boolean hostUpgradeSupported;

    void setHostUpgradeSupported(boolean supported) { hostUpgradeSupported = supported; }

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

    void showConfigPage(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed() || pages.contains(activity)) return;
        // A real Dialog owns its back/cancel handling. The native settings
        // Activity, fragment, toolbar and LazyColumn are never replaced/hidden.
        LinearLayout page = new LinearLayout(activity);
        int background = resolveColor(activity, android.R.attr.colorBackground, 0xfff7f7fa);
        Dialog dialog = new SettingsPageDialog(activity, page, background);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(background);
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(resolveColor(activity, android.R.attr.colorBackgroundFloating, Color.WHITE));
        TextView back = new TextView(activity);
        back.setText("‹");
        back.setContentDescription("返回酷安");
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
        TextView explanation = new TextView(activity);
        explanation.setText("更改后请强制停止并重新打开酷安");
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        explanation.setTextColor(resolveColor(activity, android.R.attr.textColorSecondary, 0xff777777));
        explanation.setPadding(dp(activity, 4), 0, dp(activity, 4), dp(activity, 14));
        list.addView(explanation);
        long openedRevision = config.revision();
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(activity, 8);
            list.addView(createSwitchRow(activity, feature), params);
        }
        LinearLayout.LayoutParams navigationParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navigationParams.bottomMargin = dp(activity, 8);
        list.addView(createNavigationGroup(activity), navigationParams);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dialog.setContentView(page);
        if (!pages.open(activity, dialog)) return;
        dialog.setOnDismissListener(ignored -> {
            if (pages.close(activity, dialog)) {
                if (config.revision() != openedRevision) {
                    Toast.makeText(activity.getApplicationContext(), EXIT_MESSAGE, Toast.LENGTH_LONG).show();
                }
                log.info("settings config dismissed returnToNative=true");
            }
        });
        try {
            dialog.show();
            log.info("settings config shown owner=" + activity.getClass().getName() + " navigation=dialog");
        } catch (Throwable failure) {
            pages.close(activity, dialog);
            dialog.dismiss();
            log.error("settings config page show failed", failure);
        }
    }

    private View createNavigationGroup(Activity context) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(resolveColor(context, android.R.attr.colorBackgroundFloating, Color.WHITE));
        background.setCornerRadius(dp(context, 8));
        group.setBackground(background);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(context, 68));
        header.setPadding(dp(context, 18), dp(context, 8), dp(context, 12), dp(context, 8));
        header.setFocusable(true);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("精简底部导航");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(resolveColor(context, android.R.attr.textColorPrimary, Color.BLACK));
        labels.addView(title);
        TextView summary = new TextView(context);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        summary.setTextColor(resolveColor(context, android.R.attr.textColorSecondary, 0xff888888));
        summary.setPadding(0, dp(context, 3), 0, 0);
        labels.addView(summary);
        header.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView arrow = new TextView(context);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        arrow.setTextColor(resolveColor(context, android.R.attr.textColorSecondary, 0xff888888));
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(arrow, new LinearLayout.LayoutParams(dp(context, 24), dp(context, 32)));
        group.addView(header);

        LinearLayout children = new LinearLayout(context);
        children.setOrientation(LinearLayout.VERTICAL);
        children.setVisibility(View.GONE);
        View divider = new View(context);
        divider.setBackgroundColor(resolveColor(context, android.R.attr.textColorSecondary, 0xff888888));
        divider.setAlpha(0.15f);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
        dividerParams.setMargins(dp(context, 18), 0, dp(context, 12), dp(context, 5));
        children.addView(divider, dividerParams);
        Runnable updateSummary = () -> {
            summary.setText(bottomNavigationSupported
                    ? navigationSummary() : "当前酷安版本尚未适配底栏精简");
            header.setContentDescription("精简底部导航，" + summary.getText() + "，"
                    + (children.getVisibility() == View.VISIBLE ? "已展开" : "已收起"));
        };
        for (BottomNavigationSelection.Item item : BottomNavigationSelection.Item.values()) {
            children.addView(createNavigationSwitchRow(context, item, updateSummary));
        }
        TextView note = new TextView(context);
        note.setText("至少保留一个页面入口，发布不计入页面。\n"
                + "隐藏首页后从首个保留页面启动；隐藏“我的”后可长按顶部头像进入酷安净化。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(resolveColor(context, android.R.attr.textColorSecondary, 0xff888888));
        note.setPadding(dp(context, 18), dp(context, 12), dp(context, 12), dp(context, 16));
        children.addView(note);
        group.addView(children);
        header.setOnClickListener(view -> {
            boolean expanded = children.getVisibility() != View.VISIBLE;
            children.setVisibility(expanded ? View.VISIBLE : View.GONE);
            arrow.setRotation(expanded ? 90f : 0f);
            updateSummary.run();
        });
        updateSummary.run();
        return group;
    }

    private String navigationSummary() {
        int mask = config.hiddenBottomNavigationItems();
        StringBuilder hidden = new StringBuilder();
        for (BottomNavigationSelection.Item item : BottomNavigationSelection.Item.values()) {
            if ((mask & item.bit()) == 0) continue;
            if (hidden.length() > 0) hidden.append('、');
            hidden.append(item.title);
        }
        return hidden.length() == 0 ? "全部入口均显示" : "已隐藏：" + hidden;
    }

    @SuppressWarnings("deprecation")
    private View createNavigationSwitchRow(Activity context, BottomNavigationSelection.Item item,
                                           Runnable onSelectionChanged) {
        boolean supported = bottomNavigationSupported;
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 55));
        row.setPadding(dp(context, 18), dp(context, 8), dp(context, 12), dp(context, 8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(resolveColor(context, android.R.attr.colorBackgroundFloating, Color.WHITE));
        background.setCornerRadius(dp(context, 8));
        row.setBackground(background);
        TextView title = new TextView(context);
        title.setText("隐藏底栏“" + item.title + "”");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(resolveColor(context, android.R.attr.textColorPrimary, Color.BLACK));
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch toggle = new Switch(context);
        toggle.setContentDescription(title.getText());
        toggle.setChecked((config.hiddenBottomNavigationItems() & item.bit()) != 0);
        toggle.setEnabled(supported);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (Boolean.TRUE.equals(button.getTag())) return;
            int mask = config.hiddenBottomNavigationItems();
            int next = checked ? mask | item.bit() : mask & ~item.bit();
            if (!config.setBottomNavigationHidden(item, checked)) {
                Toast.makeText(context.getApplicationContext(), BottomNavigationSelection.validMask(next)
                        ? "配置保存失败" : "请至少保留一个页面入口", Toast.LENGTH_SHORT).show();
                button.setTag(Boolean.TRUE);
                button.setChecked((config.hiddenBottomNavigationItems() & item.bit()) != 0);
                button.setTag(null);
            }
            onSelectionChanged.run();
        });
        row.addView(toggle);
        row.setAlpha(supported ? 1f : 0.45f);
        row.setEnabled(supported);
        row.setOnClickListener(view -> { if (toggle.isEnabled()) toggle.setChecked(!toggle.isChecked()); });
        return row;
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
        boolean supported = (!feature.requiresCoolapk15 || coolapkMajor >= 15)
                && (feature != PurifierConfig.Feature.BLOCK_SELF_UPDATE || hostUpgradeSupported);
        if (!supported) {
            TextView summary = new TextView(context);
            summary.setText(feature == PurifierConfig.Feature.BLOCK_SELF_UPDATE
                    ? "当前酷安版本暂未适配" : "仅支持酷安 15.x 及以上");
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            summary.setTextColor(resolveColor(context,
                    android.R.attr.textColorSecondary, 0xff888888));
            labels.addView(summary);
        } else if (feature == PurifierConfig.Feature.BLOCK_SELF_UPDATE) {
            TextView summary = new TextView(context);
            summary.setText("阻止酷安自身的更新检查、弹窗和升级下载");
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            summary.setTextColor(resolveColor(context,
                    android.R.attr.textColorSecondary, 0xff888888));
            labels.addView(summary);
        }
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(context);
        toggle.setContentDescription(feature.title);
        toggle.setChecked(config.isEnabled(feature));
        toggle.setEnabled(supported);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (Boolean.TRUE.equals(button.getTag())) return;
            if (!config.setEnabled(feature, checked)) {
                Toast.makeText(context.getApplicationContext(),
                        "配置保存失败", Toast.LENGTH_SHORT).show();
                button.setTag(Boolean.TRUE);
                button.setChecked(config.isEnabled(feature));
                button.setTag(null);
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
