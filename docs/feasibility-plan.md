# TVBox 源 · 自研现代 Android TV 客户端可行性方案

日期：2026-10-26  
版本：v0.1  
项目目录：`D:\tv-app`

> 前提假设：你说的“TVBox app 源”是指 TVBox 配置 JSON（包含 `sites`、`parses`、`lives`、`spider.jar` 等），不是指 TVBox 客户端的源码。如果指的是客户端源码，方案需要另做评估。

## 1. 结论

可行，而且不需要从零实现整套 TVBox 生态。

推荐路线是 **“新 UI + 复用 CatVod/TVBox 数据层与爬虫运行时”**：

- UI 层完全新写：Kotlin + Jetpack Compose for TV，按 10-foot UI 重新设计。
- 数据层复用 TVBox 配置协议和 CatVod Spider ABI，保证现有源可用。
- 播放器使用官方 Media3/ExoPlayer，不背 FongMi 的私有播放器包袱。
- MVP 只支持 HTTP 站点 + Java JAR 爬虫；JS/Python/Node 爬虫放到第二阶段。
- 如果项目要对外分发，代码按 GPL-3.0 开源；如果要闭源商用，需要走 clean-room 路线并做法律评估。

按 1 名熟练 Android 开发估算：

| 目标 | 工期 | 说明 |
|---|---:|---|
| 可点击的高保真 UI 原型 | 3–5 天 | 假数据，不接源，先定视觉和信息架构 |
| MVP（HTTP 源 + Java JAR + ExoPlayer） | 4–6 周 | 首页/分类/详情/搜索/播放/收藏/历史 |
| 可发布 v1（直播、字幕、弹幕等） | 8–12 周 | 含真机适配、性能优化、异常处理 |
| 闭源 clean-room 版本 | 3–5 个月 | 不复用 GPL/AGPL 代码，需自行实现兼容层 |

结论一句话：**技术上完全可行；真正的成本不在“做一个好看的 UI”，而在“把 TVBox 源的兼容性和播放链路做稳”。**

补充判断：

- 如果你只做个人自用，并且能拿到 FongMi 缺失的两个 AAR，直接 fork 换皮可以 2–4 周出第一版。
- 如果你想要一个真正属于自己的产品、需要长期维护，推荐从零建 UI，只复用 CatVod 数据/爬虫层。
- `D:\tv-app` 目前是空目录，本方案默认按“从零初始化工程”设计。

## 2. 源码与生态事实核查

已在 `D:\Code\FongMi-TV` 做了实测和源码阅读，关键事实如下。

### 2.1 基线信息

| 项 | 值 |
|---|---|
| 仓库 | `https://github.com/FongMi/TV` |
| 分支 | `fongmi` |
| HEAD | `aa30eb0`（2026-10-25，Fix filter scroll） |
| 版本 | `versionName 5.8.2` / `versionCode 582` |
| 许可证 | GPL-3.0（根目录 `LICENSE.md`） |
| 规模 | 1353 个文件，约 123 MB；`app` 模块 528 个 Java 文件、约 6.2 万行 |
| UI 规模 | leanback：100 个 Java 文件、约 1.1 万行、111 个布局；mobile：120 个布局 |
| 构建 | Gradle 9.7.1、AGP 9.4.0、compileSdk/targetSdk 37、minSdk 24、Java 21 |

生态许可证情况：

| 项目 | 许可证 | 备注 |
|---|---|---|
| `FongMi/TV` | GPL-3.0 | 本方案主要参考对象 |
| `takagen99/Box` | AGPL-3.0 | TVBox 分支，网络使用条款更严格 |
| `q215613905/TVBoxOS` | AGPL-3.0 | 老牌 TVBox 分支 |
| `FongMi/CatVodSpider` | 未发现 LICENSE 文件 | 默认按“保留所有权利”处理，不要直接复制其代码 |

如果最终产品要闭源，必须避免复制上述项目的代码，改为 clean-room 实现。

### 2.2 TVBox 配置协议

FongMi 的配置模型与 TVBox 源一致，解析入口在：

