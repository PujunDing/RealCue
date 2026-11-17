# 云端大模型指导手机端交互：可行性与架构设计调研

> 状态：**只做调研与设计，未写任何业务代码，未改 `app/src/`**
> 日期：2026-11-17
> 适用仓库：`PujunDing/RealCue`
> 前置实测：`cloud-model-probe`（提交 `bd5c8ad`）

---

## 0. 一句话结论

**可行，但分工必须重划**：云端 `qwen3-vl-4b-thinking` 实测**不接受音频输入**（`input_modalities = text / image / video`，`/v1/audio/transcriptions` 返回 501），所以你要求的「声音同步抽取」不能指望云端代劳，**必须在手机端完成**：录音 → VAD 切分 → 语音转文字 → 说话人归属 → 与 `P001` 人物编号对齐 → 只把结构化文本事件发给云端，由云端做理解与指导。

换句话说：**端侧负责「看见谁、听见谁说了什么」，云端负责「接下来该怎么办」**。

---

## 1. 现状盘点：手里已有什么、缺什么

### 1.1 已具备（可以直接复用）

| 能力 | 位置 / 证据 |
|---|---|
| 多人脸检测 + 跟踪 + 106 点关键点 | `view/FaceTrackingAnalyzer.java`、`face/FaceImageProcessor.java` |
| 512 维人脸特征、余弦相似度检索 | `face/FaceRepository.java`（FeatureHub，`PK_MANUAL_INPUT`、持久化 DB、Top1 搜索） |
| 长期人物编号 `P001 / P002 / ...` | `face/PersonIdResolver.java`（三区阈值 0.48/0.36、同帧互斥、TTL、离场重入仍命中） |
| 图片 / 视频两套页面都已统一长期编号 | `FaceDetectionActivity.java`、两个 Overlay；设备端测试 `UnifiedStableIdTest`、`PersonFlowAbacTest` |
| 单线程 Session 约束已明确 | `view/FaceEngine.java`、`UprightFaceCameraAnalyzer`、`HUB_LOCK` |
| 云侧 HTTP 客户端范式已跑通 | `plus/CapturePacket.java` + `plus/PlusLivenessClient.java`（OkHttp  multipart 上传 + 回调解析），证明「手机 → 云端」链路可用 |
| 网络权限 + OkHttp 依赖 | `AndroidManifest.xml` 有 `INTERNET`；`app/build.gradle` 有 `okhttp3:4.12.0` |
| 云端推理已验证 | `192.168.32.6:18090/v1` 的 `qwen3-vl-4b-thinking`，`/v1/chat/completions` 实测能返回非空中文 |
| 接口约定文档 | `cloud-model-probe/README.md` §9（端云契约）、§10（端侧未实现） |

### 1.2 完全缺失（要从零加）

| 缺口 | 说明 |
|---|---|
| `RECORD_AUDIO` 权限 | Manifest 只有 `CAMERA`、`INTERNET` |
| 一切音频代码 | `AudioRecord` / `MediaRecorder` / VAD / ASR / 声纹 / TTS：**完全没有** |
| 「某时刻谁在场」事件流 | 现在只有 `FaceRecord.updatedAt` 和内存 TTL 绑定，没有可回放的时间线 |
| 端云交互代码 | 没有任何调用大模型的 Java/Kotlin 代码 |
| 说话人库与 `P0xx ↔ S0xx` 绑定 | 不存在 |
| 时间戳对齐验证 | 有 `timestampNs` 字段，但没验证过它能否和音频时钟对齐 |

---

## 2. 云侧能力实测：决定架构的硬约束

### 2.1 模型元数据（本轮实测 `/v1/models`）

```json
"architecture": {
  "input_modalities":  ["text", "image", "video"],
  "output_modalities": ["text"]
},
"capabilities": ["completion", "multimodal"],
"meta": {
  "family": "qwen3-vl", "parameter_size": "4B",
  "quantization_level": "Q8_0", "n_ctx": 32768,
  "size": 4274448384
}
```

**关键读法**：有 image / video，**没有 audio**。所以：

