# 人物库匹配与自动编号调研（阶段 B）

调研日期 2026-11-15。范围：`D:\paper\RealCue\InspireFace-LocalRun\android-demo`（InspireFace 官方 Demo 1.2.4.post1，源码未改动）。
触发问题：真机上「第一次进来 ID 1，离场再进来还是我，却变成 ID 2 或别的编号」，怀疑没有对见过的人做匹配。

---

## 0. 结论先说

**能实现，而且不需要换 SDK、不需要自己训练模型。** InspireFace 自带的 FeatureHub 已经提供了「特征入库 → 1:N 检索 → 磁盘持久化 → 重开应用后继续命中同一 ID」的完整能力，本次在模拟器上实测通过（见第 2 节）。

你看到的现象**不是模型认不出人，而是当前 Demo 显示的 ID 根本不是人物库 ID**，而是跟踪器的短时 `track_id`；另外官方 Demo 的人物库是**手动录入**的，摄像头实时页面没有「识别失败就自动建号」的逻辑。

需要做的是应用层改造，工作量集中在 3 个文件 + 1 个新类，属于阶段 B 原本规划的范围。

---

## 1. 现象定位：你看到的 ID 到底是什么

### 两种 ID 必须分开

| | `track_id`（短时跟踪 ID） | `person_id`（长期人物编号） |
| --- | --- | --- |
| 来源 | SDK 跟踪器 `MultipleFaceData.trackIds[]` | 应用侧 FeatureHub 主键 `FaceFeatureIdentity.id` |
| 生命周期 | **仅当前 camera Session 内的连续轨迹** | 写入 `features.db`，跨帧、跨离场、跨应用重启 |
| 离场再进来 | 原轨迹结束，重新分配，**必然变号** | 检索特征命中则**沿用旧号** |
| 当前 Demo 显示位置 | `Face Tracking` 页面的框上数字 | `Face Recognition` 页面的 `record.name` / `record.id` |

### 源码证据

- `view/FaceTrackingAnalyzer.java:105-106` 直接把 `faces.trackIds[i]` 作为标签，`:144-145` 提交给 `TrackIdOverlayView`。
- `view/FaceTrackingAnalyzer.java:189-190` 的注释写得很直白：`Keeps a Track ID's color stable for the lifetime of this camera Session` —— 稳定性只保证到本次 Session。
- `view/RecognitionFaceAnalyzer.java:20` 类注释：`Tracks only SDK face 0 and searches it after roughly one stable second`。
- `view/FaceEngine.java:130-133` `createVideoRecognitionSession()` 用 `maxFaces = 1`，**视频识别 Session 硬限制只检测 1 张脸**。
- `FaceRecognitionActivity.java:255` 在初始化时算一次 `libraryEmpty`，`:652-653` 把这个布尔值传给 analyzer；即使中途录入了人脸，analyzer 仍认为库是空的，走 `EMPTY_LIBRARY` 分支（`:790-796`）**根本不查库**。
- `face/FaceRepository.java:179-223` 的 `insert()` 由 `FaceManagementActivity` 手动调用（拍照 → 输入名字 → 入库），**没有任何自动建档调用点**。

### 因此有两种可能，都需要修

1. **你看的是 Face Tracking 页面**：数字是 `track_id`，离场/切页面/重建 Session 后必然重新分配。这是设计如此，不是 bug。要显示稳定编号，必须把标签从 `track_id` 换成 `person_id`。
2. **你看的是 Face Recognition 页面**：库里若没有先手动录入过你，就是空库，页面直接显示 EMPTY_LIBRARY；即使录了，视频页也只搜第一张脸、只在稳定后搜一次，且 `libraryEmpty` 是快照值。

> 补充：本次验证过的 `ModelFeasibilityTest` 里 LIGHT_TRACK 三帧 `trackId=1` 恒定、`trackCount 1→2→3`，那是在**一段连续视频内**；一旦目标离开画面，跟踪器就丢失该轨迹，回来就是新 ID。这与你的观察完全一致。

---

## 2. 底层能力实测：人物库已经能跨会话沿用 ID

用 `runtime/verify_model_on_emulator.py`（本次已支持传入测试类名）在 `RealCue_API35` 上跑了官方自带设备端测试：

```
python runtime/verify_model_on_emulator.py \
    com.example.inspireface_example.view.FaceRepositorySdkTest
```

结果：`BUILD SUCCESSFUL in 1m 30s`，`tests=1 failures=0 errors=0`，日志 `runtime/logs/model-gradle-20261115-195927.log`；Pikachu 与 Megatron 两个模型各跑一遍。

该测试 `bothModelsPersistSearchUpdateAndDeleteLongIds` 验证的断言（`androidTest/.../FaceRepositorySdkTest.java:71-121`）：

| 断言 | 含义 |
| --- | --- |
| `assertFalse(repository.search(feature).matched)` | 空库不会误匹配 |
| `FeatureHubFaceSearch(feature).id == -1` | 空库返回 no-match |
| `insert(...)` 后 `FeatureHubGetExistingIds() == [id]` | 特征成功入库 |
| `repository.search(feature).matched`，`confidence == 1.0` | **同一个人的特征能命中自己，分数 1.0** |
| `close()` → 新 `FaceRepository` → `open()` → `search` 仍命中同一 `id` 且 `name` 保留 | **持久化：重开后沿用旧编号** |
| `delete(id)` 后 `search` 不再匹配 | 删除生效 |

**结论**：`FaceRepository` / FeatureHub 这一层不用重写，直接复用即可。缺的是「实时帧里逐脸提特征 → 查库 → 未命中自动 insert → 把 `person_id` 贴回框上」这一段应用逻辑。

其他已具备的积木：

- `face/FaceImageProcessor.java:80-103` 已证明可对 `faces.tokens[i]` **逐脸**调用 `InspireFace.ExtractFaceFeature(session, stream, token)`，多脸提特征可行。
- `view/FaceTrackingAnalyzer.java:104-147` 已实现逐脸画框 + `TrackBoxSmoother` 平滑 + `trackId` 标签渲染，多人画框的渲染层可直接扩展。
- `view/FaceStabilityGate.java` 已实现「框稳定 N 毫秒才动作」的门控，可用于避免糊脸/侧脸误建档。
- `face/FaceRepository.java:100-106` 的 FeatureHub 配置：`PK_MANUAL_INPUT` + `setEnablePersistence(true)` + `setSearchThreshold(GetRecommendedCosineThreshold())` + `SEARCH_MODE_EXHAUSTIVE`，主键由应用自己分配（`next_id` 自增），**正好可以拿来生成 `P001`**。

---

## 3. 现有代码的缺口清单

| # | 缺口 | 位置 | 影响 |
| --- | --- | --- | --- |
| 1 | 视频识别 Session `maxFaces = 1` | `FaceEngine.java:130-133` | 多人同框只处理 1 张脸 |
| 2 | 摄像头识别只取 `rects[0]` / `tokens[0]` | `RecognitionFaceAnalyzer.java:102,149-151` | 无法逐脸匹配 |
| 3 | `libraryEmpty` 是构造时快照 | `FaceRecognitionActivity.java:255,652` | 中途入库后仍不查库 |
| 4 | 识别只在稳定后做一次（`recognizedStableRun`） | `RecognitionFaceAnalyzer.java:129-141` | 新轨迹出现不会触发查库/建号 |
| 5 | 入库只有手动入口 | `FaceManagementActivity.java` | 陌生人不会自动获得编号 |
| 6 | 标签是 `track_id` | `FaceTrackingAnalyzer.java:105` | 离场重入必然变号（你遇到的现象） |
| 7 | 缺「同帧互斥」约束 | 无 | 两个人可能被分到同一个 `person_id`（串号） |
| 8 | 缺阈值三区决策 | `FaceRepository.java:162-171` 只有单一 TopK 阈值 | 边界分数容易重复建号或误认 |
| 9 | `insert()` 强制要求 `Bitmap crop` | `FaceRepository.java:186` | 自动建档必须能从实时帧产出裁剪图 |

---

## 4. 目标设计

