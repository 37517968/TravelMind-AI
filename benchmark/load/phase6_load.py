#!/usr/bin/env python3
"""Phase 6 black-box load runner. Uses only the Python standard library."""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import json
import math
import os
import socket
import statistics
import threading
import time
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib import error, parse, request


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
QUERIES = [
    "杭州三天亲子路线", "北京历史文化五日游", "上海周末轻松行程",
    "成都熊猫基地和美食", "三亚海边度假注意事项", "西安博物馆路线",
]


@dataclass
class Sample:
    scenario: str
    latency_ms: float
    success: bool
    outcome: str
    details: dict[str, Any] = field(default_factory=dict)


class Samples:
    def __init__(self) -> None:
        self._items: list[Sample] = []
        self._lock = threading.Lock()

    def add(self, sample: Sample) -> None:
        with self._lock:
            self._items.append(sample)

    def report(self) -> dict[str, Any]:
        by_scenario: dict[str, list[Sample]] = {}
        for item in self._items:
            by_scenario.setdefault(item.scenario, []).append(item)
        return {name: summarize(items) for name, items in sorted(by_scenario.items())}


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(quantile * len(ordered)) - 1))
    return round(ordered[index], 3)


def summarize(items: list[Sample]) -> dict[str, Any]:
    latencies = [item.latency_ms for item in items]
    successes = sum(1 for item in items if item.success)
    outcomes: dict[str, int] = {}
    for item in items:
        outcomes[item.outcome] = outcomes.get(item.outcome, 0) + 1
    return {
        "count": len(items),
        "successes": successes,
        "failures": len(items) - successes,
        "success_rate": round(successes / len(items), 4) if items else 0,
        "latency_ms": {
            "min": round(min(latencies), 3) if latencies else None,
            "mean": round(statistics.fmean(latencies), 3) if latencies else None,
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "p99": percentile(latencies, 0.99),
            "max": round(max(latencies), 3) if latencies else None,
        },
        "outcomes": outcomes,
    }


def http_json(method: str, url: str, payload: dict[str, Any] | None, headers: dict[str, str],
              timeout: float) -> tuple[int, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    merged = {"Accept": "application/json", **headers}
    if payload is not None:
        merged["Content-Type"] = "application/json"
    req = request.Request(url, data=body, headers=merged, method=method)
    try:
        with request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else None
    except error.HTTPError as failure:
        raw = failure.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            parsed = {"body": raw[:500]}
        return failure.code, parsed


def task_payload(index: int) -> dict[str, Any]:
    return {
        "conversationId": f"phase6-{uuid.uuid4()}",
        "taskType": "PLAN",
        "prompt": f"压测样例 {index}：规划杭州三天行程，包含交通、预算和天气建议",
        "destination": "杭州",
        "startDate": "2026-10-10",
        "days": 3,
        "budget": 6000,
        "travelers": 2,
        "travelType": "文化休闲",
        "maxModelCalls": 3,
        "maxTokens": 12000,
        "maxNodeExecutions": 32,
    }


def submit_task(args: argparse.Namespace, index: int) -> tuple[int, Any, float]:
    started = time.perf_counter()
    status, body = http_json("POST", f"{args.base_url}/agent/tasks", task_payload(index),
                             {"Idempotency-Key": str(uuid.uuid4())}, args.timeout)
    return status, body, (time.perf_counter() - started) * 1000


def run_parallel(count: int, concurrency: int, call: Callable[[int], None]) -> None:
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(call, index) for index in range(count)]
        for future in concurrent.futures.as_completed(futures):
            future.result()


def run_api(args: argparse.Namespace, samples: Samples, scenario: str = "api_submit") -> list[int]:
    task_ids: list[int] = []
    lock = threading.Lock()

    def call(index: int) -> None:
        try:
            status, body, latency = submit_task(args, index)
            task_id = body.get("taskId") if isinstance(body, dict) else None
            ok = status == 202 and task_id is not None
            if ok:
                with lock:
                    task_ids.append(int(task_id))
            samples.add(Sample(scenario, latency, ok, str(status), {"task_id": task_id}))
        except Exception as failure:  # black-box runner must record, not abort the batch
            samples.add(Sample(scenario, 0, False, type(failure).__name__, {"error": str(failure)}))

    run_parallel(args.requests, args.concurrency, call)
    return task_ids


def run_rag(args: argparse.Namespace, samples: Samples) -> None:
    def call(index: int) -> None:
        started = time.perf_counter()
        try:
            status, body = http_json("POST", f"{args.base_url}/knowledge/search",
                                     {"query": QUERIES[index % len(QUERIES)], "topK": 5}, {}, args.timeout)
            latency = (time.perf_counter() - started) * 1000
            hits = len(body.get("hits", [])) if isinstance(body, dict) else 0
            samples.add(Sample("rag_search", latency, status == 200, str(status), {"hits": hits}))
        except Exception as failure:
            samples.add(Sample("rag_search", (time.perf_counter() - started) * 1000, False,
                               type(failure).__name__, {"error": str(failure)}))

    run_parallel(args.requests, args.concurrency, call)