- `app/src/main/java/com/fongmi/android/tv/api/config/VodConfig.java:161`
- `app/src/main/java/com/fongmi/android/tv/bean/Site.java`
- `app/src/main/java/com/fongmi/android/tv/bean/Config.java`

主配置支持：

- `urls`：配置仓库（多仓），加载第一项
- `spider`：全局 Spider JAR
- `sites`：点播站点
- `parses`：解析器
- `lives`：直播源
- `wallpaper`：背景图/视频
- `headers` / `proxy` / `rules` / `doh` / `hosts` / `flags` / `ads`
- `danmaku` / `assrt` / `notice`

站点类型（官方文档 `website/app/config-fields.ts` 明确给出）：

| type | 含义 | 数据入口 |
|---:|---|---|
| 0 | XML HTTP | `api` + `ac=videolist/detail` |
| 1 | JSON HTTP | `api` + `ac=videolist/detail` |
| 3 | Spider | `api` 为 `csp_ClassName`、`.js`、`.py` 或 `node:` |
| 4 | 扩展 HTTP JSON | `api`，分类筛选通过 Base64 `ext` |

站点关键字段：`key`、`name`、`api`、`ext`、`jar`、`style`、`searchable`、`changeable`、`categories`、`header`。

### 2.3 Spider ABI

Java JAR 爬虫的兼容契约是 `com.github.catvod.crawler.Spider`：

- `catvod/src/main/java/com/github/catvod/crawler/Spider.java`

核心方法：

```java
void init(Context context, String extend)
String homeContent(boolean filter)
String homeVideoContent()
String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
String detailContent(List<String> ids)
String searchContent(String key, boolean quick)
String searchContent(String key, boolean quick, String pg)
String playerContent(String flag, String id, List<String> vipFlags)
String liveContent(String url)
Object[] proxy(Map<String, String> params)
```

加载逻辑：

- `app/src/main/java/com/fongmi/android/tv/api/loader/BaseLoader.java:119`
- `app/src/main/java/com/fongmi/android/tv/api/loader/JarLoader.java`
- `api.startsWith("csp_")` → `DexClassLoader` 加载 `com.github.catvod.spider.<ClassName>`
- `.js` → QuickJS
- `.py` → Chaquopy
- `node:` → Node.js
- 其它 → `SpiderNull`（纯 HTTP 站点）

**这意味着：只要新 App 提供同名、同方法签名（ABI）的 `Spider` 类，并用 `DexClassLoader` 作为宿主，现有 Java JAR 爬虫就可以继续工作。**

### 2.4 运行时模块与签名约束

新版 FongMi 把 Python、Node.js、QuickJS、Thunder、MPV、BD-J 做成了**独立 APK 运行时模块**：

- 包名：`com.fongmi.runtime.python`、`com.fongmi.runtime.nodejs` 等
- 下载目录：`https://github.com/FongMi/Release/releases/download/module/`
- 校验逻辑：`catvod/src/main/java/com/github/catvod/utils/RuntimeModule.java:452`

关键代码：

```java
PackageInfo host = Runtime.context().getPackageManager()
        .getPackageInfo(Runtime.context().getPackageName(), signatureFlags());
...
if (expected == null || actual == null || !Arrays.asList(expected).containsAll(Arrays.asList(actual)))
    throw new IOException("Invalid runtime signer");
```

**结论：运行时 APK 必须和宿主 App 使用同一签名。**

- 如果直接使用 FongMi 官方签名的运行时 APK，而我们的 App 用新 keystore 签名，会校验失败。
- 因此要么自己构建并签名运行时模块，要么把 QuickJS/Python/Node 直接嵌入 App，要么 MVP 先只支持 Java JAR。

### 2.5 播放器事实

FongMi 当前 `fongmi` 分支使用的是一个**定制 Media3 分支**：

- `app/src/main/java/.../player/engine/PlayerEngineFactory.java`
- 代码引用 `androidx.media3.mpvplayer.*`
- 使用 `DiscMenuState`、`PlayerSeekView`、`SubtitleOffsets`、`DolbyVisionOutputPolicy` 等类

