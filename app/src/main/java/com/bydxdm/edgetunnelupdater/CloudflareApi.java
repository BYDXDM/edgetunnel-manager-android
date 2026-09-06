package com.bydxdm.edgetunnelupdater;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
    /** 上游源码多镜像源，按优先级依次回退；全部失败时汇总各源错误。 */
    public static final String[] SOURCE_URLS = {
            "https://codeload.github.com/cmliu/edgetunnel/zip/refs/heads/main",
            "https://codeload.github.com/cmliu/edgetunnel/zip/refs/heads/master",
            "https://github.com/cmliu/edgetunnel/archive/refs/heads/main.zip",
            "https://raw.githubusercontent.com/cmliu/edgetunnel/main/_worker.js",
            "https://cdn.jsdelivr.net/gh/cmliu/edgetunnel@main/_worker.js",
    };
    private static final Set<String> TRUSTED_SOURCE_HOSTS = new HashSet<>(Arrays.asList(
            "codeload.github.com", "github.com", "raw.githubusercontent.com", "cdn.jsdelivr.net"));
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
                                         byte[] script, String kvId, String compatDate,
                                         org.json.JSONArray compatFlags) throws Exception {
        String nestedBoundary = "----EdgeTunnelWorker" + UUID.randomUUID().toString().replace("-", "");
        byte[] workerBundle = buildWorkerBundle(script, kvId, nestedBoundary, false,
                compatDate, compatFlags);
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
                                    byte[] script, String kvId, String adminPassword,
                                    String compatDate, org.json.JSONArray compatFlags) throws Exception {
        String boundary = "----EdgeTunnelWorker" + UUID.randomUUID().toString().replace("-", "");
        byte[] form = buildWorkerBundle(script, kvId, boundary, true, compatDate, compatFlags);
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

    /** 读取现有 Workers 脚本的兼容性设置（compatibility_date / flags）；读取失败返回 null。 */
    public static JSONObject getWorkerSettings(String accountId, String scriptName,
                                               String token) {
        try {
            JSONObject response = requestJson("GET", "/accounts/" + path(accountId)
                    + "/workers/scripts/" + path(scriptName) + "/settings", token, null);
            return response.optJSONObject("result");
        } catch (Exception error) {
            return null;
        }
    }

    /** 读取 Workers 脚本源码原文（该端点不返回 v4 信封）。 */
    public static String fetchScriptContent(String accountId, String scriptName,
                                            String token) throws Exception {
        Request request = new Request.Builder()
                .url(API + "/accounts/" + path(accountId) + "/workers/scripts/" + path(scriptName))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", USER_AGENT)
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (response.code() != 200) {
                throw new CloudflareException("读取脚本失败（HTTP " + response.code() + "）",
                        response.code(), text);
            }
            return text;
        }
    }

    /** 在线探测 pages.dev 站点是否运行 EdgeTunnel：依次检查 /login 与 / 的页面特征。 */
    public static boolean probePagesSite(String baseUrl) throws Exception {
        String normalized = baseUrl.trim();
        if (!normalized.startsWith("http")) normalized = "https://" + normalized;
        URL url = new URL(normalized);
        if (!"https".equalsIgnoreCase(url.getProtocol())) return false;
        String host = url.getHost();
        if (host == null || !(host.equals("pages.dev") || host.endsWith(".pages.dev"))) return false;
        assertPublicHttpsHost(url, null);
            for (String pathName : new String[]{"/login", "/"}) {
                HttpURLConnection connection = null;
                try {
                    connection = openGet(new URL("https", host, url.getPort(), pathName));
                    int status = connection.getResponseCode();
                    // 不限定状态码：未设 ADMIN 的部署会以 404 返回同样带署名的页面
                    InputStream stream = status >= 200 && status < 400
                            ? connection.getInputStream() : connection.getErrorStream();
                    if (stream != null) {
                        String body = new String(readAll(stream), StandardCharsets.UTF_8);
                        if (looksLikeEdgeTunnel(body)) return true;
                    }
                } catch (IOException ignore) {
                    // 单个路径探测失败不影响整体结论
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
            return false;
    }

    /** EdgeTunnel 内容特征：兼容老版本明文署名与新版本混淆后的中文变量名。 */
    public static boolean looksLikeEdgeTunnel(String text) {
        if (text == null || text.isEmpty()) return false;
        String low = text.toLowerCase(Locale.ROOT);
        if (low.contains("edgetunnel")) return true; // 老版本明文署名 / 面板页脚
        if (low.contains("edt-pages.github.io")) return true; // 新版静态面板地址
        if (text.contains("Pages静态页面") && text.contains("特征码字典")) return true;
        if (text.contains("SOCKS5白名单") && text.contains("特征码字典")) return true;
        return false;
    }

    /** 仅允许 https、白名单主机（allowedHosts 为 null 表示不限制主机名），且解析结果不得为内网/保留地址。 */
    private static void assertPublicHttpsHost(URL url, Set<String> allowedHosts) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("仅允许 https 请求：" + url);
        }
        String host = url.getHost();
        if (host == null || host.isEmpty()) throw new IOException("URL 缺少主机名");
        if (allowedHosts != null && !allowedHosts.contains(host)) {
            throw new IOException("主机不在白名单内：" + host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException error) {
            throw new IOException("域名解析失败：" + host);
        }
        for (InetAddress addr : addresses) {
            boolean reserved = addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                    || addr.isMulticastAddress();
            if (addr instanceof Inet4Address) {
                int o0 = addr.getAddress()[0] & 0xFF;
                int o1 = addr.getAddress()[1] & 0xFF;
                if (o0 == 0 || o0 == 100 && o1 >= 64 && o1 <= 127 || o0 >= 240
                        || o0 == 198 && o1 >= 18 && o1 <= 19) {
                    reserved = true; // 0/8、CGNAT、广播段、基准测试段
                }
            }
            if (addr instanceof Inet6Address) {
                byte[] bytes = addr.getAddress();
                if (bytes.length > 0 && (bytes[0] & 0xFE) == 0xFC) {
                    reserved = true; // fc00::/7 唯一本地地址
                }
            }
            if (reserved) {
                throw new IOException("主机解析到内网/保留地址，已拒绝：" + host);
            }
        }
    }

    /** 依次尝试多镜像源下载 _worker.js，任一源成功且通过特征校验即返回。 */
    public static SourceWorker downloadLatestWorker() throws Exception {
        StringBuilder attempts = new StringBuilder();
        for (String spec : SOURCE_URLS) {
            try {
                URL url = new URL(spec);
                assertPublicHttpsHost(url, TRUSTED_SOURCE_HOSTS);
                byte[] worker = spec.endsWith(".zip") || spec.contains("/zip/")
                        ? fetchZipWorker(url) : fetchRawWorker(url);
                String text = new String(worker, StandardCharsets.UTF_8);
                if (!looksLikeEdgeTunnel(text)) {
                    throw new IOException("内容未通过 EdgeTunnel 特征校验");
                }
                return new SourceWorker(worker, extractVersion(text));
            } catch (Exception error) {
                if (attempts.length() > 0) attempts.append("\n");
                attempts.append(spec).append("：").append(error.getMessage());
            }
        }
        throw new IOException("所有源码源均不可用：\n" + attempts);
    }

    private static byte[] fetchZipWorker(URL url) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openGet(url);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            byte[] worker = null;
            try (ZipInputStream zip = new ZipInputStream(connection.getInputStream())) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    if (!entry.isDirectory()
                            && (name.equals("_worker.js") || name.endsWith("/_worker.js"))) {
                        worker = readAll(zip);
                        break;
                    }
                }
            }
            if (worker == null || worker.length < 1000) {
                throw new IOException("源码压缩包中没有找到有效的 _worker.js");
            }
            return worker;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] fetchRawWorker(URL url) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openGet(url);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            byte[] worker = readAll(connection.getInputStream());
            if (worker.length < 1000) {
                throw new IOException("_worker.js 内容过短");
            }
            return worker;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection openGet(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private static String extractVersion(String text) {
        int marker = text.indexOf("const Version");
        if (marker >= 0) {
            int firstQuote = text.indexOf('"', marker);
            if (firstQuote < 0) firstQuote = text.indexOf('\'', marker);
            if (firstQuote >= 0) {
                char quote = text.charAt(firstQuote);
                int endQuote = text.indexOf(quote, firstQuote + 1);
                if (endQuote > firstQuote) return text.substring(firstQuote + 1, endQuote);
            }
        }
        return "未知版本";
    }

    private static byte[] buildWorkerBundle(byte[] script, String kvId, String boundary,
                                             boolean preserveExistingBindings,
                                             String compatDate, org.json.JSONArray compatFlags)
            throws IOException, JSONException {
        // 沿用现有脚本的兼容性设置；无法读取时才落到当天日期，避免覆盖用户配置。
        String resolvedDate = compatDate == null || compatDate.trim().isEmpty()
                ? new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date())
                : compatDate.trim();
        JSONObject metadata = new JSONObject()
                .put("main_module", "_worker.js")
                .put("compatibility_date", resolvedDate);
        if (compatFlags != null && compatFlags.length() > 0) {
            metadata.put("compatibility_flags", compatFlags);
        }
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
