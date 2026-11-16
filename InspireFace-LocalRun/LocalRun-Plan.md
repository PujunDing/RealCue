# InspireFace 本机试跑：进度还原与继续规划

整理日期 2026-11-15。本文件用于接手 `InspireFace-LocalRun/` 下未完成的工作，
避免会话中断后丢失上下文。上方调研见 `../Research-Mobile-Realtime-FaceID.md`。

> 说明：目录里**没有**独立的规划文档。以下内容根据已存在的文件、目录骨架、
> 下载物、Gradle 配置痕迹还原，属于「从现场痕迹反推」，不是原样抄录的原文计划。

## 目标

在 Windows 本机搭一套**项目内自包含、尽量离线**的 Android 试跑环境，把
InspireFace 官方 Android Demo（1.2.4.post1）编译成 APK，并在模拟器/真机上跑起来，
用于验证移动端实时人脸检测（CameraX 逐帧跟踪、画框、Track ID）。

这一步是调研文档结论的落地前置：官方 Demo 的「Face recognition / Video stream」
只跟踪 face 0，要做本项目真正需要的「多人实时画框 + 首次自动编号 + 离场重现沿用编号」，
必须先把官方 Demo 跑通、摸清它的 Session / FeatureHub / 跟踪接口，再改应用侧逻辑。

## 目录设计（前一个助手留下的骨架）

```
InspireFace-LocalRun/
├── android-demo/            官方 Demo 源码（git 单提交 8d3db87 "update to 1.2.4.post1"，工作区干净）
└── runtime/                 自包含运行时，全部为空目录或下载物
    ├── android/             ← 计划放 Android SDK（platforms / build-tools / platform-tools）
    ├── avd/                 ← 计划放模拟器 AVD
    ├── gradle/              ← 计划作为 GRADLE_USER_HOME
    ├── java-user/           ← 计划放 JDK / java.io.tmpdir
    ├── logs/                ← 计划放构建日志
    ├── sdk/                 ← 计划放 SDK 命令行工具或解压后的依赖
    ├── downloads/           ✅ 已下载 3 个包（见下）
    └── tmp/                 ✅ adb.log、AndroidEmulator 崩溃库
```

`downloads/` 已下载：

| 文件 | 大小 | 内容 / 用途 |
| --- | --- | --- |
| `platform.api35.zip` | 64.3 MB | Android SDK Platform 15 / API 35 rev 2，根目录 `android-35/`，11162 项，含 `android.jar` |
| `inspireface-1.2.4.post1.aar` | 82.9 MB | 完整 SDK AAR：classes.jar、Pikachu / Megatron 模型、三 ABI .so、consumer rules |
| `repository2-1.xml` | 384 KB | Android SDK 官方仓库清单，用于离线查组件下载 URL（**尚未被用来解析下载**） |

- `gradle.properties` 中 `inspirefaceSdkSource=jitpack`，但 AAR 已下载到本地，
  说明计划走 **`-PinspirefaceSdkSource=local` + `sdk.local.properties`**，避免 JitPack 联网。
- 项目 `compileSdk 35` / `targetSdk 35`，而本机全局 SDK 只有 `android-36`、`android-36.1`，
  所以必须补 API 35 平台——这正是 `platform.api35.zip` 的用途。
- `~/.gradle/gradle.properties` 里留了代理 `127.0.0.1:7897` 和
  `bak2 / bak3 / bak4 / noproxy` 四份备份，说明曾与依赖下载/代理问题反复调试。
- `~/.gradle/wrapper/dists` 里 `gradle-8.9-bin.zip.part` 停在 102 MB（未下完），
  完整可用的只有 `gradle-8.2-bin`；本项目 wrapper 要的是 **8.7**，尚未下载。
- `runtime/tmp/adb.log` 显示 adb 起过（全局 SDK 的 platform-tools），
  `AndroidEmulator/emu-crash-36.5.11.db` 显示模拟器尝试启动过。

## 进度判定：做到一半停在哪

**已完成**：克隆官方 Demo、建好自包含目录骨架、下载三个关键依赖包、探测过 adb 与模拟器、
配置过 Gradle 代理。

**未完成（断点）**：

1. `platform.api35.zip` 未解压，`runtime/android/` 仍是空目录。
2. 缺 build-tools、platform-tools、SDK licenses；`repository2-1.xml` 未被解析使用。
3. 无 `local.properties`（未指定 sdk.dir），无 `sdk.local.properties`（未启用本地 AAR）。
4. Gradle 8.7 发行版未下载；`android-demo/` 下无 `app/build/`，**从未成功构建过**。
5. 无 AVD、无 APK、无安装/运行记录。
6. 官方 Demo 源码一行未改，多人画框与自动编号的功能改造尚未开始。

## 本机可用资源（本次核查，2026-11-15）

| 资源 | 位置 | 状态 |
| --- | --- | --- |
| JDK 17 | `D:\software\Android\Android Studio\jbr`（另有 `C:\Users\pjd02\.jdks\jbr-17.0.14`） | ✅ 满足 AGP 8.6.1 |
| Android SDK（全局） | `C:\Users\pjd02\AppData\Local\Android\Sdk` | ✅ 有 build-tools 34.0.0 / 36.1.0 / 37.0.0、platform-tools 37.0.0、emulator、licenses |
| system-image（全局） | `system-images/android-34/google-xr/x86_64` | ✅ 可用，x86_64，匹配 app 的 abiFilters |
| platforms（全局） | `android-36`、`android-36.1` | ⚠️ 缺 API 35 |
| Gradle 缓存 | `~/.gradle/caches/modules-2` | ✅ 99 个 group，含 androidx / com.android.tools.build 等 |
| 网络 | 直连 `services.gradle.org` 200（134 MB）、JitPack 200；代理 7897 开放 | ✅ 可下载缺失依赖 |
| 项目 wrapper | `gradle-8.7-bin.zip` | ❌ 需下载 |

策略：**延续自包含路线**，但复用全局已装好的重组件（build-tools / platform-tools /
emulator / system-image / Gradle 缓存），只把缺的 API 35 平台和本地 AAR 放进 `runtime/`。
这样既保留项目内可搬迁的环境，又避免重复下载数 GB 镜像。

## 继续执行的步骤

### 阶段 A：把构建跑通（当前接手的工作）

1. 解压 `platform.api35.zip` → `runtime/android/platforms/android-35/`。
2. 从 `repository2-1.xml` 解析并补齐 build-tools（AGP 8.6.1 默认 35.0.0）与
   platform-tools；或复用全局对应目录并建立目录联接；补 `licenses/`。
3. 写 `android-demo/local.properties`（`sdk.dir` 指向 `runtime/android`）与
   `android-demo/sdk.local.properties`（`aarPath` 指向 `runtime/downloads/inspireface-1.2.4.post1.aar`）。
   两个文件均被 `.gitignore` 忽略，不会进版本库。
4. 下载 Gradle 8.7 发行版，设置 `JAVA_HOME` / `ANDROID_HOME` / `GRADLE_USER_HOME`，
   日志落 `runtime/logs/`。
5. 执行 `./gradlew -PinspirefaceSdkSource=local :app:assembleDebug :app:testDebugUnitTest`，
   产出 `app/build/outputs/apk/debug/app-debug.apk`。
6. 用全局 emulator + android-34 x86_64 镜像在 `runtime/avd/` 建 AVD，启动后
   `adb install` 并冷启动，确认首页、Face tracking / Face recognition 摄像头页面可用。

