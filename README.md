# 见屿 · Android

见屿是一款浅色海雾画廊风格的安卓生图应用，支持文生图/图改图创作、灵感频道（实时创意广场 + 5,400+ 提示词画廊分类检索与一键做同款）、双列作品库管理以及应用内检查更新。概念设计位于 `design/jianyu-redesign-concept.jpg`。

## 用浏览器构建 APK

1. 在 GitHub 新建一个自己有写入权限的仓库。可以选择私有仓库。
2. 打开上传入口 **Add file → Upload files**。从电脑打开 `D:\Jianyu`，将文件夹内的源码拖入页面。也可以先解压交付的源码 ZIP，再拖入解压后的内容。提交后，仓库首页应能看到 `app/`、`build.gradle` 和 `settings.gradle`。
3. 确认仓库内存在 `.github/workflows/android.yml`，然后在 **Actions** 打开 **Build Android APK**。上传提交会触发构建。如果 GitHub 提示启用 Actions，启用后可在工作流页面点 **Run workflow** 手动启动。
4. 打开显示绿色对勾的那次运行，在底部 **Artifacts** 中下载 **Jianyu-debug-apk**。解压下载文件，将 `app-debug.apk` 传到安卓手机安装。支持 Android 8.0 及以上。

如果网页上传忽略了 `.github` 文件夹，在仓库首页点 **Add file → Create new file**。文件名填 `.github/workflows/android.yml`，用记事本打开项目内同名文件，将全部内容粘贴进去并提交。工作流会在该次提交后自动启动。

工作流在 GitHub 托管的 Ubuntu 环境运行，使用 Java 17、Gradle 8.9、Android Gradle Plugin 8.7.3 和 Android SDK 35，并使用仓库内固定签名 `debug.keystore` 打包，支持应用内覆盖升级而不丢失本地作品库。

## 应用内检查更新与发版

- **应用内更新**：App 启动时静默检查更新，也可在创作页右上角点击 `vX.Y.Z · 检查更新` 手动检查。优先通过 jsDelivr CDN / Raw GitHub 读取 `version.json`，并配备 GitHub Releases 302 探针与官方 API 兜底；下载 APK 支持官方直链与 `ghfast.top` 镜像自动回退，完成后通过 `FileProvider` 调起系统安装器覆盖安装。
- **发布新版本**：修改仓库根目录 `version.json`（递增 `versionCode`、更新 `versionName`、`downloadUrl` 与 `releaseNotes`）并推送到 `main`，GitHub Actions 会自动编译、校验、发布 GitHub Release 并刷新 jsDelivr 缓存。

## 连接皮皮智绘生图 API

接口依据为项目所有者提供的《皮皮智绘 · 生图 API 说明》（2026-10-25）。支持两种接入方式：

1. **手机直连模式（推荐自用，无需服务器）**：未配置 `JIANYU_IMAGE_ENDPOINT` 时，Android 客户端直接请求 `https://img.pipiwangcom.com/api/v1`，并内置多密钥顺序轮询与任务归属保持。在 GitHub 仓库 **Settings → Secrets and variables → Actions → Secrets** 添加 `IMAGE_API_KEYS`（多个密钥用逗号或换行分隔），打包 APK 时自动内置，App 界面不提供密钥填写入口。
2. **可选网关模式（适合对外分发）**：如需将密钥留在服务端，可部署项目内的 `gateway/server.py`（从环境变量或本地 `.env` 读取 `IMAGE_API_KEYS` / `IMAGE_API_KEY`），并在构建变量中设置 `JIANYU_IMAGE_ENDPOINT` 为你的 HTTPS 网关地址。

在创作页输入 1–4000 字的提示词，选择 GPT 或 Grok，点击“生成图片”。请求向 `/api/v1/images/generations` 提交一张图的任务，GPT 使用 `image-1` 和 `auto`（1:1 映射为 `1024x1024`，竖图 3:4/9:16 映射为 `1024x1536`，横图 4:3/16:9 映射为 `1536x1024`），Grok 使用 `image-2` 和 `1k`（保持 1K 比例尺寸，并可在创作页开启“允许 NSFW”开关）。可选一张 4 MiB 以内的 PNG、JPEG 或 WebP 作为参考图，此时走 `/api/v1/images/edits`。返回的图片下载后保存到作品库。

上游返回错误或任务失败时，会提取响应中的 `detail`（支持字符串、对象及校验列表）并展示在错误提示中。超时响应带有 `task_id` 时，客户端优先使用提交该任务的原密钥通过 `GET /api/v1/tasks/{task_id}` 取回原任务。任务仍在处理时，可在创作页点“继续获取上次作品”。若请求中断且没有取得任务编号，请在皮皮智绘核对任务记录，以免重复扣费。

## 官方资料

- [通过浏览器向仓库添加文件](https://docs.github.com/en/repositories/working-with-files/managing-files/adding-a-file-to-a-repository)
- [下载 GitHub Actions 构建产物](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)
- [Android Gradle Plugin 8.7 兼容版本](https://developer.android.com/build/releases/past-releases/agp-8-7-0-release-notes)