- ✅ 可以把画面帧（或人脸 crop）发给它看；
- ✅ 可以把文字发给它理解；
- ❌ **不能直接把录音/音频发过去让它听**；
- ❌ `/v1/embeddings` 也未实现（501），想让云端算声纹向量也不行。

### 2.2 端点实测

| 端点 | GET | POST 空 JSON | 结论 |
|---|---|---|---|
| `/v1/chat/completions` | — | **400** | 存在，参数不合法时报错，正常 |
| `/v1/audio/transcriptions` | 404 | **501** | **未实现**，云端不能转写 |
| `/v1/audio/translations` | 404 | — | 未实现 |
| `/v1/embeddings` | 404 | **501** | 未实现 |
| `/v1/models/<id>` | 404 | — | 未实现（用列表端点取 `data[0].id`） |
| `/v1/files`、`/v1/completions` | 404 | — | 未实现 |

### 2.3 延迟实测（同一局域网）

| 阶段 | 实测 |
|---|---|
| ICMP ping | 4/4 通，丢包 0.0%，平均 14 ms |
| `GET /v1/models` | 200，约 58 ms |
| `POST` 首轮 `max_tokens=128` | 约 2.3 s，`finish_reason=length`，`content` 为空（thinking 假阴性） |
| `POST` 复测 `max_tokens=512` | 约 4.2 s，`finish_reason=stop`，返回非空中文 |

**设计含义**：单次云端往返是 **秒级**。因此架构必须是**事件驱动、低频请求**（一句话说完才问一次），绝不能每帧请求；「指导」也不能是硬实时的逐帧控制，而应是「下一句该怎么接 / 该看谁 / 该提示什么」这种对话级策略。

---

## 3. 需求澄清：「声音同步抽取」我先按这个理解

你原话是「手机端有一个轻量化的人脸检测以及人物库归类的东西，还必须要有一个声音同步抽取」。我按最贴合场景的方式理解为：

> 在摄像头识别 `P001/P002` 的同时，**同步采集麦克风音频**，把说的话转成带时间戳的文字，并判断「这句话是谁说的」，最终形成一条和人物编号对齐的「说话事件」，交给云端大模型作为上下文。

可能有歧义的地方，请你确认（见第 9 节）：

1. 是否要云端**听到原始声音**？（当前模型不支持，除非换服务端）
2. 是否要把说话人声纹和 `P001` 人脸**长期绑定**成同一个人？（P2 阶段做，见 6.3）
3. 云端返回的「指导」要不要手机**播报出来**（TTS），还是只显示在界面上？

---

## 4. 推荐架构：端侧感知 + 端侧关联 + 云端决策

```
┌─────────────────────── 手机端（本地，离线可跑）───────────────────────┐
│                                                                      │
│  [L0 感知]                                                           │
│   CameraX ──> FaceTrackingAnalyzer ──> FaceRepository ──> P001/P002  │
│      │              (单线程 Session)         (FeatureHub DB)          │
│      │  timestampNs                                                   │
│      │                                                               │
│   AudioRecord 16 kHz mono ──> Silero VAD ──> 语音段 [t0,t1]           │
│      │  elapsedRealtimeNanos                                          │
│      ▼                                                               │
│  [L1 关联]  TimeAligner（统一单调时钟）                                │
│      ├── 单人画面：语音段直接归属给画面里唯一的 P0xx                    │
│      ├── 多人画面：CAM++ 声纹 embedding + 聚类 → S0xx                  │
│      └── FaceVoiceBinder：S0xx ⇄ P0xx 绑定表（带置信度 / 未确认态）     │
│      ▼                                                               │
│   Utterance { person_id, start_ms, end_ms, text, asr_conf, spk_conf }  │
│                                                                      │
│  [L2 交互]  InteractionClient（OkHttp，复用 PLUS 范式）                │
│      ├── 组包：schema=realcue.interaction.v1 + 事件数组               │
│      ├── POST http://192.168.32.6:18090/v1/chat/completions           │
│      │        max_tokens=512, stream=false（thinking 假阴性保护）      │
│      └── 解析 choices[0].message.content → 白名单动作                  │
│                                                                      │
│  执行：高亮某人框 / 提示文本 / 系统 TTS 播报 / NOOP                    │
└──────────────────────────────────────────────────────────────────────┘
                                  │ 只上传「文字事件」，不上传音频与人脸特征
                                  ▼
                      ┌──────────────────────────┐
                      │ 云端 qwen3-vl-4b-thinking │
                      │  理解上下文 → 给指导策略   │
                      └──────────────────────────┘
```

