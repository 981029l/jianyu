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

**见屿 (Jianyu)** 是一款专为移动端打造的高品质原生 AI 绘图应用。应用采用浅色海雾与柔和流光视觉设计，遵循纯原生极简交互体系，拥有**四 Tab 架构（创作 · 提示词 · 广场 · 作品）**，集成了**双线路生图引擎**、**13,780+ 离线与在线精选提示词工坊**、**创意广场社区灵感流**、**生成记录与本地作品管家**以及**全自动 CI/CD 构建发版体系**。

---

## ✨ 核心特性

### 🧭 四 Tab 原生悬浮底栏架构
* **【创作】**：提示词输入、系统相册参考图、双线路参数面板与流体生图；
* **【提示词】**：本地 18,170+ 条去重离线词库（热榜图鉴 6,601 · 设计精选 11,551 · 经典画廊 5,443，含立得设计 CDN 全量静态库）+ **「发现灵感」在线双源按需分页库（AIART.PICS 13,467 + OpenNana 18,727 共 32,000+ 条）**；
* **【广场】**：全网社区实时热门作品流，无限滚动与下拉刷新；
* **【作品】**：「成品作品」、「生成记录」与「灵感收藏」三视图，支持任务追踪、灵感收藏管理、一键重试与批量管理。

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

### 💡 提示词工坊与以图生图
* **五大标签页清晰区分**：
  * **全部（18,170+ 条）**：本地离线、立得设计与经典画廊全量去重合集，支持按「动漫二次元 (1,250+)」、「人物写真 (930+)」、「海报广告 (1,160+)」、「插画艺术」等完整分类树快速直达；
  * **热榜图鉴（6,601 条）**：本地内置离线数据，按热度点赞降序排列，毫秒秒开；
  * **设计精选（11,551 条）**：本地内置精选 + 立得设计（LeaderAI）全量 CDN 静态库（涵盖建筑空间、平面设计、角色设定、创意品牌等 15 大类设计行业模版），支持离线秒开与 CDN 动态持久化缓存；
  * **经典画廊（5,443 条）**：丰富艺术人像、科幻概念与构图提示词；
  * **发现灵感（32,000+ 条在线库）**：APP 直接按需向 `aiart.pics`（13,467 条）与 `opennana.com`（18,727 条）在线分页拉取，支持「双源聚合 / AIART.PICS / OpenNana」切换与中英文关键词在线检索。
* **一键以图生图**：在任意提示词详情卡片中轻触「设为参考图」，系统自动高速缓存高清大图并载入【创作】主面板参考图插槽，无缝开启多模态图生图改画；
* **一键做同款**：提取原配提示词与画面比例直接填入创作台；
* **动态分类联动与搜索**：顶部来源切换即时联动刷新专属分类胶囊，支持多维度模糊搜索与在线实时检索；
* **安全浏览模式**：内置安全模式开关，可智能折叠敏感缩略图。

### 🌐 创意广场
* **实时全网灵感流**：聚合全网社区最新热门创作，下拉刷新与触底无限滑动加载更多；
* **渐进式高清看图**：轻触作品可即刻打开详情与全屏弹性缩放，支持一键做同款与参考图改画。

### 🖼️ 作品库、生成记录与灵感收藏
* **三视图自由切换**：支持「成品作品」、「生成记录」与「灵感收藏」选项卡平滑切换；
* **灵感收藏集中管理**：在提示词工坊、发现灵感或创意广场轻触红心收藏的灵感统一归档于作品库，自动在后台补全完整提示词并永久持久化在本机；
* **任务状态与耗时全程追溯**：生成任务（绘制中 / 成功 / 失败 / 取消）、耗时秒数、错误明细、模型参数全量持久化；
* **一键重试与同款参数**：绘制失败或历史任务支持一键重试，可随时提取历史提示词重新创作；
* **原生系统相册直连**：参考图选择与成品保存全面适配 Android 原生 Photo Picker，直接关联系统相册，无需多级寻找文件管理器；
* **纯本地存储**：生成作品与元数据全部保存在设备本地私有目录，保护用户隐私；
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
