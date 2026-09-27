#!/usr/bin/env python3
"""Phase 6 black-box load runner. Uses only the Python standard library."""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import http.cookiejar
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
OBSERVABLE_END = TERMINAL | {"WAITING_USER"}
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
        self._windows: dict[str, float] = {}
        self._lock = threading.Lock()

    def add(self, sample: Sample) -> None:
        with self._lock:
            self._items.append(sample)

    def report(self) -> dict[str, Any]:
        by_scenario: dict[str, list[Sample]] = {}
        for item in self._items:
            by_scenario.setdefault(item.scenario, []).append(item)
        report = {name: summarize(items) for name, items in sorted(by_scenario.items())}
        for name, value in report.items():
            wall = self._windows.get(name)
            if wall and wall > 0:
                value["wall_time_seconds"] = round(wall, 3)
                value["throughput_per_second"] = round(value["count"] / wall, 3)
        return report

    def raw(self) -> list[dict[str, Any]]:
        with self._lock:
            return [asdict(item) for item in self._items]

    def record_window(self, scenarios: str | list[str], started: float) -> None:
        elapsed = time.perf_counter() - started
        names = [scenarios] if isinstance(scenarios, str) else scenarios
        with self._lock:
            for name in names:
                self._windows[name] = self._windows.get(name, 0) + elapsed


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


def request_headers(args: argparse.Namespace, extra: dict[str, str] | None = None) -> dict[str, str]:
    headers = dict(getattr(args, "auth_headers", {}))
    if extra:
        headers.update(extra)
    return headers


