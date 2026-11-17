# cloud-model-probe · 云侧模型在线与推理探针

> 这个目录是未来「云端大模型 ↔ 端侧 Android 人脸 ID 模块」交互的最小自检入口。
> 它回答的不是「网络通不通」，而是「**模型服务是不是真的在线、并且真的能推理出内容**」。

## 1. 为什么需要它

`ping` 只验证 ICMP 网络连通性：主机在线、路由可达，不代表 18090 端口有服务，
更不代表模型已加载、能生成回复。真正能证明「模型在线且可推理」的，是向
`/v1/chat/completions` 发一句「你好」并拿到非空的 `choices[0].message.content`。

本目录把这套验证固化成一个可复用脚本 `probe_model.py`：

| 步骤 | 端点 / 动作 | 能证明什么 | 不能证明什么 |
| --- | --- | --- | --- |
| 1 | `ping -n 4 <host>` | 局域网 ICMP 连通 | 端口开放、模型加载、推理能力 |
| 2 | `GET /v1/models` | OpenAI-compatible API 在线、模型已注册 | 实际生成能力 |
| 3 | `POST /v1/chat/completions` | **模型实际推理正常**（非空 `content`） | 长期稳定性、并发、精度 |

模型名不写死：脚本自动取 `data[0].id`（当前实测为 `qwen3-vl-4b-thinking`），
也可用 `--model` 覆盖。

## 2. 快速开始

只需要 Python 3.9+，**不依赖 curl / jq / requests**（仅标准库），clone 后可直接跑：

```bash
# 默认目标 http://192.168.32.6:18090，发送「你好」
python probe_model.py

# 换成别的服务
python probe_model.py --base-url http://10.0.0.5:8000
python probe_model.py --host 10.0.0.5 --port 8000

# 直接给足 token，跳过复测
python probe_model.py --max-tokens 512

# 复现「128 token 假阴性」而不自动复测
python probe_model.py --no-retry
```

Windows / Git Bash 下若 `python` 不在 PATH，用绝对路径调用解释器即可。
脚本默认**绕过系统代理**（等价于 `curl --noproxy '*'`），需要走代理时加 `--use-proxy`。

典型输出（节选，2026-11-17 实测）：

```text
[1/3] ping 192.168.32.6 : OK  loss=0.0%  avg=13.0ms
[2/3] GET /v1/models: HTTP 200  0.051s  models=qwen3-vl-4b-thinking
[3/3 primary] POST /v1/chat/completions : HTTP 200  2.295s  1343B
      model=qwen3-vl-4b-thinking  finish_reason=length  max_tokens=128
      content: <EMPTY>
      -> thinking 模型假阴性：finish_reason=length、content 为空、reasoning_content 占满 128 token
      -> 自动复测 max_tokens=512
[3/3 retry] POST /v1/chat/completions : HTTP 200  4.939s  1850B
      model=qwen3-vl-4b-thinking  finish_reason=stop  max_tokens=512
      content: 你好！... 有什么可以帮到您的吗？...
=== VERDICT: PASS_WITH_RETRY (exit 0) ===
```

## 3. 参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--host` | `192.168.32.6` | 目标主机 |
| `--port` | `18090` | 目标端口 |
| `--base-url` | 无 | 完整地址，设置后覆盖 `--host/--port`（也支持 https） |
| `--model` | 自动探测 | 覆盖 `GET /v1/models` 的 `data[0].id` |
| `--prompt` | `你好` | 发送的用户消息 |
| `--max-tokens` | `128` | 首次请求上限（沿用最初验证口径） |
| `--retry-max-tokens` | `512` | 识别到 thinking 假阴性后的复测上限 |
| `--no-retry` | 关 | 关闭自动复测，用于复现假阴性 |
| `--timeout` | `30` | 单次 HTTP 超时（秒） |
| `--ping-count` | `4` | ping 次数 |
| `--no-ping` | 关 | 跳过 ICMP 检查 |
| `--use-proxy` | 关 | 走系统代理；默认绕过 |
| `--out-dir` | 同级 `results/` | 结果输出目录 |

环境变量等价形式：`REALCUE_MODEL_HOST`、`REALCUE_MODEL_PORT`、
`REALCUE_MODEL_BASE_URL`、`REALCUE_MODEL_NAME`、`REALCUE_MODEL_PROMPT`、
`REALCUE_MODEL_MAX_TOKENS`、`REALCUE_MODEL_RETRY_MAX_TOKENS`、`REALCUE_MODEL_TIMEOUT`。
CLI 参数优先级高于环境变量。

### 退出码

| 码 | 含义 |
| --- | --- |
| `0` | `PASS` / `PASS_WITH_RETRY`：拿到非空 `content`，推理验证通过 |
| `1` | `FAIL`：`/v1/models` 不可达，或未取得非空 `content` 且非 thinking 截断 |
| `2` | `INCONCLUSIVE_THINKING_TRUNCATED`：被思维链占满、且未成功复测；**不能据此判定模型离线** |

## 4. thinking 模型的假阴性陷阱（重要）

`qwen3-vl-4b-thinking` 会先输出 `reasoning_content`（思维链），再输出
`message.content`。`max_tokens` 同时约束两者，给得太小就会出现：