```
CameraX 帧 (NV21, 预旋转)
  → ExecuteFaceTrack(session, stream)       // 多脸，maxFaces = N
  → 每张脸：rect / trackId / token
      ├─ BoxSmoother(trackId) → 稳定框       // 画框，沿用现有实现
      ├─ FaceStabilityGate(trackId) → 是否够清晰稳定
      ├─ ExtractFaceFeature(session, stream, token) → 512 维特征
      └─ PersonIdResolver.resolve(trackId, feature, now)
            ├─ 已有绑定且未过期        → 沿用 personId（不查库，省算力）
            ├─ 查库 TopK(2) 明确命中    → 绑定 personId
            ├─ 灰色区 / 脸不够好        → 继续采样，暂不显示编号
            └─ 连续 N 帧确认无匹配      → insert("P%03d") → 绑定新 personId
  → Overlay 渲染：框上显示 "P001" 而不是 "1"
```

编号规则沿用项目调研文档 `Research-Mobile-Realtime-FaceID.md:16`：从空库开始，A 首次入镜 `P001`，B 首次入镜 `P002`，A 离场再回来仍显示 `P001`，重启应用后仍可检索旧编号。

**关键分工**：`track_id` 只用来维持「这一帧的框对应哪条轨迹」，`person_id` 才用来显示身份。两者用一张映射表连接，绝不混用。

---

## 5. 关键实现细节

### 5.1 打开多脸识别 Session

```java
// FaceEngine.java — 新增，不动现有方法
static synchronized Session createAutoIdVideoSession() {
    return createTrackingSession(
            InspireFace.CreateCustomParameter().enableRecognition(true),
            /* maxFaces */ PREVIEW_MAX_FACES,   // 3，或按需求 5/10
            /* minFacePx */ 24);               // 沿用 createVideoRecognitionSession 的值
}
```

注意 `enableRecognition(true)` 与 `maxFaces > 1` 组合需真机确认性能；`FaceImageProcessor` 已证明逐 token 提特征在 API 层可行，但视频流每帧对多脸提特征会增加耗时，建议按「新轨迹 / 需要重新核对」触发，而不是每帧全量提。

### 5.2 逐脸处理骨架

```java
int n = Math.min(faces.detectedNum, MAX_LABELS);
boxSmoother.beginFrame();
for (int i = 0; i < n; i++) {
    int trackId = faces.trackIds != null && i < faces.trackIds.length
            ? faces.trackIds[i] : -1;
    FaceRect r = faces.rects[i];
    boxSmoother.smooth(trackId, r.x, r.y, r.x + r.width, r.y + r.height, rect);

    float stable = stabilityGate.update(trackId, rect[0], rect[1], rect[2], rect[3], now);
    if (stable < 0f || r.width < uprightWidth * MIN_FACE_WIDTH_RATIO) {
        labels[i] = null;                 // 框先出来，编号等清晰稳定再给
        continue;
    }

    FaceFeature f = InspireFace.ExtractFaceFeature(session, stream, faces.tokens[i]);
    long pid = resolver.resolve(trackId, f, now);
    labels[i] = pid > 0 ? String.format(Locale.ROOT, "P%03d", pid) : null;
    colors[i] = colorByPersonId(pid);     // 同一人同色，跨离场仍一致
}
overlay.submit(boxes, labels, colors, n, ...);
```

### 5.3 trackId → personId 绑定表（核心）

```java
final class PersonIdResolver {
    private final SparseLongArray trackToPerson = new SparseLongArray();
    private final LongSparseArray<Long> lastSeenMs = new LongSparseArray<>();
    private static final long TRACK_TTL_MS = 2_000L;   // 轨迹消失后保留绑定的时间

    synchronized long resolve(int trackId, FaceFeature feature, long nowMs) {
        long bound = trackToPerson.get(trackId, -1L);
        if (bound > 0) {
            lastSeenMs.put(bound, nowMs);
            return bound;                              // 帧间沿用，不查库
        }
        expireStale(nowMs);
        // 查库、决策、必要时 insert —— 见 5.4
        ...
    }

    void onTrackLost(int trackId) { /* 不立即删，走 TTL，避免短暂遮挡重分配 */ }
}
```

**离场重现的正确语义**：轨迹消失时**不要**清空 `track_id → person_id` 的「意图」，而是让新轨迹重新走一次查库；只要库里有这个人，就会命中同一个 `person_id`。因此「沿用编号」由库保证，不依赖跟踪器记住旧轨迹。

### 5.4 查库 / 自动建档：三区阈值决策

单一阈值容易在边界分数上重复建号，建议三区：

```
conf = FeatureHubFaceSearchTopK(feature, 2) 的最佳分数
T_match = InspireFace.GetRecommendedCosineThreshold()      // 官方推荐，需真机标定
T_new   = T_match - 0.10 ~ 0.15                            // 明显不像任何人

conf >= T_match 且次优分不接近         → 命中：绑定 result.ids[0]
T_new < conf < T_match 或次优分接近    → 灰色区：继续采样，不建号、不显示编号
conf <= T_new 且连续 N 帧稳定无匹配    → 新人物：insert("P%03d", feature, crop)
```

`FaceRepository.search()` 目前是 `TopK(1)` 且只返回是否 `>= threshold`（`FaceRepository.java:162-171`）。建议新增 `searchTop2(feature)` 返回 `{id, confidence, runnerUpConfidence}`，供灰色区判断。

自动建档：

```java
long id = ...;                                   // 复用 next_id 自增
String label = String.format(Locale.ROOT, "P%03d", id);
FaceRepository.InsertResult r = repository.insert(label, feature, crop);
```

`insert()` 需要 `Bitmap crop`（`FaceRepository.java:186`）。实时帧可从 `uprightNv21` 按 `EnrollmentFaceAnalyzer.writeCapture()` 的现有逻辑裁一张 JPEG 再解码，或用 `InspireFace.GetFaceAlignmentImage(session, stream, token)`（`FaceImageProcessor.java:93` 已用过）。

### 5.5 防串号（多人同框必做）

- 同一帧内一个 `person_id` 只能被一个 `trackId` 占用：`HashMap<personId, trackId>` 做互斥检查，命中已被别人占的库记录时**不要**直接复用。
- 建新号时用 TopK(2)：若次优分数与最优分数接近，说明可能是同一个人没认稳，先不建号。
- 两个人同时首次入镜，必须等各自的稳定门控都通过再分别 insert，避免把同一张脸写两次。

### 5.6 是否更新已有特征

`FaceRepository.update()` 支持传新 `feature` 调 `FeatureHubFaceUpdate`（`:250-251`）。**建议默认关闭自动更新**：光照/角度变化会让人脸特征漂移，逐步把库带偏；只有在明确高置信度命中（`conf` 明显高于 `T_match`）且姿态质量好时才做带权更新，并且要限频。

### 5.7 标签渲染

`TrackIdOverlayView` / `FaceOverlayView.Frame` 目前只支持 `RectF[] + 单一颜色`（`RecognitionFaceAnalyzer.java:184-188`）。扩展为 `RectF[] + String[] labels + int[] colors`，或在 `FaceTrackingGlView` 的 `labelTrackIds[]` 旁并行加 `labelTexts[]`。GL 文字可用现有 `AnimatedLabelView` 思路或预渲染成纹理图集。

### 5.8 持久化与模型隔离

`FaceRepository` 按 `model.sdkName()` 分目录和 SharedPreferences（`:73-80`），Pikachu / Megatron 各有独立 `features.db` 和 `next_id`。**切换首页模型后编号会从 `P001` 重新开始**，这是上游设计，需要在 UI 上说明或改为跨模型统一编号（额外工作量）。

重启应用后 `repository.open()` 会重新 `FeatureHubDataEnable` 并加载 `features.db`——本次实测已验证这一步能命中旧 ID，所以「重启后沿用编号」不需要额外代码。

---

## 6. 阈值标定（B0：测试开发已完成，真人样本待采集）

`GetRecommendedCosineThreshold()` 是官方通用推荐值，**实测为 0.48**（Pikachu / 512 维，附录 B.1）。附录 B 已把「同人跨压缩 / 明暗 / 尺度 / 小角度旋转 / 镜像」的分数分布测出来（最低 0.9680），但**异人分数分布仍完全空白**。真正决定 `T_match` / `T_new` 的是「同人最低分」与「异人最高分」之间的间隔，后半段必须用真机两个真人样本标定。