def authenticate(args: argparse.Namespace) -> None:
    """Resolve one server-side session and reuse its Cookie in concurrent requests."""
    if args.session_cookie:
        cookie = args.session_cookie if "=" in args.session_cookie else f"SESSION={args.session_cookie}"
        args.auth_headers = {"Cookie": cookie}
        return
    if not args.username or not args.password:
        raise RuntimeError(
            "Agent scenarios require --username/--password (or AGENT_USERNAME/AGENT_PASSWORD) "
            "or --session-cookie"
        )
    jar = http.cookiejar.CookieJar()
    opener = request.build_opener(request.HTTPCookieProcessor(jar))
    payload = json.dumps({"userAccount": args.username, "userPassword": args.password}).encode("utf-8")
    req = request.Request(
        f"{args.base_url}/user/login",
        data=payload,
        headers={"Accept": "application/json", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with opener.open(req, timeout=args.timeout) as response:
            body = json.loads(response.read().decode("utf-8") or "{}")
            if response.status != 200 or not isinstance(body, dict) or body.get("data") is None:
                raise RuntimeError(f"login rejected: HTTP {response.status}, body={body}")
    except error.HTTPError as failure:
        raw = failure.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"login failed: HTTP {failure.code}, body={raw[:300]}") from failure
    cookie_header = "; ".join(f"{cookie.name}={cookie.value}" for cookie in jar)
    if not cookie_header:
        raise RuntimeError("login succeeded but the server did not return a session cookie")
    args.auth_headers = {"Cookie": cookie_header}


def create_conversation(args: argparse.Namespace, index: int) -> str:
    status, body = http_json(
        "POST",
        f"{args.base_url}/agent/conversations",
        {"title": f"load-{args.run_id}-{index}"},
        request_headers(args),
        args.timeout,
    )
    conversation_id = body.get("conversationId") if isinstance(body, dict) else None
    if status != 200 or not conversation_id:
        raise RuntimeError(f"conversation creation failed: HTTP {status}, body={body}")
    return str(conversation_id)


def task_payload(args: argparse.Namespace, index: int, conversation_id: str) -> dict[str, Any]:
    return {
        "conversationId": conversation_id,
        "taskType": "PLAN",
        "prompt": (
            f"压测样例 {args.run_id}-{index}：为两人规划杭州三天行程，必须游览西湖和灵隐寺，"
            "预算6000元，偏好文化休闲，请包含交通、住宿、餐饮和天气建议。"
        ),
        "destination": "杭州",
        "startDate": "2026-10-10",
        "days": 3,
        "budget": 6000,
        "travelers": 2,
        "travelType": "文化休闲",
        "maxModelCalls": args.max_model_calls,
        "maxTokens": 12000,
        "maxNodeExecutions": 32,
    }


def submit_task(args: argparse.Namespace, index: int) -> tuple[int, Any, float]:
    conversation_id = create_conversation(args, index)
    return submit_task_in_conversation(args, index, conversation_id)


def submit_task_in_conversation(args: argparse.Namespace, index: int,
                                conversation_id: str) -> tuple[int, Any, float]:
    started = time.perf_counter()
    status, body = http_json("POST", f"{args.base_url}/agent/tasks", task_payload(args, index, conversation_id),
                             request_headers(args, {"Idempotency-Key": str(uuid.uuid4())}), args.timeout)
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

    window_started = time.perf_counter()
    run_parallel(args.requests, args.concurrency, call)
    samples.record_window(scenario, window_started)
    return task_ids


def run_task_query(args: argparse.Namespace, samples: Samples) -> None:
    if not args.task_id:
        raise RuntimeError("--task-id is required for the query scenario")

    def call(_index: int) -> None:
        started = time.perf_counter()
        try:
            status, body = http_json("GET", f"{args.base_url}/agent/tasks/{args.task_id}/status", None,
                                     request_headers(args), args.timeout)
            if status == 404 and args.allow_legacy_status_fallback:
                status, body = http_json("GET", f"{args.base_url}/agent/tasks/{args.task_id}", None,
                                         request_headers(args), args.timeout)
                task = body.get("task", {}) if isinstance(body, dict) else {}
            else:
                task = body if isinstance(body, dict) else {}
            latency = (time.perf_counter() - started) * 1000
            samples.add(Sample("task_query", latency, status == 200, str(status),
                               {"task_id": args.task_id, "task_status": task.get("status")}))
        except Exception as failure:
            samples.add(Sample("task_query", (time.perf_counter() - started) * 1000, False,
                               type(failure).__name__, {"error": str(failure)}))

    window_started = time.perf_counter()
    run_parallel(args.requests, args.concurrency, call)
    samples.record_window("task_query", window_started)


def run_idempotent_replay(args: argparse.Namespace, samples: Samples, metadata: dict[str, Any]) -> None:
    conversation_id = create_conversation(args, 0)
    key = f"load-replay-{args.run_id}"
    payload = task_payload(args, 0, conversation_id)
    setup_status, setup_body = http_json(
        "POST", f"{args.base_url}/agent/tasks", payload,
        request_headers(args, {"Idempotency-Key": key}), args.timeout,
    )
    expected_task_id = setup_body.get("taskId") if isinstance(setup_body, dict) else None
    if setup_status != 202 or expected_task_id is None:
        raise RuntimeError(f"idempotency setup failed: HTTP {setup_status}, body={setup_body}")
    task_ids: set[int] = set()
    lock = threading.Lock()

    def call(_index: int) -> None:
        started = time.perf_counter()
        try:
            status, body = http_json(
                "POST", f"{args.base_url}/agent/tasks", payload,
                request_headers(args, {"Idempotency-Key": key}), args.timeout,
            )
            latency = (time.perf_counter() - started) * 1000
            task_id = body.get("taskId") if isinstance(body, dict) else None
            if task_id is not None:
                with lock:
                    task_ids.add(int(task_id))
            ok = status == 202 and task_id == expected_task_id
            samples.add(Sample("idempotent_submit", latency, ok, str(status), {"task_id": task_id}))
        except Exception as failure:
            samples.add(Sample("idempotent_submit", (time.perf_counter() - started) * 1000, False,
                               type(failure).__name__, {"error": str(failure)}))

    window_started = time.perf_counter()
    run_parallel(args.requests, args.concurrency, call)
    samples.record_window("idempotent_submit", window_started)
    metadata["idempotency"] = {
        "expected_task_id": expected_task_id,
        "unique_task_ids": sorted(task_ids),
        "deduplicated": task_ids == {int(expected_task_id)},
    }


def run_rag(args: argparse.Namespace, samples: Samples) -> None:
    def call(index: int) -> None:
        started = time.perf_counter()
        try:
            status, body = http_json("POST", f"{args.base_url}/knowledge/search",
                                     {"query": QUERIES[index % len(QUERIES)], "topK": 5},
                                     request_headers(args), args.timeout)
            latency = (time.perf_counter() - started) * 1000
            hits = len(body.get("hits", [])) if isinstance(body, dict) else 0
            samples.add(Sample("rag_search", latency, status == 200, str(status), {"hits": hits}))
        except Exception as failure:
            samples.add(Sample("rag_search", (time.perf_counter() - started) * 1000, False,
                               type(failure).__name__, {"error": str(failure)}))

    window_started = time.perf_counter()
    run_parallel(args.requests, args.concurrency, call)
    samples.record_window("rag_search", window_started)


def read_sse(args: argparse.Namespace, task_id: int, last_event_id: str | None,
             max_events: int) -> dict[str, Any]:
    headers = request_headers(args, {"Accept": "text/event-stream"})
    if last_event_id:
        headers["Last-Event-ID"] = last_event_id
    req = request.Request(f"{args.base_url}/agent/tasks/{task_id}/events", headers=headers)
    started = time.perf_counter()
    first_event_ms: float | None = None
    first_token_ms: float | None = None
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
                    if event_type == "token" and first_token_ms is None:
                        first_token_ms = (time.perf_counter() - started) * 1000
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
        "first_token_ms": first_token_ms,
        "terminal": terminal,
        "elapsed_ms": (time.perf_counter() - started) * 1000,
    }