| `max_tokens` | `finish_reason` | `content` | `completion_tokens` | 结论 |
| --- | --- | --- | --- | --- |
| `128` | `length` | **空** | `128` | 思维链把预算吃光 → 假阴性 |
| `512` | `stop` | 非空中文问候 | `268` | 正常推理 |

首轮手工 `curl` 与脚本复测都复现了这一形态（`created` / `usage` 见 `results/*.json`）。
因此：

- **不要用 `max_tokens=128` + 空 `content` 判定模型离线**；
- 脚本默认先按 128 发一次（保留原始口径），识别到
  `finish_reason == "length" && content 为空 && reasoning_content 非空` 后，
  自动用 512 复测，并在汇总里如实记录**两次**结果，不掩盖第一次；
- 未来端侧封装时，`max_tokens` 必须可配置，且要解析 `reasoning_content` 与
  `finish_reason=length` 做区分提示。

## 5. 等价的手工 curl

脚本内部用 `urllib` 实现了同样的事；若只想手工验证：

```bash
# 1) 只测连通性（注意：Windows ping 用 -n，不是 Unix 的 -c）
ping -n 4 192.168.32.6

# 2) 看模型名
curl --noproxy '*' -sS http://192.168.32.6:18090/v1/models

# 3) 真正测推理（把 <MODEL_ID> 换成上一步的 data[0].id）
curl --noproxy '*' -sS -X POST \
  http://192.168.32.6:18090/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"<MODEL_ID>","messages":[{"role":"user","content":"你好"}],"max_tokens":512,"stream":false}'
```

> Windows 下 `ping -c 4` 会报「选项 -c 需要具有管理权限」，这是参数语义差异，
> 不是网络问题；脚本已按平台自动选择 `-n` / `-c`。

## 6. 结果文件

每次运行以 `YYYYmmdd-HHMMSS` 为时间戳落盘到 `results/`：

| 文件 | 内容 |
| --- | --- |
| `probe-<stamp>.json` | 完整汇总：ping / models / 两次 chat / 诊断 / verdict / 结论 / 产出清单 |
| `chat-nihao-<stamp>.json` + `.meta.txt` | 首次请求的原始响应与 HTTP / 时延 / 大小元数据 |
| `chat-nihao-mt512-<stamp>.json` + `.meta.txt` | 自动复测的原始响应（仅触发复测时产生） |

当前目录保留两组证据：

- `160705` / `160739`：首轮**手工 curl** 的 128 与 512 响应（诊断 thinking 陷阱的原始依据）；
- `162932`：修复 ping 解码后**脚本正式跑**的完整产物（含 `probe-20261117-162932.json`）。

边界自测（`--no-retry`、直接 `--max-tokens 512`）的产物写到
`InspireFace-LocalRun/runtime/tmp/`（已被 `.gitignore` 排除），不污染本目录。

## 7. 自测矩阵

| 场景 | 命令 | 期望 verdict | 实测退出码 |
| --- | --- | --- | --- |
| 默认（128 → 512 自动复测） | `python probe_model.py` | `PASS_WITH_RETRY` | `0` |
| 关闭复测 | `python probe_model.py --no-retry` | `INCONCLUSIVE_THINKING_TRUNCATED` | `2` |
| 直接给足 token | `python probe_model.py --max-tokens 512` | `PASS`（不触发复测） | `0` |

## 8. 未来端云交互的接口约定

端侧 Android 模块后续接这个服务时，建议沿用探针已验证的契约：

1. **服务形态**：OpenAI-compatible，`GET /v1/models` + `POST /v1/chat/completions`。
2. **模型发现**：启动时取 `data[0].id`，不要把模型名写死在 APK 里。
3. **非流式**：`stream: false`；本探针只解析非流式响应，流式需另做解析。
4. **token 预算**：`max_tokens` 可配置，默认给足（≥512）；必须处理
   `reasoning_content` 与 `finish_reason=length`。
5. **成功判据**：以 `choices[0].message.content` 非空为准，HTTP 200 不等于推理成功。
6. **网络**：局域网直连，绕过系统代理；设置超时，失败要有明确的连通性 / API / 推理三段区分。
7. **地址配置**：主机与端口走配置或环境变量，不硬编码；桥接前先跑一次本探针。

## 9. 诚实边界

- 本探针只证明「单次请求能拿到非空回复」，**不测**并发、长时稳定性、吞吐、模型精度或多轮上下文。
- `ping` 失败不必然代表服务不可用（ICMP 可能被防火墙禁用），因此脚本不会因为 ping 失败而中断后续 HTTP 检查。
- 目前只验证了 `192.168.32.6:18090` 这一台局域网服务；换地址请用 `--base-url` / 环境变量。
- 本目录**不包含、也不修改** `app/src/` 下的任何 Android 业务源码；端侧接入尚未实现。

## 10. 关联文档

- 主仓库入口：[`../README.md`](../README.md)
- 完整探测报告：[`../Cloud-Model-Probe-Report.html`](../Cloud-Model-Probe-Report.html)
- 端侧主体工程：`../InspireFace-LocalRun/android-demo/`
