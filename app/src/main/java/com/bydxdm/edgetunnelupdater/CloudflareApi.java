package com.bydxdm.edgetunnelupdater;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Small Cloudflare v4 API client used by the Android app. */
public final class CloudflareApi {
    public static final String API = "https://api.cloudflare.com/client/v4";
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    public static final String EDGE_TUNNEL_ZIP =
            "https://github.com/cmliu/edgetunnel/archive/refs/heads/main.zip";
    private static final String USER_AGENT = "EdgeTunnel-Updater/1.0 (Android)";

    private CloudflareApi() {
    }

    public static final class CloudflareException extends Exception {
        public final int httpCode;
        public final String responseBody;

        CloudflareException(String message, int httpCode, String responseBody) {
            super(message);
            this.httpCode = httpCode;
            this.responseBody = responseBody;
        }
    }

    public static final class KvNamespace {
        public final String id;
        public final String title;

        KvNamespace(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    public static final class SourceWorker {
        public final byte[] script;
        public final String version;

        SourceWorker(byte[] script, String version) {
            this.script = script;
            this.version = version;
        }
    }

    public static JSONArray listAccounts(String token) throws Exception {
        JSONObject response = requestJson("GET", "/accounts?page=1&per_page=100", token, null);
        return response.optJSONArray("result") == null
                ? new JSONArray() : response.optJSONArray("result");
    }

    public static JSONArray listKvNamespaces(String accountId, String token) throws Exception {
        JSONObject response = requestJson("GET", "/accounts/" + path(accountId)
                + "/storage/kv/namespaces?per_page=100", token, null);
        return response.optJSONArray("result") == null
                ? new JSONArray() : response.optJSONArray("result");
    }

    /** Uses an existing ID, reuses a same-title namespace, or creates one. */
    public static KvNamespace ensureKvNamespace(String accountId, String token,
                                                String namespaceId, String title) throws Exception {
        if (namespaceId != null && !namespaceId.trim().isEmpty()) {
            return new KvNamespace(namespaceId.trim(), title == null ? "KV" : title.trim());
        }
        String wantedTitle = title == null || title.trim().isEmpty()
                ? "EDT-KV" : title.trim();
        JSONArray namespaces = listKvNamespaces(accountId, token);
        for (int i = 0; i < namespaces.length(); i++) {
            JSONObject item = namespaces.optJSONObject(i);
            if (item != null && wantedTitle.equalsIgnoreCase(item.optString("title"))) {
                return new KvNamespace(item.optString("id"), item.optString("title"));
            }
        }
        JSONObject body = new JSONObject().put("title", wantedTitle);
        JSONObject response = requestJson("POST", "/accounts/" + path(accountId)
                + "/storage/kv/namespaces", token, body.toString());
        JSONObject result = response.optJSONObject("result");
        if (result == null || result.optString("id").isEmpty()) {
            throw new CloudflareException("KV 命名空间创建成功但没有返回 ID", 200,
                    response.toString());
        }
        return new KvNamespace(result.optString("id"), wantedTitle);
    }

    public static JSONArray listPagesProjects(String accountId, String token) throws Exception {
        JSONObject response = requestJson("GET", "/accounts/" + path(accountId)
                + "/pages/projects", token, null);
        return response.optJSONArray("result") == null
                ? new JSONArray() : response.optJSONArray("result");
    }

    public static JSONArray listWorkerScripts(String accountId, String token) throws Exception {
        JSONObject response = requestJson("GET", "/accounts/" + path(accountId)
                + "/workers/scripts", token, null);
        return response.optJSONArray("result") == null
                ? new JSONArray() : response.optJSONArray("result");
    }

    public static JSONObject getPagesProject(String accountId, String projectName,
                                             String token) throws Exception {
        JSONObject response = requestJson("GET", "/accounts/" + path(accountId)
                + "/pages/projects/" + path(projectName), token, null);
        return response.optJSONObject("result") == null
                ? response : response.optJSONObject("result");
    }

    public static JSONObject ensurePagesProject(String accountId, String projectName,
                                                String token) throws Exception {
        try {
            return getPagesProject(accountId, projectName, token);
        } catch (CloudflareException error) {
            if (error.httpCode != 404) throw error;
            JSONObject body = new JSONObject()
                    .put("name", projectName)
                    .put("production_branch", "main");
            JSONObject response = requestJson("POST", "/accounts/" + path(accountId)
                    + "/pages/projects", token, body.toString());
            JSONObject result = response.optJSONObject("result");
            return result == null ? response : result;
        }
    }

    /** Updates only EdgeTunnel's Pages variables, leaving other project settings untouched. */
    public static void configurePages(String accountId, String projectName, String token,
                                      String kvId, String adminPassword) throws Exception {
        JSONObject deploymentConfigs = new JSONObject();
        JSONObject admin = new JSONObject().put("type", "secret_text").put("value", adminPassword);
        JSONObject envVars = new JSONObject().put("ADMIN", admin);
        JSONObject kvNamespaces = new JSONObject().put("KV", new JSONObject().put("namespace_id", kvId));
        JSONObject config = new JSONObject().put("env_vars", envVars)
                .put("kv_namespaces", kvNamespaces);
        deploymentConfigs.put("preview", new JSONObject(config.toString()));
        deploymentConfigs.put("production", new JSONObject(config.toString()));

        JSONObject body = new JSONObject().put("deployment_configs", deploymentConfigs);
        requestJson("PATCH", "/accounts/" + path(accountId) + "/pages/projects/"
                + path(projectName), token, body.toString());
    }

    /** Deploys an advanced-mode Pages Worker bundle from the upstream _worker.js. */
    public static JSONObject deployPages(String accountId, String projectName, String token,
                                         byte[] script, String kvId) throws Exception {
        String nestedBoundary = "----EdgeTunnelWorker" + UUID.randomUUID().toString().replace("-", "");
        byte[] workerBundle = buildWorkerBundle(script, kvId, nestedBoundary, false);
        String outerBoundary = "----EdgeTunnelDeploy" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        addTextPart(body, outerBoundary, "manifest", "{}", "application/json");
        addFilePart(body, outerBoundary, "_worker.bundle", "_worker.bundle",
                workerBundle, "application/octet-stream");
        closeMultipart(body, outerBoundary);
        JSONObject response = requestJsonBytes("POST", "/accounts/" + path(accountId)
                + "/pages/projects/" + path(projectName) + "/deployments", token,
                body.toByteArray(), "multipart/form-data; boundary=" + outerBoundary);
        return response.optJSONObject("result") == null ? response : response.optJSONObject("result");
    }

    /** Uploads the module Worker, preserving unrelated existing bindings. */
    public static void deployWorker(String accountId, String scriptName, String token,
                                    byte[] script, String kvId, String adminPassword) throws Exception {
        String boundary = "----EdgeTunnelWorker" + UUID.randomUUID().toString().replace("-", "");
        byte[] form = buildWorkerBundle(script, kvId, boundary, true);
        requestJsonBytes("PUT", "/accounts/" + path(accountId) + "/workers/scripts/"
                + path(scriptName), token, form, "multipart/form-data; boundary=" + boundary);
        JSONObject secret = new JSONObject().put("name", "ADMIN")
                .put("text", adminPassword).put("type", "secret_text");
        requestJson("PUT", "/accounts/" + path(accountId) + "/workers/scripts/"
                + path(scriptName) + "/secrets", token, secret.toString());
    }

    /** Waits until a Pages deployment reaches a terminal state. */
    public static String waitForPagesDeployment(String accountId, String projectName,
                                                String deploymentId, String token,
                                                int attempts, long delayMillis) throws Exception {
        if (deploymentId == null || deploymentId.trim().isEmpty()) return "已提交部署";
        String last = "处理中";
        for (int i = 0; i < attempts; i++) {
            JSONObject deployment = getPagesDeployment(accountId, projectName, deploymentId, token);
            JSONObject stage = deployment.optJSONObject("latest_stage");
            String status = stage == null ? "" : stage.optString("status");
            String name = stage == null ? "" : stage.optString("name");
            if (!status.isEmpty()) last = name + "/" + status;
            if ("success".equalsIgnoreCase(status)) return deployment.optString("url", last);
            if ("failure".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
                throw new CloudflareException("Pages 部署失败：" + last, 200, deployment.toString());
            }
            Thread.sleep(delayMillis);
        }
        return "已提交，状态：" + last;
    }

    public static JSONObject getPagesDeployment(String accountId, String projectName,
                                                String deploymentId, String token) throws Exception {
        JSONObject response = requestJson("GET", "/accounts/" + path(accountId)
                + "/pages/projects/" + path(projectName) + "/deployments/" + path(deploymentId),
                token, null);
        return response.optJSONObject("result") == null ? response : response.optJSONObject("result");
    }

    /** Downloads the current upstream archive and extracts only its _worker.js. */
    public static SourceWorker downloadLatestWorker() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(EDGE_TUNNEL_ZIP).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("下载 EdgeTunnel 源码失败：HTTP " + status);
            }
            ZipInputStream zip = new ZipInputStream(connection.getInputStream());
            byte[] worker = null;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && (name.equals("_worker.js") || name.endsWith("/_worker.js"))) {
                    worker = readAll(zip);
                    break;
                }
            }
            zip.close();
            if (worker == null || worker.length < 1000) {
                throw new IOException("源码压缩包中没有找到有效的 _worker.js");
            }
            String text = new String(worker, StandardCharsets.UTF_8);
            if (!text.contains("export default") || !text.contains("async fetch")) {
                throw new IOException("下载到的 _worker.js 不是可识别的 EdgeTunnel Worker");
            }
            String version = "未知版本";
            int marker = text.indexOf("const Version");
            if (marker >= 0) {
                int firstQuote = text.indexOf('"', marker);
                if (firstQuote < 0) firstQuote = text.indexOf('\'', marker);
                if (firstQuote >= 0) {
                    char quote = text.charAt(firstQuote);
                    int endQuote = text.indexOf(quote, firstQuote + 1);
                    if (endQuote > firstQuote) version = text.substring(firstQuote + 1, endQuote);
                }
            }
            return new SourceWorker(worker, version);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] buildWorkerBundle(byte[] script, String kvId, String boundary,
                                             boolean preserveExistingBindings)
            throws IOException, JSONException {
        JSONObject metadata = new JSONObject()
                .put("main_module", "_worker.js")
                .put("compatibility_date", "2025-11-04");
        if (preserveExistingBindings) {
            metadata.put("keep_bindings", new JSONArray()
                    .put("plain_text").put("json").put("secret_text")
                    .put("secret_key").put("kv_namespace"));
            JSONArray bindings = new JSONArray().put(new JSONObject()
                    .put("name", "KV").put("type", "kv_namespace").put("namespace_id", kvId));
            metadata.put("bindings", bindings);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        addTextPart(body, boundary, "metadata", metadata.toString(), "application/json");
        addFilePart(body, boundary, "_worker.js", "_worker.js", script,
                "application/javascript+module");
        closeMultipart(body, boundary);
        return body.toByteArray();
    }

    private static JSONObject requestJson(String method, String path, String token, String body)
            throws Exception {
        byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return requestJsonBytes(method, path, token, bytes, "application/json; charset=utf-8");
    }

    private static JSONObject requestJsonBytes(String method, String path, String token, byte[] body,
                                               String contentType) throws Exception {
        RequestBody requestBody = body == null ? null
                : RequestBody.create(MediaType.parse(contentType), body);
        Request request = new Request.Builder()
                .url(API + path)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .method(method, requestBody)
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            int status = response.code();
            String text = response.body() == null ? "" : response.body().string();
            JSONObject parsed;
            try {
                parsed = text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException parseError) {
                throw new CloudflareException("Cloudflare 返回了无法解析的响应（HTTP " + status + ")",
                        status, text);
            }
            if (status < 200 || status >= 300 || !parsed.optBoolean("success", true)) {
                throw new CloudflareException(apiErrorMessage(parsed, status), status, text);
            }
            return parsed;
        }
    }

    private static String apiErrorMessage(JSONObject response, int status) {
        StringBuilder message = new StringBuilder("Cloudflare API 请求失败（HTTP ")
                .append(status).append(")");
        JSONArray errors = response.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            JSONObject error = errors.optJSONObject(0);
            if (error != null && !error.optString("message").isEmpty()) {
                message.append("：").append(error.optString("message"));
            }
        }
        return message.toString();
    }

    private static String path(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) throw new IOException("缺少 Cloudflare 路径参数");
        try {
            return URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20");
        } catch (Exception error) {
            throw new IOException(error);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static void addTextPart(ByteArrayOutputStream output, String boundary, String name,
                                    String value, String contentType) throws IOException {
        write(output, "--" + boundary + "\r\n");
        write(output, "Content-Disposition: form-data; name=\"" + name + "\"\r\n");
        write(output, "Content-Type: " + contentType + "\r\n\r\n");
        write(output, value);
        write(output, "\r\n");
    }

    private static void addFilePart(ByteArrayOutputStream output, String boundary, String name,
                                    String fileName, byte[] value, String contentType) throws IOException {
        write(output, "--" + boundary + "\r\n");
        write(output, "Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + fileName + "\"\r\n");
        write(output, "Content-Type: " + contentType + "\r\n\r\n");
        output.write(value);
        write(output, "\r\n");
    }

    private static void closeMultipart(ByteArrayOutputStream output, String boundary) throws IOException {
        write(output, "--" + boundary + "--\r\n");
    }

    private static void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }
}
