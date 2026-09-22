#!/usr/bin/env python3
"""Evaluate lexical-only, semantic-only and hybrid deployments with one frozen dataset."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import statistics
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib import error, request


@dataclass
class Result:
    case_id: str
    category: str
    answerable: bool
    request_ok: bool
    relevant_rank: int | None
    no_answer_correct: bool
    degraded: bool
    latency_ms: float
    error: str | None = None


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, min(len(ordered) - 1, math.ceil(len(ordered) * quantile) - 1))], 3)


def endpoint(value: str) -> str:
    value = value.rstrip("/")
    return value if value.endswith("/knowledge/search") else value + "/knowledge/search"


def post_json(url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    req = request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode(), method="POST",
                          headers={"Content-Type": "application/json", "Accept": "application/json"})
    with request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def relevant(case: dict[str, Any], hit: dict[str, Any]) -> bool:
    text = " ".join(str(hit.get(key, "")) for key in ("title", "content", "city", "district"))
    expected_city = str(case.get("expectedCity", ""))
    city_match = not expected_city or expected_city in text
    terms = [str(term) for term in case.get("goldTerms", [])]
    term_match = not terms or any(term in text for term in terms)
    return city_match and term_match


def evaluate_case(url: str, case: dict[str, Any], top_k: int, timeout: float) -> Result:
    started = time.perf_counter()
    try:
        body = post_json(url, {"query": case["query"], "city": case.get("expectedCity"), "topK": top_k}, timeout)
        hits = body.get("hits", [])
        ranks = [index + 1 for index, hit in enumerate(hits) if relevant(case, hit)]
        answerable = bool(case.get("answerable", True))
        return Result(
            str(case["id"]), str(case["category"]), answerable, True,
            ranks[0] if ranks else None,
            (not ranks) if not answerable else False,
            bool(body.get("degraded", False)),
            (time.perf_counter() - started) * 1000,
        )
    except Exception as failure:
        return Result(str(case["id"]), str(case["category"]), bool(case.get("answerable", True)), False,
                      None, False, True, (time.perf_counter() - started) * 1000,
                      f"{type(failure).__name__}: {failure}")


def metrics(results: list[Result]) -> dict[str, Any]:
    requested = len(results)
    answerable = [item for item in results if item.answerable and item.request_ok]
    no_answer = [item for item in results if not item.answerable and item.request_ok]
    hits = [item for item in answerable if item.relevant_rank is not None]
    latencies = [item.latency_ms for item in results if item.request_ok]
    categories: dict[str, dict[str, int]] = {}
    for item in results:
        value = categories.setdefault(item.category, {"cases": 0, "relevant_hits": 0, "errors": 0})
        value["cases"] += 1
        value["relevant_hits"] += int(item.relevant_rank is not None)
        value["errors"] += int(not item.request_ok)
    return {
        "cases": requested,
        "request_errors": sum(1 for item in results if not item.request_ok),
        "recall_at_k": round(len(hits) / len(answerable), 4) if answerable else None,
        "mrr": round(sum(1 / item.relevant_rank for item in hits) / len(answerable), 4) if answerable else None,
        "no_answer_accuracy": round(sum(item.no_answer_correct for item in no_answer) / len(no_answer), 4)
        if no_answer else None,
        "degraded_rate": round(sum(item.degraded for item in results) / requested, 4) if requested else None,
        "latency_ms": {
            "mean": round(statistics.fmean(latencies), 3) if latencies else None,
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "p99": percentile(latencies, 0.99),
        },
        "categories": categories,
    }


def markdown(report: dict[str, Any]) -> str:
    lines = ["# RAG 消融实验", "", f"- 时间：`{report['generated_at']}`",
             f"- TopK：`{report['top_k']}`", f"- 数据集：`{report['dataset']}`", "",
             "| 模式 | 样例 | Recall@K | MRR | 无答案准确率 | 降级率 | P95(ms) | 请求错误 |",
             "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for name, value in report["modes"].items():
        def fmt(number: Any) -> str:
            return "N/A" if number is None else str(number)
        lines.append(f"| {name} | {value['cases']} | {fmt(value['recall_at_k'])} | {fmt(value['mrr'])} | "
                     f"{fmt(value['no_answer_accuracy'])} | {fmt(value['degraded_rate'])} | "
                     f"{fmt(value['latency_ms']['p95'])} | {value['request_errors']} |")
    lines.extend(["", "## 解释限制", "",
                  "- 相关性由冻结数据集的 expectedCity/goldTerms 自动判断，正式发布前仍需人工抽样复核。",
                  "- 三种模式必须使用同一索引快照、硬件、候选数和 TopK；否则不能横向比较。",
                  "- lexical-only 应关闭 PGVector，semantic-only 应关闭 Elasticsearch，hybrid 同时开启。", ""])
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", action="append", required=True, metavar="NAME=URL",
                        help="repeat, e.g. lexical=http://localhost:8124/api")
    parser.add_argument("--dataset", default="src/test/resources/evaluation/travel-rag-eval.jsonl")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=15)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--output-dir", default="target/phase6-reports")
    args = parser.parse_args()
    modes: dict[str, str] = {}
    for item in args.mode:
        if "=" not in item:
            parser.error("--mode must be NAME=URL")
        name, url = item.split("=", 1)
        modes[name] = endpoint(url)
    args.modes = modes
    return args


def main() -> int:
    args = parse_args()
    cases = [json.loads(line) for line in Path(args.dataset).read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.limit:
        cases = cases[:args.limit]
    report: dict[str, Any] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "dataset": args.dataset,
        "top_k": args.top_k,
        "modes": {},
    }
    raw_results: dict[str, list[dict[str, Any]]] = {}
    for name, url in args.modes.items():
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
            results = list(pool.map(lambda case: evaluate_case(url, case, args.top_k, args.timeout), cases))
        report["modes"][name] = metrics(results)
        raw_results[name] = [result.__dict__ for result in results]
    report["raw_results"] = raw_results

    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = output / f"rag-ablation-{stamp}.json"
    md_path = output / f"rag-ablation-{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    md_path.write_text(markdown(report), encoding="utf-8")
    print(json.dumps({"json": str(json_path), "markdown": str(md_path), "modes": report["modes"]},
                     ensure_ascii=False, indent=2))
    return 2 if any(value["request_errors"] for value in report["modes"].values()) else 0


if __name__ == "__main__":
    raise SystemExit(main())
