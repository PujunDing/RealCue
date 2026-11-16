# RealCue 环境搭建指南（从零复现）

本文是 [README.md](README.md) 第 5 节的详细版。目标是：**在一台干净的 Windows 机器上，从 `git clone` 到能构建、能跑 JVM 单测、能跑设备端模型验证**。

所有路径、URL、大小与 SHA-1 都来自本仓库 `runtime/setup_env.py` / `setup_phone_emulator.py` 以及本机保存的 Android SDK repository 元数据（`repository2-1.xml`、`sys-img-google_apis.xml`），不是凭记忆写的。

---

## 1. 为什么需要这份文档

本仓库是**源码仓库**，刻意不带完整环境：

- GitHub 单文件硬限制约 **100 MB**，而 `app-debug.apk` 约 116 MB、InspireFace AAR 约 83 MB、手机系统镜像 1.7 GB；
- Android SDK / Gradle / AVD 展开后约 **14 GB**，且含大量本机路径相关状态；
- `local.properties` / `sdk.local.properties` 每台机器都不同。

因此 `.gitignore` 排除了这些文件，由 `runtime/*.py` 在你本机重新装配。**环境配置是一次性的；装配脚本是幂等的，重复运行只补缺失项。**

## 2. 组件版本总表

| 组件 | 精确版本 | 来源文件 |
| --- | --- | --- |
| JDK | **17**（实测 `JBR-17.0.14`） | AGP 8.6.1 要求；Android Studio 自带 JBR 21 不兼容 |
| Android Gradle Plugin | `8.6.1` | `android-demo/gradle/libs.versions.toml` |
| Gradle | `8.7` | `gradle/wrapper/gradle-wrapper.properties` 与 standalone |
| compileSdk / targetSdk / minSdk | `35` / `35` / `24` | `app/build.gradle` |
| Android platform | `platforms;android-35` | `platform-35_r02.zip` |
| build-tools | `35.0.0` | `build-tools_r35_windows.zip` |
| platform-tools | `37.0.3` | `platform-tools_r37.0.3-win.zip` |
| CameraX | `1.5.3` | `libs.versions.toml` |
| InspireFace SDK | `v1.2.4.post1` | `com.github.HyperInspire:inspireface-android-sdk`（JitPack） |
| ABI | `arm64-v8a`, `armeabi-v7a`, `x86_64` | `app/build.gradle` ndk.abiFilters |
| 手机系统镜像 | `system-images;android-35;google_apis;x86_64`, revision 9 | `x86_64-35_r09.zip` |
| AVD | `RealCue_API35` / Pixel 6 / 1080×2400 / 竖屏 touch | `setup_phone_emulator.py` |
| Python | 3.9+（实测 3.13） | 仅用于 `runtime/*.py` |

## 3. 目录布局与「D 盘策略」

```text
<repo>/                                  ← 必须纯 ASCII，例如 D:\paper\RealCue
└── InspireFace-LocalRun/
    ├── android-demo/                    ← Gradle 工程根
    │   ├── local.properties             ← 生成/手填，gitignored（指向下面的 SDK）
    │   ├── sdk.local.properties         ← 生成/手填，gitignored（指向 AAR）
    │   └── app/build/outputs/apk/debug/app-debug.apk
    └── runtime/
        ├── downloads/                   ← 放下面第 4 节的大文件
        ├── android/                     ← 自包含 SDK（platforms / build-tools / platform-tools / licenses）
        ├── gradle/gradle-8.7/           ← standalone Gradle
        ├── gradle-home/                 ← GRADLE_USER_HOME（可写缓存）
        ├── avd/                         ← ANDROID_EMULATOR_HOME / ANDROID_AVD_HOME
        ├── tmp/                         ← TMP / TEMP / LOCALAPPDATA
        └── logs/                        ← 构建/测试证据（已入库）
```

脚本通过 `runtime/verify_model_on_emulator.py::make_env()` 统一注入环境变量：

| 变量 | 指向 | 目的 |
| --- | --- | --- |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `runtime/android` | 让 AGP 用自包含 SDK |
| `GRADLE_USER_HOME` | `runtime/gradle-home` | **所有可写 Gradle 状态放项目盘** |
| `GRADLE_RO_DEP_CACHE` | `~/.gradle/caches`（若存在） | **只读**复用 C 盘已有依赖，不写入 |
| `TMP` / `TEMP` / `LOCALAPPDATA` | `runtime/tmp` | 临时文件不落 C 盘 |
| `ANDROID_EMULATOR_HOME` / `ANDROID_AVD_HOME` | `runtime/avd` | AVD 放项目盘 |
| `JAVA_HOME` | JDK 17 目录 | AGP 8.6.1 必需 |

