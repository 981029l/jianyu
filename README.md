# 见屿 (Jianyu) · Android

<p align="center">
  <strong>浅色海雾风格的高品质原生 AI 绘图与灵感创作工坊</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Java_17-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java" />
  <img src="https://img.shields.io/badge/Gradle-8.9-02303A?style=flat-square&logo=gradle&logoColor=white" alt="Gradle" />
  <img src="https://img.shields.io/badge/Build-GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white" alt="CI" />
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="License" />
</p>

---

## 📖 项目简介

**见屿 (Jianyu)** 是一款专为移动端打造的原生 AI 绘图应用。应用采用浅色海雾与柔和流光视觉设计，遵循纯原生极简交互体系，集成了**双线路生图引擎**、**7,100+ 离线与在线精选提示词画廊**、**本地作品管家**以及**全自动 CI/CD 构建发版体系**。

---

## ✨ 核心特性

### 🎨 双线路生图引擎（自由切换）
* **线路 1（经典稳定）**：
  * 支持 `GPT` 与 `Grok` 双引擎；
  * 原生支持多种画面比例自动映射（1:1、3:4、4:3、9:16、16:9）；
  * Grok 模型专属支持 NSFW 内容创作开关；
  * 支持带参考图的图片改画。
* **线路 2（新版多模型）**：
  * 支持全新 `GPT-2.5`、`GPT-2.5 Flare`、`GPT-2.5 Sunburst`、`GPT-2` 等多版本模型；
  * 原生支持文生图与基于 Multipart 的多模态参考图图生图；
  * 具备毫秒级任务分发与异步轮询机制。
* **参数一键切换**：首页参数胶囊支持在两条线路间即时无缝切换，界面根据线路动态适配可用模型与参数。

### 💡 灵感频道与提示词工坊
* **7,100+ 提示词画廊**：整合内置 1,740 组精选本地资源与在线灵感数据，冷启动无网亦可秒级浏览；
* **分类检索与一键同款**：提供数十种专业风格标签（海报设计、UI界面、摄影人像、趣味脑洞等），支持搜索、关键词高亮与一键将提示词及参考图带回创作台；
* **安全浏览模式**：内置安全模式开关，可智能折叠敏感缩略图。

### 🖼️ 本地作品管家
* **纯本地存储**：生成作品与元数据全部保存在设备本地私有目录，保护用户隐私；
* **系统相册直存**：Android 10+ 免存储权限一键将作品保存至系统相册（`Pictures/见屿`）；
* **流体全屏查看器**：支持双击弹性平滑缩放、手势拖拽浏览与元数据（尺寸、体积、耗时、模型）完整溯源；
* **批量管理与分享**：支持长按多选批量删除及系统级无损图片分享。

### 🚀 自动化构建与应用内更新
* **GitHub Actions 自动化流水线**：推送到 `main` 分支自动完成 SDK 配置、APK 构建、固定签名、Badging 元数据校验与 GitHub Release 发布；
* **应用内热更新**：启动自动静默检查更新，发现新版本弹出小蓝点提醒，支持应用内直装覆盖升级，无需重新配置。

---

## 🔒 密钥安全与多源配置

本项目遵循**生产环境零明文密钥**原则，Git 仓库与提交历史中不包含任何真实 API 凭据。密钥统一通过安全通道注入：

### 1. 云端构建（GitHub Actions）
在 GitHub 仓库中进入 **Settings → Secrets and variables → Actions**，添加以下 Repository Secrets：

| Secret 变量名 | 说明 | 示例 / 格式 |
| :--- | :--- | :--- |
| `IMAGE_API_KEYS` | 线路 1 生图接口密钥 | 多个密钥以逗号分隔，如 `sk-xxx,sk-yyy` |
| `IMAGE_API_KEYS_V2` | 线路 2 生图接口密钥 | 多个密钥以逗号分隔，如 `lupi_xxx,lupi_yyy` |

> **提示**：GitHub Actions 在构建时会自动将上述 Secrets 作为环境变量注入 Gradle 构建流程，打出的 APK 即可直接使用对应线路。

### 2. 本地离线开发（`.env`）
在项目根目录下创建 `.env` 文件（该文件已列入 `.gitignore`，不会被 Git 追踪）：
```properties
IMAGE_API_KEYS=你的线路1密钥
IMAGE_API_KEYS_V2=你的线路2密钥
```
本地运行 Gradle 构建时会自动读取该文件中的环境变量进行本地打包调试。

---

## 🛠️ 构建与运行指南

### 方式一：GitHub Actions 自动构建（推荐）
1. Fork 或将本项目推送到您的 GitHub 仓库；
2. 在仓库 **Settings → Secrets and variables → Actions** 中配置所需的密钥变量；
3. 推送代码至 `main` 分支，GitHub Actions 将自动执行构建；
4. 构建成功后，在仓库 **Releases** 页面即可直接下载打包好的 APK 安装包。

### 方式二：本地命令行构建
1. 确保本地安装有 **JDK 17** 及 **Android SDK (Platform 35, Build-Tools 34.0.0+)**；
2. 配置项目根目录 `.env` 文件或设置环境变量；
3. 执行打包命令：
   ```bash
   # Windows PowerShell
   gradle :app:assembleRelease
   
   # Linux / macOS
   ./gradlew :app:assembleRelease
   ```
4. 构建产物位于：`app/build/outputs/apk/release/`。

---

## 📦 版本发版规范

如需发布新版本：
1. 修改根目录的 [version.json](file:///d:/Jianyu/version.json)：
   * 递增 `versionCode`（如 `7`）；
   * 更新 `versionName`（如 `0.6.1`）；
   * 更新 `downloadUrl` 为对应 release 链接；
   * 填写 `releaseNotes` 版本更新说明。
2. 提交并推送至 `main` 分支；
3. GitHub Actions 自动拉取 `version.json` 中的元数据完成打包并创建正式 Release。

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 许可协议开源。
