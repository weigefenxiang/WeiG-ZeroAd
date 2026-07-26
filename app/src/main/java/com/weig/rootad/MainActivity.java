package com.weig.rootad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String UI_PREFERENCES = "zeroad-ui";
    private static final String THEME_OVERRIDE = "theme-override";
    private static final int THEME_SYSTEM = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;

    private record UpdateCheck(
            RuleUpdater.Available rules,
            CodeUpdater.Available code,
            String rulesError,
            String codeError
    ) {}
    private record RetainedState(RootStatus status, boolean themeRecreation) {}

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean zh;
    private int surface, card, primary, secondary, accent, accentSoft, divider;
    private int updateAvailable, updateUnavailable, updateError;
    private TextView protectionText, countText, detailText, updateText;
    private TextView actionText, rewardCountdown;
    private ProgressBar progress, actionProgress;
    private Button protectionButton, rewardButton;
    private Button cnOffButton, cnLeanButton, cnBalancedButton, cnStrictButton;
    private Button globalOffButton, globalLeanButton, globalBalancedButton, globalStrictButton;
    private CheckBox rewardTencent, rewardWechat, rewardShortVideo, rewardOther;
    private RootStatus latest;
    private boolean updatingUi;
    private boolean themeRecreation;
    private boolean skipResumeRefresh;
    private boolean destroyed;
    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            if (latest == null || !latest.rewardTemporarilyAllowed()) return;
            long remaining = latest.rewardExpiresAt() - System.currentTimeMillis() / 1000L;
            if (remaining <= 0) { refresh(); return; }
            rewardCountdown.setText(t("奖励广告临时放行  ", "Reward ads allowed  ") +
                    String.format(Locale.US, "%02d:%02d", remaining / 60, remaining % 60));
            main.postDelayed(this, 1000);
        }
    };

    @Override protected void attachBaseContext(Context base) {
        int mode = base.getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .getInt(THEME_OVERRIDE, THEME_SYSTEM);
        if (mode == THEME_SYSTEM) {
            super.attachBaseContext(base);
            return;
        }
        Configuration themed = new Configuration(base.getResources().getConfiguration());
        int night = mode == THEME_DARK
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        themed.uiMode = (themed.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        super.attachBaseContext(base.createConfigurationContext(themed));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        zh = Locale.getDefault().getLanguage().equals("zh");
        surface = getColor(R.color.surface); card = getColor(R.color.surface_card);
        primary = getColor(R.color.text_primary); secondary = getColor(R.color.text_secondary);
        accent = getColor(R.color.accent); accentSoft = getColor(R.color.accent_soft);
        divider = getColor(R.color.divider);
        updateAvailable = getColor(R.color.update_available);
        updateUnavailable = getColor(R.color.update_unavailable);
        updateError = getColor(R.color.update_error);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(buildScreen());

        // Some vendor Android 16 builds do not create PhoneWindow's DecorView
        // until setContentView(). Querying the controller earlier crashes launch.
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark ? 0 : light, light);
        }
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof RetainedState saved && saved.status() != null) {
            skipResumeRefresh = saved.themeRecreation();
            showStatus(saved.status());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (skipResumeRefresh) {
            skipResumeRefresh = false;
            return;
        }
        refresh();
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return new RetainedState(latest, themeRecreation);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacks(countdownTick);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void toggleTheme() {
        boolean dark = (getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean saved = getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE).edit()
                .putInt(THEME_OVERRIDE, dark ? THEME_LIGHT : THEME_DARK)
                .commit();
        if (!saved) {
            toast(t("无法保存主题设置", "Cannot save theme setting"));
            return;
        }
        themeRecreation = true;
        recreate();
    }

    private void followSystemTheme() {
        boolean saved = getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE).edit()
                .remove(THEME_OVERRIDE)
                .commit();
        if (!saved) {
            toast(t("无法保存主题设置", "Cannot save theme setting"));
            return;
        }
        toast(t("已恢复跟随系统", "Following system theme"));
        themeRecreation = true;
        recreate();
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(surface);
        LinearLayout body = column();
        int horizontal = dp(20), top = dp(24), bottom = dp(40);
        body.setPadding(horizontal, top, horizontal, bottom);
        scroll.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            body.setPadding(horizontal, top + bars.top, horizontal, bottom + bars.bottom);
            return windowInsets;
        });
        scroll.addView(body);

        LinearLayout header = row();
        header.setGravity(Gravity.TOP);
        LinearLayout heading = column();
        TextView brand = text("Wei.G", 14, accent, Typeface.BOLD);
        heading.addView(brand);
        TextView title = text("ZeroAd", 34, primary, Typeface.BOLD);
        heading.addView(title, margins(-2, 2, 0, 0));
        header.addView(heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton theme = new ImageButton(this);
        theme.setImageResource(R.drawable.ic_theme);
        theme.setColorFilter(accent);
        theme.setBackground(round(accentSoft, 14, divider));
        theme.setPadding(dp(10), dp(10), dp(10), dp(10));
        theme.setContentDescription(t("切换白天或黑暗模式", "Toggle light or dark mode"));
        theme.setTooltipText(theme.getContentDescription());
        theme.setOnClickListener(view -> toggleTheme());
        theme.setOnLongClickListener(view -> {
            followSystemTheme();
            return true;
        });
        LinearLayout.LayoutParams themeLayout = new LinearLayout.LayoutParams(dp(44), dp(44));
        themeLayout.setMargins(dp(12), 0, 0, 0);
        header.addView(theme, themeLayout);
        body.addView(header, margins(0, 0, 0, 20));

        LinearLayout hero = card();
        protectionText = text(t("正在检测 Root 核心", "Checking Root core"), 14, secondary, Typeface.BOLD);
        hero.addView(protectionText);
        countText = text("—", 42, primary, Typeface.BOLD);
        hero.addView(countText, margins(0, 8, 0, 0));
        hero.addView(text(t("条规则运行中", "rules running"), 14, secondary, Typeface.NORMAL));
        detailText = text("Android " + android.os.Build.VERSION.RELEASE + " · " + BuildConfig.VERSION_NAME,
                13, secondary, Typeface.NORMAL);
        hero.addView(detailText, margins(0, 14, 0, 0));
        actionText = text("", 13, accent, Typeface.BOLD);
        actionText.setVisibility(View.GONE);
        hero.addView(actionText, margins(0, 12, 0, 0));
        actionProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        actionProgress.setIndeterminate(true);
        actionProgress.setVisibility(View.GONE);
        hero.addView(actionProgress, margins(0, 8, 0, 0));
        protectionButton = button(t("关闭保护", "Disable protection"), true);
        protectionButton.setOnClickListener(v -> toggleProtection());
        hero.addView(protectionButton, margins(0, 18, 0, 0));
        body.addView(hero);

        body.addView(section(t("过滤模式", "Filter profiles")));
        LinearLayout profiles = card();
        profiles.addView(text(t("境内和境外分别选择强度；六个普通配置均不含奖励广告域名。", "Choose domestic and global strength separately. All normal profiles exclude reward-ad domains."),
                14, secondary, Typeface.NORMAL));
        profiles.addView(text(t("境内规则", "Domestic rules"), 14, primary, Typeface.BOLD),
                margins(0, 16, 0, 0));
        LinearLayout cnTop = row();
        cnOffButton = actionButton(t("关闭", "Off"), v -> command(
                "cn-profile off", t("正在关闭境内规则…", "Disabling domestic rules…")));
        cnLeanButton = actionButton(t("精简", "Lean"), v -> command("cn-profile lean", t("正在切换境内精简…", "Selecting domestic Lean…")));
        cnTop.addView(cnOffButton, weightMargins(1, 0, 10, 5, 0));
        cnTop.addView(cnLeanButton, weightMargins(1, 0, 10, 0, 0));
        profiles.addView(cnTop);
        LinearLayout cnBottom = row();
        cnBalancedButton = actionButton(t("平衡", "Balanced"), v -> command("cn-profile balanced", t("正在切换境内平衡…", "Selecting domestic Balanced…")));
        cnStrictButton = actionButton(t("严格", "Strict"), v -> command("cn-profile strict", t("正在切换境内严格…", "Selecting domestic Strict…")));
        cnBottom.addView(cnBalancedButton, weightMargins(1, 0, 8, 5, 0));
        cnBottom.addView(cnStrictButton, weightMargins(1, 0, 8, 0, 0));
        profiles.addView(cnBottom);
        profiles.addView(text(t("境外规则", "Global rules"), 14, primary, Typeface.BOLD), margins(0, 16, 0, 0));
        LinearLayout globalTop = row();
        globalOffButton = actionButton(t("关闭", "Off"), v -> command("global-profile off", t("正在关闭境外规则…", "Disabling global rules…")));
        globalLeanButton = actionButton(t("精简", "Lean"), v -> command("global-profile lean", t("正在切换境外精简…", "Selecting global Lean…")));
        globalTop.addView(globalOffButton, weightMargins(1, 0, 10, 5, 0));
        globalTop.addView(globalLeanButton, weightMargins(1, 0, 10, 0, 0));
        profiles.addView(globalTop);
        LinearLayout globalBottom = row();
        globalBalancedButton = actionButton(t("平衡", "Balanced"), v -> command("global-profile balanced", t("正在切换境外平衡…", "Selecting global Balanced…")));
        globalStrictButton = actionButton(t("严格", "Strict"), v -> command("global-profile strict", t("正在切换境外严格…", "Selecting global Strict…")));
        globalBottom.addView(globalBalancedButton, weightMargins(1, 0, 8, 5, 0));
        globalBottom.addView(globalStrictButton, weightMargins(1, 0, 8, 0, 0));
        profiles.addView(globalBottom);
        body.addView(profiles);

        body.addView(section(t("奖励广告拦截", "Reward-ad blocking")));
        LinearLayout rewards = card();
        rewards.addView(text(t("默认全部拦截，且始终与普通规则分离；需要领取奖励时可临时放行。",
                "All packs are blocked by default and remain separate from normal rules. Temporarily allow them when needed."),
                14, secondary, Typeface.NORMAL));
        rewardTencent = packCheckBox(t("腾讯 / QQ 奖励广告", "Tencent / QQ reward ads"), "reward.tencent");
        rewardWechat = packCheckBox(t("微信奖励广告", "WeChat reward ads"), "reward.wechat");
        rewardShortVideo = packCheckBox(t("短视频奖励广告", "Short-video reward ads"), "reward.short-video");
        rewardOther = packCheckBox(t("其他奖励广告", "Other reward ads"), "reward.other");
        rewards.addView(rewardTencent, margins(0, 12, 0, 0));
        rewards.addView(rewardWechat);
        rewards.addView(rewardShortVideo);
        rewards.addView(rewardOther);
        rewardCountdown = text("", 13, accent, Typeface.BOLD);
        rewardCountdown.setVisibility(View.GONE);
        rewards.addView(rewardCountdown, margins(0, 10, 0, 0));
        rewardButton = button(t("临时允许已选奖励广告 10 分钟", "Allow selected reward ads for 10 minutes"), false);
        rewardButton.setOnClickListener(v -> toggleRewardTimer());
        rewards.addView(rewardButton, margins(0, 10, 0, 0));
        body.addView(rewards);

        body.addView(section(t("自定义规则", "Custom rules")));
        LinearLayout custom = card();
        custom.addView(text(t("添加精确域名到拦截、放行或手动关闭列表。自定义内容不会被规则更新覆盖。",
                "Add exact domains to block, allow, or disabled lists. Updates never overwrite them."), 14, secondary, Typeface.NORMAL));
        Button edit = button(t("添加或调整域名", "Add or change a domain"), false);
        edit.setOnClickListener(v -> ruleDialog());
        custom.addView(edit, margins(0, 14, 0, 0));
        body.addView(custom);

        body.addView(section(t("更新", "Updates")));
        LinearLayout updates = card();
        updateText = text(t("规则、管理器和核心模块分别更新。", "Rules, manager, and core update independently."),
                14, secondary, Typeface.NORMAL);
        updates.addView(updateText);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setVisibility(View.GONE);
        updates.addView(progress, margins(0, 12, 0, 4));
        Button checkUpdates = button(t("检查更新", "Check for updates"), true);
        checkUpdates.setOnClickListener(v -> checkUpdates());
        updates.addView(checkUpdates, margins(0, 10, 0, 0));
        Button rollback = button(t("回滚上一版规则", "Roll back rules"), false);
        rollback.setOnClickListener(v -> command("rules-rollback"));
        updates.addView(rollback, margins(0, 10, 0, 0));
        body.addView(updates);

        body.addView(section(t("支持与卸载", "Support & removal")));
        LinearLayout support = card();
        Button issue = button(t("问题反馈 · 提交 GitHub Issue", "Report issue · GitHub"), false);
        issue.setOnClickListener(v -> openIssue());
        support.addView(issue);
        Button logs = button(t("查看运行日志", "View runtime log"), false);
        logs.setOnClickListener(v -> showRuntimeLog());
        support.addView(logs, margins(0, 10, 0, 0));
        Button uninstall = button(t("完整卸载", "Complete uninstall"), false);
        uninstall.setTextColor(Color.rgb(210, 55, 60));
        uninstall.setOnClickListener(v -> confirmUninstall());
        support.addView(uninstall, margins(0, 10, 0, 0));
        body.addView(support);

        body.addView(text("WeiG ZeroAd  " + BuildConfig.VERSION_NAME + "  ·  Android 12–16",
                12, secondary, Typeface.NORMAL), margins(0, 22, 0, 0));
        return scroll;
    }

    private void refresh() {
        busy(true, t("读取核心状态…", "Reading core status…"));
        worker.execute(() -> {
            RootStatus status = RootStatus.read();
            postUi(() -> showStatus(status));
        });
    }

    private void showStatus(RootStatus status) {
        latest = status;
        busy(false, null);
        updateText.setText(t("规则与 APK 更新无需重启；只有核心安装或更新后需要重启。",
                "Rule and APK updates need no reboot; only core installation or updates do."));
        main.removeCallbacks(countdownTick);
        if (status.requiresReboot()) {
            protectionText.setText(t("核心已安装，等待重启", "Core installed; reboot required"));
            countText.setText("0");
            detailText.setText(t("重启后广告过滤才会生效", "Ad filtering starts after reboot"));
            protectionButton.setText(t("立即重启", "Reboot now"));
            protectionButton.setEnabled(true);
            setRuleControlsEnabled(false);
            return;
        }
        if (!status.installed()) {
            protectionText.setText(t("尚未安装 Root 核心", "Root core is not installed"));
            countText.setText("0");
            detailText.setText(t("请安装一体包或 core-only 模块；若刚安装，请重启设备。",
                    "Install the all-in-one or core-only module. Reboot if it was just installed."));
            protectionButton.setEnabled(false);
            setRuleControlsEnabled(false);
            return;
        }
        setRuleControlsEnabled(true);
        protectionButton.setEnabled(true);
        protectionText.setText(status.protection() ? t("保护运行中", "Protection active") : t("保护已关闭", "Protection disabled"));
        countText.setText(String.format(Locale.US, "%,d", status.running()));
        boolean cnOff = status.cnProfile().equals("off");
        String cnMode = cnOff ? t("境内关闭", "Domestic off") :
                t("境内", "Domestic ") + profileLabel(status.cnProfile());
        String globalMode = status.globalProfile().equals("off") ? t("境外关闭", "Global off") :
                t("境外", "Global ") + profileLabel(status.globalProfile());
        detailText.setText(cnMode + " · " + globalMode + " · " + status.root() +
                " · rules " + status.ruleVersion() +
                "\n" + t("关闭 ", "disabled ") + status.disabled() + " · " +
                t("自定义拦截 ", "custom block ") + status.customBlock() + " · " +
                t("自定义放行 ", "custom allow ") + status.customAllow() +
                "\n" + t("奖励广告拦截 ", "reward blocks ") + status.rewardBlock());
        protectionButton.setText(status.protection() ? t("关闭保护", "Disable protection") : t("开启保护", "Enable protection"));
        styleProfileButtons(status.cnProfile(), status.globalProfile());
        updatingUi = true;
        rewardTencent.setChecked(status.packEnabled("reward.tencent"));
        rewardWechat.setChecked(status.packEnabled("reward.wechat"));
        rewardShortVideo.setChecked(status.packEnabled("reward.short-video"));
        rewardOther.setChecked(status.packEnabled("reward.other"));
        updatingUi = false;
        if (status.rewardTemporarilyAllowed()) {
            rewardCountdown.setVisibility(View.VISIBLE);
            rewardButton.setText(t("立即结束临时放行", "End temporary allowance"));
            main.post(countdownTick);
        } else {
            rewardCountdown.setVisibility(View.GONE);
            rewardButton.setText(t("临时允许已选奖励广告 10 分钟", "Allow selected reward ads for 10 minutes"));
        }
        rewardButton.setEnabled(status.rewardBlock() > 0 || status.rewardTemporarilyAllowed());
    }

    private void toggleProtection() {
        if (latest != null && latest.requiresReboot()) { confirmReboot(); return; }
        boolean disable = latest != null && latest.protection();
        command(disable ? "protection-off" : "protection-on",
                disable ? t("正在关闭保护…", "Disabling protection…") : t("正在开启保护…", "Enabling protection…"));
    }

    private void command(String command) { command(command, t("正在应用…", "Applying…")); }

    private void command(String command, String message) {
        busy(true, message);
        if (command.startsWith("cn-profile ")) {
            styleProfileButtons(command.substring("cn-profile ".length()),
                    latest == null ? "off" : latest.globalProfile());
        } else if (command.startsWith("global-profile ")) {
            styleProfileButtons(latest == null ? "lean" : latest.cnProfile(),
                    command.substring("global-profile ".length()));
        } else if (command.equals("protection-on") || command.equals("protection-off")) {
            protectionText.setText(message);
            protectionButton.setText(t("处理中…", "Working…"));
        } else if (command.equals("reward 10")) {
            rewardButton.setText(t("正在临时放行…", "Allowing temporarily…"));
            rewardCountdown.setText(t("正在应用，完成后开始倒计时", "Applying; countdown starts when ready"));
            rewardCountdown.setVisibility(View.VISIBLE);
        } else if (command.equals("reward-stop")) {
            rewardButton.setText(t("正在恢复拦截…", "Restoring blocking…"));
            rewardCountdown.setVisibility(View.GONE);
        }
        protectionButton.setEnabled(false);
        setRuleControlsEnabled(false);
        worker.execute(() -> {
            RootShell.Result result = RootShell.runControl(command);
            RootStatus status = result.ok() ? RootStatus.fromResult(result) : RootStatus.read();
            postUi(() -> {
                if (!result.ok()) toast(result.output());
                showStatus(status);
            });
        });
    }

    private void toggleRewardTimer() {
        boolean stop = latest != null && latest.rewardTemporarilyAllowed();
        command(stop ? "reward-stop" : "reward 10",
                stop ? t("正在恢复奖励广告拦截…", "Restoring reward blocking…") :
                        t("正在临时放行奖励广告…", "Temporarily allowing reward ads…"));
    }

    private void setPack(String id, boolean enabled) {
        command((enabled ? "pack-enable " : "pack-disable ") + id,
                enabled ? t("正在加入奖励广告规则…", "Adding reward-ad rules…") :
                        t("正在移除奖励广告规则…", "Removing reward-ad rules…"));
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this).setTitle(t("立即重启", "Reboot now"))
                .setMessage(t("设备将立即重启，使 Root 核心生效。", "The device will reboot now to activate the Root core."))
                .setPositiveButton(t("重启", "Reboot"), (dialog, which) ->
                        worker.execute(() -> RootShell.run("reboot")))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void ruleDialog() {
        EditText input = new EditText(this);
        input.setHint("ads.example.com");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        int padding = dp(22); input.setPadding(padding, dp(8), padding, 0);
        String[] choices = {t("加入拦截", "Add to block"), t("加入放行", "Add to allow"),
                t("关闭现有规则", "Disable existing rule"), t("恢复现有规则", "Enable existing rule")};
        new AlertDialog.Builder(this).setTitle(t("自定义域名", "Custom domain")).setView(input)
                .setItems(choices, (dialog, which) -> {
                    String domain = input.getText().toString().trim().toLowerCase(Locale.ROOT);
                    if (domain.isEmpty()) { toast(t("请输入域名", "Enter a domain")); return; }
                    String operation = switch (which) { case 0 -> "block-add"; case 1 -> "allow-add";
                        case 2 -> "domain-disable"; default -> "domain-enable"; };
                    command(operation + " " + RootShell.quote(domain));
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void checkUpdates() {
        busy(true, t("正在检查规则、管理器和核心…", "Checking rules, manager, and core…"));
        worker.execute(() -> {
            UpdateCheck result = performUpdateCheck();
            postUi(() -> showUpdateCheck(result));
        });
    }

    private UpdateCheck performUpdateCheck() {
        RuleUpdater.Available rules = null;
        CodeUpdater.Available code = null;
        String rulesError = "";
        String codeError = "";
        try { rules = RuleUpdater.checkLatest(); }
        catch (Exception error) { rulesError = errorMessage(error); }
        try { code = CodeUpdater.check(this); }
        catch (Exception error) { codeError = errorMessage(error); }
        return new UpdateCheck(rules, code, rulesError, codeError);
    }

    private void showUpdateCheck(UpdateCheck result) {
        busy(false, null);
        RootStatus status = latest;
        long currentRules = status == null ? 0 : status.ruleVersion();
        boolean downloaded = status != null && status.rulesDownloaded();
        boolean rulesNew = result.rules() != null &&
                (!downloaded || result.rules().version() > currentRules);
        boolean managerNew = result.code() != null &&
                result.code().manager().versionCode() > BuildConfig.VERSION_CODE;
        boolean corePending = status != null && status.pendingReboot();
        int currentCoreCode = status == null ? 0 : status.coreVersionCode();
        boolean coreNew = result.code() != null && !corePending &&
                result.code().core().versionCode() > currentCoreCode;

        String rulesCurrent = downloaded ? formatRuleVersion(currentRules) :
                t("内置基础规则", "Built-in base");
        String rulesLine = result.rules() == null
                ? t("规则：检查失败 · ", "Rules: check failed · ") + result.rulesError()
                : t("规则：", "Rules: ") + rulesCurrent + " → " +
                formatRuleVersion(result.rules().version()) +
                (rulesNew ? t(" · 可更新", " · update available") : t(" · 已是最新", " · up to date"));
        String managerLine = result.code() == null
                ? t("APK：检查失败 · ", "APK: check failed · ") + result.codeError()
                : t("APK：", "APK: ") +
                formatCodeVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) + " → " +
                formatCodeVersion(result.code().manager().versionName(),
                        result.code().manager().versionCode()) +
                (managerNew ? t(" · 可更新", " · update available") : t(" · 已是最新", " · up to date"));
        String coreCurrent = status == null || !status.installed()
                ? t("未安装", "not installed")
                : formatCodeVersion(status.coreVersion(), status.coreVersionCode());
        String coreLine = result.code() == null
                ? t("核心：检查失败 · ", "Core: check failed · ") + result.codeError()
                : t("核心：", "Core: ") + coreCurrent + " → " +
                formatCodeVersion(result.code().core().versionName(),
                        result.code().core().versionCode()) +
                (corePending ? t(" · 等待重启", " · reboot pending") :
                        (coreNew ? t(" · 可更新", " · update available") : t(" · 已是最新", " · up to date")));
        String message = rulesLine + "\n\n" + managerLine + "\n\n" + coreLine + "\n\n" + t(
                "规则立即生效；APK 安装后重新打开；核心重启后生效。",
                "Rules apply immediately; reopen the APK after installation; core updates apply after reboot.");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("检查更新", "Check for updates"))
                .setMessage(message)
                .setPositiveButton(t("更新规则", "Update rules"), null)
                .setNeutralButton(t("更新 APK", "Update APK"), null)
                .setNegativeButton(t("更新核心", "Update core"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button rulesButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button managerButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            Button coreButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            rulesButton.setEnabled(rulesNew);
            managerButton.setEnabled(managerNew);
            coreButton.setEnabled(coreNew);
            styleUpdateButton(rulesButton, rulesNew, result.rules() == null);
            styleUpdateButton(managerButton, managerNew, result.code() == null);
            styleUpdateButton(coreButton, coreNew, result.code() == null);
            if (rulesNew) rulesButton.setOnClickListener(view -> {
                dialog.dismiss();
                installRules(result.rules());
            });
            if (managerNew) managerButton.setOnClickListener(view -> {
                dialog.dismiss();
                updateApp(result.code().manager());
            });
            if (coreNew) coreButton.setOnClickListener(view -> {
                dialog.dismiss();
                updateCore(result.code().core());
            });
        });
        dialog.show();
    }

    private void installRules(RuleUpdater.Available available) {
        busy(true, t("正在下载并验证规则…", "Downloading and verifying rules…"));
        worker.execute(() -> {
            try {
                RuleUpdater.Result result = RuleUpdater.install(this, available);
                postUi(() -> {
                    toast(t("规则已更新：", "Rules updated: ") +
                            formatRuleVersion(result.version()));
                    refresh();
                });
            } catch (Exception error) {
                postUi(() -> rulesUpdateFailed(error));
            }
        });
    }

    private void updateApp(CodeUpdater.Component manager) {
        busy(true, t("正在下载并验证 APK…", "Downloading and verifying APK…"));
        worker.execute(() -> {
            try {
                File file = CodeUpdater.download(this, manager, 80L * 1024 * 1024);
                postUi(() -> {
                    busy(false, null);
                    try { ApkInstaller.install(this, file, (message, ok) -> postUi(() -> toast(message))); }
                    catch (Exception error) { failed(error); }
                });
            } catch (Exception error) { postUi(() -> failed(error)); }
        });
    }

    private void updateCore(CodeUpdater.Component core) {
        busy(true, t("正在下载并验证核心…", "Downloading and verifying core…"));
        worker.execute(() -> {
            try {
                File file = CodeUpdater.download(this, core, 50L * 1024 * 1024);
                String path = file.getAbsolutePath();
                String command = "if command -v magisk >/dev/null 2>&1; then magisk --install-module " + RootShell.quote(path) +
                        "; elif command -v ksud >/dev/null 2>&1; then ksud module install " + RootShell.quote(path) +
                        "; elif command -v apd >/dev/null 2>&1; then apd module install " + RootShell.quote(path) +
                        "; else echo 'No supported module installer found'; exit 1; fi";
                RootShell.Result result = RootShell.run(command);
                if (!result.ok()) throw new IllegalStateException(result.output());
                postUi(() -> { toast(t("核心已更新，重启后生效", "Core updated; reboot to apply")); refresh(); });
            } catch (Exception error) { postUi(() -> failed(error)); }
        });
    }

    private void showRuntimeLog() {
        busy(true, t("正在读取运行日志…", "Reading runtime log…"));
        worker.execute(() -> {
            RootShell.Result result = RootShell.runControl("events");
            String crashes = CrashLog.read(this);
            postUi(() -> presentRuntimeLog(result, crashes));
        });
    }

    private void presentRuntimeLog(RootShell.Result result, String crashes) {
        busy(false, null);
        String raw = result.ok() ? result.output() : "";
        long startedAt = readStartedAt(raw);
        String events = eventLines(raw);
        RootStatus status = latest;
        String summary = t("当前已加载：", "Currently loaded: ") +
                String.format(Locale.US, "%,d", status == null ? 0 : status.running()) +
                t(" 条拦截规则", " blocking rules") + "\n" +
                t("保护运行时间：", "Protection uptime: ") +
                protectionDuration(startedAt, status != null && status.protection()) +
                "\n\n" + t("核心事件", "Core events") + "\n" + (result.ok()
                ? (events.isBlank() ? t("暂无事件记录", "No events recorded") : events)
                : t("当前核心不支持运行日志，请更新核心并重启。",
                        "The installed core does not support runtime logs. Update it and reboot.")) +
                "\n\n" + t("管理器闪退", "Manager crashes") + "\n" +
                (crashes.isBlank() ? t("暂无闪退记录", "No crash records") : crashes);
        TextView content = text(summary, 12, primary, Typeface.NORMAL);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle(t("运行日志", "Runtime log"))
                .setView(scroll)
                .setPositiveButton(t("复制日志", "Copy log"), (dialog, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("WeiG ZeroAd log", summary));
                    toast(t("日志已复制", "Log copied"));
                })
                .setNegativeButton(t("关闭", "Close"), null)
                .show();
    }

    private void openIssue() {
        busy(true, t("正在准备安全诊断…", "Preparing safe diagnostics…"));
        worker.execute(() -> {
            RootShell.Result events = RootShell.runControl("events");
            String recent = events.ok() ? recentEventLines(events.output(), 8) : "";
            String crash = CrashLog.latest(this);
            postUi(() -> launchIssue(recent, crash));
        });
    }

    private void launchIssue(String recentEvents, String crash) {
        busy(false, null);
        RootStatus status = latest;
        String title = "[Issue] ";
        String body = "## 问题描述 / Description\n\n\n## 安全诊断 / Safe diagnostics\n" +
                "- APK: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")" +
                "\n- Android: " + android.os.Build.VERSION.RELEASE +
                "\n- Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                (status == null ? "" : "\n- Root: " + status.root() +
                        "\n- Core: " + status.coreVersion() + " (" + status.coreVersionCode() + ")" +
                        "\n- Rule version: " + status.ruleVersion() +
                        "\n- Domestic profile: " + status.cnProfile() +
                        "\n- Global profile: " + status.globalProfile() +
                        "\n- Running rules: " + status.running() +
                        "\n- Protection: " + status.protection()) +
                (recentEvents.isBlank() ? "" :
                        "\n\n## 最近事件 / Recent events\n```\n" + recentEvents + "\n```") +
                (crash.isBlank() ? "" :
                        "\n\n## 最近一次管理器闪退 / Latest manager crash\n```\n" +
                                crash.replace("```", "'''") + "\n```") +
                "\n\n请勿附加账号令牌、Cookie 或完整 HTTPS 数据。" +
                "\nDo not attach account tokens, cookies, or full HTTPS payloads.";
        String url = "https://github.com/" + BuildConfig.GITHUB_OWNER + "/" + BuildConfig.CODE_REPOSITORY +
                "/issues/new?title=" + encode(title) + "&body=" + encode(body);
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private long readStartedAt(String output) {
        for (String line : output.split("\\R")) {
            if (!line.startsWith("started_at=")) continue;
            try { return Long.parseLong(line.substring("started_at=".length())); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private String eventLines(String output) {
        StringBuilder value = new StringBuilder();
        for (String line : output.split("\\R")) {
            if (line.isBlank() || line.startsWith("started_at=")) continue;
            if (!value.isEmpty()) value.append('\n');
            value.append(line);
        }
        return value.toString();
    }

    private String recentEventLines(String output, int maximumLines) {
        String events = eventLines(output);
        if (events.isBlank()) return "";
        String[] lines = events.split("\\R");
        int first = Math.max(0, lines.length - maximumLines);
        StringBuilder value = new StringBuilder();
        for (int index = first; index < lines.length; index++) {
            if (!value.isEmpty()) value.append('\n');
            value.append(lines[index]);
        }
        return value.toString();
    }

    private String protectionDuration(long startedAt, boolean enabled) {
        if (!enabled) return t("未运行", "not running");
        if (startedAt <= 0) return t("未知", "unknown");
        long seconds = Math.max(0, System.currentTimeMillis() / 1000L - startedAt);
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) return days + t(" 天 ", "d ") + hours + t(" 小时", "h");
        if (hours > 0) return hours + t(" 小时 ", "h ") + minutes + t(" 分钟", "m");
        return minutes + t(" 分钟", "m");
    }

    private void confirmUninstall() {
        new AlertDialog.Builder(this).setTitle(t("完整卸载", "Complete uninstall"))
                .setMessage(t("将关闭 hosts 挂载，并删除核心模块、官方规则、自定义规则和全部 ZeroAd 状态。APK 随后交给系统卸载。",
                        "This removes the hosts mount, core module, official and custom rules, and all ZeroAd state. Android will then remove the app."))
                .setPositiveButton(t("全部删除", "Remove everything"), (d, w) -> fullUninstall())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void fullUninstall() {
        busy(true, t("正在清理规则…", "Removing rules…"));
        worker.execute(() -> {
            RootShell.Result result = RootShell.runControl("cleanup-mount");
            if (!result.ok()) {
                postUi(() -> {
                    busy(false, null);
                    toast(t("无法关闭 hosts 挂载，未删除任何文件：",
                            "Could not remove the hosts mount; no files were deleted: ") +
                            result.output());
                });
                return;
            }
            RootShell.Result remove = RootShell.run(
                    "rm -rf /data/adb/weig_rootad /data/adb/weig_rootad-user-backup " +
                    "/data/adb/modules/weig_rootad " +
                    "/data/adb/modules_update/weig_rootad");
            postUi(() -> {
                busy(false, null);
                if (!remove.ok()) { toast(remove.output()); return; }
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName())));
            });
        });
    }

    private void failed(Exception error) { busy(false, null); toast(error.getMessage() == null ? error.toString() : error.getMessage()); }
    private String errorMessage(Exception error) {
        String value = error.getMessage() == null ? error.toString() : error.getMessage();
        return value.length() > 96 ? value.substring(0, 96) + "…" : value;
    }
    private void rulesUpdateFailed(Exception error) {
        busy(false, null);
        String detail = error.getMessage() == null ? error.toString() : error.getMessage();
        toast(t(
                "规则更新失败，已继续使用当前规则；首次安装会使用 Wei.G 20260723 基础规则。原因：",
                "Rule update failed. Current rules were kept; first install uses the Wei.G " +
                        "20260723 base. Reason: ") + detail);
        refresh();
    }
    private String formatRuleVersion(long version) {
        if (version <= 0) return t("内置基础规则", "Built-in base");
        String value = Long.toString(version);
        if (value.length() != 10) return value;
        return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" +
                value.substring(6, 8) + " #" + value.substring(8);
    }
    private String formatCodeVersion(String name, int code) {
        return name + " (" + code + ")";
    }
    private void styleUpdateButton(Button button, boolean available, boolean failed) {
        button.setTextColor(available ? updateAvailable :
                (failed ? updateError : updateUnavailable));
    }
    private void busy(boolean value, String message) {
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        actionProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        actionText.setVisibility(value && message != null ? View.VISIBLE : View.GONE);
        if (message != null) actionText.setText(message);
    }
    private void postUi(Runnable action) {
        main.post(() -> {
            if (!destroyed && !isFinishing()) action.run();
        });
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private String t(String chinese, String english) { return zh ? chinese : english; }
    private String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception impossible) { return value; }
    }

    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout row() { LinearLayout value = column(); value.setOrientation(LinearLayout.HORIZONTAL); return value; }
    private LinearLayout card() { LinearLayout value = column(); value.setPadding(dp(18), dp(18), dp(18), dp(18)); value.setBackground(round(card, 20, divider)); return value; }
    private TextView section(String value) { TextView text = text(value, 16, primary, Typeface.BOLD); text.setPadding(0, dp(26), 0, dp(10)); return text; }
    private TextView text(String value, int size, int color, int style) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setTypeface(Typeface.create("sans", style)); text.setLineSpacing(0, 1.12f); return text; }
    private Button actionButton(String value, View.OnClickListener listener) { Button button = button(value, false); button.setOnClickListener(listener); return button; }
    private CheckBox packCheckBox(String label, String id) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextSize(14);
        checkBox.setTextColor(primary);
        checkBox.setMinHeight(dp(44));
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingUi) setPack(id, checked);
        });
        return checkBox;
    }
    private void setRuleControlsEnabled(boolean enabled) {
        if (cnLeanButton == null) return;
        cnOffButton.setEnabled(enabled);
        cnLeanButton.setEnabled(enabled);
        cnBalancedButton.setEnabled(enabled);
        cnStrictButton.setEnabled(enabled);
        globalOffButton.setEnabled(enabled);
        globalLeanButton.setEnabled(enabled);
        globalBalancedButton.setEnabled(enabled);
        globalStrictButton.setEnabled(enabled);
        rewardTencent.setEnabled(enabled);
        rewardWechat.setEnabled(enabled);
        rewardShortVideo.setEnabled(enabled);
        rewardOther.setEnabled(enabled);
        rewardButton.setEnabled(enabled);
    }
    private void styleChoice(Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : accent);
        button.setBackground(round(selected ? accent : accentSoft, 14, selected ? accent : divider));
    }
    private String profileLabel(String value) {
        return switch (value) {
            case "lean" -> t("精简", "Lean");
            case "balanced" -> t("平衡", "Balanced");
            case "strict" -> t("严格", "Strict");
            case "off" -> t("关闭", "Off");
            default -> value;
        };
    }
    private void styleProfileButtons(String cn, String global) {
        styleChoice(cnOffButton, cn.equals("off"));
        styleChoice(cnLeanButton, cn.equals("lean"));
        styleChoice(cnBalancedButton, cn.equals("balanced"));
        styleChoice(cnStrictButton, cn.equals("strict"));
        styleChoice(globalOffButton, global.equals("off"));
        styleChoice(globalLeanButton, global.equals("lean"));
        styleChoice(globalBalancedButton, global.equals("balanced"));
        styleChoice(globalStrictButton, global.equals("strict"));
    }
    private Button button(String value, boolean filled) { Button button = new Button(this); button.setText(value); button.setTextSize(14); button.setAllCaps(false); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setTextColor(filled ? Color.WHITE : accent); button.setGravity(Gravity.CENTER); button.setMinHeight(dp(48)); button.setBackground(round(filled ? accent : accentSoft, 14, filled ? accent : divider)); return button; }
    private GradientDrawable round(int fill, int radius, int stroke) { GradientDrawable value = new GradientDrawable(); value.setColor(fill); value.setCornerRadius(dp(radius)); value.setStroke(dp(1), stroke); return value; }
    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); value.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return value; }
    private LinearLayout.LayoutParams weightMargins(float weight, int left, int top, int right, int bottom) { LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight); value.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