配合 `--offline`，构建过程**不向 C 盘写任何新数据**。C 盘只被只读复用（JDK、机器级 SDK 的 `emulator.exe`、已有 Gradle 缓存）。

> 若你把仓库放在 D 盘，以上目录天然都在 D 盘。若放 C 盘，可用 `REALCUE_GRADLE_USER_HOME` / `REALCUE_AVD_HOME` 把它们改到 D 盘。

## 4. 大文件下载清单

全部放到 `InspireFace-LocalRun/runtime/downloads/`。

### 4.1 由 `setup_env.py` 自动下载（无需手动）

| 文件名 | URL | 大小 (B) | SHA-1 |
| --- | --- | --- | --- |
| `build-tools_r35_windows.zip` | `https://dl.google.com/android/repository/build-tools_r35_windows.zip` | 59,878,107 | `af059bb67cf7786f45ee0db85e2d24985df1b4b6` |
| `platform-tools_r37.0.3-win.zip` | `https://dl.google.com/android/repository/platform-tools_r37.0.3-win.zip` | 8,188,595 | `b3fde5239ad89229d5a9178375b81c9887a24172` |
| `gradle-8.7-bin.zip` | `https://services.gradle.org/distributions/gradle-8.7-bin.zip` | 约 128 MB | — |

脚本会解压并把 build-tools 顶层目录重命名为 `35.0.0`，Gradle 解压为 `runtime/gradle/gradle-8.7/`。

### 4.2 需要手动放置

**Android 15 platform（必需）**

```text
下载: https://dl.google.com/android/repository/platform-35_r02.zip
大小: 64,273,788 B
SHA-1: 0bb560a90a7a2cbd0dd8348224d518b638fe7949
重命名: platform.api35.zip
放置: runtime/downloads/platform.api35.zip
```

它对应 SDK repository 里的 `platforms;android-35`。zip 顶层目录为 `android-35`，解压后落在 `runtime/android/platforms/android-35/`，内含 `android.jar`。也可用机器级 SDK 的 `sdkmanager "platforms;android-35"` 装好后，把原始归档复制过来重命名。

**InspireFace 完整 AAR（离线模式必需）**

```text
坐标: com.github.HyperInspire:inspireface-android-sdk:v1.2.4.post1 (JitPack)
约 83 MB
重命名为: inspireface-1.2.4.post1.aar
放置: runtime/downloads/inspireface-1.2.4.post1.aar
```

这个**完整** AAR 内含 Pikachu / Megatron 模型包与三种 ABI 的 `libInspireFace.so`，缺失任何一个都会导致运行时模型加载失败。获取途径：上游 GitHub release、JitPack 产物，或从上游 SDK 源码本地构建 release AAR。

> 不想下载 AAR：直接用在线 jitpack 模式（第 8 节），首次构建需要联网。

**手机系统镜像（仅模拟器需要）**

```text
包名: system-images;android-35;google_apis;x86_64 (revision 9)
下载: https://dl.google.com/android/repository/sys-img/google_apis/x86_64-35_r09.zip
大小: 1,738,815,903 B
SHA-1: 0103e6dab21290c4b9d16550a3ce99476f884eef
放置: runtime/downloads/x86_64-35_r09.zip
```

`setup_phone_emulator.py` 会**同时校验大小与 SHA-1**，不完整或校验失败会直接报错，不会解压出坏镜像。也可用 `sdkmanager "system-images;android-35;google_apis;x86_64"` 下载，再用 `REALCUE_EXTRA_DOWNLOADS` 指向归档所在目录。

> 不要用 Android XR 头显镜像：`hw.camera.back=none`、横屏 no-touch、SwiftShader/Vulkan 下 boot 失败，无法验证 CameraX。

## 5. 环境变量完整参考

`runtime/env_config.py` 是唯一配置源。优先级：`REALCUE_*` 显式值 → 标准环境变量 → 本机常见路径自动探测。