### 4.1 三条设计原则

1. **端侧做识别与关联，云端做理解与决策**：人脸特征、声纹、原始音频都不出端；云端只看到 `P001 说：“你好”` 这类语义事件。
2. **事件驱动而非帧驱动**：VAD 检测到一句话结束才触发一次云端请求；同一句话去重，不重复提问。
3. **时间戳统一到单调时钟，但先验证再信任**（见 5.5，这是最大工程风险）。

---

## 5. 关键技术点与选型

### 5.1 音频采集

- `AudioRecord`，`AudioFormat.ENCODING_PCM_16BIT`、16 kHz、单声道，20 ms 一帧。
- 权限：`RECORD_AUDIO`；Android 10+ 后台录音需要前台服务，建议只在交互页前台采集。
- 若后续要 TTS 播报，优先 `AudioSource.VOICE_COMMUNICATION` 以启用回声消除（AEC），否则播报声会被自己录进去。
- 环形缓冲 + 背压策略：满了就丢最旧，绝不能阻塞人脸那个单线程 Session。

### 5.2 VAD（语音端点检测）

| 候选 | 体积 | 延迟 | 备注 |
|---|---|---|---|
| **Silero VAD（sherpa-onnx）** | **约 0.6 MB** | 约 5 ms | 推荐，成熟、Android arm64 现成 |
| WebRTC VAD | 极小 | 极低 | 更轻但只做能量/统计判断，无 ONNX 生态 |

输出语音段 `[startNs, endNs]`，静音段不进 ASR，省电省算力。

### 5.3 端侧 ASR（语音转文字）

| 模型 | 体积 | 特点 | 适配 |
|---|---|---|---|
| **SenseVoice Small int8** | **160–239 MB** | 中/英/日/韩/粤，带标点与 ITN，非流式约 110 ms/5 s 音频 | **推荐起步**，中文场景最合适 |
| Whisper tiny.int8 | 约 40 MB | 仅英文，最省空间 | 英文专用备选 |
| Paraformer-zh int8 | 约 30–60 MB | 仅中文 | 极简中文备选 |
| FunASR Nano / Qwen3-ASR 0.6B | 690 MB / 806 MB+ | 精度更高 | 体积太大，暂不考虑 |
| sherpa-onnx arm64 运行库 | onnxruntime 约 24.6 MB + JNI 约 4.5 MB | 随 APK | 可接受 |

**工程约定（沿用本项目既有经验）**：
- 模型**不进 APK**，首次使用时动态下载到 app 私有目录，带 SHA-256 校验；否则 APK 会从现在的 116 MB 涨到 300 MB+，也突破 GitHub 单文件 100 MB 限制。
- 下载与缓存目录沿用 D 盘策略（开发期），远端用户走应用内下载。
- 仓库现有 OkHttp 正好可承担下载 + 校验后的加载。

### 5.4 说话人区分（谁在说话）

分两级，先易后难：

- **A 级（MVP 必做）— 单人画面归属**：如果 VAD 语音段内画面只有一个人脸且 `person_id` 稳定，直接把这句话归属给该 `P0xx`。**最可靠、零额外模型**，覆盖大多数 1:1 交互场景。
- **B 级（P2）— 多人画面归属**：
  - `pyannote segmentation` 找说话人切换边界；
  - `3D-Speaker CAM++` 声纹模型（约 27 MB，192 维 embedding）或 ERes2Net（约 111 MB，质量更好）提声纹；
  - 层次聚类 / 余弦阈值聚成 `S001 / S002`；
  - 建立 `S0xx ⇄ P0xx` 绑定表：单人有声时互相「注册」，后续用声纹认人。
