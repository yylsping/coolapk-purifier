package io.github.yylsping.coolapkpurifier;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Visible, user-initiated configuration surface hosted by the module process.
 * This is the only production path that obtains writable RemotePreferences.
 */
public final class ModuleConfigActivity extends Activity {
    private static final String TAG = "CoolapkPurifier";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "purifier-config-writer");
        thread.setDaemon(true);
        return thread;
    });

    private LinearLayout content;
    private PurifierConfig config;
    private int coolapkMajor;
    private boolean changed;
    private String trustedCallerPackage;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        trustedCallerPackage = getCallingPackage();
        // Reject before touching XposedService or RemotePreferences. The
        // injected settings entry uses startActivityForResult specifically so
        // Android supplies this non-forgeable caller identity.
        if (!ModuleStorageContract.isTrustedActivityCaller(trustedCallerPackage)) {
            Log.w(TAG, "configActivity rejected caller=" + bounded(trustedCallerPackage));
            finish();
            return;
        }
        coolapkMajor = Math.max(0, getIntent().getIntExtra(
                ModuleStorageContract.EXTRA_COOLAPK_MAJOR, 0));
        buildLoadingPage();
        ModuleStorageServiceCore.ensureServiceListener();
        executor.execute(this::loadConfiguration);
    }

    private void buildLoadingPage() {
        int background = resolveColor(android.R.attr.colorBackground, 0xfff7f7fa);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(background);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), 0, dp(16), 0);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setContentDescription("返回酷安设置");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        back.setTextColor(resolveColor(android.R.attr.textColorPrimary, Color.BLACK));
        back.setGravity(Gravity.CENTER);
        back.setFocusable(true);
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));

        TextView title = new TextView(this);
        title.setText("酷安净化");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTextColor(resolveColor(android.R.attr.textColorPrimary, Color.BLACK));
        toolbar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(14), dp(20), dp(14), dp(28));

        ProgressBar progress = new ProgressBar(this);
        content.addView(progress, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView loading = new TextView(this);
        loading.setText("正在连接 LSPosed 配置服务…");
        loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        loading.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xff777777));
        LinearLayout.LayoutParams loadingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loadingParams.topMargin = dp(12);
        content.addView(loading, loadingParams);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void loadConfiguration() {
        ModuleStorage.ProbeResult probe = ModuleServiceBlobStore.probe();
        if (!probe.available) {
            Log.w(TAG, "configActivity serviceAvailable=false detail=" + probe.detail);
            runOnUiThread(() -> showUnavailable(probe.detail));
            return;
        }

        ModuleServiceBlobStore store = new ModuleServiceBlobStore(
                ModuleStorageContract.CONFIG_KEY);
        PurifierConfig.LegacySource initial = trustedInitialSnapshot();
        PurifierConfig loaded = new PurifierConfig(store, initial, null);
        boolean cacheImported = importTrustedResolverCache();
        config = loaded;
        Log.i(TAG, "configActivity serviceAvailable=true backend=" + store.backendName()
                + " source=" + loaded.loadedSource()
                + " cacheImported=" + cacheImported
                + " caller=" + bounded(trustedCallerPackage));
        runOnUiThread(() -> showConfiguration(loaded));
    }

    private PurifierConfig.LegacySource trustedInitialSnapshot() {
        byte[] bytes = getIntent().getByteArrayExtra(
                ModuleStorageContract.EXTRA_INITIAL_CONFIG);
        if (bytes == null) {
            return null;
        }
        if (bytes.length == 0 || bytes.length > ModuleStorageContract.CONFIG_MAX_BYTES) {
            return null;
        }
        return new PurifierConfig.LegacySource() {
            @Override public byte[] read() { return bytes.clone(); }
            @Override public String description() { return "trustedCoolapkActivitySnapshot"; }
        };
    }

    private boolean importTrustedResolverCache() {
        byte[] candidate = getIntent().getByteArrayExtra(
                ModuleStorageContract.EXTRA_INITIAL_CACHE);
        if (candidate == null
                || candidate.length > ModuleStorageContract.CACHE_HANDOFF_MAX_BYTES
                || !ResolutionCache.isValidSnapshot(candidate)) {
            return false;
        }
        ModuleServiceBlobStore cacheStore = new ModuleServiceBlobStore(
                ModuleStorageContract.CACHE_KEY);
        byte[] existing = cacheStore.read();
        if (Arrays.equals(existing, candidate)) {
            return true;
        }
        boolean imported = cacheStore.write(candidate, "cacheSaveTargets");
        Log.i(TAG, "configActivity cacheHandoff bytes=" + candidate.length
                + " success=" + imported);
        return imported;
    }

    private void showConfiguration(PurifierConfig loaded) {
        if (isFinishing() || isDestroyed()) return;
        content.removeAllViews();
        content.setGravity(Gravity.NO_GRAVITY);

        TextView explanation = new TextView(this);
        explanation.setText("配置由模块前台写入 LSPosed，酷安冷启动仅从框架只读加载。更改后请强制停止并重新打开酷安。");
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        explanation.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xff777777));
        explanation.setPadding(dp(4), 0, dp(4), dp(14));
        content.addView(explanation);

        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            View row = createSwitchRow(feature);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            content.addView(row, params);
        }

        Log.i(TAG, "configActivity uiReady=true source=" + loaded.loadedSource());
    }

    private void showUnavailable(String detail) {
        if (isFinishing() || isDestroyed()) return;
        content.removeAllViews();
        TextView message = new TextView(this);
        message.setText("无法连接 LSPosed 配置服务。请确认框架正常运行后重试。\n\n详情："
                + bounded(detail));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        message.setTextColor(resolveColor(android.R.attr.textColorPrimary, Color.BLACK));
        content.addView(message);
    }

    @SuppressWarnings("deprecation")
    private View createSwitchRow(PurifierConfig.Feature feature) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(18), dp(8), dp(12), dp(8));

        GradientDrawable background = new GradientDrawable();
        background.setColor(resolveColor(android.R.attr.colorBackgroundFloating, Color.WHITE));
        background.setCornerRadius(dp(8));
        row.setBackground(background);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(feature.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(resolveColor(android.R.attr.textColorPrimary, Color.BLACK));
        labels.addView(title);
        boolean supported = !feature.requiresCoolapk15 || coolapkMajor >= 15;
        if (!supported) {
            TextView summary = new TextView(this);
            summary.setText("仅支持酷安 15.x 及以上");
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            summary.setTextColor(resolveColor(android.R.attr.textColorSecondary, 0xff888888));
            labels.addView(summary);
        }
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(config.isEnabled(feature));
        toggle.setEnabled(supported);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (Boolean.TRUE.equals(button.getTag()) || !supported) return;
            button.setEnabled(false);
            executor.execute(() -> {
                boolean saved = config.setEnabled(feature, checked);
                boolean actual = config.isEnabled(feature);
                Log.i(TAG, "configActivity write feature=" + feature.key
                        + " requested=" + checked + " success=" + saved);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    button.setTag(Boolean.TRUE);
                    button.setChecked(actual);
                    button.setTag(null);
                    button.setEnabled(supported);
                    if (saved) {
                        changed = true;
                    } else {
                        Toast.makeText(getApplicationContext(),
                                "设置未保存", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setEnabled(supported);
        row.setAlpha(supported ? 1f : 0.45f);
        row.setOnClickListener(view -> {
            if (supported && toggle.isEnabled()) {
                toggle.setChecked(!toggle.isChecked());
            }
        });
        return row;
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (isFinishing() && changed) {
            Toast.makeText(getApplicationContext(),
                    SettingsHooks.EXIT_MESSAGE, Toast.LENGTH_LONG).show();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int resolveColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return getColor(value.resourceId);
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

    private static String bounded(String value) {
        if (value == null) return "unknown";
        String clean = value.replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= 96 ? clean : clean.substring(0, 96);
    }
}