| 变量 | 默认值 / 自动探测 | 什么时候需要设置 |
| --- | --- | --- |
| `REALCUE_JAVA_HOME` | 自动找 JDK 17（`~/.jdks/*17*`、Adoptium、Android Studio jbr、`JAVA_HOME`） | 自动探测失败，或 `JAVA_HOME` 指向 JDK 21 |
| `REALCUE_GLOBAL_ANDROID_SDK` | `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `%LOCALAPPDATA%\Android\Sdk` | 机器级 SDK 不在默认位置；它提供 `emulator.exe` 与 licenses |
| `REALCUE_EMULATOR` | `<上述 SDK>/emulator/emulator.exe` | emulator 装在别处 |
| `REALCUE_GRADLE_RO_DEP_CACHE` | `~/.gradle/caches`（存在才启用） | 想复用别处的只读依赖缓存；**留空则不使用只读缓存** |
| `REALCUE_GRADLE_USER_HOME` | `runtime/gradle-home` | 想把 Gradle 可写状态挪走 |
| `REALCUE_AVD_HOME` | `runtime/avd` | 想把 AVD 挪走 |
| `REALCUE_LOCAL_SDK` | `runtime/android` | 想用自己已有的 SDK 目录 |
| `REALCUE_DOWNLOADS` | `runtime/downloads` | 大文件放在别处 |
| `REALCUE_EXTRA_DOWNLOADS` | 无 | 第二个大文件查找目录 |
| `REALCUE_ASCII_MIRROR` | 仓库根目录的同级 `ifd-build` | 用 `build_ascii.py` 时想换镜像位置 |
| `REALCUE_GRADLE_ONLINE` | 未设置（即 `--offline`） | **首次构建设为 `1`** 以允许下载依赖 |
| `REALCUE_SDK_SOURCE` | `local` | 设为 `jitpack` 走在线 SDK 依赖，不需要 AAR |

> 注意 `ANDROID_HOME` 在构建时会被脚本**覆盖**为项目内 `runtime/android`（这是刻意的）；`REALCUE_GLOBAL_ANDROID_SDK` 只用于找 emulator 和 licenses，两者不冲突。

### 三种 shell 的设置方式

**cmd.exe**（推荐用模板文件）

```bat
copy runtime\env.example.bat runtime\env.local.bat
notepad runtime\env.local.bat
call runtime\env.local.bat
```

**PowerShell**

```powershell
$env:REALCUE_JAVA_HOME = "D:\jdk\jdk-17.0.14"
$env:REALCUE_GLOBAL_ANDROID_SDK = "C:\Users\me\AppData\Local\Android\Sdk"
$env:REALCUE_GRADLE_ONLINE = "1"       # 仅首次构建
```

**Git Bash**

```bash
export REALCUE_JAVA_HOME="/d/jdk/jdk-17.0.14"
export REALCUE_GRADLE_ONLINE=1
```

设置只对当前会话生效。想固化就写进 `env.local.bat`（已 gitignored）或系统环境变量。

## 6. 四个本机配置文件

全部由 `*.example` 模板复制而来，**真实文件已被 `.gitignore` 排除**。

| 文件 | 模板 | 内容 | 由谁生成 |
| --- | --- | --- | --- |
| `android-demo/local.properties` | `local.properties.example` | `sdk.dir=../runtime/android`（或绝对 ASCII 路径） | `setup_env.py` 自动生成 |
| `android-demo/sdk.local.properties` | `sdk.local.properties.example` | `aarPath=../runtime/downloads/inspireface-1.2.4.post1.aar` | `setup_env.py` 自动生成 |
| `android-demo/plus.local.properties` | `plus.local.properties.example` | 可选业务凭证 `apiBase` / `token` | 手动，debug 构建不需要 |
| `android-demo/keystore.properties` | — | release 签名配置 | 手动，debug 构建不需要 |

Java Properties 里 Windows 绝对路径要注意：用正斜杠，盘符冒号转义为 `\:`，例如 `sdk.dir=D\:/Android/Sdk`。**路径必须纯 ASCII**（见第 11 节）。

## 7. 逐步搭建（带验证点）

```powershell
# 0) 前置：JDK 17、Python 3.9+、Android Studio / 机器级 SDK（含 emulator）
java -version          # 必须是 17
python --version

# 1) clone 到纯 ASCII 路径
git clone git@github.com:PujunDing/RealCue.git D:\paper\RealCue
cd D:\paper\RealCue\InspireFace-LocalRun

# 2) 放手动大文件（见第 4 节）
#    runtime/downloads/platform.api35.zip
#    runtime/downloads/inspireface-1.2.4.post1.aar   （离线模式）
#    runtime/downloads/x86_64-35_r09.zip              （模拟器）

# 3) 可选：设置环境变量（见第 5 节）