- **C 级（P3）— 视觉辅助归属**：用嘴唇运动 / 头部朝向 / 空间位置给归属打分，处理「两个人同时说话」。研究上可行，但工程量最大，**不建议一开始就做**。

**重要**：多人场景允许输出 `speaker: "UNKNOWN"`，**绝不强行绑定**——错误绑定比不绑定更糟。

### 5.5 时间同步：最大工程风险，必须先实测

这是全方案最容易翻车的地方，不能想当然：

- CameraX 的 `ImageInfo.getTimestamp()` 是纳秒、单调，官方只保证**同一相机实例**的 use case 之间可比。
- 它**能否与 `SystemClock.elapsedRealtimeNanos()`（音频时钟）对齐，取决于 `SENSOR_INFO_TIMESTAMP_SOURCE`**：
  - `REALTIME`：与 `elapsedRealtimeNanos` 同域，可比较；
  - `UNKNOWN`：起点未知，**不可**与音频时钟、墙钟或其他相机比较。
- `AudioRecord` 的时间戳来自音频时钟域，也要实测它和 `elapsedRealtimeNanos` 的关系。

**前置验证 V1（必须先做）**：在目标真机（小米 14 Pro）上读出 `SENSOR_INFO_TIMESTAMP_SOURCE`，并同时记录 camera timestamp、音频 timestamp、回调到达时间，跑 1 分钟统计偏差。
- 若是 `REALTIME`：按同一单调时钟对齐，目标归属窗口 **±100 ms**；
- 若是 `UNKNOWN`：退化为「用回调到达时间 + 实测固定偏移」并明确标注误差，或改用 Camera2 拿 `SENSOR_TIMESTAMP`；唇级同步（±20 ms）**暂不承诺**。

### 5.6 端 → 云事件协议（建议 schema）

```json
{
  "schema": "realcue.interaction.v1",
  "session_id": "8f3a...",
  "clock": { "domain": "elapsedRealtimeNanos", "verified": true, "offset_ms": 0 },
  "window": { "start_ms": 10240, "end_ms": 13860 },
  "persons": [
    { "person_id": "P001", "present_ms": [10240, 13860], "conf": 0.97 }
  ],
  "utterances": [
    {
      "speaker": "P001",
      "start_ms": 10510, "end_ms": 11220,
      "text": "你好，你是谁？",
      "asr_conf": 0.86, "spk_conf": 0.71
    }
  ],
  "request": "根据在场的人和对话内容，给出下一步交互建议。"
}
```

**不出端的数据**：原始 PCM、人脸 512 维特征、声纹 embedding、人脸图片（v1 不传；P3 若要让 VL 模型看图，需单独授权传压缩 crop）。

### 5.7 云 → 端响应与执行

- 请求：`POST /v1/chat/completions`，`stream=false`，`max_tokens=512`（这就是 thinking 模型不假阴性的下限）；
- 必须解析 `reasoning_content` / `finish_reason`，复用 `cloud-model-probe` 里的假阴性检测逻辑；
- 建议用 system prompt 约束云端返回结构化 JSON，端侧只接受**白名单动作**：

```json
{ "action": "ASK", "target": "P001", "content": "你刚才提到想了解这个，需要我展开吗？" }
```

| 动作 | 端侧行为 |
|---|---|
| `SPEAK` | 系统 `TextToSpeech` 播报内容 |
| `ASK` | 界面显示提示语 / 高亮目标人物框 |
| `NOOP` | 什么都不做 |
| 其他 | **一律拒绝**，不允许云端直接控制相机或人物库 |

---

## 6. 分阶段路线（每阶段都有可测验收点）

### P0 前置验证（不做产品功能，只验证假设）
- V1：真机时间戳源与偏差统计（5.5）；
- V2：云端能否稳定返回结构化 JSON（连跑 20 次，解析成功率）；
- V3：SenseVoice int8 在目标机上的加载时间、内存峰值、每 5 秒音频推理耗时。
- **验收**：三项都有实测数字；若 V1 为 `UNKNOWN` 且偏差过大，方案降级为「单人模式 + 宽松时间窗」。

