# EdgeTunnel 更新器（Android APK）

一个直接在 Android 手机上更新 [cmliu/edgetunnel](https://github.com/cmliu/edgetunnel) 的小工具。

## 功能

- 多镜像源下载最新 `_worker.js`（codeload → github archive → raw → jsdelivr 自动回退），并按内容特征校验
- 自动检测账户中的 EdgeTunnel 部署（Workers 内容指纹、Pages 部署来源与在线页面特征），自动回填目标
- 更新 Cloudflare Workers Script（沿用现有 `compatibility_date` / `compatibility_flags`，不覆盖兼容性配置）
- 更新 Cloudflare Pages 项目（Advanced Mode Worker）
- Pages / Workers 可单独选择，也可以同时更新
- 自动复用同名 KV Namespace，或按确认后创建新的 KV Namespace
- 自动配置 EdgeTunnel 所需的 `KV` 绑定和 `ADMIN` 密码
- Pages 部署完成后轮询部署状态
- Cloudflare API Token 使用 Android Keystore + AES-GCM 加密保存在本机，不经过本项目服务器

## 使用方法

1. 在 Cloudflare 创建 **API Token**，不要使用 Global API Key。
2. 至少授予目标账号以下权限：
   - Account: Read
   - Workers Scripts: Edit
   - Workers KV Storage: Edit
   - Pages: Edit
3. 打开 APK，填写 Token，点击“读取账户并检查 API”。
4. 填写 Account ID、Pages 项目名或 Workers Script 名称。
5. 填写 EdgeTunnel 的 `ADMIN` 管理密码。
6. 已有 KV 可填 Namespace ID；留空并勾选自动创建时，程序会复用同名 `EDT-KV`，找不到才创建。
7. 点击“更新 EdgeTunnel”，确认后执行部署。

## 安全说明

- Token 只在手机端直接请求 `api.cloudflare.com`；上游源码通过 GitHub HTTPS 下载。
- 不要把 Token 截图、粘贴到 Issue、聊天群或 GitHub 仓库。
- 默认使用上游项目最新源码；上游项目按 GPL-2.0 发布。本项目只实现更新工具，不替代或重新发布上游项目。
- 本工具只应操作你本人有权限管理的 Cloudflare 账号、项目和脚本。请遵守 Cloudflare 条款、上游项目许可证及所在地法律法规。

## 编译 APK

GitHub Actions 会在 push 到 `main` 后自动编译并创建 Release。也可以在 Actions 页面手动运行 `Build APK`。

本项目使用 Java + Android SDK，网络层使用 OkHttp 处理 Cloudflare API 的 PATCH 和 multipart 请求。APK 产物位于：

```text
app/build/outputs/apk/release/app-release.apk
```

## API 实现依据

- [Cloudflare Workers Script Upload API](https://developers.cloudflare.com/api/resources/workers/subresources/scripts/methods/update)
- [Cloudflare Pages Direct Upload API](https://developers.cloudflare.com/api/resources/pages/subresources/projects/subresources/deployments/methods/create)
- [Cloudflare Pages REST API](https://developers.cloudflare.com/pages/configuration/api)
- [Cloudflare KV Namespace API](https://developers.cloudflare.com/api/resources/kv/subresources/namespaces/methods/create)