**状态更新（2026-11-15 深夜）**：B0 的**测试开发部分已经实现并在 `RealCue_API35` 上实跑通过**——不再只是「建议新增」。新增 4 个文件，`main/` 源码仍一行未改：

| 文件 | 职责 |
| --- | --- |
| `androidTest/.../view/CalibrationMath.java` | 纯统计类，不依赖 Android / InspireFace：`analyze()` 两个重载 + `percentile()`，输出 `sameMin` / `diffMax` / `separable` / `margin` / `matchThreshold` / `newThreshold` / `recallAtMatch` / `falseAcceptAtMatch` |
| `androidTest/.../view/GalleryThresholdCalibrationTest.java` | 3 项设备端测试：纯数学断言、内置样本走真实 native 特征链路、完整 pipeline（读样本 → 提特征 → pair 分数矩阵 → JSON/CSV 落盘 → 回读校验） |
| `runtime/calibrate_threshold.py` | 一键脚本：预装 APK → push A/B 照片 → 触发设备端测试 → 回收结果 → 打印结论与 Markdown 摘要 |
| `runtime/tmp/compile_androidtest.py` | 可复用编译闸门，启动模拟器前先 `:app:assembleDebugAndroidTest`，避免白等 |

**三层断言**保证不是「只写方案」：统计逻辑可被穷尽覆盖、内置样本验证 `libInspireFace.so` 真的加载并产出 512 维特征、完整 pipeline 验证产物可解析回读。设备端 XML 实测 `tests="3" failures="0" errors="0" skipped="0"`（`BUILD SUCCESSFUL in 1m 29s`，附录 C.2）。

**但还没有可用的 `T_match` / `T_new`**：最终一轮只是**链路验证**，A/ 与 B/ 误放了同一张 `kun.jpg`，脚本与设备端测试都正确地把结果标记为 `doNotUseForThreshold=true`（同人/异人分数全是 1.0000、`separable=false`、`margin=0`），并打印 `NOT USABLE FOR THRESHOLD`。这正是「把测试做扎实」的一部分——**不允许拿合成演练或同人误放的数据去拍板阈值**。

采集与复跑步骤、实跑中修掉的坑、证据文件清单见 **附录 C**。底层能力已验证，剩下唯一缺的实验数据就是**两个真人的样本**。

---

## 7. 改动清单

| 文件 | 改动 | 性质 |
| --- | --- | --- |
| `view/FaceEngine.java` | 新增 `createAutoIdVideoSession()`，多脸 + recognition | 新增方法 |
| `view/RecognitionFaceAnalyzer.java` | 改为逐脸循环；或新建 `AutoIdFaceAnalyzer` 复用 `UprightFaceCameraAnalyzer` | 重构 / 新增类 |
| `face/FaceRepository.java` | 新增 `searchTop2()`；可选 `insertAuto(label, feature, crop)` | 小改 |
| **新增** `face/PersonIdResolver.java` | trackId→personId 绑定、TTL、三区决策、防串号 | 新类，核心 |
| `view/FaceOverlayView.java` / `TrackIdOverlayView.java` | 支持多框 + 文字标签 + 按 personId 着色 | 扩展 |
| `FaceTrackingAnalyzer.java` 或新 Activity | 接入 resolver，把标签从 trackId 换成 `P%03d` | 接线 |
| `androidTest/.../GalleryThresholdCalibrationTest.java` | 阈值标定 | 新增测试 |
| `androidTest/.../AutoIdReentryTest.java` | 自动建档 + 离场重入断言 | 新增测试 |

上游 `main/` 源码目前**一行未改**；阶段 B 会首次修改业务代码。

---

## 8. 验证方案（沿用项目调研文档第 47 行的 A/B/C 脚本）

从空库开始，在真机上：

1. **A 首次入镜** → 应显示 `P001`，记录「首次出框延迟」和「首次显示编号延迟」。
2. **B 首次入镜** → 应显示 `P002`；A、B 同框交叉走动，检查没有两人共用同一编号。
3. **A 离开画面**至原轨迹失效 → 从另一侧回来 → 应仍显示 `P001`（**这就是你这次报的失败点**）。
4. **C 首次入镜** → 应显示 `P003`。
5. **重启应用** → A 回来 → 应仍显示 `P001`（验证 `features.db` 持久化）。
6. 指标：旧号恢复率、同一人被重复编号比例、不同人共用编号比例、出框/身份确认延迟、帧率。

自动化：在设备端测试里用两张不同的人脸图 + 同一人的不同帧，直接断言 `resolve()` 返回同一个 `person_id`；真机手动脚本按上面 6 步录屏核对。

---

## 9. 风险与边界

- **模型许可**：官方模型包限学术用途（`Research-Mobile-Realtime-FaceID.md:10,26`），商用需另行核实。
- **性能**：每帧对多脸提 512 维特征 + 查库会增加耗时；`SESSION_RELEASE_WAIT`、`DETECT_MODE_LIGHT_TRACK` 的检测摊帧策略要一起调，真机测帧率。
- **误建档**：模糊/侧脸/遮挡容易造出重复编号，`FaceStabilityGate` + 连续 N 帧确认 + 灰色区是必要的，不能省。
- **模型切换**：库按模型隔离，切模型编号重置。
- **FeatureHub 进程级单例**：`FaceRepository.HUB_LOCK` 已做同步（`:62-63`），新逻辑必须继续在同一把锁下访问，且 `open()` 要在 `GlobalLaunch` 之后、SDK executor 上调用。
- **本次实测边界**：验证用的是静态样本图 `kun.jpg` 与 FeatureHub round-trip，不是真机摄像头实时帧；多人同框、跨帧重入、阈值、帧率仍未验证。

---

## 10. 分阶段建议

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| B0 | 真机阈值标定（第 6 节），拿到 `T_match` / `T_new` | **测试开发已完成并实跑通过**（3 项设备端测试 0 失败、一键脚本、JSON/CSV 产物、防误用保护，附录 C）；**参数待两个真人样本** |
| B1 | `PersonIdResolver` 纯逻辑 + JVM 单测（不依赖摄像头） | 可离线验证的身份绑定 |
| B2 | 多脸 Session + 逐脸提特征 + 自动建档接入，标签换 `P%03d` | 可安装 APK |
| B3 | 真机 A/B/C 重入测试，调防串号与门控 | 验收数据 |

**回答你的问题**：有机会实现，而且已经比预想中近——人物库、检索、持久化、跨会话沿用 ID 的底层能力本次实测已通过；剩下的是把它接到实时帧上、把 `track_id` 标签换成 `person_id`、补自动建档和防串号。建议先做 B0 阈值标定，因为它决定后面所有匹配逻辑的参数。

---

## 附：本次实测证据

- 命令：`python runtime/verify_model_on_emulator.py com.example.inspireface_example.view.FaceRepositorySdkTest`
- 日志：`runtime/logs/model-gradle-20261115-195927.log`
- 结果：`BUILD SUCCESSFUL in 1m 30s`；`tests=1 failures=0 errors=0 skipped=0`；`[PASS] FaceRepositorySdkTest.bothModelsPersistSearchUpdateAndDeleteLongIds`
- 设备：`RealCue_API35(AVD) - 15`，Pikachu 与 Megatron 均加载成功
- 相关脚本改动：`runtime/verify_model_on_emulator.py` 支持 `argv[1]` 指定测试类（默认仍为 `ModelFeasibilityTest`）

**准确性实测（附录 B）**：`python runtime/verify_model_on_emulator.py com.example.inspireface_example.view.GalleryAccuracyProbeTest`
→ `BUILD SUCCESSFUL in 53s`，`tests=1 failures=0`，日志 `runtime/logs/model-gradle-20261115-201325.log`，
数值 `runtime/logs/model-logcat-20261115-201325.txt`。实测阈值 `0.48` / 512 维；7 个同人变体
全部命中 `PERSON_ID=1001`，最低 0.9680；纯黑图 `NO_FACE` 不误建档。

---

## 附录 A：当前 APK 里「人物库 ID」能不能看到

**能，你现在手机上这个 APK 就能看到，不用改代码**——只是不在实时画框上，而且必须先手动录入。