### P1 MVP：单人 1:1 交互
- 加 `RECORD_AUDIO` + 前台采集 + Silero VAD + SenseVoice；
- 仅实现 A 级归属（画面单人直接归属）；
- 打通「一句话 → 云端 → 文本/TTS 指导」闭环。
- **验收**：A 出镜说话，云端回复内容里正确带上 `P001`；静音不触发请求；端到端延迟有实测值。

### P2：多人说话人区分
- 加 CAM++ 声纹 + 聚类，建立 `S0xx ⇄ P0xx` 绑定；
- 处理「先后说话」「两人同时说话（UNKNOWN）」。
- **验收**：A、B 交替说话，归属正确率有统计；同时说话不串号，或明确输出 UNKNOWN。

### P3：增强
- 唇动 / 朝向辅助归属（可选）；
- 授权后上传压缩人脸 crop，让 VL 模型「看着人」给指导；
- 流式 ASR 降低延迟。
- **验收**：按具体指标另定。

---

## 7. 风险与诚实边界

| 风险 | 说明 / 应对 |
|---|---|
| 云端不支持音频（已实测） | 必须端侧 ASR；换服务端才可能改变 |
| 秒级延迟 | 事件驱动、低频请求；不承诺硬实时 |
| 摄像头时间戳源 UNKNOWN | P0 先实测；不通过就降级，不做假精度 |
| 模型体积 160–239 MB | 动态下载 + SHA-256，不进 APK、不进 Git |
| 端侧算力叠加 | CameraX + InspireFace + ASR + VAD 同时跑，需实测帧率 / 发热 / 内存 |
| 隐私 | v1 只出文字事件；音频、人脸特征、声纹都不出端 |
| 错误绑定 | 允许 `UNKNOWN`，不强行把声音绑给某张脸 |
| 中文 ASR 质量 | 检索到的实践反馈是「日常可懂但离完美有距离」；LLM 语义理解能否接受需 P1 实测 |
| 静音 / 噪声场景 | 降级为纯人脸模式，不阻塞原有功能 |

---

## 8. 与现有资产的关系

- **不改**现有 `PersonIdResolver` / `FaceRepository` / 两个 Overlay 的长期编号逻辑；新模块是增量挂载。
- 新增内容建议放在新包 `com.example.inspireface_example.interaction/` 下：`AudioCapture`、`VadSegmenter`、`AsrEngine`、`SpeakerAttributor`、`TimeAligner`、`InteractionClient`、`GuidanceExecutor`。
- 协议定义与常量集中在一个 `InteractionContract.java`，方便和 `cloud-model-probe` 的约定对齐。
- 测试开发沿用现有风格：JVM 单测覆盖纯逻辑（时间窗归属、协议组包/解析、白名单动作），设备端测试覆盖真实音频 + 真实云端往返。

---

## 9. 需要你拍板的 4 件事

1. 「声音同步抽取」是否就是我理解的「录音 → 转文字 → 判断谁说的 → 与 P0xx 对齐」？
2. 是否要求云端**听原始声音**或**看人脸画面**？（当前模型只能看画面、不能听声音；传图需要额外授权）
3. 云端指导要不要 **TTS 播报**，还是只显示在界面上？
4. 长期遗留：新人拿 `P003` 还是复用空出来的 `P002`？（当前实现为自增不复用）

---

## 10. 参考依据

- 本轮实测：`/v1/models` 元数据 + 端点 404/501/400 矩阵 + 延迟数据；
- `cloud-model-probe/README.md` §9–§10（端云契约、端侧未实现）；
- `Research-PersonGallery-Matching.md`、`Unified-StableId-Report.html`（P001 长期编号与匹配原理）；
- sherpa-onnx 官方模型/APK 列表（`k2-fsa.github.io/sherpa/onnx`）、3D-Speaker（ModelScope）、Silero VAD、SenseVoice 相关资料；
- Android 官方文档 `ImageAnalysis.Analyzer` 时间戳说明与 `SENSOR_INFO_TIMESTAMP_SOURCE` 约束。
