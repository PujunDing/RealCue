#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RealCue 云侧模型探针 (cloud-model-probe / probe_model.py)

用途
----
验证局域网内的 OpenAI-compatible 大模型服务是否「真的在线且能推理」，供未来
「云端大模型 <-> 端侧 Android 人脸 ID 模块」交互前做最小连通/推理自检。

它会依次做三件事（对应用户的原始验证口径）：
  1. ping 目标主机            -> 只验证 ICMP 网络连通性，**不能**证明模型在线
  2. GET  /v1/models          -> 自动取 data[0].id 作为模型名，不写死
  3. POST /v1/chat/completions-> 发送「你好」，解析 choices[0].message.content
                                 只有拿到非空 content 才算「实际推理正常」

重要：thinking 模型陷阱
-----------------------
qwen3-vl-4b-thinking 这类模型会先输出 reasoning_content（思维链）。若
max_tokens 给得太小（例如 128），token 可能全被思维链吃掉，表现为
finish_reason=length + content 为空 —— 这是**假阴性**，不是模型离线。
本脚本默认按用户口径先发 128，一旦识别到该形态，自动用更大的
--retry-max-tokens（默认 512）复测一次，并在汇总里如实记录两次结果。

设计约束
--------
* 仅用 Python 标准库，不依赖 curl / jq / requests，clone 后可直接跑。
* 路径全部由脚本自身位置推导，不含任何本机绝对路径。
* 默认绕过系统代理（等价于 curl --noproxy '*'），可用 --use-proxy 关闭。
* 结果结构化落盘到 results/，便于复核与 diff。