| 位置 | 显示内容 | 前提 |
| --- | --- | --- |
| 首页 → **Face management**（人物库列表） | 每条 `ID · %d`（`strings.xml` 的 `face_id_format`）+ 名字（不填名字默认为 `Face N`） | 已手动录入记录 |
| **Face recognition** → 照片 / 视频命中 | `ID · %1$d · cosine confidence %2$.3f · threshold %3$.3f`（`recognition_result_details`） | 库非空且检索命中 |
| **Face recognition** → 未命中 | `cosine confidence %1$.3f · threshold %2$.3f`（`recognition_no_match_details`） | 库非空 |
| **Face 1:1** 比对 | cosine 分数 + `GetRecommendedCosineThreshold()` 阈值（`FaceCompareActivity.java`） | 两侧各选一张脸 |

源码定位：

- `FaceManagementActivity.java`：`holder.id.setText(getString(R.string.face_id_format, record.id))`
- `strings.xml`：`face_id_format = "ID · %1$d"`、`face_list_count = "%1$d stored identities"`
- `FaceRecognitionActivity.java:491-492` 命中时把 `record.id / confidence / threshold` 一起显示；`:501-503` 未命中时仍显示分数与阈值
- `FaceCompareActivity.java`：`float cosine = InspireFace.FaceComparison(...)`、`float threshold = InspireFace.GetRecommendedCosineThreshold()`

**第三行是判断「为什么没匹配上」的关键**：未命中时也会显示实际分数和阈值。分数只比阈值低一点 → 阈值偏严或录入样本不够代表；分数差很多 → 角度、光照、遮挡或录入质量问题。你现在就可以用这个页面量一下自己的重入分数。

**看不到的部分**：`Face Tracking` 页框上的数字是 `track_id`，不是库 ID；库为空时视频识别页直接走 `EMPTY_LIBRARY` 不查库；没有任何地方会**自动**给陌生人分配编号。

**所以**：想看「对应人物库给他分的是什么」→ 现在去 Face management 或 Face recognition 就能看；想让它在实时画框上自动出现、并且准 → 需要阶段 B 改造 + 重新构建 APK + 重新传到手机（现在手机上的 APK 是旧版，改源码不会自动生效）。

---

## 附录 B：准确性实测（同一个人，不同画面条件）

用户追问「希望这个是比较准的」。为回答「准」是否可控，新增项目自建设备端测试
`app/src/androidTest/.../view/GalleryAccuracyProbeTest.java`（`main/` 源码仍未改）：

- 在隔离的 FeatureHub 里只录入**一个**身份（`Pikachu`，`PERSON_ID = 1001`）。
- 对同一张内置样本脸生成 7 种画面条件：原图、JPEG q=60 往返、亮度 +40 / -40、缩放 0.75、旋转 4°、水平镜像，外加纯黑图。
- 每个变体重新检测 + 提特征，记录 `FaceComparison(base, variant)` 与
  `FeatureHubFaceSearchTopK(variant, 1)` 的 confidence / 命中 ID。
- 同时打印 `InspireFace.GetRecommendedCosineThreshold()` 的**实际数值**——这是所有阈值策略的基准。
- 纯黑图应报 `NO_FACE`，不产生特征、不误建档。

### B.1 实测结果

设备 `RealCue_API35`（Android 15 / API 35 / x86_64），模型 `Pikachu`。
构建日志 `runtime/logs/model-gradle-20261115-201325.log`（`BUILD SUCCESSFUL in 53s`，
`tests=1 failures=0 errors=0 skipped=0`），数值行在
`runtime/logs/model-logcat-20261115-201325.txt`：

```text
=== THRESHOLD 0.48 featureLength=512 ===
base selfSimilarity=1.0
galleryCount=1 enrolledId=1001
base         cmp=1.0000 hubConf=1.0000 hitId=1001 aboveThreshold=true sameId=true
jpeg_q60     cmp=0.9845 hubConf=0.9845 hitId=1001 aboveThreshold=true sameId=true
bright_+40   cmp=0.9839 hubConf=0.9839 hitId=1001 aboveThreshold=true sameId=true
dark_-40     cmp=0.9732 hubConf=0.9732 hitId=1001 aboveThreshold=true sameId=true
scale_0.75   cmp=0.9952 hubConf=0.9952 hitId=1001 aboveThreshold=true sameId=true
rotate_4deg  cmp=0.9937 hubConf=0.9937 hitId=1001 aboveThreshold=true sameId=true
mirror       cmp=0.9680 hubConf=0.9680 hitId=1001 aboveThreshold=true sameId=true
blank        NO_FACE (correctly not enrolled)
```

| 画面条件 | 与底图相似度 | FeatureHub confidence | 命中 ID | ≥ 阈值 0.48 | 沿用同一 ID |
| --- | --- | --- | --- | --- | --- |
| 原图 base | 1.0000 | 1.0000 | 1001 | 是 | 是 |
| JPEG q=60 往返 | 0.9845 | 0.9845 | 1001 | 是 | 是 |
| 亮度 +40 | 0.9839 | 0.9839 | 1001 | 是 | 是 |
| 亮度 −40 | 0.9732 | 0.9732 | 1001 | 是 | 是 |
| 缩放 0.75 | 0.9952 | 0.9952 | 1001 | 是 | 是 |
| 旋转 4° | 0.9937 | 0.9937 | 1001 | 是 | 是 |
| 水平镜像 | 0.9680 | 0.9680 | 1001 | 是 | 是 |
| 纯黑图（对照） | — | — | — | — | `NO_FACE`，未建档 |

**读数**：

- 官方推荐余弦阈值 `GetRecommendedCosineThreshold() = 0.48`，特征维度 512。
- 7 个同人变体**全部命中同一个 `PERSON_ID = 1001`**，`sameId=true` 无一例外，零漏匹配。
- 分数区间 `0.9680 ~ 1.0000`，极差仅 `0.0320`；最低的镜像 `0.9680` 仍高出阈值 `0.4880`（约为阈值的 2 倍）。
- `FaceComparison` 与 `FeatureHubFaceSearchTopK` 的 confidence **逐行完全相等**，说明离线两两比对与库检索用同一套特征/度量，阈值可互相搬用。
- 纯黑图正确返回 `NO_FACE`，没有凭空造特征、没有误建档。

### B.2 这能说明什么、不能说明什么

**能说明**：模型这一层「同一个人不会因为压缩、明暗、尺度、小角度旋转、镜像就被认成别人」。
你担心的「离场再回来变号」，**不是模型认不出人**——只要库里有这个人，重新提特征查库
就能拿回同一个 ID。第 2 节的持久化测试与本节变体测试互相印证。

**不能说明**（不讲清楚，「准」就是虚的）：

1. 这些是**同一张静态图做数字变换**，不是真机摄像头的跨帧、跨姿态、跨表情、
   跨距离、跨妆容变化。像素级扰动对深度特征影响小，分数高是预期内的。
2. **异人（不同人）分数分布完全没测**——内置样本只有 `kun.jpg` 一张脸，本机造不出
   第二个真人。真正决定「准不准」的是「同人最低分」与「异人最高分」之间的间隔；
   现在只有上界，没有下界。
3. 阈值 `0.48` 对这批变体**过于宽松**（最低分都高出近 0.49）。同人几乎不会漏，
   但换成陌生人、分数落在 `0.48 ~ 0.9` 时会怎样**未知**。阈值偏低的风险是
   「把陌生人误认成已知人」，而不是「认不出熟人」。

**结论**：`沿用旧号` 这件事本身是稳的，可以放心；剩下会让你觉得「不准」的风险集中在
两处——**应用层决策**（单帧就建号、单一阈值、串号、糊脸入库，见第 3 / 5 节）与
**异人阈值未标定**（第 6 节 B0）。这两项都要真机两个真人样本才能收口，本机测不出来，
也不该拿这批数字去拍板 `T_match` / `T_new`。

### B.3 一键复跑

```sh
python runtime/verify_model_on_emulator.py \
    com.example.inspireface_example.view.GalleryAccuracyProbeTest
```

### B.4 证据文件

