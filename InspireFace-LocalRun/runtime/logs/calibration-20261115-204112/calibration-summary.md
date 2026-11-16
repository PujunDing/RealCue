# B0 阈值标定结果

> **此结果不可用于设定阈值**：这是合成演练，A/ 与 B/ 是同一张内置人脸。请按下面的采集说明放入两个真人样本后重跑。
运行时间 20261115-204112 · 数据目录 `D:\paper\RealCue\InspireFace-LocalRun\runtime\logs\calibration-20261115-204112`

| 项目 | 值 |
| --- | --- |
| 模式 | `synthetic_dry_run` |
| 模型 | `Pikachu` |
| 官方阈值 | 0.4800 |
| 特征维度 | 512 |
| 同人样本对 / 异人样本对 | 6 / 9 |
| 同人最低分 sameMin | 0.9523 |
| 异人最高分 diffMax | 0.9910 |
| 分布可分 separable | False |
| 间隔 margin | -0.0387 |
| **建议 T_match** | **0.9924** |
| **建议 T_new** | **0.8724** |
| T_match 下同人召回 | 0.1667 |
| T_match 下异人误接受 | 0.0000 |
| 官方阈值误接受风险 | True |
| 官方阈值漏识别风险 | False |

## 警告

SYNTHETIC DRY RUN - both folders contain the SAME bundled face. These numbers only prove the pipeline works. Push two real people into A/ and B/ before using any threshold.

## 文件

- `threshold-result.json` 完整统计
- `threshold-pairs.csv` 每一对样本的分数明细
- `../calibration-logcat-20261115-204112.txt` 设备端日志

## 采集说明

1. 在 `D:\paper\RealCue\InspireFace-LocalRun\runtime\chain_samples` 下建 `A/` 和 `B/` 两个目录。
2. `A/` 放同一个人的至少 2 张照片，`B/` 放另一个人的至少 2 张照片。
   用最终要部署的那台手机拍，覆盖不同角度、距离、光照、表情，每张只含一张正脸。
3. 重跑：`python runtime/calibrate_threshold.py D:\paper\RealCue\InspireFace-LocalRun\runtime\chain_samples`