退出码：0 = 推理成功；1 = 明确失败；2 = 结论不确定（例如被 --no-retry 截断）。
"""

from __future__ import annotations

import argparse
import json
import locale
import os
import platform
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from urllib import error as urlerror
from urllib import parse as urlparse
from urllib import request as urlrequest

PROBE_VERSION = "1.0"
HERE = Path(__file__).resolve().parent
DEFAULT_RESULTS = HERE / "results"

DEFAULT_HOST = os.environ.get("REALCUE_MODEL_HOST", "192.168.32.6")
DEFAULT_PORT = int(os.environ.get("REALCUE_MODEL_PORT", "18090"))
DEFAULT_PROMPT = os.environ.get("REALCUE_MODEL_PROMPT", "你好")


def _force_utf8_stdout() -> None:
    """Windows 控制台默认可能是 cp936；尽力切到 UTF-8，失败也不影响主流程。"""
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


_force_utf8_stdout()


def log(msg: str = "") -> None:
    print(msg, flush=True)


# --------------------------------------------------------------------------- #
# HTTP
# --------------------------------------------------------------------------- #

def make_opener(use_proxy: bool):
    """默认构造一个不走任何代理的 opener，等价于 curl --noproxy '*'。"""
    if use_proxy:
        return urlrequest.build_opener()
    return urlrequest.build_opener(urlrequest.ProxyHandler({}))


def safe_json(body: bytes):
    try:
        return json.loads(body.decode("utf-8", "replace"))
    except (ValueError, TypeError):
        return None


def http_json(opener, method: str, url: str, payload=None, timeout: float = 30.0) -> dict:
    """发一个 JSON 请求，返回结构化结果；网络/HTTP 错误不抛出，转为 ok=False。"""
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urlrequest.Request(url, data=data, headers=headers, method=method)
    t0 = time.perf_counter()
    try:
        with opener.open(req, timeout=timeout) as resp:
            body = resp.read()
            elapsed = time.perf_counter() - t0
            return {
                "ok": True,
                "http_status": getattr(resp, "status", None) or resp.getcode(),
                "elapsed_s": round(elapsed, 6),
                "size_bytes": len(body),
                "json": safe_json(body),
                "raw": body,
            }
    except urlerror.HTTPError as exc:
        body = exc.read()
        elapsed = time.perf_counter() - t0
        return {
            "ok": False, "http_status": exc.code,
            "elapsed_s": round(elapsed, 6), "size_bytes": len(body),
            "json": safe_json(body), "raw": body,
            "error": "HTTPError: %s" % exc.reason,
        }
    except (urlerror.URLError, OSError) as exc:
        elapsed = time.perf_counter() - t0
        return {
            "ok": False, "http_status": None,
            "elapsed_s": round(elapsed, 6), "size_bytes": 0,
            "json": None, "raw": b"",
            "error": "%s: %s" % (type(exc).__name__, exc),
        }


# --------------------------------------------------------------------------- #
# Step 1: ping
# --------------------------------------------------------------------------- #

def _decode_console(data: bytes) -> str:
    """解码 ping 等 Windows 控制台程序的输出。

    ping.exe 在中文 Windows 上按系统 ANSI/OEM 代码页（cp936 / GBK）输出，
    而 Python 在 Git Bash 里报告的 locale 往往是 utf-8 —— 直接按 utf-8 解码
    会把「丢失 / 平均」变成乱码。优先用 mbcs，再逐级回退。
    """
    candidates = []
    if platform.system() == "Windows":
        candidates += ["mbcs", "gbk"]  # mbcs = 当前 ANSI 代码页，中文系统即 cp936
    candidates += [locale.getpreferredencoding(False) or "utf-8", "utf-8"]
    for enc in dict.fromkeys(candidates):
        try:
            return data.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return data.decode("utf-8", "replace")


def ping_host(host: str, count: int = 4) -> dict:
    """跨平台连通性检查。注意：ICMP 通 ≠ 模型 API 通，更不等于能推理。"""
    system = platform.system()
    if system == "Windows":
        # Windows ping 没有 -c；用户给的 `ping -c 4` 在本机会报
        # 「选项 -c 需要具有管理权限」。等价写法是 -n。
        cmd = ["ping", "-n", str(count), "-w", "2000", host]
        note = "Windows ping: 用 -n 代替 Unix -c（本机 -c 需管理员权限）"
    else:
        cmd = ["ping", "-c", str(count), "-W", "2", host]
        note = "Unix ping: -c %d" % count

    base = {
        "command": " ".join(cmd),
        "note": note,
        "semantics": "ICMP 仅验证网络连通性；不验证 API 端口、模型加载或推理",
    }
    try:
        proc = subprocess.run(
            cmd, capture_output=True, timeout=max(15, count * 3 + 5),
        )
        raw = _decode_console((proc.stdout or b"") + (proc.stderr or b""))
        base["ok"] = proc.returncode == 0
        base["returncode"] = proc.returncode
    except FileNotFoundError:
        base.update({"ok": False, "raw": "ping executable not found",
                     "loss_pct": None, "avg_ms": None})
        return base
    except subprocess.TimeoutExpired:
        base.update({"ok": False, "raw": "ping timed out",
                     "loss_pct": None, "avg_ms": None})
        return base

    base["raw"] = raw
    loss = re.search(r"(\d+(?:\.\d+)?)\s*%\s*(?:丢失|loss|packet loss)", raw, re.I)
    if not loss:
        # 控制台编码异常时中文标签可能乱码，但 "0%" 仍在；退化为取第一个百分比。
        loss = re.search(r"(\d+(?:\.\d+)?)\s*%", raw)

    avg_value = None
    avg = re.search(r"(?:平均|Average)\s*[=:]\s*(\d+(?:\.\d+)?)\s*ms", raw, re.I)
    if avg:
        avg_value = float(avg.group(1))
    else:
        # Linux: rtt min/avg/max/mdev = 1.0/2.0/3.0/0.5 ms
        rtt = re.search(r"=\s*[\d.]+/([\d.]+)/[\d.]+/[\d.]+\s*ms", raw)
        if rtt:
            avg_value = float(rtt.group(1))
        else:
            # Windows 统计行 "最短 = 5ms，最长 = 11ms，平均 = 7ms"：取最后一个 = Nms。
            equals = re.findall(r"=\s*(\d+(?:\.\d+)?)\s*ms", raw)
            if equals:
                avg_value = float(equals[-1])

    base["loss_pct"] = float(loss.group(1)) if loss else None
    base["avg_ms"] = avg_value
    base["sent"] = count
    return base


# --------------------------------------------------------------------------- #
# Step 2: /v1/models
# --------------------------------------------------------------------------- #

def fetch_models(opener, base_url: str, timeout: float) -> dict:
    result = http_json(opener, "GET", base_url + "/v1/models", timeout=timeout)
    ids = []
    doc = result.get("json")
    if isinstance(doc, dict):
        for item in doc.get("data") or []:
            if isinstance(item, dict) and item.get("id"):
                ids.append(item["id"])
    result["model_ids"] = ids
    result["auto_model"] = ids[0] if ids else None
    return result


# --------------------------------------------------------------------------- #
# Step 3: /v1/chat/completions
# --------------------------------------------------------------------------- #

def chat_once(opener, base_url: str, model: str, prompt: str,
              max_tokens: int, timeout: float) -> dict:
    """固定 stream=false（本探针只解析非流式响应）。"""
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "stream": False,
    }
    result = http_json(opener, "POST", base_url + "/v1/chat/completions",
                       payload, timeout)
    doc = result.get("json")
    doc = doc if isinstance(doc, dict) else {}
    try:
        choice = (doc.get("choices") or [{}])[0] or {}
    except (IndexError, TypeError):
        choice = {}
    message = choice.get("message") or {}

    result.update({
        "request_max_tokens": max_tokens,
        "request_stream": False,
        "model": doc.get("model") or model,
        "response_id": doc.get("id"),
        "system_fingerprint": doc.get("system_fingerprint"),
        "finish_reason": choice.get("finish_reason"),
        "content": message.get("content") or "",
        "reasoning_content": message.get("reasoning_content") or "",
        "usage": doc.get("usage") or {},
        "timings": doc.get("timings") or {},
    })
    result["thinking_model"] = bool(result["reasoning_content"].strip())
    result["truncated_by_thinking"] = (
        result["finish_reason"] == "length"
        and not result["content"].strip()
        and bool(result["reasoning_content"].strip())
    )
    result["inference_ok"] = (
        bool(result["content"].strip()) and result["finish_reason"] == "stop"
    )
    return result


# --------------------------------------------------------------------------- #
# 落盘
# --------------------------------------------------------------------------- #

def public_step(result: dict) -> dict:
    """去掉不可序列化的 raw bytes，把 json 改名为更直观的 raw_response。"""
    out = {k: v for k, v in result.items() if k != "raw"}
    if "json" in out:
        out["raw_response"] = out.pop("json")
    return out


def save_raw_response(results_dir: Path, result: dict, filename_stem: str) -> dict:
    """保存原始响应 JSON + meta.txt，命名延续上一轮手工 curl 的风格。"""
    json_path = results_dir / (filename_stem + ".json")
    meta_path = results_dir / (filename_stem + ".meta.txt")
    body = result.get("raw") or b""
    json_path.write_bytes(body)
    meta_lines = [
        "HTTP=%s TIME=%.6fs SIZE=%dB" % (
            result.get("http_status"), result.get("elapsed_s", 0.0),
            result.get("size_bytes", 0)),
        "MODEL=%s MAX_TOKENS=%s STREAM=false" % (
            result.get("model"), result.get("request_max_tokens")),
        "FINISH_REASON=%s CONTENT_EMPTY=%s REASONING_LEN=%d" % (
            result.get("finish_reason"),
            str(not bool((result.get("content") or "").strip())).lower(),
            len(result.get("reasoning_content") or "")),
    ]
    meta_path.write_text("\n".join(meta_lines) + "\n", encoding="utf-8")
    return {"json": json_path.name, "meta": meta_path.name}


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

EPILOG = """等价的手工 curl（本脚本内部用 Python 标准库实现了同样的事）:

  curl --noproxy '*' -sS http://192.168.32.6:18090/v1/models

  curl --noproxy '*' -sS -X POST \\
    http://192.168.32.6:18090/v1/chat/completions \\
    -H 'Content-Type: application/json' \\
    -d '{"model":"<data[0].id>","messages":[{"role":"user","content":"你好"}],"max_tokens":512,"stream":false}'

