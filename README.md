# RealCue — 移动端实时多人脸检测与长期人物编号

基于 [InspireFace Android SDK 1.2.4.post1](https://github.com/HyperInspire/InspireFace) 官方 Demo 的二次开发。核心目标：**同一个人离场再回来，仍然显示同一个编号**。

- A 第一次出镜 → 自动建档 `P001`
- B 第一次出镜 → `P002`
- A 完全离场后回来 → 重新提特征、查库命中 → 仍是 `P001`
- 新人 C 出镜 → `P003`

图片检测、视频跟踪、人脸管理、识别详情、搜索框的编号口径已全部统一为长期 `person_id`，不再使用只在一个 camera Session 内有效的短时 `track_id`。

---

> ⚠️ **本仓库不包含完整构建环境。**
> 约 14 GB 的 Android SDK、Gradle 发行包、手机系统镜像、AVD、InspireFace AAR 和 APK 都被 `.gitignore` 排除（GitHub 单文件上限 100 MB，且这些是本机产物）。
> **clone 之后不能直接构建**，请先按本文第 5 节或 [ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md) 从零配置。

---

## 1. 先看哪份文档

| 你想做什么 | 看这里 |
| --- | --- |
| 从零把环境配起来、跑通构建 | [ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md)（详细版，本文第 5 节是主线速览） |
| 了解长期人物库匹配的调研与实现 | [Research-PersonGallery-Matching.md](Research-PersonGallery-Matching.md) |
| 了解需求来源 | [Research-Mobile-Realtime-FaceID.md](Research-Mobile-Realtime-FaceID.md) |
| 了解完整试跑过程与每次构建证据 | [InspireFace-LocalRun/LocalRun-Plan.md](InspireFace-LocalRun/LocalRun-Plan.md) |
| 看可视化验证报告 | 根目录 `*.html`（见第 11 节索引） |
| 直接读业务源码 | `InspireFace-LocalRun/android-demo/app/src/main/java/com/example/inspireface_example/` |

## 2. 仓库里没有什么

被 [`.gitignore`](.gitignore) 排除、需要你自己准备的内容：

| 类别 | 具体内容 | 为什么不在仓库 |
| --- | --- | --- |
| 本地工具链 | `runtime/android/`（自包含 SDK）、`runtime/gradle/`（Gradle 8.7）、`runtime/gradle-home/`（可写缓存）、`runtime/avd/`、`runtime/tmp/` | 体积约 14 GB，且是本机路径相关产物 |
| 大二进制 | `inspireface-1.2.4.post1.aar`（约 83 MB）、`app-debug.apk`（约 116 MB）、`*.img` / `*.qcow2` / `*.zip` | APK 超过 GitHub 单文件 100 MB 限制；AAR 是上游 SDK |
| 本机配置 | `local.properties`、`sdk.local.properties`、`plus.local.properties`、`keystore.properties`、`runtime/env.local.bat` | 每台机器不同；已提供 `*.example` 模板 |
| 模拟器镜像 | `x86_64-35_r09.zip`（1.7 GB）及其解压后的 system image | 体积过大 |

**仓库里有**：全部 Java 源码、资源、JVM 单测、设备端 instrumented 测试、构建/验证脚本、调研文档、HTML 报告、以及可复核的日志证据（`runtime/logs/`）。本仓库是干净的源码仓库，首次提交约 6 MB。

## 3. 环境速览

| 组件 | 版本 / 值 | 说明 |
| --- | --- | --- |
| JDK | **17** | AGP 8.6.1 要求 JDK 17；Android Studio 自带 JBR 通常是 **21，不可用** |
| Android Gradle Plugin | 8.6.1 | `gradle/libs.versions.toml` |
| Gradle | 8.7 | wrapper 与 standalone 均为 8.7 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 | `app/build.gradle` |
| Android SDK | platform `android-35`、build-tools `35.0.0`、platform-tools `37.0.3` | 由 `setup_env.py` 装配到 `runtime/android/` |
| CameraX | 1.5.3 | `camera-core` / `camera2` / `lifecycle` / `view` |
| InspireFace SDK | `v1.2.4.post1`（`com.github.HyperInspire:inspireface-android-sdk`） | AAR 内含 Pikachu / Megatron 模型与 `libInspireFace.so` |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64` | `ndk.abiFilters` |
| 模拟器 | `RealCue_API35`（Android 15 / google_apis / x86_64 / Pixel 6 / 竖屏 touch） | 手机镜像，**不要用 Android XR 头显镜像** |
| Python | 3.9+ | 仅用于 `runtime/*.py` 构建与验证脚本 |

## 4. 目录布局

```text
RealCue/
├── README.md                     ← 本文（主入口）
├── ENVIRONMENT_SETUP.md          ← 从零配环境的详细版
├── .gitignore                    ← 仓库卫生规则
├── Research-*.md / *.html        ← 调研文档与可视化报告
└── InspireFace-LocalRun/
    ├── LocalRun-Plan.md          ← 试跑全过程与证据
    ├── android-demo/             ← Android 工程（Gradle 根）
    │   ├── app/src/main/         ← 业务源码
    │   ├── app/src/test/         ← JVM 单测
    │   ├── app/src/androidTest/  ← 设备端测试
    │   ├── local.properties.example
    │   ├── sdk.local.properties.example
    │   └── plus.local.properties.example
    └── runtime/
        ├── env_config.py         ← 所有本机路径的唯一配置源
        ├── env.example.bat       ← 环境变量模板
        ├── setup_env.py          ← 装配 SDK / Gradle / 配置文件
        ├── setup_phone_emulator.py
        ├── run_gradle.py         ← 统一 Gradle 入口
        ├── build_demo.py / build_ascii.py
        ├── verify_*.py / calibrate_threshold.py
        ├── downloads/            ← 你放入的大文件（gitignored）
        ├── android/  gradle/  gradle-home/  avd/  tmp/   ← 本机产物（gitignored）
        └── logs/                 ← 构建与测试证据（已入库）
```

## 5. 从零搭建（主线步骤）

以下步骤在 **Windows** 上验证过；脚本面向 Windows（`.bat` / `.exe`）。所有可写状态都落在项目目录内，不会往 C 盘写新数据。

### 5.0 前置

- Windows 10/11，Python 3.9+ 已加入 `PATH`
- 已安装 **JDK 17**（不是 21）
- 已安装 Android Studio 或至少有一个机器级 Android SDK，且装有 **emulator** 组件（提供 `emulator.exe`）与已接受的 licenses
- 可选：硬件加速（WHPX / Hyper-V），否则 x86_64 模拟器会非常慢
- 磁盘：装配完成后约需 **14 GB** 空间

### 5.1 clone 到**纯 ASCII 路径**

```powershell
git clone git@github.com:PujunDing/RealCue.git D:\paper\RealCue
```

> ⚠️ **路径里不能有中文/非 ASCII 字符**。AGP 8.6.1 的 `aapt2.exe` 和 Gradle test worker 会把它搞成乱码，`android.overridePathCheck=true` 只能绕过表面检查、治不了根。
> 如果实在无法换路径，用 `runtime/build_ascii.py` 把源码镜像到纯 ASCII 目录再构建（配置见 `REALCUE_ASCII_MIRROR`）。

### 5.2 准备大文件到 `runtime/downloads/`

`setup_env.py` 会**自动下载**下面前 3 项，你只需要手动放后 2 项：

| 文件名 | 大小 | 获取方式 |
| --- | --- | --- |
| `build-tools_r35_windows.zip` | 59,878,107 B | 脚本自动下载 |
| `platform-tools_r37.0.3-win.zip` | 8,188,595 B | 脚本自动下载 |
| `gradle-8.7-bin.zip` | 约 128 MB | 脚本自动下载 |
| `platform.api35.zip` | 64,273,788 B | **手动**：下载 `platform-35_r02.zip` 后重命名 |
| `inspireface-1.2.4.post1.aar` | 约 83 MB | **手动**：上游 release / JitPack 产物，或走在线 jitpack 模式 |
| `x86_64-35_r09.zip` | 1,738,815,903 B | **仅模拟器需要**：`sdkmanager "system-images;android-35;google_apis;x86_64"` |

精确 URL、SHA-1 与放置位置见 [ENVIRONMENT_SETUP.md §4](ENVIRONMENT_SETUP.md)。`platform.api35.zip` 就是 Android SDK repository 里的 `platform-35_r02.zip`（SHA-1 `0bb560a9…`），重命名为脚本期望的名字即可。

> 想省事、且能联网：可以不放 AAR，改用 **jitpack 在线模式**（见 5.6 的 `REALCUE_SDK_SOURCE=jitpack`）。

### 5.3 设置环境变量（通常可跳过）

`runtime/env_config.py` 会自动探测 JDK 17、机器级 SDK 和 `emulator.exe`。只有探测失败时才需要显式指定：

```powershell
# PowerShell（当前会话）
$env:REALCUE_JAVA_HOME = "D:\jdk\jdk-17.0.14"
$env:REALCUE_GLOBAL_ANDROID_SDK = "C:\Users\你的用户名\AppData\Local\Android\Sdk"
```

或者复制 `runtime/env.example.bat` 为 `runtime/env.local.bat`，编辑后在 cmd 里 `call runtime\env.local.bat`。完整变量表见 [ENVIRONMENT_SETUP.md §5](ENVIRONMENT_SETUP.md)。

### 5.4 一键装配

```powershell
cd D:\paper\RealCue\InspireFace-LocalRun
python runtime/setup_env.py
```

它会：下载并解压 build-tools / platform-tools / Gradle 8.7 → 校验 `android.jar` → 复制已接受的 SDK licenses → 生成 `android-demo/local.properties` 和 `sdk.local.properties`。**脚本是幂等的**，重复运行只补缺失项。

### 5.5 自检（先做这一步，别急着构建）

```powershell
python runtime/env_config.py
```

输出 `RESULT: READY (core build toolchain present)` 才算环境齐了。若有 `[TODO]`，按提示补齐后再继续。这一步能在几十秒内告诉你缺什么，避免对着 Gradle 报错猜半天。

### 5.6 首次构建：允许 Gradle 联网下载依赖

新机器没有 Gradle 依赖缓存，`--offline` 会直接失败，所以第一次要开在线开关：

```powershell
# 已有本地 AAR（离线 SDK 模式）
$env:REALCUE_GRADLE_ONLINE = "1"
python runtime/run_gradle.py :app:assembleDebug :app:testDebugUnitTest

# 或者没有 AAR，直接走 JitPack 在线依赖
$env:REALCUE_GRADLE_ONLINE = "1"
$env:REALCUE_SDK_SOURCE = "jitpack"
python runtime/run_gradle.py :app:assembleDebug :app:testDebugUnitTest
```

依赖会被下载到 **D 盘的 `runtime/gradle-home/`**（不是 C 盘）。构建成功后把开关去掉，恢复纯离线：

```powershell
Remove-Item Env:REALCUE_GRADLE_ONLINE
Remove-Item Env:REALCUE_SDK_SOURCE
```

### 5.7 之后的日常构建（离线）

```powershell
python runtime/run_gradle.py :app:assembleDebug
```

产物：`android-demo/app/build/outputs/apk/debug/app-debug.apk`（已被 `.gitignore` 排除，不会进仓库）。

### 5.8 可选：手机模拟器

```powershell
python runtime/setup_phone_emulator.py     # 校验 SHA-1 → 解压系统镜像 → 写 RealCue_API35 AVD
python runtime/verify_on_emulator.py       # 启动、装 APK、冷启动、截图
python runtime/verify_model_on_emulator.py com.example.inspireface_example.view.ModelFeasibilityTest
```

> 用 **手机镜像**（`hw.camera.back=virtualscene`、`hw.screen=touch`、竖屏）。Android XR 头显镜像无后摄、横屏 no-touch，在 SwiftShader/Vulkan 下还容易 boot 失败，不适合 CameraX 验证。

### 5.9 可选：真机

```powershell
adb devices                              # 确认设备在线、已接受 RSA 授权
python runtime/verify_on_device.py        # 安装并冷启动
```

MTP 方式把 APK 复制到手机内部存储目录在本机受会话隔离限制，未能做到自动化；用 `adb install` 或手动拷贝更可靠。

## 6. 日常命令速查

| 目的 | 命令 |
| --- | --- |
| 环境自检 | `python runtime/env_config.py` |
| 出 debug APK | `python runtime/run_gradle.py :app:assembleDebug` |
| JVM 单测（纯逻辑，不需要设备） | `python runtime/run_gradle.py :app:testDebugUnitTest` |
| 单个 JVM 测试类 | `python runtime/run_gradle.py :app:testDebugUnitTest --tests <fqcn>` |
| 编 androidTest APK | `python runtime/run_gradle.py :app:assembleDebugAndroidTest` |
| 设备端跑某个测试类 | `python runtime/verify_model_on_emulator.py <fqcn>` |
| 强制全量重编译（排除缓存假象） | `python runtime/run_gradle.py --rerun-tasks :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` |
| 一键构建（旧脚本） | `python runtime/build_demo.py` |
| 非 ASCII 路径问题的应急镜像构建 | `python runtime/build_ascii.py` |
| B0 真人阈值标定 | `python runtime/calibrate_threshold.py` |

`run_gradle.py` 会把完整日志写到 `runtime/logs/gradle-<task>-<时间戳>.txt`，并在控制台打印关键行。

设备端测试类（都需先起 `RealCue_API35`）：

| 类 | 验证什么 |
| --- | --- |
| `ModelFeasibilityTest` / `Sdk124SmokeTest` | native 库、Pikachu/Megatron 模型加载、检测、106 关键点、512 维特征 |
| `FaceRepositorySdkTest` | FeatureHub 持久化：插入 → 检索 → 关库重开仍是同一 ID |
| `GalleryAccuracyProbeTest` | 同人 7 种画面变体命中同一库 ID |
| `AutoIdReentryTest` | 自动建档 → Session 重置 → 重入同 ID |
| `UnifiedStableIdTest` | 图片通路 feature/crop/关键点、同人换画面重入仍 `P001` |
| `PersonFlowAbacTest` | 完整 A→B→A→C 出镜流程 |
| `GalleryThresholdCalibrationTest` | B0 阈值标定链路（当前无真人异人样本，结果**不可用于设阈值**） |

## 7. 匹配原理（简述）

1. SDK 对每张脸提取 **512 维特征向量**；`FaceImageProcessor.detectWithLandmarksAndFeatures()` 同时产出 106 点关键点与 aligned crop。
2. `FaceRepository` 用 FeatureHub 把特征持久化到 `features.db`（`PK_MANUAL_INPUT` + `setEnablePersistence(true)`），库检索用余弦相似度 `cos(f1,f2) = (f1·f2)/(‖f1‖·‖f2‖)`，取 Top1。
3. `PersonIdResolver`（纯 Java、零 Android 依赖）按**三区阈值**决策：`>= T_match` 命中库 ID；`< T_new` 判新人并自动建档；中间灰色区继续采样。还做同帧 `person_id` 互斥防串号、TTL、`touch()` 保活、连续多次 miss 才建档。
4. `track_id` 只是当前 camera Session 的临时工牌；`person_id`（显示成 `P001`）才是跨 Session、跨重启的户籍号。

完整推导、阈值边界与实测数据见 [Research-PersonGallery-Matching.md](Research-PersonGallery-Matching.md)。

## 8. Git 约定

项目约定：**每次写完代码/文档都要及时 `git add` → `git commit`，并按约定 `git push` 到远端 `origin/main`**，不要长期留一堆未提交改动。同时：

- 不要把 `runtime/` 下的大目录、`*.apk` / `*.aar` / `*.zip` / `*.img` / `*.qcow2` 误 `git add`；
- 本机配置用 `*.example` 模板，真实文件已被 `.gitignore` 排除；
- 提交前可用 `git status --short` 与 `git ls-files` 自查体积。

远端：`git@github.com:PujunDing/RealCue.git`，分支 `main`。

## 9. 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| `aapt2` 报 `android.jar` 找不到 / 单测 `ClassNotFoundException` | 路径含非 ASCII 字符 | 迁移到纯 ASCII 路径；或用 `build_ascii.py` |
| Gradle 报 JDK 版本不兼容 | 用了 Android Studio 自带的 JBR 21 | 设 `REALCUE_JAVA_HOME` 指向 JDK 17 |
| 首次构建 `No cached version ... available for offline mode` | 新机器无依赖缓存 | `REALCUE_GRADLE_ONLINE=1` 跑一次 |
| `Local InspireFace AAR does not exist` | `sdk.local.properties` 指向的 AAR 没放 | 放 AAR 到 `runtime/downloads/`，或 `REALCUE_SDK_SOURCE=jitpack` |
| `git push` 被拒 / 文件过大 | APK 116 MB、AAR 83 MB 超过 100 MB 单文件限制 | 用 `.gitignore`；二进制走 Release 附件或 Git LFS |
| 模拟器起不来或没有相机 | 用了 XR 头显镜像 | 用 `RealCue_API35` 手机镜像 |
| 灰色区阈值不生效 | FeatureHub 搜索阈值设为推荐值，低分候选被提前过滤 | 改 `FaceRepository.open()` 的阈值或改查 TopK 原始结果 |
| 想让「新人复用 2 号」 | 当前实现是「下一个未占用自增主键」→ `P003` | 见第 10 节，需要确认语义 |

## 10. 当前状态与诚实边界

**已完成并在设备上验证**：模型 native 推理、FeatureHub 持久化、统一长期编号（图片 + 视频）、A→B→A→C 完整流程（`A:P001 B:P002 C:P003 gallery=3 enrollCalls=3 RESULT=PASS`）、强制 `--rerun-tasks` 全量重编译（`65 actionable tasks: 65 executed`）及新包复测 4/4 通过。证据在 `runtime/logs/` 与根目录 HTML 报告。

本轮补环境文档时把 6 个脚本的本机硬编码路径收敛到 `runtime/env_config.py`，随后再次用 `--rerun-tasks` 全量复验：`BUILD SUCCESSFUL in 5m 23s`，`65 actionable tasks: 65 executed`，RC=0（`runtime/logs/gradle---rerun-tasks-20261116-151804.txt`）。改造未改变任何 `app/src/` 业务源码。

**仍未收口**：

- **B0 真人阈值标定未完成**：目前只有 `kun.jpg` 一张真人样本，`T_match=0.48` / `T_new=0.36` 是官方推荐值/占位值；B、C 在流程测试里是合成向量，**不能用来定阈值**。需按 [runtime/calibration/README.md](InspireFace-LocalRun/runtime/calibration/README.md) 采集两个真人的多样本再跑 `calibrate_threshold.py`。
- 真机（Xiaomi 14 Pro）重装最新 APK 后的 image/video tab 行为、多人同框、帧率、跨光照/跨姿态准确率尚未实测。
- 「再来个新人 2 号」当前按「下一个未占用号 `P003`」实现；若本意是复用 2 号或每轮重新编号，需再确认。
- `plus.local.properties`（业务凭证）与 release 签名配置为可选，默认不影响 debug 构建。

## 11. 文档与报告索引

| 文件 | 内容 |
| --- | --- |
| [Research-Mobile-Realtime-FaceID.md](Research-Mobile-Realtime-FaceID.md) | 需求与调研依据 |
| [Research-PersonGallery-Matching.md](Research-PersonGallery-Matching.md) | 人物库匹配专题（含附录 A–F） |
| [Gallery-Accuracy-Report.html](Gallery-Accuracy-Report.html) | 库 ID 可见性与同人变体稳定性 |
| [B0-Calibration-Test-Report.html](B0-Calibration-Test-Report.html) | B0 测试开发实录 |
| [StageB-AutoId-Report.html](StageB-AutoId-Report.html) | 阶段 B 新页面落地（注：后续已改造用户实际在用的旧页面） |
| [Unified-StableId-Report.html](Unified-StableId-Report.html) | 统一所有编号显示为长期 `person_id` |
| [PersonFlow-ABAC-Report.html](PersonFlow-ABAC-Report.html) | A→B→A→C 完整流程验证 |
| [Rebuild-Verify-Report.html](Rebuild-Verify-Report.html) | 强制全量重编译与复核证据 |
| [InspireFace-LocalRun/LocalRun-Plan.md](InspireFace-LocalRun/LocalRun-Plan.md) | 试跑全过程主记录 |