# 4) 装配 SDK / Gradle / 配置文件
python runtime/setup_env.py
#    期望：[ok] aapt2.exe = True / adb.exe = True / gradle launcher = True

# 5) 自检（务必先做）
python runtime/env_config.py
#    期望：RESULT: READY (core build toolchain present)

# 6) 手机 AVD（可选）
python runtime/setup_phone_emulator.py
#    期望：[ok] archive size and SHA-1 match Google repository metadata

# 7) 首次构建：开在线开关
$env:REALCUE_GRADLE_ONLINE = "1"
python runtime/run_gradle.py :app:assembleDebug :app:testDebugUnitTest
#    期望：BUILD SUCCESSFUL

# 8) 恢复离线（此后所有写入都在项目盘）
Remove-Item Env:REALCUE_GRADLE_ONLINE
python runtime/run_gradle.py :app:assembleDebug
```

每一步都有可观察的成功标志，卡住时先看 `runtime/logs/` 里的完整日志。

## 8. 两种 SDK 依赖模式

`app/build.gradle` 通过 `-PinspirefaceSdkSource` 切换，**不会在两种模式间静默回退**：

| 模式 | 开关 | 依赖来源 | 需要 AAR | 需要联网 | 适用 |
| --- | --- | --- | --- | --- | --- |
| `local`（脚本默认） | 默认 / `REALCUE_SDK_SOURCE=local` | `files(localSdkAar)` | **是** | 首次需在线拉其它依赖，之后可完全离线 | 已验证的复现环境 |
| `jitpack` | `REALCUE_SDK_SOURCE=jitpack` | JitPack `com.github.HyperInspire:...:v1.2.4.post1` | 否 | 是 | 新机器快速起步 |

Gradle 属性 `inspirefaceSdkSource` 默认写在 `gradle.properties` 里为 `jitpack`；`run_gradle.py` 显式传 `local` 覆盖它。若 `aarPath` 为空或文件不存在，构建会**直接抛 GradleException 并给出提示**，不会偷偷换成在线依赖。

## 9. 构建、测试与产物验证

```powershell
# 统一入口（日志自动落 runtime/logs/）
python runtime/run_gradle.py :app:testDebugUnitTest
python runtime/run_gradle.py :app:assembleDebug
python runtime/run_gradle.py :app:assembleDebugAndroidTest

# 强制全量重编译，排除 UP-TO-DATE 造成的「以为编过了」
python runtime/run_gradle.py --rerun-tasks :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest

# 设备端（自动起 RealCue_API35、跑测试、抓 logcat、关模拟器）
python runtime/verify_model_on_emulator.py com.example.inspireface_example.view.ModelFeasibilityTest
python runtime/verify_model_on_emulator.py com.example.inspireface_example.view.UnifiedStableIdTest
python runtime/verify_model_on_emulator.py com.example.inspireface_example.view.PersonFlowAbacTest
```

手工验证 APK（工具都在自包含 SDK 里，无需 PATH）：

```powershell
$env:JAVA_HOME = "D:\jdk\jdk-17.0.14"
& runtime\android\build-tools\35.0.0\aapt2.exe dump badging android-demo\app\build\outputs\apk\debug\app-debug.apk
& runtime\android\build-tools\35.0.0\apksigner.bat verify --verbose --print-certs android-demo\app\build\outputs\apk\debug\app-debug.apk
runtime\android\platform-tools\adb.exe install -r android-demo\app\build\outputs\apk\debug\app-debug.apk
```

`apksigner` 依赖 `JAVA_HOME`，所以先设置 JDK 17。

## 10. 脚本清单

| 脚本 | 作用 |
| --- | --- |
| `runtime/env_config.py` | 解析所有本机路径，`python runtime/env_config.py` 自检 |
| `runtime/setup_env.py` | 幂等装配 SDK / Gradle / 两个 properties |
| `runtime/setup_phone_emulator.py` | 校验 SHA-1、解压系统镜像、写 `RealCue_API35` AVD |
| `runtime/run_gradle.py` | **统一 Gradle 入口**：D 盘环境 + 离线 + 日志 |
| `runtime/build_demo.py` | 早期的一键构建（assembleDebug + JVM 单测） |
| `runtime/build_ascii.py` | 非 ASCII 路径的应急镜像构建 |
| `runtime/verify_on_emulator.py` | 起模拟器、装 APK、冷启动、截图 |
| `runtime/verify_on_device.py` | 真机安装 + 冷启动 |
| `runtime/verify_model_on_emulator.py` | 设备端 instrumented 测试 + XML/logcat 证据 |
| `runtime/verify_on_device.py` | 真机安装 + 冷启动（不验证算法精度） |
| `runtime/calibrate_threshold.py` | B0 真人阈值标定（需两个真人样本） |

## 11. 故障排查

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| `aapt2.exe: cannot stat android.jar` / 单测 `ClassNotFoundException` | 路径含中文等非 ASCII 字符 | 迁移到纯 ASCII 路径；`android.overridePathCheck=true` **治不了根**；或用 `build_ascii.py` |
| `Unsupported class file major version` / AGP 报 JDK 版本 | 用了 JDK 21（Android Studio 自带 JBR） | `REALCUE_JAVA_HOME` 指向 JDK 17 |
| `No cached version of ... available for offline mode` | 首次构建无依赖缓存 | `REALCUE_GRADLE_ONLINE=1` 跑一次 |
| `Local InspireFace AAR does not exist: ...` | AAR 未放 / 路径写错 | 放 `runtime/downloads/inspireface-1.2.4.post1.aar`，或 `REALCUE_SDK_SOURCE=jitpack` |
| `Missing platform.api35.zip` | 手动大文件缺失 | 下载 `platform-35_r02.zip` 重命名放置 |
| `Incomplete archive: N/M bytes` / `SHA-1 mismatch` | 系统镜像下载不完整 | 重新下载，核对 1,738,815,903 B 与 SHA-1 |
| `FAIL: emulator binary not found` | 机器级 SDK 没装 emulator 组件 | 装 Android SDK emulator，或设 `REALCUE_EMULATOR` |
| 模拟器 boot 超时 / 无相机 / 黑屏 | 用了 XR 头显镜像或没硬件加速 | 用 `RealCue_API35` 手机镜像；启用 WHPX / Hyper-V |
| `git push` 报文件超过 100 MB | 误把 APK / AAR / zip 加进提交 | 靠 `.gitignore`；二进制走 Release 附件或 Git LFS |
| `sdkmanager --licenses` 被要求接受条款 | 自包含 SDK 缺 licenses | `setup_env.py` 会从机器级 SDK 复制已接受的 licenses；没有就手动跑一次 |
| `P001` 变 `ID 4` 之类短时编号 | 跑的是旧 APK 或页面未走稳定模式 | 重装最新 APK；确认 `FaceTrackingAnalyzer.usesStablePersonIds()` 为真 |
| 灰色区阈值不生效 | `FaceRepository.open()` 把 FeatureHub 搜索阈值设为推荐值，低分候选被提前过滤 | 改该阈值或改查 TopK 原始结果 |

## 12. 搭建完成核对清单

- [ ] `python runtime/env_config.py` 输出 `RESULT: READY`
- [ ] `runtime/android/platforms/android-35/android.jar` 存在
- [ ] `runtime/android/build-tools/35.0.0/aapt2.exe` 存在
- [ ] `runtime/android/platform-tools/adb.exe` 存在
- [ ] `runtime/gradle/gradle-8.7/bin/gradle.bat` 存在
- [ ] `android-demo/local.properties` 指向纯 ASCII 的 SDK 路径
- [ ] `android-demo/sdk.local.properties` 指向存在的 AAR（或已切 jitpack）
- [ ] 首次构建 `BUILD SUCCESSFUL`
- [ ] `app-debug.apk` 生成且 `apksigner verify` 通过
- [ ]（可选）`RealCue_API35` 能 boot，`adb devices` 可见
- [ ]（可选）至少一个设备端测试类 `tests>0 failures=0`

## 13. 已知边界

- **B0 阈值未标定**：`T_match=0.48` / `T_new=0.36` 是官方推荐值与占位值。目前只有 `kun.jpg` 一张真人样本，异人分数分布空白，按 [runtime/calibration/README.md](InspireFace-LocalRun/runtime/calibration/README.md) 采集两个真人各 3–5 张后再标定。脚本对「A/ 与 B/ 放同一人」有防误用拦截，会标记 `doNotUseForThreshold=true`。
- 真机多人同框、帧率、跨光照/跨姿态准确率未实测；设备端验证用的是模拟器 + `kun.jpg` + 合成向量。
- MTP 自动复制 APK 到手机内部存储受会话隔离限制未做成，用 `adb install` 更可靠。
- 若需要 **release 签名**，另配 `keystore.properties`（gitignored）；debug 构建不依赖它。