环境变量: REALCUE_MODEL_HOST / REALCUE_MODEL_PORT / REALCUE_MODEL_PROMPT /
          REALCUE_MODEL_BASE_URL / REALCUE_MODEL_NAME / REALCUE_MODEL_MAX_TOKENS /
          REALCUE_MODEL_RETRY_MAX_TOKENS / REALCUE_MODEL_TIMEOUT
"""


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        description="RealCue 云侧模型在线与推理探针（ping / models / chat 三段式）",
        epilog=EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--host", default=DEFAULT_HOST, help="目标主机（默认 %(default)s）")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT,
                    help="端口（默认 %(default)s）")
    ap.add_argument("--base-url",
                    default=os.environ.get("REALCUE_MODEL_BASE_URL"),
                    help="完整服务地址，设置后覆盖 --host/--port")
    ap.add_argument("--model", default=os.environ.get("REALCUE_MODEL_NAME"),
                    help="覆盖自动探测到的 data[0].id")
    ap.add_argument("--prompt", default=DEFAULT_PROMPT,
                    help="发送的用户消息（默认 %(default)s）")
    ap.add_argument("--max-tokens", type=int,
                    default=int(os.environ.get("REALCUE_MODEL_MAX_TOKENS", "128")),
                    help="首次请求的 max_tokens（默认 %(default)s，沿用用户原始口径）")
    ap.add_argument("--retry-max-tokens", type=int,
                    default=int(os.environ.get("REALCUE_MODEL_RETRY_MAX_TOKENS", "512")),
                    help="检测到 thinking 假阴性后的复测上限（默认 %(default)s）")
    ap.add_argument("--no-retry", action="store_true",
                    help="关闭自动复测（用于复现 128 token 假阴性）")
    ap.add_argument("--timeout", type=float,
                    default=float(os.environ.get("REALCUE_MODEL_TIMEOUT", "30")),
                    help="单次 HTTP 超时秒数（默认 %(default)s）")
    ap.add_argument("--ping-count", type=int, default=4, help="ping 次数（默认 %(default)s）")
    ap.add_argument("--no-ping", action="store_true", help="跳过 ping")
    ap.add_argument("--use-proxy", action="store_true",
                    help="走系统代理；默认绕过（等价 curl --noproxy '*'）")
    ap.add_argument("--out-dir", default=str(DEFAULT_RESULTS),
                    help="结果输出目录（默认脚本同级 results/）")
    return ap


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)

    base_url = args.base_url or ("http://%s:%d" % (args.host, args.port))
    base_url = base_url.rstrip("/")
    parsed = urlparse.urlparse(base_url)
    ping_target = parsed.hostname or args.host

    results_dir = Path(args.out_dir).expanduser().resolve()
    results_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    opener = make_opener(args.use_proxy)

    log("=== RealCue cloud model probe v%s ===" % PROBE_VERSION)
    log("target    : %s" % base_url)
    log("proxy     : %s" % ("system proxy" if args.use_proxy else "BYPASSED (--noproxy '*')"))
    log("prompt    : %s" % args.prompt)
    log("max_tokens: %d (retry %s)" % (
        args.max_tokens, "disabled" if args.no_retry else str(args.retry_max_tokens)))
    log("results   : %s" % results_dir)
    log("")

    # --- Step 1: ping ------------------------------------------------------ #
    if args.no_ping:
        ping = {"ok": None, "skipped": True,
                "semantics": "ICMP 仅验证网络连通性；本次被 --no-ping 跳过"}
        log("[1/3] ping: SKIPPED")
    else:
        ping = ping_host(ping_target, args.ping_count)
        loss_txt = ("%s%%" % ping["loss_pct"]) if ping.get("loss_pct") is not None else "n/a"
        avg_txt = ("%sms" % ping["avg_ms"]) if ping.get("avg_ms") is not None else "n/a"
        log("[1/3] ping %s : %s  loss=%s  avg=%s" % (
            ping_target, "OK" if ping["ok"] else "FAIL", loss_txt, avg_txt))
        log("      %s" % ping["note"])
        log("      %s" % ping["semantics"])
    log("")

    # --- Step 2: /v1/models ------------------------------------------------ #
    models = fetch_models(opener, base_url, args.timeout)
    auto_model = models.get("auto_model")
    if not models.get("ok") or not auto_model:
        log("[2/3] GET /v1/models: FAIL (HTTP=%s)" % models.get("http_status"))
        log("      error=%s" % models.get("error"))
        _write_failure_summary(results_dir, stamp, base_url, args, ping, models,
                               "models endpoint unreachable or returned no model id")
        return 1
    model = args.model or auto_model
    log("[2/3] GET /v1/models: HTTP %s  %.3fs  models=%s" % (
        models.get("http_status"), models.get("elapsed_s", 0.0),
        ", ".join(models.get("model_ids") or [])))
    log("      auto data[0].id = %s" % auto_model)
    if args.model:
        log("      overridden by --model = %s" % model)
    log("")

    # --- Step 3: chat ------------------------------------------------------ #
    primary = chat_once(opener, base_url, model, args.prompt,
                        args.max_tokens, args.timeout)
    primary_files = save_raw_response(results_dir, primary,
                                      "chat-nihao-%s" % stamp)
    _log_chat("3/3 primary", primary)

    retry = None
    retry_files = None
    retry_reason = None
    need_retry = (
        primary.get("truncated_by_thinking")
        and not args.no_retry
        and args.retry_max_tokens > args.max_tokens
    )
    if need_retry:
        retry_reason = (
            "thinking 模型假阴性：finish_reason=length、content 为空、"
            "reasoning_content 占满 %d token" % args.max_tokens
        )
        log("      -> %s" % retry_reason)
        log("      -> 自动复测 max_tokens=%d" % args.retry_max_tokens)
        retry = chat_once(opener, base_url, model, args.prompt,
                          args.retry_max_tokens, args.timeout)
        retry_files = save_raw_response(
            results_dir, retry,
            "chat-nihao-mt%d-%s" % (args.retry_max_tokens, stamp))
        _log_chat("3/3 retry", retry)
    elif args.no_retry and primary.get("truncated_by_thinking"):
        retry_reason = "thinking 假阴性，但 --no-retry 已关闭自动复测"
        log("      -> %s" % retry_reason)
    log("")

    # --- Verdict ----------------------------------------------------------- #
    final = retry if (retry and retry.get("inference_ok")) else primary
    if final and final.get("inference_ok"):
        verdict = "PASS_WITH_RETRY" if (retry and retry.get("inference_ok")) else "PASS"
        exit_code = 0
    elif primary.get("truncated_by_thinking") and not (retry and retry.get("inference_ok")):
        verdict = "INCONCLUSIVE_THINKING_TRUNCATED"
        exit_code = 2
    else:
        verdict = "FAIL"
        exit_code = 1

    thinking_detected = bool(
        primary.get("thinking_model") or (retry and retry.get("thinking_model")))
    conclusion = _conclusion(verdict, ping, model, primary, retry)

    summary = {
        "probe_version": PROBE_VERSION,
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "target": {"base_url": base_url, "host": ping_target,
                   "port": args.port, "proxy": "system" if args.use_proxy else "bypassed"},
        "request": {"prompt": args.prompt, "max_tokens": args.max_tokens,
                    "retry_max_tokens": None if args.no_retry else args.retry_max_tokens,
                    "stream": False, "timeout_s": args.timeout,
                    "model_used": model, "model_autodetected": auto_model,
                    "model_overridden": bool(args.model)},
        "steps": {
            "ping": ping,
            "models": public_step(models),
            "chat_primary": public_step(primary),
            "chat_retry": public_step(retry) if retry else None,
        },
        "diagnosis": {
            "thinking_model_detected": thinking_detected,
            "primary_truncated_by_thinking": bool(primary.get("truncated_by_thinking")),
            "auto_retry_triggered": bool(retry),
            "retry_reason": retry_reason,
            "ping_only_checks_connectivity": True,
            "inference_proven_by": "non-empty choices[0].message.content",
        },
        "verdict": verdict,
        "exit_code": exit_code,
        "conclusion": conclusion,
        "artifacts": {
            "summary": "probe-%s.json" % stamp,
            "chat_primary": primary_files,
            "chat_retry": retry_files,
        },
    }
    summary_path = results_dir / ("probe-%s.json" % stamp)
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    log("=== VERDICT: %s (exit %d) ===" % (verdict, exit_code))
    log(conclusion)
    log("")
    log("summary : %s" % summary_path.name)
    log("primary : %s / %s" % (primary_files["json"], primary_files["meta"]))
    if retry_files:
        log("retry   : %s / %s" % (retry_files["json"], retry_files["meta"]))
    return exit_code


def _log_chat(tag: str, result: dict) -> None:
    log("[%s] POST /v1/chat/completions : HTTP %s  %.3fs  %dB" % (
        tag, result.get("http_status"), result.get("elapsed_s", 0.0),
        result.get("size_bytes", 0)))
    log("      model=%s  finish_reason=%s  max_tokens=%s" % (
        result.get("model"), result.get("finish_reason"),
        result.get("request_max_tokens")))
    content = (result.get("content") or "").strip()
    if content:
        shown = content if len(content) <= 200 else content[:200] + "…"
        log("      content: %s" % shown.replace("\n", "  "))
    else:
        log("      content: <EMPTY>")
    log("      reasoning_len=%d  thinking_model=%s  inference_ok=%s" % (
        len(result.get("reasoning_content") or ""),
        result.get("thinking_model"), result.get("inference_ok")))
    if result.get("error"):
        log("      error: %s" % result["error"])


def _conclusion(verdict: str, ping: dict, model: str,
                primary: dict, retry) -> str:
    parts = []
    if ping.get("skipped"):
        parts.append("ping 已跳过（未做 ICMP 连通性检查）。")
    elif ping.get("ok"):
        detail = []
        if ping.get("loss_pct") is not None:
            detail.append("丢包 %s%%" % ping["loss_pct"])
        if ping.get("avg_ms") is not None:
            detail.append("平均 %sms" % ping["avg_ms"])
        suffix = "，" + "，".join(detail) if detail else ""
        parts.append("局域网连通正常（ping %s 次%s）。"
                     % (ping.get("sent"), suffix))
    else:
        parts.append("ping 未通过（ICMP 可能被防火墙禁用，不必然代表服务不可用）。")

    if verdict in ("PASS", "PASS_WITH_RETRY"):
        parts.append("/v1/models 返回模型 %s。" % model)
        if retry and retry.get("inference_ok"):
            parts.append(
                "首次 max_tokens=%d 触发 thinking 模型假阴性"
                "（finish_reason=length、content 为空）；自动提升到 %d 后，"
                "finish_reason=stop 且返回非空中文回复。"
                % (primary.get("request_max_tokens"), retry.get("request_max_tokens")))
            parts.append("=> 网络连通、模型 API 与实际推理均正常。")
        else:
            parts.append("POST /v1/chat/completions 返回非空 content，实际推理正常。")
            parts.append("=> 网络连通、模型 API 与实际推理均正常。")
    elif verdict == "INCONCLUSIVE_THINKING_TRUNCATED":
        parts.append("首次请求被 thinking 模型的思维链占满，未取得非空 content，"
                     "且本次未成功复测；不能据此判定模型离线。")
        parts.append("=> 请提高 --retry-max-tokens（例如 512）后重跑。")
    else:
        parts.append("未取得非空 choices[0].message.content，推理验证失败"
                     "（error=%s）。" % primary.get("error"))
        parts.append("=> 需检查服务进程、端口与 /v1/chat/completions 实现。")
    return " ".join(parts)


def _write_failure_summary(results_dir: Path, stamp: str, base_url: str,
                           args, ping: dict, models: dict, reason: str) -> None:
    """/v1/models 都拿不到时，也要留一份可复核的失败记录。"""
    doc = {
        "probe_version": PROBE_VERSION,
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "target": {"base_url": base_url},
        "steps": {"ping": ping, "models": public_step(models)},
        "verdict": "FAIL", "exit_code": 1,
        "reason": reason,
    }
    (results_dir / ("probe-%s.json" % stamp)).write_text(
        json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