def run_sse(args: argparse.Namespace, samples: Samples) -> None:
    def call(index: int) -> None:
        try:
            if args.task_id:
                task_id = args.task_id
            else:
                status, body, submit_latency = submit_task(args, index)
                task_id = body.get("taskId") if isinstance(body, dict) else None
                submitted = status == 202 and task_id is not None
                samples.add(Sample("sse_task_submit", submit_latency, submitted, str(status),
                                   {"task_id": task_id}))
                if not submitted:
                    return
            result = read_sse(args, task_id, None, args.sse_events)
            first = result["first_event_ms"]
            samples.add(Sample("sse_first_event", first or result["elapsed_ms"], first is not None,
                               "EVENT" if first is not None else "NO_EVENT", result))
            first_token = result["first_token_ms"]
            samples.add(Sample("sse_first_token", first_token or result["elapsed_ms"], first_token is not None,
                               "TOKEN" if first_token is not None else "NO_TOKEN", result))
            samples.add(Sample("sse_stream_terminal", result["elapsed_ms"], result["terminal"],
                               "TERMINAL" if result["terminal"] else "INCOMPLETE", result))
            if args.sse_reconnect and result["last_event_id"] and not result["terminal"]:
                replay = read_sse(args, task_id, result["last_event_id"], 1)
                samples.add(Sample("sse_reconnect", replay["elapsed_ms"], replay["events"] > 0,
                                   "REPLAYED" if replay["events"] > 0 else "NO_NEW_EVENT", replay))
        except Exception as failure:
            samples.add(Sample("sse_first_event", 0, False, type(failure).__name__, {"error": str(failure)}))

    window_started = time.perf_counter()
    run_parallel(1 if args.task_id else args.requests, args.concurrency, call)
    samples.record_window(
        ["sse_task_submit", "sse_first_event", "sse_first_token", "sse_stream_terminal", "sse_reconnect"],
        window_started,
    )


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
    def execute(index: int) -> None:
        outcome = "TIMEOUT"
        task_id: int | None = None
        try:
            conversation_id = create_conversation(args, index)
            started = time.perf_counter()
            status, body, submit_latency = submit_task_in_conversation(args, index, conversation_id)
            task_id = body.get("taskId") if isinstance(body, dict) else None
            submitted = status == 202 and task_id is not None
            samples.add(Sample("worker_task_submit", submit_latency, submitted, str(status),
                               {"task_id": task_id}))
            if not submitted:
                return
            deadline = started + args.worker_timeout
            while time.perf_counter() < deadline:
                status, body = http_json("GET", f"{args.base_url}/agent/tasks/{task_id}/status", None,
                                         request_headers(args), args.timeout)
                if status == 404 and args.allow_legacy_status_fallback:
                    status, body = http_json("GET", f"{args.base_url}/agent/tasks/{task_id}", None,
                                             request_headers(args), args.timeout)
                    task = body.get("task", {}) if status == 200 and isinstance(body, dict) else {}
                else:
                    task = body if status == 200 and isinstance(body, dict) else {}
                outcome = str(task.get("status", "UNKNOWN"))
                if outcome in OBSERVABLE_END:
                    break
                time.sleep(args.poll_interval)
            success = outcome == "SUCCEEDED"
            samples.add(Sample("agent_worker_e2e", (time.perf_counter() - started) * 1000,
                               success, outcome, {"task_id": task_id}))
        except Exception as failure:
            elapsed = (time.perf_counter() - started) * 1000 if "started" in locals() else 0
            samples.add(Sample("agent_worker_e2e", elapsed,
                               False, type(failure).__name__, {"task_id": task_id, "error": str(failure)}))

    window_started = time.perf_counter()
    run_parallel(args.requests, args.concurrency, execute)
    samples.record_window(["worker_task_submit", "agent_worker_e2e"], window_started)