实测官方 `androidx.media3:media3-common:1.8.0`、`media3-ui:1.8.0`、`media3-exoplayer:1.8.0` 中**不存在**这些类，说明 FongMi 用的是私有 Media3 fork。

同时，`app/build.gradle:102` 通过 `fileTree(dir: "libs", include: ["*.aar"])` 引入本地 AAR，而仓库里缺少：

- `app/libs/lib-mpvplayer-release.aar`
- `app/libs/lib-decoder-ffmpeg-release.aar`

`buildSrc/.../AbiApkPackaging.groovy:49-51` 会在 native libs 合并前强校验这两个文件。GitHub Release 的 5.8.3 只发布 APK，不发布这两个 AAR。

**结论：直接 fork FongMi 当前分支，在干净环境里无法编译通过。**  
这不是“配一下环境”能解决的问题，而是缺少闭源/未发布的编译期依赖。

### 2.6 设备与环境

本机已具备：

- JDK 21：`C:\Users\Lenovo、\.jdks\ms-21.0.9`
- Android SDK：`D:\Android\Sdk`（platform-37、build-tools 37.0.0、NDK 28.2、CMake 3.22.1）
- Gradle 9.7.1 wrapper 已缓存
- 网络可通过 `127.0.0.1:7890` 访问 GitHub / Maven

当前缺口：

- 没有连接真机，`adb devices` 为空
- 唯一 AVD `MomTV` 是 **Android 28 x86_64**，而 FongMi 只打包 `arm64-v8a` / `armeabi-v7a`，无法安装

新项目如果使用官方 Media3，可以额外构建 `x86_64` 变体用于模拟器预览；但最终仍建议准备一台 Android 9+ 的 arm64 TV 盒子做真机验收。

## 3. 方案对比

### 方案 A：直接 fork FongMi/TV，换皮

做法：保留全部代码，替换主题、布局、首页、详情页、播放器控制层。

优点：

- 功能最全：点播、直播、弹幕、DLNA、投屏、下载、字幕、多仓
- 源兼容性最好
- UI 改动可以逐步做

缺点：

- **当前分支缺 AAR，无法编译**；需要先解决私有 Media3 fork
- 6.2 万行 Java + 111 个 XML 布局，改造成本不低
- GPL-3.0，对外分发必须开源
- 旧式 View/Leanback 架构，做现代动效和复杂布局会比较别扭

适合：个人自用、能接受 GPL、且能拿到/重建私有 AAR。

### 方案 B：新 UI + 复用 CatVod 数据/爬虫层（推荐）

做法：新建 Kotlin + Compose for TV 工程，复用 `catvod` 的 Spider ABI、网络层和 JAR 加载思路；播放器用官方 Media3。

优点：

- UI 完全自由，能做真正现代的 TV 设计
- 不依赖 FongMi 的私有 Media3 AAR
- 代码量可控，模块边界清晰
- 可以先用假数据做 UI 原型，再逐层接源
- x86_64 模拟器可直接跑 UI

缺点：

- 需要自己实现配置解析、站点仓库、搜索聚合、播放解析、错误处理
- GPL 代码复用后仍需按 GPL-3.0 开源
- JS/Python/Node 爬虫需要额外运行时

适合：想要“自己的产品感”，同时保留现有 TVBox 源的主力路线。

### 方案 C：完全 clean-room，自研协议与运行时

做法：不复制 FongMi/AGPL 代码，按公开协议重新实现配置解析、Spider ABI 宿主和播放器。

优点：

- 可以闭源/商业化
- 架构最干净

缺点：

- 工作量大，兼容性风险高
- Spider JAR 的二进制兼容需要大量实测
- 仍需处理内容授权问题

适合：有明确商业目标、需要闭源、且愿意投入 3–5 个月的情况。

### 方案 D：WebView/H5 套壳

做法：用 Web 前端做 UI，原生只负责播放。

优点：UI 迭代最快。

缺点：TV 遥控焦点、播放器叠加层、硬解、性能都不理想。

结论：不推荐作为主路线。