### 阶段 B：功能改造（构建跑通后）

依据调研文档「手机原型怎么验证」与 Demo 现有结构：

1. **逐脸处理**：`view/RecognitionFaceAnalyzer.java` 目前只取 face 0、稳定约 1 s 后
   单次检索。改为遍历 `MultipleFaceData` 的每一张脸，按 `trackId` 关联状态。
2. **短时跟踪 + 叠框**：复用 `view/FaceTrackingAnalyzer.java`、`FaceTrackingGlView`、
   `TrackIdOverlayView`、`TrackBoxSmoother` 的多脸画框与三帧中值滤波，
   把标签从 SDK 的 `track_id` 换成长期 `person_id`。
3. **自动编号**：接 `face/FaceRepository.java`（已按 Pikachu / Megatron 隔离、FeatureHub
   持久化、CRUD 齐全）。新轨迹取到清晰稳定样本 → 先 1:N 查库 → 命中沿用旧号，
   连续多次未命中才分配 `P001 / P002 …` 并写库；边界分数/小脸/模糊继续采样。
4. **防串号**：多人同框时对新匹配做一对一复核，避免两个人共用同一编号。
5. **验证**：按调研文档第 47 行的「真实重入」脚本（A/B 入镜、同框交叉、A 离场后
   从另一侧回来、C 首次入镜、重启应用后 A 回来），记录旧号恢复率、重复编号率、
   共用编号率，以及首次出框 / 显示身份的延迟；同人异人样本本地校准阈值。

## 验证边界（不要越界声称）

- 静态构建、单测、模拟器启动 ≠ 真机帧率与识别准确率。
- 官方模型包限学术用途；商用需另行核实许可。
- PLUS 云活体为收费接口，本机试跑不调用；`plus.local.properties` 保持为空。
- 模拟器摄像头需配置虚拟摄像头或投屏真实人脸画面，否则只能验证 UI 与推理链路，
  不能验证真人实时检测效果。

## 接手执行记录（2026-11-15）

按上述路线补齐环境，新增两个可复现脚本（均在 `runtime/`，重跑安全）：

- `runtime/setup_env.py` — 校验并解压 API 35 平台；从 `repository2-1.xml` 解析出的
  Windows 包地址下载 `build-tools_r35_windows.zip`（AGP 8.6.1 默认 build-tools 35.0.0）、
  `platform-tools_r37.0.3-win.zip`、`gradle-8.7-bin.zip`；复制全局 SDK licenses；
  生成 `android-demo/local.properties`（中文路径用 `\uXXXX` 转义，避免 Java Properties
  按 ISO-8859-1 读错）与 `android-demo/sdk.local.properties`（相对路径指向本地 AAR）。
- `runtime/build_demo.py` — 用 `runtime/gradle/gradle-8.7/bin/gradle.bat` 执行
  `:app:assembleDebug :app:testDebugUnitTest`，统一设置 `JAVA_HOME` / `ANDROID_HOME`，
  日志写入 `runtime/logs/build-<时间戳>.log`。

补充核查结论：

- **JDK 必须用 17**：`C:\Users\pjd02\.jdks\jbr-17.0.14`（17.0.14）。
  `D:\software\Android\Android Studio\jbr` 实测是 **OpenJDK 21**，不是 17，别选错。
- 全局 Gradle 缓存缺 `androidx.camera`、`okhttp3`、`material`、`HyperInspire`，
  构建时需联网补下载；HyperInspire 走本地 AAR，不解析 JitPack。
- `GRADLE_USER_HOME` 沿用默认 `~/.gradle`（复用已有缓存与代理设置），
  `runtime/gradle/` 只放独立 Gradle 发行版，两者不混用。
- 源码复核：`view/RecognitionFaceAnalyzer.java:102,150` 确实只取
  `faces.rects[0]` / `faces.tokens[0]`，类注释也写明 "Tracks only SDK face 0"。
- `face/FaceRepository.java:60` 已有 `KEY_NEXT_ID`（`next_id`）自增序列，
  阶段 B 的 `P001/P002` 自动编号可直接复用该序列，无需另建计数器。

### 模拟器卡点还原

- `runtime/tmp/AndroidEmulator/emu-crash-36.5.11.db` 的版本号，与全局 SDK
  `emulator.exe -version` 报出的 **36.5.11** 完全一致 → 前一个助手确实启动过模拟器并崩溃。
- 全局 SDK **没有 `cmdline-tools`**（无 `avdmanager` / `sdkmanager`），
  `system-images` 里只有 **Android XR Headset / Google Play XR**（API 34、x86_64、
  `system.img` 8.6 GB、`Pkg.Dependencies=emulator#35.6.7`）。
  `~/.android/avd/` 里已有 `XR_Headset.avd` + `XR_Headset.ini`。
- XR 头显镜像不是普通手机形态，没有常规前后摄与竖屏布局，**不适合**验证
  CameraX 手机端实时人脸检测；且 8.6 GB 镜像对硬件加速要求高。
- 当前 `adb devices -l` 为空，既无真机也无运行中的模拟器。

后续二选一：① 下载常规手机镜像（如 `android-34`/`android-35` 的
`google_apis` x86_64，约 1–1.5 GB）并用手写 AVD `config.ini` 启动（无 avdmanager）；
② 直接连真机（`adb` 已就位），这也是调研文档本来就要求的最终验证方式。
在拿到 APK 之前不要启动模拟器，避免与 Gradle 构建抢占资源。

补充：`emulator.exe -accel-check` 实测 **WHPX(10.0.26200) is installed and usable**，
所以前人那次崩溃不是缺硬件加速。`-list-avds` 只有 `XR_Headset`，
其 `config.ini` 有 `hw.camera.front=emulated`、`hw.camera.back=none`、
`hw.initialOrientation=landscape`、`hw.screen=no-touch`——前置虚拟摄像头可让
CameraX 预览链路跑起来，但虚拟画面不含真人脸，只能验证「不崩溃 / 推理链路」，
不能验证检测准确率。重试建议加 `-no-window -no-audio -gpu swiftshader_indirect`
规避窗口/GPU 相关的崩溃。

### 首次构建失败与修复

第一次 `:app:assembleDebug` 在配置阶段就被 AGP 拒绝，日志
`runtime/logs/build-20261115-150449.log`：

```
Your project path contains non-ASCII characters. This will most likely cause the
build to fail on Windows. ... This warning can be disabled by adding the line
'android.overridePathCheck=true' to gradle.properties
```