PROMETHEUS_QUERIES = {
    "task_submit_rate": "sum(rate(agent_task_submitted_total[5m]))",
    "task_success_rate": (
        'sum(rate(agent_task_completed_total{outcome="SUCCEEDED"}[5m])) '
        '/ clamp_min(sum(rate(agent_task_completed_total[5m])), 0.000001)'
    ),
    "queue_delay_p95_seconds": (
        "histogram_quantile(0.95, sum by (le) (rate(agent_task_queue_delay_seconds_bucket[5m])))"
    ),
    "end_to_end_p95_seconds": (
        "histogram_quantile(0.95, sum by (le) (rate(agent_task_end_to_end_seconds_bucket[5m])))"
    ),
    "first_token_p95_seconds": (
        "histogram_quantile(0.95, sum by (le) (rate(agent_model_first_token_duration_seconds_bucket[5m])))"
    ),
    "node_p95_seconds": (
        "histogram_quantile(0.95, sum by (node, le) (rate(agent_node_duration_seconds_bucket[5m])))"
    ),
    "tool_failure_rate": (
        'sum(rate(tool_calls_total{outcome="FAILED"}[5m])) '
        '/ clamp_min(sum(rate(tool_calls_total[5m])), 0.000001)'
    ),
    "rag_degraded_rate": (
        'sum(rate(rag_search_total{degraded="true"}[5m])) '
        '/ clamp_min(sum(rate(rag_search_total[5m])), 0.000001)'
    ),
    "model_tokens_rate": "sum(rate(agent_model_tokens_total[5m]))",
    "outbox_backlog": "sum(agent_outbox_backlog)",
    "rabbit_ready": "sum(rabbitmq_queue_messages_ready)",
    "sse_active_connections": "sum(agent_sse_connections_active)",
    "jvm_heap_ratio": (
        'sum(jvm_memory_used_bytes{area="heap"}) / clamp_min(sum(jvm_memory_max_bytes{area="heap"}), 1)'
    ),
}