def read_sse(args: argparse.Namespace, task_id: int, last_event_id: str | None,
             max_events: int) -> dict[str, Any]:
    headers = {"Accept": "text/event-stream"}
    if last_event_id:
        headers["Last-Event-ID"] = last_event_id
    req = request.Request(f"{args.base_url}/agent/tasks/{task_id}/events", headers=headers)
    started = time.perf_counter()
    first_event_ms: float | None = None
    event_id = last_event_id
    event_type = "message"
    data_lines: list[str] = []
    events = 0
    terminal = False
    try:
        with request.urlopen(req, timeout=args.sse_timeout) as response:
            for raw in response:
                line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                if line.startswith("id:"):
                    event_id = line[3:].strip()
                elif line.startswith("event:"):
                    event_type = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].lstrip())
                elif line == "" and (data_lines or event_type != "message"):
                    events += 1
                    if first_event_ms is None:
                        first_event_ms = (time.perf_counter() - started) * 1000
                    terminal = event_type == "terminal"
                    data_lines = []
                    event_type = "message"
                    if terminal or events >= max_events:
                        break
    except (TimeoutError, socket.timeout):
        pass
    return {
        "events": events,
        "last_event_id": event_id,
        "first_event_ms": first_event_ms,
        "terminal": terminal,
        "elapsed_ms": (time.perf_counter() - started) * 1000,
    }


def run_sse(args: argparse.Namespace, samples: Samples) -> None:
    task_ids = [args.task_id] if args.task_id else run_api(args, samples, "sse_task_submit")
    task_ids = [task_id for task_id in task_ids if task_id is not None]

    def call(index: int) -> None:
        task_id = task_ids[index % len(task_ids)]
        try:
            result = read_sse(args, task_id, None, args.sse_events)
            first = result["first_event_ms"]
            samples.add(Sample("sse_first_event", first or result["elapsed_ms"], first is not None,
                               "EVENT" if first is not None else "NO_EVENT", result))
            if args.sse_reconnect and result["last_event_id"] and not result["terminal"]:
                replay = read_sse(args, task_id, result["last_event_id"], 1)
                samples.add(Sample("sse_reconnect", replay["elapsed_ms"], replay["events"] > 0,
                                   "REPLAYED" if replay["events"] > 0 else "NO_NEW_EVENT", replay))
        except Exception as failure:
            samples.add(Sample("sse_first_event", 0, False, type(failure).__name__, {"error": str(failure)}))

    if not task_ids:
        return
    run_parallel(min(args.requests, len(task_ids)), args.concurrency, call)


def queue_snapshot(args: argparse.Namespace) -> dict[str, Any]:
    vhost = parse.quote(args.rabbit_vhost, safe="")
    queue = parse.quote(args.rabbit_queue, safe="")
    url = f"{args.rabbit_management_url}/api/queues/{vhost}/{queue}"
    token = base64.b64encode(f"{args.rabbit_user}:{args.rabbit_password}".encode()).decode()
    status, body = http_json("GET", url, None, {"Authorization": f"Basic {token}"}, args.timeout)
    if status != 200 or not isinstance(body, dict):
        return {"status": status, "body": body}
    stats = body.get("message_stats", {})
    return {
        "status": status,
        "messages": body.get("messages"),
        "messages_ready": body.get("messages_ready"),
        "messages_unacknowledged": body.get("messages_unacknowledged"),
        "consumers": body.get("consumers"),
        "publish": stats.get("publish"),
        "deliver_get": stats.get("deliver_get"),
        "ack": stats.get("ack"),
    }


def run_mq(args: argparse.Namespace, samples: Samples, metadata: dict[str, Any]) -> None:
    try:
        metadata["rabbit_before"] = queue_snapshot(args)
    except Exception as failure:
        metadata["rabbit_before"] = {"error": str(failure)}
    run_api(args, samples, "mq_outbox_submit")
    time.sleep(args.mq_settle_seconds)
    try:
        metadata["rabbit_after"] = queue_snapshot(args)
    except Exception as failure:
        metadata["rabbit_after"] = {"error": str(failure)}


