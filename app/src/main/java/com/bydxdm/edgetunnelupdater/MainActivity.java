package com.bydxdm.edgetunnelupdater;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app deliberately talks directly to Cloudflare. No token, password, or
 * deployment data is sent to an intermediary service.
 */
public class MainActivity extends Activity {
    private EditText tokenInput;
    private EditText accountInput;
    private EditText pagesProjectInput;
    private EditText workerScriptInput;
    private EditText adminInput;
    private EditText kvIdInput;
    private EditText kvTitleInput;
    private CheckBox pagesCheck;
    private CheckBox workersCheck;
    private CheckBox saveTokenCheck;
    private CheckBox createKvCheck;
    private Button checkButton;
    private Button updateButton;
    private Button clearTokenButton;
    private ProgressBar progressBar;
    private TextView statusView;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        buildUi();
    }

    @Override
    protected void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(12), pad, dp(8));
        root.setBackgroundColor(Color.rgb(245, 248, 252));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView title = text("EdgeTunnel 更新器", 24, Color.rgb(13, 71, 161));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title, margin(0, 4, 0, 2));
        content.addView(text("从 cmliu/edgetunnel 下载最新 _worker.js，直接部署到 Cloudflare Pages / Workers。", 14,
                Color.DKGRAY), margin(0, 0, 0, 12));

        TextView warning = text("Token 仅在本机 Android Keystore 中加密保存；更新前会显示确认框。请只使用你自己账号的 Cloudflare API Token。", 13,
                Color.rgb(92, 64, 12));
        warning.setBackgroundColor(Color.rgb(255, 243, 205));
        warning.setPadding(dp(10), dp(8), dp(10), dp(8));
        content.addView(warning, margin(0, 0, 0, 14));

        tokenInput = field("Cloudflare API Token", true);
        tokenInput.setText(SecureStore.readToken(this));
        content.addView(tokenInput);
        content.addView(text("建议权限：Account Read、Workers Scripts Edit、Workers KV Storage Edit、Pages Edit。", 12,
                Color.GRAY), margin(0, 3, 0, 8));

        LinearLayout tokenButtons = row();
        saveTokenCheck = new CheckBox(this);
        saveTokenCheck.setText("保存 Token（Keystore）");
        saveTokenCheck.setTextSize(13);
        saveTokenCheck.setChecked(!tokenInput.getText().toString().trim().isEmpty());
        tokenButtons.addView(saveTokenCheck, new LinearLayout.LayoutParams(0, -2, 1));
        clearTokenButton = button("清除已保存");
        tokenButtons.addView(clearTokenButton, new LinearLayout.LayoutParams(-2, -2));
        content.addView(tokenButtons, margin(0, 0, 0, 12));
        clearTokenButton.setOnClickListener(v -> {
            SecureStore.clearToken(this);
            tokenInput.setText("");
            saveTokenCheck.setChecked(false);
            appendStatus("已清除本机保存的 Token。");
        });

        accountInput = field("Cloudflare Account ID（可点读取账户自动填入）", false);
        content.addView(accountInput);
        checkButton = button("读取账户并检查 API");
        content.addView(checkButton, margin(0, 6, 0, 10));
        checkButton.setOnClickListener(v -> checkApi());

        content.addView(section("部署目标"));
        LinearLayout targetRow = row();
        pagesCheck = new CheckBox(this);
        pagesCheck.setText("Pages");
        pagesCheck.setChecked(true);
        workersCheck = new CheckBox(this);
        workersCheck.setText("Workers");
        workersCheck.setChecked(true);
        targetRow.addView(pagesCheck, new LinearLayout.LayoutParams(0, -2, 1));
        targetRow.addView(workersCheck, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(targetRow);

        pagesProjectInput = field("Pages 项目名称（例如 edgetunnel）", false);
        content.addView(pagesProjectInput);
        workerScriptInput = field("Workers Script 名称（例如 edgetunnel）", false);
        content.addView(workerScriptInput, margin(0, 6, 0, 0));

        adminInput = field("EdgeTunnel ADMIN 管理密码（必填）", true);
        content.addView(adminInput, margin(0, 12, 0, 0));

        content.addView(section("KV 命名空间"));
        kvIdInput = field("已有 KV Namespace ID（可留空自动复用/创建）", false);
        content.addView(kvIdInput);
        kvTitleInput = field("自动复用/创建时的 KV 名称", false);
        kvTitleInput.setText("EDT-KV");
        content.addView(kvTitleInput, margin(0, 6, 0, 0));
        createKvCheck = new CheckBox(this);
        createKvCheck.setText("KV ID 为空时自动复用同名或创建（推荐）");
        createKvCheck.setChecked(true);
        createKvCheck.setTextSize(13);
        content.addView(createKvCheck, margin(0, 2, 0, 12));

        TextView source = text("源码来源：github.com/cmliu/edgetunnel（每次更新都重新下载 main.zip，只提取 _worker.js）", 12,
                Color.GRAY);
        content.addView(source, margin(0, 0, 0, 12));

        updateButton = button("更新 EdgeTunnel");
        updateButton.setTextSize(16);
        content.addView(updateButton, margin(0, 0, 0, 10));
        updateButton.setOnClickListener(v -> confirmUpdate());

        statusView = text("状态日志\n等待操作。", 13, Color.rgb(35, 49, 66));
        statusView.setGravity(Gravity.TOP | Gravity.START);
        statusView.setPadding(dp(10), dp(10), dp(10), dp(10));
        statusView.setBackgroundColor(Color.WHITE);
        content.addView(statusView, margin(0, 0, 0, 20));

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(-2, -2) {{
            gravity = Gravity.CENTER_HORIZONTAL;
        }});
        setContentView(root);
    }

    private void checkApi() {
        final String token = tokenInput.getText().toString().trim();
        if (token.isEmpty()) {
            toast("请先填写 Cloudflare API Token");
            return;
        }
        rememberTokenIfRequested(token);
        setBusy(true);
        appendStatus("正在读取 Cloudflare 账户……");
        executor.execute(() -> {
            try {
                JSONArray accounts = CloudflareApi.listAccounts(token);
                if (accounts.length() == 0) throw new Exception("Token 没有可访问的账户");
                String currentAccount = accountInput.getText().toString().trim();
                int selected = 0;
                StringBuilder report = new StringBuilder("账户：\n");
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject account = accounts.optJSONObject(i);
                    if (account == null) continue;
                    String id = account.optString("id");
                    String name = account.optString("name");
                    report.append("• ").append(name).append("  ").append(id).append('\n');
                    if (!currentAccount.isEmpty() && currentAccount.equals(id)) selected = i;
                }
                JSONObject first = accounts.optJSONObject(selected);
                if (currentAccount.isEmpty() && first != null) {
                    final String firstId = first.optString("id");
                    runOnUiThread(() -> accountInput.setText(firstId));
                    currentAccount = first == null ? "" : first.optString("id");
                }
                final String accountId = currentAccount.isEmpty() && first != null
                        ? first.optString("id") : currentAccount;
                JSONArray pages = CloudflareApi.listPagesProjects(accountId, token);
                JSONArray workers = CloudflareApi.listWorkerScripts(accountId, token);
                JSONArray kv = CloudflareApi.listKvNamespaces(accountId, token);
                report.append("\nPages 项目：").append(pages.length())
                        .append("\nWorkers Script：").append(workers.length())
                        .append("\nKV 命名空间：").append(kv.length());
                String suggestedPage = "";
                for (int i = 0; i < pages.length(); i++) {
                    JSONObject item = pages.optJSONObject(i);
                    if (item != null && "edgetunnel".equalsIgnoreCase(item.optString("name"))) {
                        suggestedPage = item.optString("name");
                        break;
                    }
                }
                if (suggestedPage.isEmpty() && pages.length() > 0) {
                    JSONObject item = pages.optJSONObject(0);
                    if (item != null) suggestedPage = item.optString("name");
                }
                String suggestedWorker = "";
                for (int i = 0; i < workers.length(); i++) {
                    JSONObject item = workers.optJSONObject(i);
                    String name = item == null ? "" : item.optString("id");
                    if (name.isEmpty() && item != null) name = item.optString("name");
                    if ("edgetunnel".equalsIgnoreCase(name)) {
                        suggestedWorker = name;
                        break;
                    }
                }
                if (suggestedWorker.isEmpty() && workers.length() > 0) {
                    JSONObject item = workers.optJSONObject(0);
                    if (item != null) {
                        suggestedWorker = item.optString("id");
                        if (suggestedWorker.isEmpty()) suggestedWorker = item.optString("name");
                    }
                }
                final String result = report.toString();
                final String pageSuggestion = suggestedPage;
                final String workerSuggestion = suggestedWorker;
                runOnUiThread(() -> {
                    appendStatus(result);
                    if (pagesProjectInput.getText().toString().trim().isEmpty()
                            && !pageSuggestion.isEmpty()) {
                        pagesProjectInput.setText(pageSuggestion);
                    }
                    if (workerScriptInput.getText().toString().trim().isEmpty()
                            && !workerSuggestion.isEmpty()) {
                        workerScriptInput.setText(workerSuggestion);
                    }
                });
            } catch (Exception error) {
                showError(error);
            } finally {
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    private void confirmUpdate() {
        final String token = tokenInput.getText().toString().trim();
        final boolean pages = pagesCheck.isChecked();
        final boolean workers = workersCheck.isChecked();
        final String accountId = accountInput.getText().toString().trim();
        final String project = pagesProjectInput.getText().toString().trim();
        final String script = workerScriptInput.getText().toString().trim();
        final String admin = adminInput.getText().toString();
        final String kvId = kvIdInput.getText().toString().trim();
        final String kvTitle = kvTitleInput.getText().toString().trim();

        if (token.isEmpty()) {
            toast("请填写 Cloudflare API Token");
            return;
        }
        if (!pages && !workers) {
            toast("至少选择 Pages 或 Workers");
            return;
        }
        if (accountId.isEmpty()) {
            toast("请先填写 Account ID，或点击读取账户");
            return;
        }
        if (pages && project.isEmpty()) {
            toast("选择 Pages 后必须填写项目名称");
            return;
        }
        if (workers && script.isEmpty()) {
            toast("选择 Workers 后必须填写 Script 名称");
            return;
        }
        if (admin.trim().isEmpty()) {
            toast("EdgeTunnel ADMIN 密码不能为空");
            return;
        }
        if (kvId.isEmpty() && !createKvCheck.isChecked()) {
            toast("未填写 KV ID 时，请勾选自动复用/创建");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("确认更新")
                .setMessage("将从 cmliu/edgetunnel 下载最新 Worker，并直接修改你选择的 Cloudflare 部署。"
                        + (kvId.isEmpty() ? "\nKV ID 为空时可能创建一个新的 KV 命名空间。" : "")
                        + "\n\n继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> runUpdate(
                        token, accountId, project, script, admin, kvId, kvTitle, pages, workers))
                .show();
    }

    private void runUpdate(String token, String accountId, String project, String script,
                           String admin, String kvId, String kvTitle, boolean pages, boolean workers) {
        rememberTokenIfRequested(token);
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

                if (workers) {
                    appendStatus("正在更新 Workers Script：" + script + "……");
                    CloudflareApi.deployWorker(accountId, script, token, source.script,
                            namespace.id, admin);
                    appendStatus("Workers 更新请求已成功提交。");
                }

                if (pages) {
                    appendStatus("正在检查/创建 Pages 项目：" + project + "……");
                    CloudflareApi.ensurePagesProject(accountId, project, token);
                    appendStatus("正在设置 Pages 的 ADMIN 和 KV 绑定……");
                    CloudflareApi.configurePages(accountId, project, token, namespace.id, admin);
                    appendStatus("正在部署 Pages Worker……");
                    JSONObject deployment = CloudflareApi.deployPages(accountId, project, token,
                            source.script, namespace.id);
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
            progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
            checkButton.setEnabled(!busy);
            updateButton.setEnabled(!busy);
            clearTokenButton.setEnabled(!busy);
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
            if (old.startsWith("状态日志\n")) old = old.substring("状态日志\n".length());
            if (statusView != null) {
                statusView.setText("状态日志\n" + old + "\n[" + time + "] " + message);
            }
        });
    }

    private TextView section(String value) {
        TextView view = text(value, 17, Color.rgb(13, 71, 161));
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private EditText field(String hint, boolean password) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextSize(15);
        edit.setSingleLine(true);
        edit.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (password) edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return edit;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
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