def prometheus_snapshot(args: argparse.Namespace) -> dict[str, Any]:
    if not args.prometheus_url:
        return {}
    result: dict[str, Any] = {}
    for name, query in PROMETHEUS_QUERIES.items():
        url = f"{args.prometheus_url}/api/v1/query?{parse.urlencode({'query': query})}"
        try:
            status, body = http_json("GET", url, None, {}, args.timeout)
            values = body.get("data", {}).get("result", []) if isinstance(body, dict) else []
            result[name] = {"status": status, "series": values}
        except Exception as failure:
            result[name] = {"error": str(failure)}
    return result


def threshold_results(args: argparse.Namespace, results: dict[str, Any]) -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []

    def add(name: str, scenario: str, field: str, limit: float, lower_bound: bool = False) -> None:
        value = results.get(scenario, {}).get(field) if field == "success_rate" else \
            results.get(scenario, {}).get("latency_ms", {}).get(field)
        if value is None:
            return
        passed = value >= limit if lower_bound else value <= limit
        checks.append({"name": name, "scenario": scenario, "value": value,
                       "operator": ">=" if lower_bound else "<=", "limit": limit, "passed": passed})

    for scenario in results:
        add(f"{scenario} success rate", scenario, "success_rate", args.min_success_rate, True)
    add("submit p95", "api_submit", "p95", args.max_submit_p95_ms)
    add("worker submit p95", "worker_task_submit", "p95", args.max_submit_p95_ms)
    add("SSE task submit p95", "sse_task_submit", "p95", args.max_submit_p95_ms)
    add("MQ task submit p95", "mq_outbox_submit", "p95", args.max_submit_p95_ms)
    add("worker e2e p95", "agent_worker_e2e", "p95", args.max_e2e_p95_ms)
    add("SSE first event p95", "sse_first_event", "p95", args.max_first_event_p95_ms)
    add("SSE first token p95", "sse_first_token", "p95", args.max_first_token_p95_ms)
    return checks