## 4. 推荐架构

```text
tv-app/
├─ app/                    # Compose for TV：导航、页面、焦点、动效
├─ core/
│  ├─ model/               # Vod / Site / Category / Episode / PlaySpec
│  ├─ config/              # TVBox JSON、Depot、Parse、Live、Rules
│  ├─ source/              # HTTP 站点（type 0/1/4）
│  ├─ spider/              # Java JAR 宿主（type 3 / csp_）
│  ├─ player/              # Media3 ExoPlayer 封装
│  └─ data/                # Room：历史、收藏、源缓存
├─ feature/
│  ├─ home/                # 首页：Hero + 横向内容行
│  ├─ browse/              # 分类、筛选、分页
│  ├─ detail/              # 详情、线路、剧集
│  ├─ search/              # 搜索、聚合结果
│  ├─ library/             # 收藏、历史
│  └─ settings/            # 源管理、播放设置
└─ tools/
   └─ audit-tvbox-source.ps1
```

数据流：

```text
TVBox 配置 JSON
      │
      ▼
ConfigRepository ──► SiteRepository ──► HttpSource / SpiderSource
      │                                      │
      │                                      ▼
      └──────────────► UI State ◄──── Result(Vod/Episode/PlaySpec)
                                             │
                                             ▼
                                      Media3 ExoPlayer
```

关键设计：

- **Spider 隔离进程**：第三方 JAR 属于不可信代码，建议放在 `:spider` 独立进程，主进程只通过 AIDL/Binder 调用；至少不要给主进程敏感权限。
- **源健康检查**：每个站点记录最近一次首页/搜索/播放成功时间，失败自动降级。
- **缓存优先**：首页和详情先读本地缓存，再后台刷新，避免电视盒子冷启动等待。
- **统一错误模型**：网络、解析、无版权、无播放地址、播放器不支持分开提示。

## 5. UI/UX 设计方向

### 5.1 视觉概念

当前方向：**tvOS 式极简画廊**

- 近黑背景与白色焦点，避免金色、紫色渐变和装饰性纹理
- 大画幅剧照、海报墙和不对称 Hero 构图
- 系统无衬线字体，依靠字号、字重和留白建立层级
- 首页首先呈现内容，工具入口退到顶部导航和设置页

设计 token：

| Token | 值 | 用途 |
|---|---|---|
| `bg.base` | `#050506` | 全局背景 |
| `bg.raised` | `#151517` | 卡片/浮层 |
| `text.primary` | `#F5F5F7` | 主文字 |
| `text.secondary` | `#A1A1A6` | 次要文字 |
| `accent` | `#FFFFFF` | 焦点、进度、主按钮 |
| `accent.soft` | `#29FFFFFF` | 选中背景 |
| `danger` | `#D96C5F` | 错误 |

字体：

- 标题：系统无衬线黑体，粗体
- 正文：系统 Noto Sans CJK
- 数字：沿用系统无衬线字体，时间与集数保持稳定字宽

### 5.2 关键页面

1. **首页**
   - 顶部 40% 为 Hero：剧照、标题、评分/年份/类型、继续观看按钮
   - 下方 3–5 行横向内容：继续观看、今日更新、收藏、各站点推荐
   - 左侧窄导航：首页、搜索、收藏、历史、设置

2. **分类页**
   - 顶部筛选条（类型/地区/年份/排序）
   - 海报网格，焦点放大 1.06，短暂白色描边

3. **详情页**
   - 左侧剧照/简介，右侧线路和剧集
   - 线路切换用分段控件，剧集用横向轨道

4. **搜索页**
   - 大字号搜索框 + 遥控器软键盘
   - 聚合结果按站点分组，显示“来自哪个源”

5. **播放器**
   - 极简控制层：进度、剧集、音轨、字幕、倍速
   - 3 秒无操作自动隐藏
   - 左右键 ±10 秒，上下键呼出剧集/设置

### 5.3 TV 交互规则

