# 移动端实时人脸检测与人物自动编号调研

调研日期 2026-11-14。按单台手机、实时摄像头、本地人物库评估 Android 和 iOS，另列可在电脑上验证行为的项目。结论来自官方文档、仓库、发布页和关键源码。手机帧率和身份匹配表现有待真机验证。

## 结论

**实时画人脸框、提取人脸特征、检索本地人物库，都有成熟组件。** 在此次核查的项目中，尚未找到能确认同时满足手机端本地运行、多人实时画框、首次自动编号、离场重现沿用编号的可直接安装开源成品。

- **想先在 Android 手机上体验画框和本地人物库**，可以下载 OnDevice-Face-Recognition-Android 的 APK。现有版本需要手动录入，自动建档需要改代码。[APK 发布页](https://github.com/shubham0204/OnDevice-Face-Recognition-Android/releases/tag/v2)
- **要做科研原型，想少接几套组件**，优先看 InspireFace。它把检测、跟踪、特征提取和可持久化检索放在同一个 SDK 里。当前官方 Android 摄像头识别示例只处理第一张脸，应用侧需要补齐逐脸匹配、自动编号和叠框。官方模型包限学术用途。[官方 Demo](https://doc.inspireface.online/introduction.html#try-the-android-example-app) · [识别示例源码](https://github.com/HyperInspire/InspireFace/blob/master/android/InspireFaceExample/app/src/main/java/com/example/inspireface_example/view/RecognitionFaceAnalyzer.java#L74-L132) · [模型许可](https://github.com/HyperInspire/InspireFace#license)
- **想直接参考手机上的自动建档代码**，Flutter 的 `flutter_face_liveness` 已有匹配旧 ID、未命中生成新 ID 并写入本地的模式。原有摄像头流程面向单人活体检测，逐脸实时画框仍要另外接入。[自动建档源码](https://github.com/sanjaysharmajw/flutter_face_liveness/blob/main/lib/src/identity/face_identity_service.dart#L319-L398) · [单脸摄像头逻辑](https://github.com/sanjaysharmajw/flutter_face_liveness/blob/main/lib/src/liveness_controller.dart#L460-L475)
- **要做可控的开源基线**，优先用 OpenCV YuNet + SFace。两者有官方预训练权重和匹配示例，可直接接入检测、对齐和特征提取。短时跟踪、人物库及自动编号由应用实现。[OpenCV 官方示例](https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html) · [YuNet 权重和许可](https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/README.md) · [SFace 权重和许可](https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/README.md)

## 需求对应的算法

人物库从空库开始。A 第一次进入画面时自动分配 `P001`，B 第一次进入时分配 `P002`。A 完全离开画面、原轨迹结束后再回来，系统用新采样的人脸特征去库里查，匹配成功就显示 `P001`。整个过程可以只用数字编号，无需录入姓名。

画框由人脸检测负责。连续视频帧之间由跟踪器维持短时 `track_id`，保证框和标签能跟着人移动。跨轨迹复用编号靠人物库中的 `person_id`，它绑定的是人脸特征向量。人物库写到本地磁盘后，同一台手机重新打开应用也可以继续检索旧编号。[InspireFace 跟踪说明](https://doc.inspireface.online/guides/tracking.html) · [FeatureHub 持久化说明](https://doc.inspireface.online/guides/recognition.html#persistence-and-model-changes)

工程和论文检索可用 `face re-identification`、`online face clustering`、`open-set face identification with automatic enrollment`。和这个需求最贴近的是在线人脸聚类，即每个新出现的轨迹根据特征加入已有类别，或创建新类别。开放集识别提供比较和拒识能力，自动建档把确定为新人的样本加入人物库。[ICIP 在线人脸聚类论文](https://wrap.warwick.ac.uk/id/eprint/109574/) · [在线人脸聚类与持续建库论文](https://arxiv.org/abs/2103.17272)

## 可直接使用的移动端项目与组件

| 方案 | 现成能力 | 距离目标还差什么 |
| --- | --- | --- |
| **InspireFace** | Android AAR、iOS 接口和 Android Demo。SDK 可返回多张脸的人脸框和轨迹 ID，提供特征提取、1:N 检索、插入、更新和磁盘持久化。 | 空库和未命中时增加自动编号与入库，摄像头识别从单脸改成逐脸处理。项目源码可查看，独立仓库未查到标准代码许可证，官方包的权重仅限学术用途。[SDK 下载](https://doc.inspireface.online/build/) · [FeatureHub](https://doc.inspireface.online/guides/recognition.html) · [许可说明](https://github.com/HyperInspire/InspireFace#license) |
| **OnDevice-Face-Recognition-Android** | 原生 Android 示例，已有 APK。按 v2 源码，CameraX 预览、ML Kit 检测、ExecuTorch 运行 FaceNet，ObjectBox 保存向量并检索，逐脸绘制标签。 | 当前通过界面导入照片和填写标签，识别失败显示 `Not recognized`。可在已有检索处增加自动建档和跟踪状态。应用源码 Apache-2.0，识别权重发布页标记 MIT，来源文档有限。[项目](https://github.com/shubham0204/OnDevice-Face-Recognition-Android) · [v2 推理源码](https://github.com/shubham0204/OnDevice-Face-Recognition-Android/blob/v2/app/src/main/java/com/ml/shubham0204/facenet_android/domain/embeddings/FaceNet.kt#L19-L46) · [v2 逐脸匹配](https://github.com/shubham0204/OnDevice-Face-Recognition-Android/blob/v2/app/src/main/java/com/ml/shubham0204/facenet_android/domain/ImageVectorUseCase.kt#L58-L100) · [模型发布页](https://huggingface.co/shubhxm0204/facenet-executorch) |
| **OpenCV YuNet + SFace** | YuNet 输出框和五点人脸关键点，SFace 支持对齐、提取向量和余弦比对。Android 有官方 SDK、摄像头检测示例和 Java 接口，iOS 可用官方 OpenCV framework 接入 C++ 实现。 | 补摄像头叠框、短时跟踪和人物库。OpenCV Zoo 对 YuNet 和 SFace 模型目录分别声明 MIT 和 Apache-2.0。SFace 指定权重的训练数据来源仍有公开追问，若要商用需单独核实。[Android 官方示例](https://opencv.org/opencv4android-samples/) · [iOS 发布包](https://opencv.org/releases/) · [模型教程](https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html) · [训练数据问题](https://github.com/opencv/opencv_zoo/issues/313) |
| **MediaPipe Face Detector / BlazeFace** | Google 提供 Android、iOS 和 Web 的视频流接口，直接输出人脸框和关键点。近距离、前置摄像头场景可用 Short-Range 模型先验证画框效果。 | 输出包含框和关键点，长期身份要增加特征模型、检索和人物库。BlazeFace 给的是嘴部中心，SFace 默认对齐需要左右嘴角，混搭时要单独处理对齐。[官方检测文档](https://developers.google.com/edge/mediapipe/solutions/vision/face_detector) · [Short-Range 模型卡](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20%28Short%20Range%29.pdf) · [SFace 对齐源码](https://github.com/opencv/opencv/blob/4.x/modules/objdetect/src/face_recognize.cpp#L38-L75) |
| **face_detection_tflite** | 如果项目使用 Flutter，这个库支持 Android 和 iOS，可离线返回人脸框、短时跟踪 ID，并提取 192 维特征。示例提供摄像头接入。 | 人物库、首次自动编号和离场后的身份恢复需集成。包源码 Apache-2.0，落地前核对实际用到的模型权重。[库与样例](https://github.com/hugocornellier/face_detection_tflite) · [人脸特征接口](https://github.com/hugocornellier/face_detection_tflite#face-recognition-embeddings) |
| **flutter_face_liveness** | Android / iOS Flutter 库。其 `FaceIdMode.auto` 可在本地按余弦相似度查库、复用 `FID-…` 或创建并持久化一个新 ID。 | 自带流程选取第一张脸，在单人检测会话完成时返回结果。可参考身份服务的接口与控制流，连续画框及多人 ID 分配需另接。源码 MIT，随包模型权重许可需单独核对。[项目和许可](https://github.com/sanjaysharmajw/flutter_face_liveness) · [本地保存实现](https://github.com/sanjaysharmajw/flutter_face_liveness/blob/main/lib/src/identity/face_identity_service.dart#L450-L490) |

OpenCV 4.x 路线可先固定 `face_detection_yunet_2023mar.onnx` 和 `face_recognition_sface_2021dec.onnx`。SFace 标准权重约 36.9 MiB，Zoo 也有体积约 9.4 MiB 的 INT8 版。量化版是否更快、是否影响重现匹配，需要用同一台手机和同一组视频测量。[YuNet 版本说明](https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/README.md) · [SFace 模型目录](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface) · [官方基准测试](https://github.com/opencv/opencv_zoo/tree/main/benchmark)

## 已实现自动编号的电脑端参考

| 项目 | 核查到的行为 | 运行和复用边界 |
| --- | --- | --- |
| **iBUG face_reid** | Python 摄像头示例为多人自动显示 `Face #N`。其重识别逻辑利用短时轨迹收集人脸特征，重新出现时与内存库匹配。 | MIT 代码，人物库有容量限制，重启后重新编号。旧版依赖与模型需另配，可查看带 dlib 路径的 `face_reidentifier_test2.py`，其命令行用法为 `python face_reidentifier_test2.py -i 0`。[仓库](https://github.com/ibug-group/face_reid) · [匹配源码](https://github.com/ibug-group/face_reid/blob/85355ab2e19276d23557402f22f44e66527d9448/ibug/face_reid/face_reidentifier.py#L338-L415) · [摄像头脚本](https://github.com/ibug-group/face_reid/blob/85355ab2e19276d23557402f22f44e66527d9448/face_reidentifier_test2.py) |
| **Samaritan** | Python/OpenCV + DeepFace。陌生人会匹配已有在线类别或新建 `Unknown_N`，实时画框显示编号，启动时从目录和缓存恢复旧类别。 | 可作电脑侧行为参考。仓库未找到明确许可。它会保存人脸裁剪图，另行运行离线 DBSCAN 重聚类可能重排编号。[仓库](https://github.com/Thimira/Samaritan) · [在线分配源码](https://github.com/Thimira/Samaritan/blob/02a3d8b509d0dde47c3259aea86babdc6b457499/samaritan.py#L370-L390) · [启动恢复源码](https://github.com/Thimira/Samaritan/blob/02a3d8b509d0dde47c3259aea86babdc6b457499/samaritan.py#L280-L347) · [离线重聚类说明](https://github.com/Thimira/Samaritan/blob/main/docs/tutorial/07-offline-clustering-and-pipeline.md) |
| **FaceVision** | YuNet + SFace + SQLite。打开 Auto-capture 后，清晰陌生脸可自动保存为 `0001`、`0002` 等编号，后续与同一个库匹配。 | iPhone 可作为相机，识别运行在电脑端的 Python 服务。源码还包含需配置密钥才启用的在线查找。仓库未找到明确许可。[仓库与运行方式](https://github.com/Forrest404/vision) · [自动建档源码](https://github.com/Forrest404/vision/blob/fa74d7680f3d3a7532de374eaf8e61447116f882/pipeline.py#L354-L444) · [在线查找源码](https://github.com/Forrest404/vision/blob/fa74d7680f3d3a7532de374eaf8e61447116f882/web_search.py#L1-L24) |

## 手机原型怎么验证

1. **先画框，再确认人物 ID。** 摄像头预览独立渲染，检测和短时跟踪持续更新框。新轨迹先出现框，取得清晰且一致的样本后再显示长期编号。已有轨迹沿用已确认的标签，特征提取优先安排在新轨迹、重现或需要重新核对的时刻。
2. **每次开新号前先查库。** 明确命中就沿用旧号，清晰样本多次确认无匹配就分配新号并写库。分数卡在边界、脸太小、画面模糊时继续采样。多人同框时对当前匹配做一对一检查，降低两个人共用同一个编号的概率。
3. **用真实重入测试人物库。** 从空库开始，让 A 和 B 分别入镜并同框交叉。A 离开到原轨迹失效，再从另一侧回到画面。然后让 C 首次入镜，并在重启应用后再让 A 回来。记录旧号恢复率、同一人被重复编号的比例、不同人共用编号的比例，同时记录首次出框和显示身份各自的延迟。

Google 公布的 Pixel 6 BlazeFace Short-Range 检测任务平均延迟为 CPU 2.94 ms、GPU 7.41 ms，测量范围是 Face Detector 任务。整机实时性还取决于相机输入、坐标转换、同框人数、特征提取、查库与渲染，部署后应在目标手机上分别测量出框和身份确认延迟。模型示例中的相似度阈值也应以目标手机的同人、异人样本校准。[Google 性能数据](https://developers.google.com/edge/mediapipe/solutions/vision/face_detector#task-benchmarks) · [OpenCV 不同数据集的匹配阈值](https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html)