def run_worker(args: argparse.Namespace, samples: Samples) -> None:
    task_ids = run_api(args, samples, "worker_task_submit")

    def wait(index: int) -> None:
        task_id = task_ids[index]
        started = time.perf_counter()
        deadline = started + args.worker_timeout
        outcome = "TIMEOUT"
        try:
            while time.perf_counter() < deadline:
                status, body = http_json("GET", f"{args.base_url}/agent/tasks/{task_id}", None, {}, args.timeout)
                task = body.get("task", {}) if status == 200 and isinstance(body, dict) else {}
                outcome = str(task.get("status", "UNKNOWN"))
                if outcome in TERMINAL:
                    break
                time.sleep(args.poll_interval)
            success = outcome == "SUCCEEDED"
            samples.add(Sample("agent_worker_e2e", (time.perf_counter() - started) * 1000,
                               success, outcome, {"task_id": task_id}))
        except Exception as failure:
            samples.add(Sample("agent_worker_e2e", (time.perf_counter() - started) * 1000,
                               False, type(failure).__name__, {"task_id": task_id, "error": str(failure)}))

    run_parallel(len(task_ids), args.concurrency, wait)


def markdown(report: dict[str, Any]) -> str:
    lines = ["# Phase 6 压测原始报告", "", f"- 时间：`{report['generated_at']}`",
             f"- 场景：`{report['configuration']['scenario']}`", "", "## 汇总", "",
             "| 场景 | 数量 | 成功率 | P50(ms) | P95(ms) | P99(ms) |", "|---|---:|---:|---:|---:|---:|"]
    for name, value in report["results"].items():
        latency = value["latency_ms"]
        lines.append(f"| {name} | {value['count']} | {value['success_rate']:.2%} | "
                     f"{latency['p50']} | {latency['p95']} | {latency['p99']} |")
    lines.extend(["", "## 限制", "", "- 本报告只代表记录的环境与参数。",
                  "- Agent 场景会实际创建任务；必须在隔离环境和受控模型额度下执行。",
                  "- RabbitMQ 快照是采样值，不替代 Prometheus 的持续速率曲线。", ""])
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", choices=["api", "sse", "mq", "rag", "worker", "all"], required=True)
    parser.add_argument("--base-url", default=os.getenv("AGENT_BASE_URL", "http://localhost:8123/api"))
    parser.add_argument("--requests", type=int, default=20)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=15)
    parser.add_argument("--confirm-agent-tasks", action="store_true",
                        help="required for scenarios that create tasks and may consume model quota")
    parser.add_argument("--task-id", type=int)
    parser.add_argument("--sse-timeout", type=float, default=30)
    parser.add_argument("--sse-events", type=int, default=10)
    parser.add_argument("--sse-reconnect", action="store_true")
    parser.add_argument("--worker-timeout", type=float, default=300)
    parser.add_argument("--poll-interval", type=float, default=1)
    parser.add_argument("--rabbit-management-url", default="http://localhost:15672")
    parser.add_argument("--rabbit-vhost", default="/")
    parser.add_argument("--rabbit-queue", default="agent.plan.q")
    parser.add_argument("--rabbit-user", default=os.getenv("RABBITMQ_USERNAME", "agent"))
    parser.add_argument("--rabbit-password", default=os.getenv("RABBITMQ_PASSWORD", ""))
    parser.add_argument("--mq-settle-seconds", type=float, default=2)
    parser.add_argument("--output-dir", default="target/phase6-reports")
    args = parser.parse_args()
    args.base_url = args.base_url.rstrip("/")
    args.rabbit_management_url = args.rabbit_management_url.rstrip("/")
    args.requests = max(1, args.requests)
    args.concurrency = max(1, args.concurrency)
    if args.scenario in {"api", "sse", "mq", "worker", "all"} and not args.confirm_agent_tasks:
        parser.error("this scenario creates Agent tasks; add --confirm-agent-tasks in an isolated environment")
    return args


def main() -> int:
    args = parse_args()
    samples = Samples()
    metadata: dict[str, Any] = {}
    started = time.perf_counter()
    if args.scenario in {"api", "all"}:
        run_api(args, samples)
    if args.scenario in {"rag", "all"}:
        run_rag(args, samples)
    if args.scenario in {"sse", "all"}:
        run_sse(args, samples)
    if args.scenario in {"mq", "all"}:
        run_mq(args, samples, metadata)
    if args.scenario in {"worker", "all"}:
        run_worker(args, samples)

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "configuration": {key: value for key, value in vars(args).items() if "password" not in key},
        "wall_time_seconds": round(time.perf_counter() - started, 3),
        "results": samples.report(),
        "metadata": metadata,
    }
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = output / f"load-{args.scenario}-{stamp}.json"
    md_path = output / f"load-{args.scenario}-{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(markdown(report), encoding="utf-8")
    print(json.dumps({"json": str(json_path), "markdown": str(md_path), "results": report["results"]},
                     ensure_ascii=False, indent=2))
    return 0 if all(item["failures"] == 0 for item in report["results"].values()) else 2


if __name__ == "__main__":
    raise SystemExit(main())