| 文件 | 内容 |
| --- | --- |
| `runtime/logs/model-gradle-20261115-201325.log` | `BUILD SUCCESSFUL in 53s`，测试 1 项 0 失败 |
| `runtime/logs/model-logcat-20261115-201325.txt` | `RealCueGallery` 全部数值行（阈值、逐变体分数、命中 ID） |
| `app/src/androidTest/.../view/GalleryAccuracyProbeTest.java` | 测试用例本体（变体生成 + 提特征 + 查库） |
| `runtime/verify_model_on_emulator.py` | 已支持 `argv[1]` 指定测试类、logcat 关键字泛化为 `RealCue` |

> 第一次运行（`model-gradle-20261115-200855.log`）虽 `BUILD SUCCESSFUL`，但脚本
> logcat 关键字只匹配 `RealCueModel`，数值行没进摘要；修正后第二次重跑才拿到上表。

---

## 附录 C：B0 阈值标定测试开发实录（2026-11-15 深夜）

用户授权「按你的步骤来，记得你要做好测试开发工作的」。本附录记录 B0 从方案落地为
**可编译、可实跑、有断言、有产物、有防误用保护**的一套测试，以及真实跑出来的结果。
`main/` 源码仍一行未改。

### C.1 新增文件

| 文件 | 职责 |
| --- | --- |
| `app/src/androidTest/.../view/CalibrationMath.java` | 纯统计类，**不依赖 Android / InspireFace**。`analyze(List<Float> same, List<Float> diff)` 与带 `newDelta` 的重载、`percentile()`、`Result.asJson()` |
| `app/src/androidTest/.../view/GalleryThresholdCalibrationTest.java` | 3 项设备端 instrumented test，TAG `RealCueCalib`，模型 `Pikachu` |
| `runtime/calibrate_threshold.py` | 一键脚本：`install_apks()` → `push_samples()` → `run_calibration_test()` → `capture_logcat()` → `pull_results()` / `recover_from_logcat()` → `write_summary()` → `print_conclusion()` |
| `runtime/tmp/compile_androidtest.py` | 可复用编译闸门：启动模拟器前先 `:app:assembleDebugAndroidTest` |
| `runtime/calibration/{A,B}/` + `README.md` | 真人样本采集模板与说明 |

`CalibrationMath` 的阈值规则（`DEFAULT_NEW_DELTA = 0.12f`）：

```java
if (!hasDiff) {
    matchThreshold = clamp01(sameMin - 0.02f);                       // 缺异人样本：仅保守下界
} else if (separable) {
    matchThreshold = clamp01((sameMin + diffMax) / 2f);              // 可分：取 gap 中点
} else {
    matchThreshold = clamp01(Math.min(sameMax, diffP95 + 0.02f));   // 重叠：优先零误接受
}
float newThreshold = clamp01(matchThreshold - Math.max(0f, newDelta));
```

### C.2 三层测试与实跑证据

| # | 用例 | 断言要点 |
| --- | --- | --- |
| 1 | `statisticsLogicIsCorrect` | 百分位（含中位数 `0.38` 的手算校验）、可分 / 重叠 / 缺异人三种分支、`recallAtMatch` / `falseAcceptAtMatch` |
| 2 | `builtInSampleProducesValidIntraPersonScores` | 内置 `kun.jpg` 经 7 种变体，逐张走 native 检测 + `ExtractFaceFeature` 得到 512 维；pair 数 = `n*(n-1)/2`；纯黑图 `extract(...) == null` |
| 3 | `calibrationPipelineWritesResultFile` | 外部 A/B 样本（或 `_dryrun` 合成）→ pair 分数矩阵 → 写 `threshold-result.json` / `threshold-pairs.csv` → 回读并断言 `doNotUseForThreshold` 与 JSON 字段一致 |

最终一轮命令：

```sh
C:/Users/pjd02/.workbuddy/binaries/python/versions/3.13.12/python.exe \
    "D:/paper/RealCue/InspireFace-LocalRun/runtime/calibrate_threshold.py" \
    "D:/paper/RealCue/InspireFace-LocalRun/runtime/chain_samples"
```

`chain_samples` 是**仅用于链路验证**的临时目录（同一张 `kun.jpg` 复制到 A/B），已删除。

结果（`runtime/logs/calibration-20261115-204457/`）：

```text
=== installing target + androidTest APK before pushing samples ===
  BUILD SUCCESSFUL in 25s
=== pushed A=3 B=2 (failures=0) ===
BUILD SUCCESSFUL in 1m 29s
Starting 3 tests on RealCue_API35(AVD) - 15
Finished 3 tests on RealCue_API35(AVD) - 15
<testsuite ... tests="3" failures="0" errors="0" skipped="0" time="3.609">
recovered CSV from logcat (11 lines)
recovered JSON from logcat (16 lines)
=== CALIBRATION RESULT ===
  mode=external_samples official=0.4800
  sameMin=1.0000 diffMax=1.0000 separable=False margin=0.0000
  T_match=1.0000 T_new=0.8800 recall=1.0000 FAR=1.0000
  warning: A/ and B/ look like the SAME PERSON ... Thresholds NOT usable.
=== NOT USABLE FOR THRESHOLD - see warning above ===
```

设备端 XML：`app/build/outputs/androidTest-results/connected/debug/
TEST-RealCue_API35(AVD) - 15-_app-.xml`，三个 `<testcase>` 分别为
`builtInSampleProducesValidIntraPersonScores`、`calibrationPipelineWritesResultFile`、
`statisticsLogicIsCorrect`。

> 脚本 stdout 里的 `[PASS] ...` 行未落盘为日志文件，**可复核的硬证据是
> Gradle 构建日志 + 设备端 XML + logcat**，不是终端回显。

### C.3 统计输出字段

| 字段 | 含义 |
| --- | --- |
| `sameMin` / `sameP05` / `diffMax` / `diffP95` | 同人最低分 / 5 分位、异人最高分 / 95 分位 |
| `separable` | `sameMin > diffMax`，两段分布不重叠 |
| `margin` | `sameMin - diffMax`，间隔；`<= 0` 表示不可分 |
| `matchThreshold` / `newThreshold` | 建议的 `T_match` / `T_new`（仅 `doNotUseForThreshold=false` 时可用） |
| `recallAtMatch` / `falseAcceptAtMatch` | `T_match` 下同人召回率 / 异人误接受率 |
| `hasDiffData` | 是否存在异人样本；`false` 时阈值只是保守下界，不能当标定结果 |

### C.4 实跑中修掉的坑（均已落地）

| # | 问题 | 修法 |
| --- | --- | --- |
| 1 | `/sdcard/Android/data/...` 在 Scoped Storage / FUSE 下 adb 不可见，`adb pull` 报 No such file | 改用 `/storage/emulated/0/Android/data/<pkg>/files/calibration` |
| 2 | `mkdir /storage/emulated/0/Android` Permission denied，`Android/data` 父目录还不存在 | 新增 `install_apks()`，push **之前**先装 `installDebug` + `installDebugAndroidTest` |
| 3 | `connectedDebugAndroidTest` 收尾清掉 App 外部私有目录，pull / `exec-out cat` 都拿不到结果 | 设备端把完整 JSON/CSV 以 `RESULT_*_BEGIN` / `JSON|` / `CSV|` / `RESULT_*_END` 镜像进 logcat，主机 `recover_from_logcat()` 重建 |
| 4 | 复用上一轮旧 `threshold-result.json`，结论错乱 | 每次回收前强制删除旧 JSON / CSV；采集说明改用 `DEFAULT_SAMPLES` |
| 5 | 无结果时 `write_summary()` 对 `None` 取 `.get()` 崩溃 | None-safe 摘要；`ok` 判定要求 `result is not None` |
| 6 | 合成演练 / A、B 误放同一个人会产出危险的「完美」阈值 | `looksLikeSamePerson` + `doNotUseForThreshold` |
| 7 | 初稿手算断言写错：中位数 `0.42`→`0.38`、`recall 0.25`→`0.5`、写死 `15 pairs`、`noFace==0` 语义误用 | 按实际分布计算；blank 单独断言 `extract(...) == null` |
| 8 | Java 里误写 Python `"..%02d" % i`；Bitmap 过早 `recycle()`；patch 脚本漏 `import os` | 改 `String.format(Locale.ROOT, ...)`；`loadedBitmaps` 保活到 `finally` 统一回收；补齐 import |