def markdown(report: dict[str, Any]) -> str:
    lines = ["# Phase 6 压测原始报告", "", f"- 时间：`{report['generated_at']}`",
             f"- 场景：`{report['configuration']['scenario']}`", "", "## 汇总", "",
             "| 场景 | 数量 | 成功率 | 吞吐/s | P50(ms) | P95(ms) | P99(ms) |",
             "|---|---:|---:|---:|---:|---:|---:|"]
    for name, value in report["results"].items():
        latency = value["latency_ms"]
        lines.append(f"| {name} | {value['count']} | {value['success_rate']:.2%} | "
                     f"{value.get('throughput_per_second')} | "
                     f"{latency['p50']} | {latency['p95']} | {latency['p99']} |")
    lines.extend(["", "## SLO 阈值", "", "| 检查项 | 实际值 | 条件 | 结果 |", "|---|---:|---:|---|"])
    for check in report["thresholds"]:
        verdict = "PASS" if check["passed"] else "FAIL"
        lines.append(
            f"| {check['name']} | {check['value']} | {check['operator']} {check['limit']} | {verdict} |"
        )
    lines.extend(["", "## 限制", "", "- 本报告只代表记录的环境与参数。",
                  "- Agent 场景会实际创建任务；必须在隔离环境和受控模型额度下执行。",
                  "- RabbitMQ 快照是采样值，不替代 Prometheus 的持续速率曲线。", ""])
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario",
                        choices=["api", "query", "idempotency", "sse", "mq", "rag", "worker", "all"],
                        required=True)
    parser.add_argument("--base-url", default=os.getenv("AGENT_BASE_URL", "http://localhost:8123/api"))
    parser.add_argument("--requests", type=int, default=20)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=15)
    parser.add_argument("--username", default=os.getenv("AGENT_USERNAME", ""))
    parser.add_argument("--password", default=os.getenv("AGENT_PASSWORD", ""))
    parser.add_argument("--session-cookie", default=os.getenv("AGENT_SESSION_COOKIE", ""),
                        help="SESSION cookie value or complete name=value; preferred in CI")
    parser.add_argument("--run-id", default=datetime.now().strftime("%Y%m%d%H%M%S"))
    parser.add_argument("--max-model-calls", type=int, default=8)
    parser.add_argument("--confirm-agent-tasks", action="store_true",
                        help="required for scenarios that create tasks and may consume model quota")
    parser.add_argument("--task-id", type=int)
    parser.add_argument("--allow-legacy-status-fallback", action="store_true",
                        help="fall back to the heavy task detail endpoint when /status is not deployed")
    parser.add_argument("--sse-timeout", type=float, default=300)
    parser.add_argument("--sse-events", type=int, default=500)
    parser.add_argument("--sse-reconnect", action="store_true")
    parser.add_argument("--worker-timeout", type=float, default=300)
    parser.add_argument("--poll-interval", type=float, default=1)
    parser.add_argument("--rabbit-management-url", default="http://localhost:15672")
    parser.add_argument("--rabbit-vhost", default="/")
    parser.add_argument("--rabbit-queue", default="agent.plan.q")
    parser.add_argument("--rabbit-user", default=os.getenv("RABBITMQ_USERNAME", "agent"))
    parser.add_argument("--rabbit-password", default=os.getenv("RABBITMQ_PASSWORD", ""))
    parser.add_argument("--mq-settle-seconds", type=float, default=2)
    parser.add_argument("--prometheus-url", default=os.getenv("PROMETHEUS_URL", ""),
                        help="optional Prometheus base URL, for example http://localhost:9090")
    parser.add_argument("--min-success-rate", type=float, default=0.99)
    parser.add_argument("--max-submit-p95-ms", type=float, default=1000)
    parser.add_argument("--max-first-event-p95-ms", type=float, default=2000)
    parser.add_argument("--max-first-token-p95-ms", type=float, default=30000)
    parser.add_argument("--max-e2e-p95-ms", type=float, default=300000)
    parser.add_argument("--output-dir", default="target/phase6-reports")
    args = parser.parse_args()
    args.base_url = args.base_url.rstrip("/")
    args.rabbit_management_url = args.rabbit_management_url.rstrip("/")
    args.prometheus_url = args.prometheus_url.rstrip("/")
    args.requests = max(1, args.requests)
    args.concurrency = max(1, args.concurrency)
    if args.scenario in {"api", "idempotency", "sse", "mq", "worker", "all"} \
            and not args.confirm_agent_tasks:
        parser.error("this scenario creates Agent tasks; add --confirm-agent-tasks in an isolated environment")
    return args


def main() -> int:
    args = parse_args()
    args.auth_headers = {}
    if args.scenario != "rag" or args.username or args.password or args.session_cookie:
        authenticate(args)
    samples = Samples()
    metadata: dict[str, Any] = {"prometheus_before": prometheus_snapshot(args)}
    started = time.perf_counter()
    if args.scenario in {"api", "all"}:
        run_api(args, samples)
    if args.scenario in {"query", "all"}:
        run_task_query(args, samples)
    if args.scenario in {"idempotency", "all"}:
        run_idempotent_replay(args, samples, metadata)
    if args.scenario in {"rag", "all"}:
        run_rag(args, samples)
    if args.scenario in {"sse", "all"}:
        run_sse(args, samples)
    if args.scenario in {"mq", "all"}:
        run_mq(args, samples, metadata)
    if args.scenario in {"worker", "all"}:
        run_worker(args, samples)

    metadata["prometheus_after"] = prometheus_snapshot(args)
    results = samples.report()
    thresholds = threshold_results(args, results)
    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "configuration": {
            key: value for key, value in vars(args).items()
            if "password" not in key and key not in {"session_cookie", "auth_headers"}
        },
        "wall_time_seconds": round(time.perf_counter() - started, 3),
        "results": results,
        "samples": samples.raw(),
        "thresholds": thresholds,
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
    if any(not check["passed"] for check in thresholds):
        return 3
    return 0 if all(item["failures"] == 0 for item in report["results"].values()) else 2


if __name__ == "__main__":
    raise SystemExit(main())
