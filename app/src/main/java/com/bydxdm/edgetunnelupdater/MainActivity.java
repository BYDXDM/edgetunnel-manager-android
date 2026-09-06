package com.bydxdm.edgetunnelupdater;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app deliberately talks directly to Cloudflare. No token, password, or
 * deployment data is sent to an intermediary service.
 *
 * 极简界面：默认只需 Token + ADMIN 密码 + 一个按钮；项目名、Script 名、
 * Account ID、KV 全部留空时自动检测/复用/创建。高级选项折叠收起。
 */
public class MainActivity extends Activity {
    private EditText tokenInput;
    private EditText adminInput;
    private EditText accountInput;
    private EditText pagesProjectInput;
    private EditText workerScriptInput;
    private EditText kvIdInput;
    private EditText kvTitleInput;
    private CheckBox pagesCheck;
    private CheckBox workersCheck;
    private CheckBox saveTokenCheck;
    private LinearLayout advancedPanel;
    private TextView advancedToggle;
    private Button updateButton;
    private Button detectOnlyButton;
    private Button clearTokenButton;
    private TextView statusView;
    private ExecutorService executor;

    /** 一次检测的结果：账户 + 统计 + 建议目标 + 报告文本。 */
    private static class Detection {
        String accountId = "";
        String suggestedPages = "";
        String suggestedWorker = "";
        String report = "";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "edgetunnel-updater");
            thread.setDaemon(true);
            return thread;
        });
        buildUi();
    }

    @Override
    protected void onDestroy() {
        // 优雅关闭而不是 shutdownNow：旋转屏幕等导致的销毁不应中断
        // 正在进行的 Cloudflare 部署，否则更新会在中途被打断且没有结果反馈。
        if (executor != null) executor.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245, 248, 252));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(20));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);

        TextView title = text("EdgeTunnel 更新器", 22, Color.rgb(13, 71, 161));
        title.setTypeface(null, Typeface.BOLD);
        content.addView(title, margin(0, 0, 0, 2));
        content.addView(text("填 Token 和管理密码，一键把最新 _worker.js 部署到 Cloudflare。", 13, Color.DKGRAY),
                margin(0, 0, 0, 14));

        // ---- 第一步：两个必填项 ----
        LinearLayout card = card();
        card.addView(label("① Cloudflare API Token"), margin(0, 0, 0, 4));
        tokenInput = passwordField("粘贴 API Token");
        tokenInput.setText(SecureStore.readToken(this));
        card.addView(tokenInput, margin(0, 0, 0, 12));

        card.addView(label("② EdgeTunnel 管理密码（ADMIN）"), margin(0, 0, 0, 4));
        adminInput = passwordField("用于登录 /admin 面板");
        card.addView(adminInput, margin(0, 0, 0, 0));
        content.addView(card, margin(0, 0, 0, 14));

        // ---- 主按钮 ----
        updateButton = primaryButton("检测并更新");
        updateButton.setOnClickListener(v -> startFlow(false));
        content.addView(updateButton, margin(0, 0, 0, 6));

        advancedToggle = text("高级设置 ▸", 13, Color.rgb(21, 101, 192));
        advancedToggle.setPadding(dp(6), dp(10), dp(6), dp(10));
        advancedToggle.setOnClickListener(v -> toggleAdvanced());
        content.addView(advancedToggle, margin(0, 0, 0, 4));

        // ---- 高级设置（默认折叠）----
        advancedPanel = card();
        advancedPanel.setVisibility(View.GONE);

        advancedPanel.addView(label("部署目标"), margin(0, 0, 0, 4));
        LinearLayout targetRow = row();
        pagesCheck = new CheckBox(this);
        pagesCheck.setText("Pages");
        pagesCheck.setChecked(true);
        pagesCheck.setTextSize(14);
        workersCheck = new CheckBox(this);
        workersCheck.setText("Workers");
        workersCheck.setChecked(true);
        workersCheck.setTextSize(14);
        targetRow.addView(pagesCheck, new LinearLayout.LayoutParams(0, -2, 1));
        targetRow.addView(workersCheck, new LinearLayout.LayoutParams(0, -2, 1));
        advancedPanel.addView(targetRow, margin(0, 0, 0, 10));

        advancedPanel.addView(label("Account ID（留空自动读取）"), margin(0, 0, 0, 4));
        accountInput = plainField("留空 = 使用 Token 可访问的账户");
        advancedPanel.addView(accountInput, margin(0, 0, 0, 10));

        advancedPanel.addView(label("Pages 项目名（留空自动检测）"), margin(0, 0, 0, 4));
        pagesProjectInput = plainField("例如 edgetunnel");
        advancedPanel.addView(pagesProjectInput, margin(0, 0, 0, 10));

        advancedPanel.addView(label("Workers Script 名（留空自动检测）"), margin(0, 0, 0, 4));
        workerScriptInput = plainField("例如 edgetunnel");
        advancedPanel.addView(workerScriptInput, margin(0, 0, 0, 10));

        advancedPanel.addView(label("KV Namespace（留空自动复用/创建）"), margin(0, 0, 0, 4));
        kvIdInput = plainField("已有 KV ID，可选");
        advancedPanel.addView(kvIdInput, margin(0, 0, 0, 6));
        kvTitleInput = plainField("自动创建时的名称");
        kvTitleInput.setText("EDT-KV");
        advancedPanel.addView(kvTitleInput, margin(0, 0, 0, 10));

        LinearLayout tokenRow = row();
        saveTokenCheck = new CheckBox(this);
        saveTokenCheck.setText("记住 Token（本机加密保存）");
        saveTokenCheck.setTextSize(13);
        saveTokenCheck.setChecked(!tokenInput.getText().toString().trim().isEmpty());
        tokenRow.addView(saveTokenCheck, new LinearLayout.LayoutParams(0, -2, 1));
        clearTokenButton = ghostButton("清除");
        clearTokenButton.setOnClickListener(v -> {
            SecureStore.clearToken(this);
            tokenInput.setText("");
            saveTokenCheck.setChecked(false);
            appendStatus("已清除本机保存的 Token。");
        });
        tokenRow.addView(clearTokenButton, new LinearLayout.LayoutParams(-2, -2));
        advancedPanel.addView(tokenRow, margin(0, 0, 0, 10));

        detectOnlyButton = ghostButton("仅检测部署，不更新");
        detectOnlyButton.setOnClickListener(v -> startFlow(true));
        advancedPanel.addView(detectOnlyButton, margin(0, 0, 0, 0));
        content.addView(advancedPanel, margin(0, 0, 0, 14));

        // ---- 状态日志 ----
        LinearLayout logCard = card();
        statusView = text("", 12, Color.rgb(35, 49, 66));
        statusView.setText("等待操作。\n");
        logCard.addView(statusView, new ViewGroup.LayoutParams(-1, -2));
        content.addView(logCard, new LinearLayout.LayoutParams(-1, -2));

        appendStatus("提示：项目名等留空会自动检测账户里的 EdgeTunnel 部署。");
    }

    private void toggleAdvanced() {
        boolean show = advancedPanel.getVisibility() != View.VISIBLE;
        advancedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        advancedToggle.setText(show ? "高级设置 ▾" : "高级设置 ▸");
    }

    // -------------------------------------------------------- 主流程入口

    /**
     * @param detectOnly true=只检测并显示结果；false=检测后弹确认框再更新
     */
    private void startFlow(boolean detectOnly) {
        final String token = tokenInput.getText().toString().trim();
        final String currentAccount = accountInput.getText().toString().trim();
        if (token.isEmpty()) {
            toast("请先填写 Cloudflare API Token");
            return;
        }
        final boolean pages = pagesCheck.isChecked();
        final boolean workers = workersCheck.isChecked();
        if (!detectOnly && !pages && !workers) {
            toast("至少选择 Pages 或 Workers（高级设置中）");
            return;
        }
        final String admin = adminInput.getText().toString();
        if (!detectOnly && admin.trim().isEmpty()) {
            toast("请填写 EdgeTunnel 管理密码（ADMIN）");
            return;
        }
        rememberTokenIfRequested(token);
        setBusy(true);
        appendStatus("正在连接 Cloudflare 并检测部署……");
        executor.execute(() -> {
            try {
                Detection d = detect(token, currentAccount);
                runOnUiThread(() -> {
                    accountInput.setText(d.accountId);
                    if (pagesProjectInput.getText().toString().trim().isEmpty()
                            && !d.suggestedPages.isEmpty()) {
                        pagesProjectInput.setText(d.suggestedPages);
                    }
                    if (workerScriptInput.getText().toString().trim().isEmpty()
                            && !d.suggestedWorker.isEmpty()) {
                        workerScriptInput.setText(d.suggestedWorker);
                    }
                    appendStatus(d.report);
                    if (detectOnly) {
                        toast("检测完成");
                    } else {
                        confirmUpdate(d);
                    }
                });
            } catch (Exception error) {
                showError(error);
            } finally {
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    /** 后台执行：读账户 → 列资源 → 指纹检测 EdgeTunnel 部署 → 生成建议与报告。 */
    private Detection detect(String token, String presetAccount) throws Exception {
        JSONArray accounts = CloudflareApi.listAccounts(token);
        if (accounts.length() == 0) throw new Exception("Token 没有可访问的账户");
        String accountId = presetAccount;
        if (accountId.isEmpty()) {
            JSONObject first = accounts.optJSONObject(0);
            accountId = first == null ? "" : first.optString("id");
        }
        JSONArray pages = CloudflareApi.listPagesProjects(accountId, token);
        JSONArray workers = CloudflareApi.listWorkerScripts(accountId, token);
        JSONArray kv = CloudflareApi.listKvNamespaces(accountId, token);

        appendStatus("正在识别账户中的 EdgeTunnel 部署……");
        List<String> detectedWorkers = new ArrayList<>();
        for (int i = 0; i < workers.length(); i++) {
            JSONObject item = workers.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("id");
            if (name.isEmpty()) name = item.optString("name");
            if (name.isEmpty()) continue;
            appendStatus("正在检查 Workers 脚本 " + (i + 1) + "/" + workers.length() + "：" + name);
            try {
                if (CloudflareApi.looksLikeEdgeTunnel(
                        CloudflareApi.fetchScriptContent(accountId, name, token))) {
                    detectedWorkers.add(name);
                }
            } catch (Exception ignore) {
                // 单个脚本读取失败不阻断检测
            }
        }
        List<String> detectedPages = new ArrayList<>();
        for (int i = 0; i < pages.length(); i++) {
            JSONObject item = pages.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name");
            if (name.isEmpty()) continue;
            JSONObject source = item.optJSONObject("source");
            JSONObject sourceMeta = source == null ? null : source.optJSONObject("metadata");
            String repo = sourceMeta == null ? "" : sourceMeta.optString("repo");
            if (repo.toLowerCase(Locale.ROOT).contains("edgetunnel")) {
                detectedPages.add(name); // GitHub 连接项目，部署来源即可信特征
                continue;
            }
            String subdomain = item.optString("subdomain");
            if (!subdomain.isEmpty()) {
                try {
                    if (CloudflareApi.probePagesSite(subdomain)) detectedPages.add(name);
                } catch (Exception ignore) {
                    // 在线探测失败不阻断检测
                }
            }
        }

        Detection d = new Detection();
        d.accountId = accountId;
        d.suggestedPages = pickDetected(detectedPages);
        if (d.suggestedPages.isEmpty()) {
            for (int i = 0; i < pages.length(); i++) {
                JSONObject item = pages.optJSONObject(i);
                if (item != null && "edgetunnel".equalsIgnoreCase(item.optString("name"))) {
                    d.suggestedPages = item.optString("name");
                    break;
                }
            }
            if (d.suggestedPages.isEmpty() && pages.length() > 0) {
                JSONObject item = pages.optJSONObject(0);
                if (item != null) d.suggestedPages = item.optString("name");
            }
        }
        d.suggestedWorker = pickDetected(detectedWorkers);
        if (d.suggestedWorker.isEmpty()) {
            for (int i = 0; i < workers.length(); i++) {
                JSONObject item = workers.optJSONObject(i);
                String name = item == null ? "" : item.optString("id");
                if (name.isEmpty() && item != null) name = item.optString("name");
                if ("edgetunnel".equalsIgnoreCase(name)) {
                    d.suggestedWorker = name;
                    break;
                }
            }
            if (d.suggestedWorker.isEmpty() && workers.length() > 0) {
                JSONObject item = workers.optJSONObject(0);
                if (item != null) {
                    d.suggestedWorker = item.optString("id");
                    if (d.suggestedWorker.isEmpty()) d.suggestedWorker = item.optString("name");
                }
            }
        }

        StringBuilder report = new StringBuilder("账户：").append(accountId).append('\n')
                .append("Pages 项目：").append(pages.length())
                .append(" · Workers Script：").append(workers.length())
                .append(" · KV：").append(kv.length());
        if (!detectedPages.isEmpty()) {
            report.append("\n检测到 EdgeTunnel Pages：")
                    .append(TextUtils.join(", ", detectedPages));
        }
        if (!detectedWorkers.isEmpty()) {
            report.append("\n检测到 EdgeTunnel Workers：")
                    .append(TextUtils.join(", ", detectedWorkers));
        }
        if (detectedPages.isEmpty() && detectedWorkers.isEmpty()) {
            report.append("\n未检测到现有 EdgeTunnel 部署；首次部署请在高级设置中填写名称。");
        }
        d.report = report.toString();
        return d;
    }

    /** 检测结果中优先取名为 edgetunnel 的目标，否则取第一个。 */
    private static String pickDetected(List<String> detected) {
        for (String name : detected) {
            if ("edgetunnel".equalsIgnoreCase(name)) return name;
        }
        return detected.isEmpty() ? "" : detected.get(0);
    }

    // ------------------------------------------------------------- 更新

    private void confirmUpdate(Detection d) {
        final String token = tokenInput.getText().toString().trim();
        final boolean pages = pagesCheck.isChecked();
        final boolean workers = workersCheck.isChecked();
        final String project = pagesProjectInput.getText().toString().trim();
        final String script = workerScriptInput.getText().toString().trim();
        final String admin = adminInput.getText().toString();
        final String kvId = kvIdInput.getText().toString().trim();
        final String kvTitle = kvTitleInput.getText().toString().trim();

        if (pages && project.isEmpty()) {
            toast("未找到 Pages 项目：请在高级设置中填写项目名");
            return;
        }
        if (workers && script.isEmpty()) {
            toast("未找到 Workers Script：请在高级设置中填写名称");
            return;
        }

        StringBuilder message = new StringBuilder("将从 cmliu/edgetunnel 下载最新 Worker：");
        if (pages) message.append("\n\nPages → ").append(project);
        if (workers) message.append("\n\nWorkers → ").append(script);
        message.append("\n\nKV → ").append(kvId.isEmpty()
                ? "自动复用/创建（" + (kvTitle.isEmpty() ? "EDT-KV" : kvTitle) + "）" : kvId);
        message.append("\n\n确认更新？");

        new AlertDialog.Builder(this)
                .setTitle("确认更新")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始更新", (dialog, which) -> runUpdate(
                        token, d.accountId, project, script, admin, kvId, kvTitle, pages, workers))
                .show();
    }

    private void runUpdate(String token, String accountId, String project, String script,
                           String admin, String kvId, String kvTitle, boolean pages, boolean workers) {
        setBusy(true);
        appendStatus("开始更新……");
        executor.execute(() -> {
            try {
                appendStatus("正在下载最新 EdgeTunnel _worker.js……");
                CloudflareApi.SourceWorker source = CloudflareApi.downloadLatestWorker();
                appendStatus("源码版本：" + source.version + "，大小：" + source.script.length + " bytes");

                appendStatus("正在准备 KV 命名空间……");
                CloudflareApi.KvNamespace namespace = CloudflareApi.ensureKvNamespace(
                        accountId, token, kvId, kvTitle);
                appendStatus("KV：" + namespace.title + "（" + namespace.id + "）");

                // 兼容性保留：沿用现有脚本的 compatibility_date/flags，读不到才用当天日期
                String compatDate = "";
                JSONArray compatFlags = null;
                JSONObject settings = CloudflareApi.getWorkerSettings(accountId, script, token);
                if (settings != null && !settings.optString("compatibility_date").isEmpty()) {
                    compatDate = settings.optString("compatibility_date");
                    compatFlags = settings.optJSONArray("compatibility_flags");
                    appendStatus("沿用现有 compatibility_date：" + compatDate);
                } else {
                    compatDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                    appendStatus("未读到现有脚本设置，使用当天 compatibility_date：" + compatDate);
                }

                if (workers) {
                    appendStatus("正在更新 Workers Script：" + script + "……");
                    CloudflareApi.deployWorker(accountId, script, token, source.script,
                            namespace.id, admin, compatDate, compatFlags);
                    appendStatus("Workers 更新请求已成功提交。");
                }

                if (pages) {
                    appendStatus("正在检查/创建 Pages 项目：" + project + "……");
                    CloudflareApi.ensurePagesProject(accountId, project, token);
                    appendStatus("正在设置 Pages 的 ADMIN 和 KV 绑定……");
                    CloudflareApi.configurePages(accountId, project, token, namespace.id, admin);
                    appendStatus("正在部署 Pages Worker……");
                    JSONObject deployment = CloudflareApi.deployPages(accountId, project, token,
                            source.script, namespace.id, compatDate, compatFlags);
                    String deploymentId = deployment.optString("id");
                    String submittedUrl = deployment.optString("url");
                    if (!submittedUrl.isEmpty()) appendStatus("Pages 部署地址：" + submittedUrl);
                    String finalState = CloudflareApi.waitForPagesDeployment(accountId, project,
                            deploymentId, token, 30, 2000L);
                    appendStatus("Pages 部署完成：" + finalState);
                }

                appendStatus("全部更新完成。请打开对应域名的 /admin 验证 ADMIN 密码和 KV 功能。");
                runOnUiThread(() -> toast("EdgeTunnel 更新完成"));
            } catch (Exception error) {
                showError(error);
            } finally {
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    // ----------------------------------------------------------- 通用逻辑

    private void rememberTokenIfRequested(String token) {
        if (!saveTokenCheck.isChecked()) return;
        try {
            SecureStore.saveToken(this, token);
        } catch (Exception error) {
            appendStatus("Token 本地加密保存失败，但本次请求仍会继续：" + error.getMessage());
        }
    }

    private void setBusy(boolean busy) {
        runOnUiThread(() -> {
            updateButton.setEnabled(!busy);
            updateButton.setText(busy ? "处理中…" : "检测并更新");
            if (detectOnlyButton != null) detectOnlyButton.setEnabled(!busy);
            if (clearTokenButton != null) clearTokenButton.setEnabled(!busy);
        });
    }

    private void showError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        final String display = message;
        runOnUiThread(() -> {
            appendStatus("失败：" + display);
            Toast.makeText(this, display, Toast.LENGTH_LONG).show();
        });
    }

    private void appendStatus(String message) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String old = statusView == null ? "" : statusView.getText().toString();
            if (old.equals("等待操作。\n") || old.equals("等待操作。")) old = "";
            if (old.endsWith("\n")) old = old.substring(0, old.length() - 1);
            if (statusView != null) {
                statusView.setText((old.isEmpty() ? "" : old + "\n") + "[" + time + "] " + message);
            }
        });
    }

    // ----------------------------------------------------------- 控件工厂

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        return card;
    }

    private TextView label(String value) {
        TextView view = text(value, 13, Color.rgb(70, 84, 100));
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private EditText passwordField(String hint) {
        EditText edit = plainField(hint);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return edit;
    }

    private EditText plainField(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextSize(15);
        edit.setSingleLine(true);
        edit.setBackground(roundStroke());
        edit.setPadding(dp(10), dp(10), dp(10), dp(10));
        return edit;
    }

    private GradientDrawable roundStroke() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(247, 249, 252));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.rgb(215, 224, 235));
        return bg;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(21, 101, 192));
        bg.setCornerRadius(dp(12));
        button.setBackground(bg);
        int v = dp(12);
        button.setPadding(0, v, 0, v);
        return button;
    }

    private Button ghostButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(Color.rgb(21, 101, 192));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.rgb(21, 101, 192));
        button.setBackground(bg);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