### C.5 防误用保护

```java
boolean external = filesA.size() >= MIN_FEATURES_PER_PERSON
        && filesB.size() >= MIN_FEATURES_PER_PERSON;
boolean looksLikeSamePerson = external && !r.separable
        && r.diffMax >= (r.sameMin - 0.02f);
boolean unusable = !external || looksLikeSamePerson;
```

- 无外部真人样本（纯自检 / `_dryrun` 合成演练）→ 不可用于阈值。
- A/ 与 B/ 看起来是同一个人 → 不可用于阈值，JSON 写 `doNotUseForThreshold=true`
  与明确 warning，脚本以 `NOT USABLE FOR THRESHOLD` 收尾。
- 每次回收前强制删除旧 JSON / CSV，绝不复用上一轮产物。

**本轮正是这条规则生效**：A/B 同脸 → 同人 / 异人分数全 `1.0000` →
`separable=false` → `margin=0` → 结果被否决。数学上算出 `T_match=1.0000`
只是演示 pipeline 能跑完，**不是可用参数**。

### C.6 采集与复跑（唯一剩下的动作）

```text
D:\paper\RealCue\InspireFace-LocalRun\runtime\calibration\
├── A\    同一个人（甲），至少 2 张（建议 3~5）
└── B\    另一个人（乙），至少 2 张（建议 3~5）
```

用最终部署的 Xiaomi 14 Pro 拍，覆盖角度 / 距离 / 光照 / 表情，每张只含一张清晰正脸，
A/ 与 B/ 必须是不同的人。然后：

```sh
cd /d D:\paper\RealCue\InspireFace-LocalRun
C:/Users/pjd02/.workbuddy/binaries/python/versions/3.13.12/python.exe runtime/calibrate_threshold.py
```

不带参数即使用默认 `runtime/calibration`；也可显式传路径。产物落在
`runtime/logs/calibration-<时间戳>/`。**判定标准**：摘要无「不可用于设定阈值」警告、
`separable=true`、`margin>0`、`doNotUseForThreshold=false`。

### C.7 产物与证据

| 文件 | 内容 |
| --- | --- |
| `runtime/logs/calibration-20261115-204457/threshold-result.json` | 905 B，完整统计，可 `json.load()` |
| `runtime/logs/calibration-20261115-204457/threshold-pairs.csv` | 217 B，header + 4 same + 6 diff |
| `runtime/logs/calibration-20261115-204457/calibration-summary.md` | 1671 B，人类可读摘要 |
| `runtime/logs/calibration-install-20261115-204457.log` | 预装 `BUILD SUCCESSFUL in 25s` |
| `runtime/logs/calibration-gradle-20261115-204457-run1.log` | `BUILD SUCCESSFUL in 1m 29s` |
| `runtime/logs/calibration-logcat-20261115-204457.txt` | `GUARD` / `RESULT_*` 镜像行 |
| `B0-Calibration-Test-Report.html` | 可单独打开 / 传手机的可视化报告 |

> 历史 `calibration-summary.md` 里的采集目录仍指向已删除的临时 `chain_samples`
> （生成时脚本尚未修正），**以本附录与 `runtime/calibration/README.md` 为准**。

### C.8 边界（写于 B0 收尾时）

B0 的**测试开发**完成，**标定**未完成：仍无可用的 `T_match` / `T_new`。
一次性修补脚本与临时 `chain_samples`、`__pycache__` 已清理，
只保留 `compile_androidtest.py` 作为可复用编译闸门。

> **后续更新**：当时写的「B1~B3、`main/` 业务源码……均未开始」已被**附录 D** 推翻 ——
> 阶段 B 主链路此后已落地并实跑通过，跨帧重入也已在设备端测试覆盖（但仍是模拟器 +
> 合成向量，不是真机真人）。「真机实时帧 / 多人同框 / 帧率 / 最终准确率」至今仍未验证。

---

## 附录 D：阶段 B 自动编号主链路落地（2026-11-15 深夜）

用户授权「按计划实行，包括修改代码，完成开发」后，不再停留在调研与 B0 测试开发，
**直接修改 `main/` 业务源码**。本附录专门回答此前三个问题现在的状态。

### D.1 一句话结论

**「离场重入编号变」这个核心问题已经在代码层解决并设备端验证通过**：实时框上的数字
不再是短时 `track_id`，而是 FeatureHub 长期 `person_id` 渲染的 `P001 / P002`；
A 完全离场（会话重置）后再回来，实测仍命中 `P001`。**但「准」这件事还没收口** ——
阈值仍是启发式占位值，必须先做真机 B0 两真人标定。

### D.2 三个原始问题的当前状态

| 用户的问题 | 附录 A/B 时的回答 | 现在（阶段 B 后） |
| --- | --- | --- |
| 人物库分配的 ID 能不能看到 | 只能在 Face management / Face recognition / Face 1:1 页面看；实时框上仍是 `track_id` | **实时框上也能看到**：`AutoIdActivity` 的多脸框直接标 `P001 / P002`，且状态栏显示库大小；自动建档记录同时进「人脸管理」列表 |
| 能不能比较准 | 同人 7 变体 7/7 命中 ID 1001（最低 0.9680），但只有一个样本、无阈值标定 | **结构已就绪，数值仍不可信**：三区阈值 / 连续 miss 防误建档 / 同帧防串号都实现且单测覆盖；`0.48 / 0.36` 只是占位值，真机准确率未知 |
| 第一次 ID 1、离场回来变 ID 2 | 定位为 track_id 短期 ID，人物库未接入实时流；给了 B0~B3 方案 | **已修复并实跑**：`PersonIdResolver` 把 track→person 绑定，会话重置只清内存不清库，设备端 `REENTRY_RESULT MATCHED(person=1, conf=1.0)` |

### D.3 分层实现（为什么这样拆）

```
PersonIdResolver<F,C>          纯 Java，零 Android / 零 SDK 依赖，15 项 JVM 单测
        ▲  Gallery / CropSource 适配器接口
AutoIdFaceAnalyzer             CameraX 单线程：逐脸提特征 → resolve → 查库/建档 → P00x 标签
        ▲
AutoIdActivity + Home 入口     用户可见页面；TrackIdOverlayView.submitLabels() 渲染
        ▲
FaceRepository / FeatureHub    持久化人物库（复用官方 next_id 自增序列）
```

**分层理由**：`app/build.gradle` 没有 Robolectric / Mockito，JVM 单测不能碰
`Bitmap`/`Context`/native。把决策逻辑抽成纯 Java 泛型类，才能把「三区阈值、TTL、
防串号、连续 miss、NaN/null 防御」穷尽覆盖；生产侧只做适配，不重写策略。

### D.4 设备端实跑证据（`AutoIdReentryTest`，3/3 通过）

设备 `RealCue_API35(AVD) - 15`，`connectedDebugAndroidTest` → `BUILD SUCCESSFUL in 46s`，
XML `tests="3" failures="0" errors="0" skipped="0"`。logcat 关键行：

```
SETUP featureLen=512 recommendedThreshold=0.48
ENROLL_STEP i=0 -> PENDING(person=-1, conf=-Infinity)   ← 空库先观察
ENROLL_STEP i=1 -> PENDING(person=-1, conf=-Infinity)
ENROLL_STEP i=2 -> ENROLLED(person=1, conf=-Infinity)   ← 连续 3 次无匹配才建档
REENTRY_RESULT MATCHED(person=1, conf=1.0) expectedId=1 ← 会话重置后仍是 P001
TWO_IDS idA=1 idB=2 enrollCalls=2                       ← 双人不同编号
SAME_FRAME a=MATCHED(person=1) b=SKIPPED(person=-1)     ← 同帧互斥防串号
B_AGAIN MATCHED(person=2, conf=1.0)
AFTER_RESTART MATCHED(person=1, conf=1.0) expectedId=1  ← 关库重开后仍命中
```

`featureLen=512 recommendedThreshold=0.48` 说明这条链路走的是**真实 native 特征提取 +
真实 FeatureHub 检索**，不是 mock 出来的分数。

### D.5 全量验证闸门

