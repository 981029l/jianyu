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

- **应用内更新与设置面板**：App 启动时静默检查更新，发现新版本时在创作页右上角「设置」齿轮按钮显示小蓝点提醒；点击齿轮可打开设置面板查看版本/手动检查更新、开关「安全浏览」（默认折叠敏感灵感预览）以及查看/清理图片与画廊缓存。优先通过 jsDelivr CDN / Raw GitHub 读取 `version.json`，并配备 GitHub Releases 302 探针与官方 API 兜底；下载 APK 支持官方直链与 `ghfast.top` 镜像自动回退，完成后通过 `FileProvider` 调起系统安装器覆盖安装。
- **发布新版本**：修改仓库根目录 `version.json`（递增 `versionCode`、更新 `versionName`、`downloadUrl` 与 `releaseNotes`）并推送到 `main`，GitHub Actions 会自动编译、校验、发布 GitHub Release 并刷新 jsDelivr 缓存。

## 双线路生图配置

见屿客户端支持双线路一键切换：
- **线路 1（经典稳定）**：支持 GPT / Grok 模型；
- **线路 2（新版多模型）**：支持 GPT-2.5 系列模型及参考图图生图；
- **密钥配置**：在 GitHub 仓库 **Settings → Secrets and variables → Actions → Secrets** 可分别配置 `IMAGE_API_KEYS`（线路 1）和 `IMAGE_API_KEYS_V2`（线路 2），云端打包自动注入，App 内无感切换。

## 官方资料

- [通过浏览器向仓库添加文件](https://docs.github.com/en/repositories/working-with-files/managing-files/adding-a-file-to-a-repository)
- [下载 GitHub Actions 构建产物](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)
- [Android Gradle Plugin 8.7 兼容版本](https://developer.android.com/build/releases/past-releases/agp-8-7-0-release-notes)