原因是改名前的工作区路径含中文。现工作区已迁移到纯英文的
`D:\paper\RealCue\InspireFace-LocalRun\`，后续可直接用 `runtime/build_demo.py` 构建；
`D:\paper\ifd-build\` 仅保留为旧构建镜像，不再是必要路径。
这很可能也是前一个助手没跑通的直接卡点之一。

修复：`runtime/build_demo.py` 在命令行加 `-Pandroid.overridePathCheck=true`
（AGP 官方开关，从命令行传入，**不改上游 `gradle.properties`**，保持源码干净）。
若跳过检查后 AAPT2 等原生工具仍因中文路径失败，退路是把 `android-demo/` 与
`runtime/android/` 复制到纯 ASCII 目录（如 `D:\ifd-build\`）构建，再把 APK 拷回。

**该退路已执行并验证有效**：`runtime/build_ascii.py` 把源码、SDK、Gradle 8.7、
AAR 镜像到纯 ASCII 的 `D:\paper\ifd-build\`，在那里构建得到

- `BUILD SUCCESSFUL in 1m 3s`，40 个 task 全部执行；
- **单测 53 项：0 失败、1 跳过**（与上游 README 记录的正式发布包结果一致）；
- `app-debug.apk` **116,427,397 字节**，已拷回
  `android-demo/app/build/outputs/apk/debug/app-debug.apk`；
- 日志 `runtime/logs/build-ascii-20261115-151707.log`。

APK 内容校验：9 个 `classes*.dex`、3 个 ABI 的 `libInspireFace.so`
（arm64-v8a / armeabi-v7a / x86_64）、`assets/inspireface/` 下
Pikachu / Megatron 模型包、`AndroidManifest.xml`、`resources.arsc` 齐全。
`D:\paper\ifd-build\` 保留作为**可复用的干净构建目录**（可安全删除）；
后续改代码建议直接在该镜像里改、构建，再把 APK 拷回本工作区，避免中文路径反复踩坑。

`runtime/verify_on_emulator.py` 负责启动 AVD、安装 APK、冷启动并抓日志。

### 模拟器验证结果：受阻（未完成）

`runtime/verify_on_emulator.py` 以 `-no-window -no-audio -gpu swiftshader_indirect`
启动 `XR_Headset`，`emulator-5554` 能上线，但 **600 s 内 `sys.boot_completed`
始终为空**，最终模拟器进程退出。日志 `runtime/logs/emulator-20261115-151924.log`
与 logcat 显示：

- `sys.init.updatable_crashing=1`，audio HAL 服务反复 `not found`；
- `surfaceflinger` 反复 `Created VkDevice` / `Destroyed VkDevice`；
- SwiftShader Vulkan 持续报 `UNSUPPORTED: curExtension->sType`。

结论：**Android XR 头显镜像 + 软件渲染不兼容**，与前人留下的
`emu-crash-36.5.11.db` 是同一个卡点。`repository2-1.xml` 里
**不含 system-images 包**，要下载常规手机镜像需另取
`https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-1.xml`
并手写 AVD `config.ini`（本机无 `avdmanager`）。这一步未继续投入，原因是
即使跑起来也只是 XR 头显形态、虚拟前置摄像头不产出真人脸，验证价值有限。

**手机模拟器验证已通过**：`RealCue_API35` 在约 82 s 完成 boot；
`adb devices -l` 识别为 `sdk_gphone64_x86_64`；APK 安装返回 Success；
`.HomeActivity` 冷启动后 `pidof` 得到 PID，前台 Activity 为
`com.example.inspireface_example/.HomeActivity`。日志
`runtime/logs/emulator-20261115-155149.log` 记录 `Boot completed in 81455 ms`。
验证脚本后续补充了设备属性与首页截图采集：设备报告
`ro.product.model=sdk_gphone64_x86_64`、`Android 15 / API 35`；
截图 `runtime/logs/RealCue-emulator-home-20261115-155740.png`（63,554 字节）
显示 InspireFace 启动页。结果均写入 `runtime/logs/`。

**尚未执行**：真机上的安装、冷启动、实时画框与重入测试。虚拟摄像头
不产出真人脸，模拟器只能验证安装/启动/SDK 加载与 UI 链路；
调研文档本来就要求最终用真机验证帧率与身份匹配。

### 英文路径迁移与手机镜像（2026-11-15）

- 工作区已迁移为 `D:\paper\RealCue\`；内部名称改为
  `InspireFace-LocalRun/`、`LocalRun-Plan.md`、`Research-Mobile-Realtime-FaceID.md`。
  `android-demo/local.properties` 已改为纯 ASCII 的 D 盘路径。
- 新增 `runtime/setup_phone_emulator.py`：下载物为 Android 15 / API 35 /
  Google APIs / x86_64 rev 9，ZIP 1,738,815,903 字节；SHA-1
  `0103e6dab21290c4b9d16550a3ce99476f884eef` 已校验通过。
- 镜像安装在 `runtime/android/system-images/android-35/google_apis/x86_64/`；
  AVD `RealCue_API35` 位于 `runtime/avd/`，配置为 1080x2400 竖屏手机、
  前后虚拟摄像头、4 核、4 GB 内存。无需 avdmanager。
- 新增的 Gradle 缓存 `runtime/gradle-home/`、模拟器临时文件 `runtime/tmp/`、
  镜像与 AVD 均位于 D 盘；C 盘只读取现有 emulator.exe 与 JDK 17。
- XR 镜像不可用的直接原因不是 WHPX，而是头显系统与当前软件图形栈反复崩溃；
  其 `hw.camera.back=none`、横屏 no-touch 配置也不适合手机 CameraX 验证。
- 按用户要求避免继续增加 C 盘负担：`runtime/build_demo.py` 与
  `runtime/build_ascii.py` 把 `GRADLE_USER_HOME`、`TMP/TEMP` 指向 D 盘，
  并通过 `GRADLE_RO_DEP_CACHE` 只读复用 C 盘已有的 1.89 GiB 依赖缓存。
  构建使用 `--offline`，不重新下载、不向 C 盘写入新数据。
- **纯英文路径直接构建已成功**：`BUILD SUCCESSFUL in 11m 27s`，
  40 个 task（38 executed、2 up-to-date）；单测 10 个类 / 53 项 / 0 失败 / 1 跳过；
  APK 116,427,397 字节，含 9 个 dex、3 个 ABI 的 `libInspireFace.so`、
  4 个 `assets/inspireface/` 模型文件。日志
  `runtime/logs/build-20261115-161153.log`。中文路径不再是构建障碍。

### 交给下一步的入口

```sh
# 1) 在纯英文 RealCue 路径直接构建并运行单测
python runtime/build_demo.py
# 2) 安装手机镜像/重建 AVD（已执行，幂等）
python runtime/setup_phone_emulator.py
# 3) 启动手机模拟器 + 安装 APK + 冷启动 + 抓日志
python runtime/verify_on_emulator.py
# 4) 真机开 USB 调试后，自动选第一台设备，也可传 serial
python runtime/verify_on_device.py
python runtime/verify_on_device.py <serial>
```

`runtime/verify_on_device.py` 会安装 `app-debug.apk`、启动 `.HomeActivity`、
检查进程存活并筛出 `AndroidRuntime / FATAL / InspireFace / FaceEngine / CameraX`
日志。真机拿到后按调研文档「手机原型怎么验证」做 A/B 入镜、离场重现、
C 首次入镜、重启后 A 回来的重入测试。

阶段 B 的代码改造（逐脸处理、自动编号、防串号）仍未开始，规划见上文。

### 模型算法可行性验证（2026-11-15）

**先澄清一个此前的表述边界**：之前所谓「跑通」只到工程链路——APK 构建、
模拟器启动、APK 安装、`HomeActivity` 冷启动。53 项 JVM 单测全是逻辑 / 契约 /
工具类测试（`DetectorDefaultsTest`、`JniDataContractTest`、`TrackBoxSmootherTest`
等），**不加载 `libInspireFace.so`**，不构成模型验证。此前也从未进入摄像头页面、
未喂入任何人脸帧。所以「模型算法是否可行」在本次补做之前**没有检验过**。

本次在手机模拟器上补跑设备端 instrumented test：

**① 上游 `Sdk124SmokeTest`（5 项，4 通过 / 1 失败）**

- 失败项 `bundledModelsSupportAppSessionsAndPipelines` 报
  `Tracking count must advance`（`checkPlusPreprocessing` 第 297 行）：
  在 `createPlusSession()` 下把**同一张静态图**连喂两帧，要求 `trackCount` 严格递增。
- 失败发生在第 93 行，说明其之前 Pikachu 下的 6 类 session 全部通过：
  检测 / LIGHT_TRACK / TRACK_BY_DETECTION / 识别 / 视频识别 / 预览，
  含 `detectedNum > 0`、106 点关键点、`FaceComparison(feature, feature) > 0.99`。
- 另 3 项通过：native 版本与 C API level 2、portable JNI 图像转换、
  capture/snapshot 扩展、session 释放隔离。
- 日志 `runtime/logs/model-gradle-20261115-163115.log`。

**② 自建 `ModelFeasibilityTest`（1 项，全通过，BUILD SUCCESSFUL）**

覆盖 **Pikachu 与 Megatron 两个模型包**，logcat tag `RealCueModel` 实测数值：

| 指标 | Pikachu | Megatron |
| --- | --- | --- |
| 模型加载 | `Successfully loaded resources` | 同左 |
| 检测（内置 kun.jpg） | `detectedNum=1` | `detectedNum=1` |
| 稠密关键点 | 106 | 106 |
| 特征维度 / 自比对 | 512 / 1.0 | 512 / 1.0 |
| LIGHT_TRACK 三帧 | trackId=1 恒定，trackCount 1→2→3 | 同左 |
| 纯黑图 | `blankDetectedNum=0` | `blankDetectedNum=0` |

日志 `runtime/logs/model-gradle-20261115-163639.log`。这同时反证第 ① 项的失败
是「PLUS session + 静态重复帧」的测试假设过严，**不是模型能力问题**。

**结论分三层，不要混为一谈**：

- ✅ **已验证**：InspireFace 底座模型算法在本机 x86_64 模拟器上可推理——
  native 库加载、两个模型包加载、人脸检测、106 点关键点、512 维特征提取与
  自比对、LIGHT_TRACK 跟踪链路全部跑通。
- ❌ **未验证**：真机摄像头实时帧、多人同框、跨帧身份重入、相似度阈值标定、
  帧率与识别准确率。虚拟摄像头不产出真人脸，静态单样本图不能代表实战。
- ❌ **未实现 / 未部署**：项目最终目标算法「多人逐脸画框 + P001/P002 首次自动
  编号 + 离场重现沿用编号」——阶段 B 代码尚未开始，`RecognitionFaceAnalyzer`
  仍只处理 `faces.rects[0]` / `tokens[0]` 单脸。

新增文件：`app/src/androidTest/.../view/ModelFeasibilityTest.java`（项目自建验证，
**非上游 Demo 代码**；`main/` 源码仍一行未改）、
`runtime/verify_model_on_emulator.py`。一键复跑：
`python runtime/verify_model_on_emulator.py`。

### 阶段 B 调研：人物库匹配与自动编号（2026-11-15 夜间）

用户已自行把 APK 传到 Xiaomi 14 Pro（`此电脑\Xiaomi 14 Pro\内部存储设备\openxr`），
工程链路确认可跑；反馈「第一次进入 ID 1，离场再进来还是本人却变成 ID 2」。

**诊断**：当前页面显示的数字是 SDK 的短时 `MultipleFaceData.trackIds[]`，只在当前
camera Session 内稳定（`FaceTrackingAnalyzer.java:105-106,189-190`），离场 / 切页 /
重建 Session 必然重新分配；官方 Demo 的人物库是 `FaceManagementActivity` 手动录入，
视频识别 Session `maxFaces=1`（`FaceEngine.java:130-133`）、只取 face 0
（`RecognitionFaceAnalyzer.java:102,149-151`）、`libraryEmpty` 是构造时快照
（`FaceRecognitionActivity.java:255,652`），且**没有自动建档调用点**。
所以这不是模型认不出人，而是显示的 ID 根本不是人物库 ID。

**关键实测**：给 `runtime/verify_model_on_emulator.py` 加 `argv[1]` 指定测试类后，
跑官方自带 `FaceRepositorySdkTest`，`BUILD SUCCESSFUL in 1m 30s`、
`tests=1 failures=0`（日志 `runtime/logs/model-gradle-20261115-195927.log`）。
该测试证明 FeatureHub：空库不误匹配 → insert → `search` 命中同一 ID 且
confidence=1.0 → `close()` / 重开后仍命中同一 ID 并保留名字 → delete 后不再匹配，
Pikachu / Megatron 各一遍。**人物库底层能力已具备且跨会话可用**，缺的只是应用层
把 `track_id` 标签换成 `person_id` + 逐脸处理 + 自动建档。

**交付**：`../Research-PersonGallery-Matching.md` —— 现象定位、9 条缺口清单、
`PersonIdResolver` 分层设计、三区阈值决策、防串号、逐文件改动清单、
A/B/C 重入验证、风险边界、B0~B3 分阶段计划。

**仍未开始**：阶段 B 业务代码（`main/` 仍一行未改）；真机阈值标定（同人跨帧 /
异人分数分布）是唯一还缺的实验数据，建议先做 B0。

MTP 直传尝试记录：PowerShell `New-Object -ComObject Shell.Application` 被安全策略
拦截；装 comtypes 到 `runtime/pylib` 后枚举「此电脑」只有 5 项、无 Xiaomi 设备
（MTP 会话隔离），PnP 层确认 `USB\VID_2717&PID_FF40\85F39845` 已连接。
最终由用户手动完成上传。

---

## 续：用户追问「人物库 ID 能不能看到 / 准不准」

**能不能看到**：能，当前 APK 不用改代码就能看到（附录 A）。位置：
Face management 列表 `ID · %d`；Face recognition 命中
`ID · %d · cosine confidence %.3f · threshold %.3f`；未命中也显示分数与阈值；
Face 1:1 显示 cosine + 推荐阈值。**但实时画框上的数字是 `track_id`**，
要自动显示库 ID 必须做阶段 B + 重新构建/传 APK。

**准不准**：新建设备端测试 `androidTest/.../view/GalleryAccuracyProbeTest.java`
（`main/` 仍一行未改），在隔离 FeatureHub 里只录一个身份 `PERSON_ID=1001`，
对 `kun.jpg` 做 7 种画面变体后重新提特征查库。结果（`model-gradle-20261115-201325.log`
/ `model-logcat-20261115-201325.txt`）：

- `GetRecommendedCosineThreshold() = 0.48`，特征 512 维。
- 7/7 变体全部命中同一 ID 1001：base 1.0000 / jpeg_q60 0.9845 / bright_+40 0.9839 /
  dark_-40 0.9732 / scale_0.75 0.9952 / rotate_4deg 0.9937 / mirror 0.9680。
  最低分仍高出阈值 0.4880；`FaceComparison` 与 FeatureHub confidence 逐行相等。
- 纯黑图 `NO_FACE`，未误建档。

**结论边界**：证明「沿用旧号」在模型层是稳的，**不等于「已经准」**。
这批是同一张静态图做数字变换，不是真机跨帧/跨姿态；且本机只有一张人脸样本，
**异人分数分布完全没测**，阈值 0.48 对这批数据偏宽松，陌生人误接受风险未知。
剩余风险在应用层决策 + B0 真机异人标定，二者都要两个真人样本才能收口。

**脚本修正**：`runtime/verify_model_on_emulator.py` 的 logcat 关键字从 `RealCueModel`
泛化为 `RealCue`，并把完整 logcat 落盘 `runtime/logs/model-logcat-<stamp>.txt`
（第一次 `model-gradle-20261115-200855.log` 虽成功但数值行没进摘要，修正后重跑才拿到）。

**交付**：`Research-PersonGallery-Matching.md` 补齐附录 A「当前 APK 能否看到库 ID」
与附录 B「准确性实测结果表 + 能/不能说明的边界」；`Gallery-Accuracy-Report.html`
为可单独打开/传手机的实测报告。阶段 B 业务代码仍未开始。

---

## 续：B0 阈值标定「测试开发」落地并实跑通过

用户授权「按你的步骤来，记得你要做好测试开发工作的」。因此本轮不是只写方案，
而是把 B0 做成能编译、能实跑、有断言、有产物、有防误用保护的一套测试：

**新增文件（均不改 `main/` 业务源码）**

| 文件 | 作用 |
| --- | --- |
| `android-demo/app/src/androidTest/.../view/CalibrationMath.java` | 纯统计类，不依赖 Android / InspireFace；`analyze()` 两个重载、`percentile()`，输出 `sameMin` / `diffMax` / `separable` / `margin` / `matchThreshold` / `newThreshold` / `recallAtMatch` / `falseAcceptAtMatch` |
| `android-demo/app/src/androidTest/.../view/GalleryThresholdCalibrationTest.java` | 3 项设备端测试：`statisticsLogicIsCorrect` / `builtInSampleProducesValidIntraPersonScores` / `calibrationPipelineWritesResultFile` |
| `runtime/calibrate_threshold.py` | 一键标定：预装 APK → push 样本 → 跑设备端测试 → 回收 JSON/CSV → 打印结论 + `calibration-summary.md` |
| `runtime/tmp/compile_androidtest.py` | 可复用编译闸门，启动模拟器前先 `:app:assembleDebugAndroidTest`，避免白等 |

**最终一轮实跑**（`runtime/logs/calibration-20261115-204457/`）：

- `:app:installDebug :app:installDebugAndroidTest` → `BUILD SUCCESSFUL in 25s`
- push A=3 / B=2，`failures=0`
- `connectedDebugAndroidTest` → `BUILD SUCCESSFUL in 1m 29s`，设备端 XML
  `tests="3" failures="0" errors="0" skipped="0"`
- logcat 镜像恢复出 `threshold-result.json`（905 B）与 `threshold-pairs.csv`（217 B），
  JSON 可 `json.load()`，CSV 含 header + 4 same + 6 diff
- 结论正确打印 `NOT USABLE FOR THRESHOLD`：A/ 与 B/ 是同一张脸，
  `doNotUseForThreshold=true`、`separable=false`、`margin=0.0`

**实跑中修掉的坑（都已落地，不是 TODO）**：`/sdcard/Android/data` 在 Scoped Storage
下不可见 → 改 `/storage/emulated/0/Android/data/...`；`Android/data` 父目录需 APK
先安装才能创建 → 脚本先预装；AGP 收尾会清掉 App 外部私有目录导致 `adb pull` 失败 →
设备端把完整 JSON/CSV 以 `RESULT_*_BEGIN` / `JSON|` / `CSV|` 镜像进 logcat、主机侧
`recover_from_logcat()` 重建；旧结果被复用 → 每次回收前强制删除；合成演练或 A/B 误放
同一个人 → `looksLikeSamePerson` 防护，禁止用于阈值。

**仍未收口**：真实的两个真人样本未采集，因此**没有可用的 `T_match` / `T_new`**；
采集目录模板已建在 `runtime/calibration/A|B/`（见其中 README）。
一次性修补脚本与临时 `chain_samples` 已清理，只保留 `compile_androidtest.py`。

> **状态快照提示**：下面这句「B1~B3、`main/` 业务代码……均未开始」是写本章时的
> 状态。此后阶段 B 已实际落地并实跑通过，见**下一章**。

## 续：阶段 B 自动编号主链路落地（B1 / B2 / B3，2026-11-15 深夜）

用户原话：「好的，你可以去往下面推了，去按照计划实行，包括修改代码，完成开发你懂么？」
据此不再停留在调研 / B0 测试开发，**直接修改 `main/` 业务源码**，把「首次自动编号
P001/P002、离场重现沿用编号」做成能编译、能实跑、有断言、有日志、有防误用保护的功能。

### B1 决策内核（纯 Java，零 Android / 零 InspireFace SDK 依赖）

| 文件 | 内容 |
| --- | --- |
| `main/.../face/PersonIdResolver.java` | 泛型 `<F, C>` 的纯逻辑类：把短时 `track_id` 映射到长期 `person_id`。三区阈值（明确命中 / 灰色区继续采样 / 连续 `DEFAULT_MISSES_TO_ENROLL=3` 次无匹配才自动建档）、`trackToPerson` 绑定 + `DEFAULT_TRACK_TTL_MS=2000` 过期清理、`beginFrame()` 同帧互斥防串号、`touch()` 保活、`clear()` 只清内存不清库、`formatLabel()` 输出 `P%03d` |
| `test/.../face/PersonIdResolverTest.java` | 15 项 JVM 单测，用 `FakeGallery` 注入，覆盖空库 / 灰色区 / 命中 / 防串号 / TTL 过期重绑 / 建档失败重试 / NaN / null / 无候选 / 双人不同 ID / 阈值配置 / `touch()` 跨 TTL 保活 / 标签格式化 / 构造防御 |

**刻意设计**：不用 `SparseLongArray`、不碰 `Bitmap`/`Context`/native，因为 `app/build.gradle`
只有 JUnit4，没有 Robolectric / Mockito。生产侧通过 `Gallery<F,C>` / `CropSource` 适配器
把 `FaceFeature`、`FaceRepository`、对齐 crop 接进来。

**阈值来源（务必记住）**：`DEFAULT_MATCH_THRESHOLD=0.48f` 直接取 SDK 推荐值，
`DEFAULT_NEW_THRESHOLD=0.36f` 是 `0.48 - B0 newDelta` 的**启发式占位值**；
代码注释里明确写了 「NOT measured values: B0 has not yet collected two real human subjects」。

### B2 生产侧接入（`main/` 源码，此前一行未改）

| 文件 | 改动 |
| --- | --- |
| `view/FaceEngine.java` | 新增 `createAutoIdVideoSession(int maxFaces)`：`enableRecognition(true)` + 最多 10 脸的 tracking Session；保留原 `createVideoRecognitionSession()`（maxFaces=1）兼容旧页 |
| `view/TrackIdOverlayView.java` | `int[] trackIds` → `String[] labels`，新增 `submitLabels(...)`；保留旧 `submit(int[] ids, ...)` 转成 `"ID n"`，`FaceDetectionActivity` 无需改 |
| `view/AutoIdFaceAnalyzer.java` | **核心**：继承 `UprightFaceCameraAnalyzer`，单 analysis executor 上逐脸处理；`MIN_TRACK_COUNT=3` + `MIN_FACE_WIDTH_RATIO=0.12` 稳定门控 → `ExtractFaceFeature()` → `PersonIdResolver.resolve()` → 命中/自动建档（`GetFaceAlignmentImage()` 懒生成 crop + `FaceRepository.insert()`）→ 框上 `P001` 标签；`FEATURE_INTERVAL_MS=320` 节流、`resetSession()` 只清内存保留库、`configureThresholds()`、状态回调 `onUpdate(faceCount, gallerySize, labels, event)` |
| `AutoIdActivity.java` | 新页面：`CameraPreviewController` + `AutoIdFaceAnalyzer` + 状态文字 + 「重置会话（保留人物库）」按钮 |
| `HomeActivity.java` / `res/layout/activity_home.xml` / `res/layout/item_home_auto_id.xml` | 首页新增 `cardAutoId` 入口卡片 |
| `res/values/strings.xml` / `values-zh/strings.xml` | 中英文文案（含「当前阈值为启发式默认值，B0 真人标定尚未完成」的诚实提示） |
| `AndroidManifest.xml` | 注册 `.AutoIdActivity`（`exported=false`） |

**单线程约束**：`UprightFaceCameraAnalyzer` 要求 Session 调用留在同一线程；FeatureHub 是
进程级单例且有 `HUB_LOCK`，因此 `AutoIdFaceAnalyzer` 全程不切线程，native 生命周期安全。

### B3 设备端离场重入验证

新增 `androidTest/.../view/AutoIdReentryTest.java`，3 项用例在 `RealCue_API35` 上实跑：

| 用例 | 验证 |
| --- | --- |
| `autoEnrollThenSessionResetThenReentryReusesSameId` | **用户最初诉求**：空库自动建档 P001 → 会话重置（模拟完全离场）→ 重新入库检索 → 仍命中 P001 |
| `gallerySurvivesRepositoryReopenWithAFreshResolver` | 关库重开 + 全新 Resolver，持久化人物库仍可命中 |
| `twoDistinctIdentitiesGetDistinctIdsAndDoNotCrossTalk` | 两个身份拿到不同 ID，同帧互斥防串号，B 再次出现仍回 P002 |

设备端 logcat 关键行（`runtime/logs/model-logcat-20261115-213010.txt`）：
```
SETUP featureLen=512 recommendedThreshold=0.48
ENROLL_STEP i=0 -> PENDING(person=-1, conf=-Infinity)
ENROLL_STEP i=1 -> PENDING(person=-1, conf=-Infinity)
ENROLL_STEP i=2 -> ENROLLED(person=1, conf=-Infinity)
REENTRY_RESULT MATCHED(person=1, conf=1.0) expectedId=1
TWO_IDS idA=1 idB=2 enrollCalls=2
SAME_FRAME a=MATCHED(person=1, conf=1.0) b=SKIPPED(person=-1, conf=1.0)
B_AGAIN MATCHED(person=2, conf=1.0)
AFTER_RESTART MATCHED(person=1, conf=1.0) expectedId=1
```

### 实跑证据链（全部落盘，可复查）

| 闸门 | 结果 | 证据 |
| --- | --- | --- |
| `compileDebugJavaWithJavac` / `assembleDebug` | 成功 | `runtime/run_gradle.py` gate |
| 全量 JVM 单测 | **68 tests / 0 failures / 0 errors / 1 skipped**（其中 `PersonIdResolverTest` 15/15） | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| `assembleDebugAndroidTest` | 成功 | 同上 gate |
| `connectedDebugAndroidTest --tests AutoIdReentryTest` | **BUILD SUCCESSFUL in 46s**，XML `tests="3" failures="0" errors="0" skipped="0"`，设备 `RealCue_API35(AVD) - 15` | `runtime/logs/model-gradle-20261115-213010.log`、`app/build/outputs/androidTest-results/connected/debug/` |
| APK 静态验证 | 117,604,366 B；`versionCode=2 versionName=1.0.1`、`targetSdk 35`、`CAMERA` 权限、**`AutoIdActivity` 已注册**（xmltree line=65, exported=false） | `runtime/logs/stageB-apk-verify.txt` |
| APK 签名验证 | **`Verifies`**，v2 scheme true，1 signer（Android Debug，RSA 2048） | 同上 |
| 独立可视化报告 | `StageB-AutoId-Report.html` | 项目根目录 |

### 仍未收口（诚实边界）

1. **阈值仍不可信**：`T_match=0.48` / `T_new=0.36` 是启发式占位值，B0 真人两样本未采集，
   `runtime/calibration/A|B/` 仍是空模板。真机上要么误接受、要么误建档，必须用
   `python runtime/calibrate_threshold.py` 跑完真人标定后回填 `configureThresholds()`。
2. **灰色区在生产线不可达**：`FaceRepository.open()` 把 FeatureHub
   `setSearchThreshold(GetRecommendedCosineThreshold())`，低分候选在 FeatureHub 层就被过滤，
   `PersonIdResolver` 实际只看到「高分命中」或「无候选」。需 B0 后降低 FeatureHub 搜索阈值
   或改查 TopK 原始结果，三区才能完整生效（连续 miss 规则已能防单帧误建档）。
3. **B3 的「第二个人」是合成向量**：`-realFeature.data` 只用于验证分支 / 防串号，
   **不是真人异人分数，禁止用于阈值标定**。
4. **真机实时帧 / 多人同框 / 帧率 / 最终准确率未验证**：本轮只在 x86_64 模拟器上跑
   设备端测试（静态样本 + 合成向量），尚未在 Xiaomi 14 Pro 上跑相机实时流。
5. **自动建档记录可见但不自动清理**：`FaceRepository` 复用 `next_id` 自增序列，
   自动建档记录会进「人脸管理」列表，长时间运行会累积，需后续加去重 / 合并策略。

### 下一步优先级

1. **真机 B0 阈值标定**（唯一阻塞准确率的实验）：两个真人各 3~5 张，跑
   `calibrate_threshold.py`，拿到 `separable=true / margin>0` 后回填阈值。
2. **真机实时流验证**：把 `app-debug.apk` 传到 Xiaomi 14 Pro，打开首页新增的
   「人物自动编号」卡片，做 A 进场→离场→重入、A+B 同框、光照/角度变化的 A/B/C 验证。
3. 若灰色区确需生效，改 `FaceRepository.open()` 让搜索阈值可配置。

---

## 统一长期编号：把用户实际在用的页面也改掉（2026-11-16 收口）

### 触发：截图证明「只做新页面」是错的方向

用户上传三张真机截图，同一张脸在「退出→进入→退出→进入」后依次被标成
`ID 4` / `ID 7` / `ID 6`。核对画面特征（顶部 `模型 · Megatron`、底部
「跟踪 Session 参数 · Light 轻量跟踪」）确认：用户用的是**官方 Demo 原有的
「人脸跟踪」页**，即 `FaceDetectionActivity` 的 video tracking tab，
**不是**阶段 B 新增的 `AutoIdActivity`。

根因仍是 `FaceTrackingAnalyzer` 里的 `labelTrackIds[]` 加上
`TrackIdOverlayView.submit(int[] ids, ...)` 硬编码的 `"ID " + ids[i]`，
`ids` 来自 `MultipleFaceData.trackIds`（仅当前 camera Session 内稳定）。
用户明确范围：**「所有的都要改，只要涉及到我说的这个不管是图片，视频也要改哈」**。
据此不再要求用户切换到新页面，直接改他正在用的这套页面，并把图片检测页
与所有「库 ID」显示统一到 `P001` 格式。

### 改动清单（`main/` 业务源码 + 资源）

| 文件 | 改动 |
| --- | --- |
| `view/FaceEngine.java` | 新增 `createFaceTrackingSession(..., boolean enableRecognition)` 重载；原 4 参方法委托 `false`。稳定模式让旧跟踪页也能逐脸提特征 |
| `view/FaceTrackingAnalyzer.java` | 完整重写为双模式：legacy（无 repository，仍 `ID n`）/ stable（注入 `FaceRepository`，逐脸 `ExtractFaceFeature` → `PersonIdResolver.resolve()` → `submitLabels()` 显示 `P001`）。保留 106 关键点、box smoother、Track ID 配色、FPS、全部可调 Session 参数；新增 `resetStableSession()` / `configureThresholds()` |
| `FaceDetectionActivity.java` | 初始化 `trackingGallery` 并 `open()`（失败降级 legacy）；稳定模式构建 recognition Session；视频 tab 把 gallery 注入 Analyzer；图片 tab 走 `detectWithLandmarksAndFeatures()` + `resolveStableLabels()`；`FaceImageOverlayView.setFaceLabels()` 替换 1/2/3 徽章；`onDestroy` 回收 crops / 关库 |
| `face/FaceImageProcessor.java` | 新增 `detectWithLandmarksAndFeatures()`：一次检测同时产出 106 关键点 + 512 维特征 + 对齐 crop |
| `widget/FaceImageOverlayView.java` | 新增 `String[] faceLabels` / `setFaceLabels()`；badge 改圆角矩形，稳定模式显示 `P001`，legacy 仍 `1/2/3` |
| `face/FaceRepository.java` | `query()` 增加 `PersonIdResolver.formatLabel(id)` 匹配，用户看到 `P001` 后能在「人脸管理」里搜到 |
| `res/values/strings.xml` / `values-zh/strings.xml` | 补 `detection_face_selected_stable`（**此前代码已引用但未定义，会直接编译失败**）；`face_id_format` / `recognition_result_details` 由 `ID · %d` 统一为 `P%03d`；检测页 / 首页中英文文案同步 |

### 本轮额外修掉的两个坑

1. **编译阻断**：`FaceDetectionActivity.updateSelectionStatus()` 引用了
   `R.string.detection_face_selected_stable`，但上一轮被总结请求打断时该字符串并未写进
   `values` / `values-zh`。已补齐，并用 `aapt2 dump resources` 验证中英实际值进包。
2. **`rebuildSession()` 标签退化**：用户改 Session 参数（输入 px / 最大脸数 /
   最小脸 px）后，原代码仍调 `detectWithLandmarks()`、不重算 `imageFaceLabels`、
   不调 `setFaceLabels()`，框上会退回 `1/2/3` 或沿用错位旧标签。已改为稳定模式下
   走 `detectWithLandmarksAndFeatures()` + `resolveStableLabels()` 并回传 labels，
   同时补上旧 crops 回收。

### 验证证据链（2026-11-16，全部落盘）

| 闸门 | 结果 | 证据 |
| --- | --- | --- |
| `compileDebugJavaWithJavac` | BUILD SUCCESSFUL in 33s，资源与 Java 均重新执行 | `runtime/logs/gradle-app_compileDebugJavaWithJavac-20261115-222254.txt` |
| 全量 JVM 单测 | **68 tests / 0 failures / 0 errors / 1 skipped**（`PersonIdResolverTest` 15/15） | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| `assembleDebug` / `assembleDebugAndroidTest` | 成功（18s / 15s） | `runtime/logs/gradle-app_assembleDebug-20261115-222449.txt` 等 |
| APK 静态 | 当时的 117,605,130 B（**后续强制重编译后为 116,455,212 B，见文末复核节**）；`versionCode=2 versionName=1.0.1`、`targetSdk 35`、`CAMERA`、launchable `HomeActivity`；**新字符串中英实际值已进 resources.arsc**（`P%1$03d` 等） | `runtime/logs/unified-apk-verify.txt` |
| APK 签名 | **`Verifies`**，v2 scheme true，1 signer（Android Debug, RSA 2048） | 同上 |
| APK ABI / 结构 | `arm64-v8a` / `armeabi-v7a` / `x86_64`，9 dex，935 entries | 同上 |
| **新增设备端 `UnifiedStableIdTest`** | **4/4 通过，0 失败 0 错误**（`RealCue_API35(AVD) - 15`，BUILD SUCCESSFUL in 52s） | `runtime/logs/model-gradle-20261115-222838.log`、`model-logcat-20261115-222838.txt` |

设备端 4 项用例：

| 用例 | 断言 |
| --- | --- |
| `stillImageDetectionProducesFeaturesCropsAnd106Landmarks` | 新图片通路一次给出 `featureLen=512`、`crop=112x112`、`landmarks=106` |
| `detectionOnlySessionHasNoFeatureButRecognitionSessionDoes` | 证明必须切 recognition Session：`legacy_feature=null stable_featureLen=512` |
| `leavingAndReturningReusesSameGalleryLabel` | **用户诉求的图片版**：首次 `P001` → JPEG q70 + 亮度 +18 的「重新入场」→ 仍 `P001`，`gallery=1` 不重复建档 |
| `gallerySearchAcceptsPersistentP001Label` | `P001` / `p001` / `1` 都能搜到该记录，`P999` 返回空 |

关键 logcat：
```
SETUP featureLength=512 recommendedThreshold=0.48
STILL_DETECT faces=1 featureLen=512 crop=112x112 landmarks=106
SESSION_SWITCH legacy_feature=null stable_featureLen=512
RESOLVE enrolled id=1 searchConfidence=NaN
FIRST_APPEARANCE label=P001 gallery=1
RESOLVE matched id=1 confidence=0.9849746 threshold=0.48
REENTRY first=P001 second=P001 gallery=1
QUERY_LABEL P001=1 p001=1 numeric=1 P999=0
```
`0.9849746` 是**换画面后同一人**的真实 FeatureHub 余弦分（不是同一特征自比对的 1.0），
比 B3 的 `conf=1.0` 更有说服力，但仍属同人 intra-person 稳定性。

### 仍未收口（诚实边界）

1. **阈值仍不可信**：0.48 / 0.36 仍是 B0 占位值，两个真人样本未采集。
2. **灰色区仍不可达**：`FaceRepository.open()` 的 FeatureHub 搜索阈值问题未改。
3. **视频跟踪页的稳定标签没有设备端直接断言**：`FaceTrackingAnalyzer` 依赖 CameraX
   实时帧与 GL overlay，设备端测试无法直接实例化；其决策内核由 15 项 JVM 单测 +
   B3 三项重入设备端测试覆盖，`createFaceTrackingSession(..., true)` 的 recognition
   分支由 `SESSION_SWITCH` 用例类比验证。**真机视频页效果仍需用户实机确认**。
4. **异人分数 / 多人同框 / 帧率 / 最终准确率未测**：仍只有一张 `kun.jpg`。
5. **必须重装 APK**：统一编号轮构建为 117,605,130 B；**后续强制全量重编译后
   最终包为 116,455,212 B**（`runtime/logs/rebuild-verify-20261116.txt`）。
   旧包不会自动更新，用户需要重新上传安装才能看到 `P001`。

### 下一步优先级（更新版，取代上一节）

1. **重新上传安装本轮 `app-debug.apk`**，在用户原来那个「人脸跟踪」页直接验证：
   图片 tab 选一张脸应显示 `P001`；视频 tab 同一人离场再进入应仍是 `P001`；
   「人脸管理」里能看到并可搜索 `P001`。不必再切到「人物自动编号」新页面。
2. **真机 B0 阈值标定**（仍是唯一阻塞准确率的实验）。
3. 若灰色区确需生效，改 `FaceRepository.open()` 让搜索阈值可配置。

---

## 完整出镜流程 A→B→A→C 验证（2026-11-16 追加）

用户把目标讲得更具体：「之前我出镜了为 1 号，另一个人出镜为 2 号，然后我又出镜了，
检测到还是我就还是 1 号，然后再来个新人拿新号」。为了不再只给方案，新增
`androidTest/.../view/PersonFlowAbacTest.java` 把**整条时间序列**在真实 FeatureHub 上跑通。

`connectedDebugAndroidTest` 指定该类，`BUILD SUCCESSFUL in 47s`，
XML `tests="2" failures="0" errors="0" skipped="0"`，设备 `RealCue_API35(AVD) - 15`。

logcat（`runtime/logs/model-logcat-20261116-102916.txt`）：
```
SETUP featureLength=512 recommendedThreshold=0.48
STEP1 A_FIRST -> P001 gallery=1
STEP2 B_FIRST -> P002 gallery=2
STEP3 SAME_FRAME a=MATCHED(person=1, conf=1.0) b=MATCHED(person=2, conf=1.0) secondA=SKIPPED(person=-1, conf=1.0)
STEP4 LEAVE_ALL bindings=0 gallery=2
STEP5 A_REENTRY MATCHED(person=1, conf=0.9849746) expected=P001
STEP6 B_REENTRY MATCHED(person=2, conf=1.0) expected=P002
STEP7 C_FIRST -> P003 gallery=3
FINAL cast=A:P001 B:P002 C:P003 gallery=3 enrollCalls=3 RESULT=PASS
NO_SKIP A_REENTRY MATCHED(person=1, conf=0.9849746)
NO_SKIP B_FIRST -> P002
run finished: 2 tests, 0 failed, 0 ignored
```

- 用例 1 `fullCastFlowKeepsEveryPersonsNumber`：A 建档 → B 建档 → A/B 同帧互斥 →
  全员离场清内存绑定（库保留）→ A 带**全新 trackId + 换画面重新提的特征**回来仍 P001 →
  B 回来仍 P002 → 新人 C 拿 P003；断言 3 条记录 / 3 次建档、三个号互不相同。
- 用例 2 `reentryDoesNotConsumeANewNumber`：A 离场重入后，第二个人仍拿 P002 而非 P003，
  证明重入不消耗编号。
- 「新人拿下一个号」按**下一个未占用的自增主键**实现：1、2 已占用时新人得 `P003`，
  不复用 2 号，也不会因 A 重入烧号。
- A 与 A 的重入是**真实 native 特征**（重入用 JPEG q70 + 亮度 +18 的图重新检测提特征，
  `conf=0.9849746`）；B/C 是 `-A` 与「前半取反」两个互异合成向量，
  **只验证分支，禁止用于阈值标定**。项目仍只有 `kun.jpg` 一张真人样本。
- 本轮**只加 androidTest，未改 `main/`，当时 APK 未变**（当时仍是 117,605,130 B）；
  **后续已做强制全量重编译，见下一节**，交付包已换成 116,455,212 B。
- 新增 `PersonFlow-ABAC-Report.html` 独立报告；同步追加到
  `Research-PersonGallery-Matching.md` 附录 E.8。

**边界不变**：阈值仍是 B0 占位值、灰色区仍被 FeatureHub 阈值过滤、
真机相机实时帧 / 多人同框 / 帧率 / 最终准确率仍未测。

---

## 强制全量重编译复核（2026-11-16 14:14，回答「你重新编译过没？」）

### 为什么要单独重编一次

用户质疑是否真的重新编译。核实结果：上一版 `app-debug.apk` 时间为 `2026-11-15 22:25:08`，
比 `main/` 最后一次改动（`FaceDetectionActivity.java`，`22:22:21`）新，说明**统一编号那一轮确实出过包**；
但 A→B→A→C 流程验证那轮只新增 `androidTest`，
`connectedDebugAndroidTest` 日志里 `compileDebugJavaWithJavac UP-TO-DATE`、
`packageDebug UP-TO-DATE`，**没有重出 APK**。为彻底排除构建缓存造成的误解，
执行强制全量重编译（`--rerun-tasks` 让所有 task 忽略 UP-TO-DATE 重新执行）。

### 执行命令

```bash
python runtime/run_gradle.py --rerun-tasks \
    :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