| 闸门 | 结果 |
| --- | --- |
| 全量 JVM 单测 | **68 tests / 0 failures / 0 errors / 1 skipped**（`PersonIdResolverTest` 15/15） |
| `assembleDebug` / `assembleDebugAndroidTest` | 成功 |
| `connectedDebugAndroidTest` | 3/3 通过，46s |
| `aapt2 dump badging` / `xmltree` | APK 112 MB，`targetSdk 35`，**`AutoIdActivity` 已注册** |
| `apksigner verify --verbose` | **`Verifies`**（v2 scheme，1 signer） |

详见 `LocalRun-Plan.md` 末章、`runtime/logs/stageB-apk-verify.txt`、
`StageB-AutoId-Report.html`。

### D.6 准确性边界（必须诚实说明）

1. **阈值未标定**：`T_match=0.48` / `T_new=0.36` 是 SDK 推荐值 + 启发式 delta，代码里
   明确标注 "NOT measured values"。`runtime/calibration/A|B/` 仍是空模板，
   B0 的 `doNotUseForThreshold=true` 警告依然成立。
2. **灰色区生产线不可达**：`FaceRepository.open()` 用 SDK 推荐阈值设置 FeatureHub
   搜索阈值，低分候选被提前过滤；`PersonIdResolver` 实际只见到「高分命中 / 无候选」。
   要三区完整生效，需 B0 后让该阈值可配置或改查 TopK 原始结果。
3. **B3 第二个人是合成向量**（`-realFeature.data`）：只验证分支与防串号，
   **不是真人异人分数，禁止用于阈值标定**。
4. **验证环境是模拟器**：静态样本 + 合成向量，真机实时帧 / 多人同框 / 帧率 /
   最终准确率都还没测。
5. **置信度 1.0 是「同一张脸自比对」**：设备端用的是同一特征或其变体，
   不代表真机跨帧、跨光照也能到 1.0。

### D.7 在真机上看效果

把 `app-debug.apk` 传到 Xiaomi 14 Pro → 打开首页新增的**「人物自动编号 / Auto Person ID」**
卡片 → 露出人脸，稳定约 1 秒后框上出现 `P001`；第二个人出现得到 `P002`；
A 离开画面再回来应仍是 `P001`。页面下方「重置会话（保留人物库）」按钮可模拟完全离场。

### D.8 产物清单

| 文件 | 内容 |
| --- | --- |
| `main/.../face/PersonIdResolver.java` | 决策内核（纯 Java） |
| `test/.../face/PersonIdResolverTest.java` | 15 项 JVM 单测 |
| `main/.../view/AutoIdFaceAnalyzer.java` | 生产侧逐脸接入 |
| `main/.../AutoIdActivity.java` + 布局 / 资源 / Manifest / Home 入口 | 可见页面 |
| `main/.../view/FaceEngine.java` / `TrackIdOverlayView.java` | 多脸 Session / 标签渲染扩展 |
| `androidTest/.../view/AutoIdReentryTest.java` | 3 项设备端重入测试 |
| `runtime/logs/model-gradle-20261115-213010.log` / `model-logcat-20261115-213010.txt` | 实跑日志 |
| `runtime/logs/stageB-apk-verify.txt` | APK 静态 + 签名验证 |
| `StageB-AutoId-Report.html` | 可单独打开 / 传手机的可视化报告 |

---

## 附录 E：把所有编号显示统一到长期 person_id（2026-11-16）

### E.1 先修正前面一个错误结论

附录 D 曾把「离场再进来变成 ID 2」判为已在代码层解决，并在 D.7 让用户去首页
打开新增的**「人物自动编号 / Auto Person ID」**卡片。用户随后发来三张真机截图
（`ID 4` / `ID 7` / `ID 6`，顶部 `模型 · Megatron`、底部「跟踪 Session 参数 ·
Light 轻量跟踪」）证明：**他一直在用官方 Demo 原有的「人脸跟踪」页**
（`FaceDetectionActivity` 的 video tracking tab），那个页面当时仍然是
`"ID " + trackIds[i]`。

也就是说，只做一个新页面让用户自己去找，并没有解决他眼前的现象。用户明确要求：
**「所有的都要改，只要涉及到我说的这个不管是图片，视频也要改哈」**。
因此本轮把「编号显示」当作一个横切关注点统一处理，而不只是加一个入口。

### E.2 统一后的编号显示矩阵

| 位置 | 改动前 | 改动后 |
| --- | --- | --- |
| 视频跟踪页框上徽章 | `ID 4` / `ID 7`（短时 `trackId`） | `P001`（FeatureHub `person_id`），离场重入沿用 |
| 图片检测页框上徽章 | `1` / `2` / `3`（数组下标） | `P001` / `P002`，同一张脸换图仍是同一号 |
| 图片选中状态文字 | `Face 1 of 2 selected` | `P001 selected · 2 face(s) · 106 landmarks returned` |
| 人脸管理列表 | `ID · 1` | `P001`（`face_id_format = P%1$03d`） |
| 人脸识别命中详情 | `ID · 1 · cosine ...` | `P001 · cosine confidence ... · threshold ...` |
| 人脸管理搜索框 | 只能按姓名 / 数字 ID 搜 | 可直接搜 `P001` / `p001` / `1` |

关键在于**口径一致**：框上看到的 `P001`，去人物库里就是 `P001`，识别命中详情
也是 `P001`，不再出现「框上 P001、库里 ID · 1」的对不上号问题。

### E.3 支撑改动

- `FaceEngine.createFaceTrackingSession(..., boolean enableRecognition)`：给旧跟踪页
  一个能逐脸提特征的 Session，同时保留用户可调的 detect mode / input px / max faces /
  min face px。
- `FaceTrackingAnalyzer` 双模式：注入 `FaceRepository` 时启用
  `PersonIdResolver`（三区阈值、TTL、同帧防串号、连续 miss 才建档），
  通过 `TrackIdOverlayView.submitLabels()` 渲染；未注入时保持 legacy `ID n` 作为降级。
- `FaceImageProcessor.detectWithLandmarksAndFeatures()`：图片通路一次产出
  106 关键点 + 512 维特征 + 对齐 crop，`FaceDetectionActivity.resolveStableLabels()`
  逐脸查库 / 自动建档。
- `FaceImageOverlayView.setFaceLabels()`、`FaceRepository.query()` 支持 `P001` 检索、
  中英文 `strings.xml` 统一。
- `rebuildSession()` 修复：改 Session 参数后不再退回 1/2/3 标签。
- 补齐此前缺失的 `detection_face_selected_stable` 字符串（否则编译直接失败）。

### E.4 新增设备端验证：`UnifiedStableIdTest`（4/4 通过）

`androidTest/.../view/UnifiedStableIdTest.java`，在 `RealCue_API35` 上跑真实
native SDK + 真实 FeatureHub，隔离存储，BUILD SUCCESSFUL in 52s，
XML `tests="4" failures="0" errors="0" skipped="0"`。

| 用例 | 结论 |
| --- | --- |
| `stillImageDetectionProducesFeaturesCropsAnd106Landmarks` | `STILL_DETECT faces=1 featureLen=512 crop=112x112 landmarks=106` |
| `detectionOnlySessionHasNoFeatureButRecognitionSessionDoes` | `SESSION_SWITCH legacy_feature=null stable_featureLen=512`，证明切 Session 是必要的 |
| `leavingAndReturningReusesSameGalleryLabel` | 首次建档 `P001`；对 JPEG q70 + 亮度 +18 的「重新入场」图重检，`confidence=0.9849746 threshold=0.48` → `REENTRY first=P001 second=P001 gallery=1` |
| `gallerySearchAcceptsPersistentP001Label` | `QUERY_LABEL P001=1 p001=1 numeric=1 P999=0` |

**这条 `0.9849746` 比 B3 的 `conf=1.0` 更有意义**：它不是同一特征自比对，
而是「同一个人、换了一次成像条件」后重新提特征再查库得到的分数，且
`gallery=1` 证明没有因为重入而重复建档。这正是用户截图里「同一张脸却依次编号」
的反面结果。

### E.5 准确性边界（不要夸大）

1. 仍是**同人 intra-person 稳定性**，只有一张 `kun.jpg`；异人 inter-person 分数分布、
   真机两人同框、跨姿态 / 跨光照、帧率与最终准确率都没测。
