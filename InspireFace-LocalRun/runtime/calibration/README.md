# B0 阈值标定：真人样本采集说明

这个目录是 `runtime/calibrate_threshold.py` 的默认样本目录
（`DEFAULT_SAMPLES`）。**目前 A/ 与 B/ 是空的**，所以还没有可用的
`T_match` / `T_new` —— 现有结果只是链路验证，见
`runtime/logs/calibration-20261115-204457/`。

## 怎么放照片

```
runtime/calibration/
├── README.md          ← 本文件
├── A/                 ← 同一个人（甲）的多张照片
│   ├── face_00.jpg
│   ├── face_01.jpg
│   └── face_02.jpg    （至少 2 张，建议 3~5 张）
└── B/                 ← 另一个人（乙）的多张照片
    ├── face_00.jpg
    ├── face_01.jpg
    └── face_02.jpg    （至少 2 张，建议 3~5 张）
```

要求：

1. **用最终要部署的那台手机（Xiaomi 14 Pro）拍**，不要用模拟器、不要用网图、
   不要把同一张图复制改名充数。
2. A/ 里必须是**同一个人**，B/ 里必须是**另一个人**。两个文件夹放同一个人会被
   `looksLikeSamePerson` 防护拦下并标记 `doNotUseForThreshold=true`（已经实测验证过）。
3. 覆盖不同角度（正脸 / 微侧）、距离（近 / 远）、光照（亮 / 暗）、表情，
   这样同人分数才有分布，标出来的阈值才稳。
4. **每张照片只含一张清晰正脸**，不要合影、不要遮挡、不要严重模糊。
   检测不到脸的文件会被计入 `noFaceA` / `noFaceB` 并跳过。
5. 文件名随意，`.jpg` / `.png` 均可，脚本按目录读取全部图片。

## 跑

```sh
cd /d D:\paper\RealCue\InspireFace-LocalRun
C:/Users/pjd02/.workbuddy/binaries/python/versions/3.13.12/python.exe runtime/calibrate_threshold.py
```

不带参数时自动用本目录；也可以显式传路径：

```sh
C:/Users/pjd02/.workbuddy/binaries/python/versions/3.13.12/python.exe runtime/calibrate_threshold.py D:\paper\RealCue\InspireFace-LocalRun\runtime\calibration
```

脚本会自动：启动 `RealCue_API35` → 预装 App 与 androidTest APK → push A/B 照片 →
跑 3 项设备端测试 → 回收 `threshold-result.json` / `threshold-pairs.csv` →
生成 `calibration-summary.md` 并打印 `T_match` / `T_new`。

产物落在 `runtime/logs/calibration-<时间戳>/`。

**判定标准**：摘要里**没有「此结果不可用于设定阈值」的警告**、且
`separable=true` / `margin>0` / `doNotUseForThreshold=false` 时，
`T_match` / `T_new` 才可以拿去填阶段 B 的三区阈值（第 5.4 节）。

## 当前状态

| 项 | 状态 |
| --- | --- |
| 3 项设备端测试实跑通过（`tests=3 failures=0`） | 已完成 |
| 完整 pipeline 产物可解析回收（JSON 905 B / CSV 217 B） | 已完成 |
| 防误用保护生效（同人误放被拦截） | 已完成 |
| 真实异人分数分布 / 可用 `T_match` `T_new` | 待 A/ B/ 放照片 |

## 背景

- 官方阈值 `GetRecommendedCosineThreshold() = 0.48`，512 维（附录 B.1）。
- 附录 B 已测同人 7 种画面变体 7/7 命中同一 ID（最低 0.9680），但本机只有
  `kun.jpg` 一张脸，**异人分数分布完全空白**。
- 决定阈值的是「同人最低分」与「异人最高分」之间的间隔，所以必须两个真人。

详见 `../../../Research-PersonGallery-Matching.md` 附录 C 与
`../../../B0-Calibration-Test-Report.html`。