实际命令行（`runtime/run_gradle.py` 自动注入 D 盘 `GRADLE_USER_HOME` / `--offline` / local AAR）：

```
gradle.bat --no-daemon --offline -p ".../android-demo" -PinspirefaceSdkSource=local \
  --rerun-tasks :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

### 结果

| 项 | 结果 | 证据 |
| --- | --- | --- |
| Gradle | **BUILD SUCCESSFUL in 29s**，**`65 actionable tasks: 65 executed`**（无 UP-TO-DATE 复用） | `runtime/logs/gradle---rerun-tasks-20261116-141428.txt` |
| 重新编译 | `compileDebugJavaWithJavac` / `compileDebugAndroidTestJavaWithJavac` / `compileDebugUnitTestJavaWithJavac` 三个都实际执行 | 同上 |
| JVM 单测 | `TOTAL files=11 tests=68 failures=0 errors=0 skipped=1` | 同上 + `TEST-*.xml` |
| **新 APK** | **`116,455,212 B`，mtime `2026-11-16 14:14:54`**（旧版 117,605,130 B） | `runtime/logs/rebuild-verify-20261116.txt` |
| androidTest APK | `672,252 B`，mtime `14:14:56` | 文件系统 |
| 签名 | `Verifies`，`Verified using v2 scheme: true`，1 signer | 同上 verify 日志 |
| badging | `versionCode=2 versionName=1.0.1` / `targetSdk 35` / `CAMERA` / launchable `HomeActivity` | 同上 |
| 资源实际值 | `P%1$03d` 中英值、`detection_face_selected_stable` 均在 `resources.arsc` | 同上 |
| ABI / 结构 | 935 entries，9 dex，`arm64-v8a` / `armeabi-v7a` / `x86_64`，模型 Pikachu / Megatron 与 `kun.jpg` 齐全 | 同上 |
| DEX 符号 | `PersonIdResolver` / `formatLabel` / `resolveStableLabels` / `detectWithLandmarksAndFeatures` / `submitLabels` / `setFaceLabels` / `createFaceTrackingSession` / `RealCueTracking` / `RealCueAutoId` / `P%03d` | 同上 |
| **新包重装后设备端复测** | `UnifiedStableIdTest` **4/4 通过**，`tests=4 failures=0 errors=0`，`REENTRY first=P001 second=P001 gallery=1`，`BUILD SUCCESSFUL in 2m 14s` | `runtime/logs/model-gradle-20261116-141608.log`、`model-logcat-20261116-141608.txt` |

### 说明

- APK 比上一版小 `1,149,918 B`，是**重新打包 / 压缩差异**；已逐项核对 native libs、
  两个模型资产、样本图、9 个 dex、`resources.arsc` 全部在包内，没有缺件。
- 设备端复测里显示 `packageDebug UP-TO-DATE` 是**正常的**：它复用的是上面
  `--rerun-tasks` 刚刚生成的新包，不是旧缓存；测试安装的是新 APK。
- 结论：**已重新编译，且是强制全量重编译，不是缓存。** 交付以
  `app/build/outputs/apk/debug/app-debug.apk`（116,455,212 B / 14:14:54）为准。