2. `T_match=0.48` / `T_new=0.36` 依旧是 B0 占位值，两个真人样本未采集，
   `doNotUseForThreshold` 警告仍然成立。
3. 灰色区依旧被 `FaceRepository.open()` 的 FeatureHub 搜索阈值提前过滤，
   生产线实际只有「高分命中 / 无候选」两种状态。
4. **视频跟踪页的稳定标签没有设备端直接断言**：它需要 CameraX 实时帧与 GL overlay，
   无法在 instrumented test 里直接实例化；其决策内核由 15 项 JVM 单测 +
   B3 三项设备端重入测试覆盖，recognition Session 分支由 `SESSION_SWITCH` 类比验证。
   真机视频效果仍需用户实机确认。
5. **必须重装 APK**：统一编号那一轮 APK 构建为 117,605,130 B；
   **后续又做了一次 `--rerun-tasks` 强制全量重编译，最终交付包为 116,455,212 B
   （2026-11-16 14:14:54）**，详见附录 F。手机上的旧包不会自动更新。

### E.6 验证闸门汇总

| 闸门 | 结果 |
| --- | --- |
| `compileDebugJavaWithJavac` | BUILD SUCCESSFUL in 33s |
| 全量 JVM 单测 | 68 tests / 0 failures / 0 errors / 1 skipped |
| `assembleDebug` + `assembleDebugAndroidTest` | 成功 |
| `connectedDebugAndroidTest` (`UnifiedStableIdTest`) | 4/4 通过，52s |
| `aapt2 dump badging` / `dump resources` | targetSdk 35；新字符串中英实际值 `P%1$03d` 已进包 |
| `apksigner verify --verbose` | `Verifies`，v2 scheme true |
| APK ABI | arm64-v8a / armeabi-v7a / x86_64，9 dex |

日志：`runtime/logs/unified-apk-verify.txt`、
`runtime/logs/model-gradle-20261115-222838.log`、
`runtime/logs/model-logcat-20261115-222838.txt`。
可视化报告：`Unified-StableId-Report.html`。

### E.7 下一步（取代 D.7）

把新的 `app-debug.apk` 重新上传到 Xiaomi 14 Pro 安装，**直接打开原来那个
「人脸跟踪」页**（不用再找新卡片）：图片 tab 选一张脸看 `P001`，
视频 tab 做「进场 → 完全离场 → 再进场」看是否仍是 `P001`，
再到「人脸管理」确认能看到并搜到 `P001`。之后才是 B0 真机双人阈值标定。

### E.8 用户描述的完整出镜流程已逐步验证（2026-11-16）

用户把目标讲成一条时间序列：「我出镜为 1 号 → 另一个人 2 号 → 我又出镜还是 1 号 →
再来个新人拿新号」。新增 `androidTest/.../view/PersonFlowAbacTest.java`，
2 项设备端用例在真实 FeatureHub 上跑通（`BUILD SUCCESSFUL in 47s`，
XML `tests=2 failures=0 errors=0 skipped=0`）：

| 步骤 | logcat |
| --- | --- |
| A 首次 | `STEP1 A_FIRST -> P001 gallery=1` |
| B 首次 | `STEP2 B_FIRST -> P002 gallery=2` |
| A/B 同帧 | `STEP3 a=MATCHED(1) b=MATCHED(2) secondA=SKIPPED` |
| 全员离场 | `STEP4 LEAVE_ALL bindings=0 gallery=2` |
| A 重入 | `STEP5 A_REENTRY MATCHED(person=1, conf=0.9849746) expected=P001` |
| B 重入 | `STEP6 B_REENTRY MATCHED(person=2, conf=1.0)` |
| 新人 C | `STEP7 C_FIRST -> P003 gallery=3` |
| 收口 | `FINAL cast=A:P001 B:P002 C:P003 enrollCalls=3 RESULT=PASS` |

第二用例 `reentryDoesNotConsumeANewNumber` 验证 A 重入后第二个人仍拿 `P002`
（`NO_SKIP B_FIRST -> P002`），即重入不烧号。

「新人拿下一个号」按**下一个未占用的自增主键**实现：1、2 已占用时新人得 `P003`，
不复用 2 号。A 与 A 重入是真实特征（重入从 JPEG q70 + 亮度 +18 的图重新提特征，
cosine `0.9849746`）；B/C 是互异合成向量，仅验证「不同人 → 新号」分支，
**不是真人异人分数**。仍只有 `kun.jpg` 一张真人样本，异人分数分布 / 阈值标定 /
真机实时帧 / 多人同框未测。本轮未改 `main/`，**APK 未变**。
报告见 `PersonFlow-ABAC-Report.html`，日志
`runtime/logs/model-logcat-20261116-102916.txt`。

---

## 附录 F：强制全量重编译复核（回答「你重新编译过没？」）

### F.1 质疑点

A→B→A→C 流程验证那一轮**只新增了 `androidTest`**，设备端测试日志中
`compileDebugJavaWithJavac UP-TO-DATE`、`packageDebug UP-TO-DATE`，
即**没有重新出 APK**。虽然统一编号那一轮确实出过包（APK `2026-11-15 22:25:08`
晚于 `main/` 最后改动 `22:22:21`），但为彻底排除构建缓存造成的误判，
执行一次强制全量重编译。

### F.2 命令与结果

```bash
python runtime/run_gradle.py --rerun-tasks \
    :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

| 项 | 结果 |
| --- | --- |
| Gradle | `BUILD SUCCESSFUL in 29s`；**`65 actionable tasks: 65 executed`**（全部 executed，无 `UP-TO-DATE` 复用） |
| 三个编译 task | `compileDebugJavaWithJavac` / `compileDebugAndroidTestJavaWithJavac` / `compileDebugUnitTestJavaWithJavac` 均实际执行 |
| JVM 单测 | `TOTAL files=11 tests=68 failures=0 errors=0 skipped=1` |
| **新 APK** | **`116,455,212 B`，mtime `2026-11-16 14:14:54`**（替代旧版 117,605,130 B） |
| 新 androidTest APK | `672,252 B`，mtime `14:14:56` |
| 签名 | `Verifies`；`Verified using v2 scheme: true`；1 signer |
| 结构 | 935 entries、9 dex、`arm64-v8a` / `armeabi-v7a` / `x86_64`；Pikachu 61,644,800 B、Megatron 17,203,200 B、`kun.jpg` 217,529 B 齐全 |
| DEX 符号 | `PersonIdResolver`、`formatLabel`、`resolveStableLabels`、`detectWithLandmarksAndFeatures`、`submitLabels`、`setFaceLabels`、`createFaceTrackingSession`、`RealCueTracking`、`RealCueAutoId`、`P%03d` 全部在包 |
| **新包重装后复测** | `UnifiedStableIdTest` **4/4 通过**，`REENTRY first=P001 second=P001 gallery=1`，`BUILD SUCCESSFUL in 2m 14s` |

### F.3 证据文件

- `runtime/logs/gradle---rerun-tasks-20261116-141428.txt` — 全量重编译原始日志
- `runtime/logs/rebuild-verify-20261116.txt` — badging / resources 实际值 / apksigner / ZIP 结构 / DEX 符号
- `runtime/logs/model-gradle-20261116-141608.log` — 用新包重跑设备端测试
- `runtime/logs/model-logcat-20261116-141608.txt` — 对应 logcat

### F.4 结论与边界

**已重新编译，且是强制全量重编译，不是缓存。** 交付以
`app/build/outputs/apk/debug/app-debug.apk`（116,455,212 B / 14:14:54）为准，
真机必须重新上传安装。APK 体积变小 1,149,918 B 属重新打包的压缩差异，
关键件已逐项核对无缺失。设备端复测日志中再次出现 `packageDebug UP-TO-DATE`
是正常的——它复用的是刚由 `--rerun-tasks` 生成的新包。

**这不改变任何未收口边界**：阈值仍是 B0 占位值、灰色区仍被 FeatureHub 阈值过滤、
仍只有 `kun.jpg` 一张真人样本、真机相机实时帧 / 多人同框 / 帧率 / 最终准确率未测。
重编译只证明「当前源码确实被编进了这个包」，不等于真机效果已验证。