- 所有可交互元素必须有清晰焦点态，不能只靠颜色变化
- 焦点移动 180–220ms，页面切换 300–400ms
- 返回键行为固定：播放器 → 详情 → 首页
- 触控/鼠标不是主路径，但手机/平板可以复用同一套 Compose UI
- 1080p 设计基准，留 5% 安全边距，兼容过扫描

### 5.4 性能预算

| 指标 | 目标 |
|---|---|
| 冷启动到首页可交互 | < 2.5s（中端盒子） |
| 首页首屏 | < 1.5s（有缓存） |
| 列表滚动 | 60fps |
| 内存峰值 | < 350MB |
| 单张海报解码 | 不超过屏幕宽度 1/3 |
| 图片缓存 | 磁盘 200MB + 内存 25MB |

## 6. MVP 范围

必须有：

- 导入 TVBox 配置 URL / 本地 JSON
- 多仓 `urls` 选择
- 站点列表、分类、筛选、分页
- 首页 Hero + 内容行
- 搜索（快速搜索 + 全站搜索）
- 详情页、线路、剧集
- HLS / MP4 / MKV 基础播放
- 播放进度记忆、收藏、历史
- 源切换、失败重试、错误提示
- 设置：源管理、播放器、缓存清理

MVP 不做：

- 直播 / EPG / 回看
- 弹幕
- DLNA / 投屏
- 下载 / 离线缓存
- Python / Node.js / QuickJS 爬虫
- MPV 播放器
- 手机端专门布局

这些放到 v1/v2，避免第一版战线过长。

## 7. 实施计划

### Phase 0：源审计 + UI 原型（3–5 天）

- 用 `tools/audit-tvbox-source.ps1` 统计你的源：站点类型、JAR/JS/PY 数量、解析器类型、直播数量
- 输出《源兼容性报告》
- 用假数据做 3 个关键页面：首页、详情、播放器
- 产出 1920×1080 截图，确认视觉方向

退出标准：确认源是否以 HTTP + Java JAR 为主；UI 方向定稿。

### Phase 1：可浏览（7–10 天）

- 初始化 Kotlin/Compose for TV 工程
- 配置解析、多仓、站点列表
- HTTP 站点（type 0/1/4）的首页、分类、详情、搜索
- 首页/分类/详情/搜索 UI 接真实数据
- 图片加载、缓存、加载/空/错误状态

退出标准：不接播放器也能完整浏览一个真实源。

### Phase 2：可播放（7–10 天）

- Media3 ExoPlayer 接入
- HLS/MP4/MKV、字幕、音轨、倍速、进度记忆
- Java JAR Spider 宿主（`csp_`）
- 代理接口、Header、播放失败重试
- 播放器控制层

退出标准：源里 80% 以上站点可以完成“列表 → 详情 → 播放”。

### Phase 3：好看 + 好用（5–8 天）

- 动效、焦点、Hero 转场、骨架屏
- 收藏、历史、继续观看
- 源健康检查、自动降级
- 真机性能调优

退出标准：D-pad 全流程可操作，1080p 稳定 60fps。

### Phase 4：发布准备（5–7 天）

- 签名、混淆、崩溃收集
- GPL-3.0 合规材料（源码、LICENSE、修改说明）
- 隐私说明、免责声明
- 安装包与升级策略

退出标准：可安装、可升级、可回滚。

## 8. 风险与对策

| 风险 | 概率 | 影响 | 对策 |
|---|---:|---:|---|
| 源以 JS/Python/Node 爬虫为主 | 中 | 高 | Phase 0 先审计；MVP 只承诺 Java JAR，后续嵌入 QuickJS/Chaquopy |
| Spider JAR ABI 不兼容 | 中 | 高 | 复用 `catvod` 的 Spider 类；建立 10–20 个真实 JAR 的回归测试集 |
| 第三方 JAR 安全问题 | 中 | 高 | 独立进程运行；限制文件/网络权限；只加载可信源 |
| 私有 Media3 fork 无法替换 | 高 | 中 | 不 fork FongMi 播放器；新项目用官方 Media3 |
| 运行时 APK 签名不匹配 | 高 | 中 | 自己构建运行时，或直接嵌入运行时，不使用官方模块 |
| 源站反爬/失效 | 高 | 高 | 多线路、重试、缓存、源健康检查；不要把单一源写死 |
| 低端盒子性能不足 | 中 | 中 | 性能预算、图片降采样、避免复杂模糊、真机测试 |
| GPL/AGPL 合规 | 高 | 高 | 对外分发则开源；闭源则 clean-room + 法律评估 |
| 内容版权风险 | 高 | 高 | 不预置源、不打包内容；只做播放器；要求用户使用合法源 |
| 没有 arm64 真机 | 高 | 中 | 先用 x86_64 模拟器做 UI；尽快准备 Android 9+ TV 盒子 |

## 9. 验收标准

功能：

- 导入一个真实 TVBox 配置，能显示站点和分类
- 至少 10 个站点完成首页、分类、详情、搜索
- 至少 5 个站点完成实际播放
- Java JAR 站点可以正常加载

体验：

- 纯遥控器操作，无必须触控的步骤
- 所有页面有焦点态、加载态、空态、错误态
- 播放器 3 秒自动隐藏控制栏
- 返回路径符合预期

性能：

- 冷启动 < 2.5s
- 首页滚动 60fps
- 连续浏览 30 分钟无 OOM
- 播放 1080p HLS 30 分钟不卡顿

工程：

- 有源兼容性测试清单
- 有崩溃日志和版本信息
- 有 GPL 合规文件（如果复用 GPL 代码）

## 10. 需要你确认的事

1. **源的类型**：把配置 URL 或 `source.json` 放到 `D:\tv-app\source\`，我先跑审计。
2. **分发方式**：个人自用 / 开源发布 / 闭源商用。这个决定 GPL 路线。
3. **目标设备**：具体 TV 盒子型号、Android 版本、ABI（arm64 / armv7）。
4. **是否要直播**：直播和 EPG 会显著增加工期。
5. **品牌**：App 名称、Logo、主色，是否已有视觉偏好。

## 11. 下一步建议

先做 Phase 0，不写业务代码：

1. 你提供源地址或 `source.json`。
2. 我运行 `tools/audit-tvbox-source.ps1`，输出站点类型分布和兼容性结论。
3. 根据审计结果确定 MVP 是否包含 JAR 爬虫。
4. 用 3 天做 Compose for TV 高保真原型（首页/详情/播放器）。
5. 原型确认后再初始化正式工程。

## 附录 A：源审计要看什么

- `sites` 总数
- `type=0/1/3/4` 各多少
- `type=3` 中 `csp_` / `.js` / `.py` / `node:` 各多少
- 全局 `spider` 是否存在
- 每个站点的 `jar` 覆盖数量
- `parses` 数量与类型（0/1/2/3/4）
- `lives` 数量
- `flags` / `headers` / `proxy` / `rules` / `ads` 是否使用
- 是否有 `urls` 多仓

## 附录 B：关键源码位置

- TVBox 配置解析：`D:\Code\FongMi-TV\app\src\main\java\com\fongmi\android\tv\api\config\VodConfig.java:161`
- 站点模型：`D:\Code\FongMi-TV\app\src\main\java\com\fongmi\android\tv\bean\Site.java`
- Spider ABI：`D:\Code\FongMi-TV\catvod\src\main\java\com\github\catvod\crawler\Spider.java`
- Spider 分发：`D:\Code\FongMi-TV\app\src\main\java\com\fongmi\android\tv\api\loader\BaseLoader.java:119`
- JAR 加载：`D:\Code\FongMi-TV\app\src\main\java\com\fongmi\android\tv\api\loader\JarLoader.java`
- 运行时签名校验：`D:\Code\FongMi-TV\catvod\src\main\java\com\github\catvod\utils\RuntimeModule.java:452`
- 缺失 native AAR：`D:\Code\FongMi-TV\buildSrc\src\main\groovy\com\fongmi\gradle\AbiApkPackaging.groovy:49`
- 站点字段说明：`D:\Code\FongMi-TV\website\app\config-fields.ts`

