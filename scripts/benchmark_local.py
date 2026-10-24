"""Repeatable local read-endpoint load run; saves every request and summary.

Run against the isolated loopback stack only. This is a single-host development
benchmark, not a production capacity claim.
"""

import argparse
import csv
import json
import platform
import statistics
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

import requests


def percentile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--requests", type=int, default=300)
    parser.add_argument("--warmup", type=int, default=30)
    parser.add_argument("--url", default="http://127.0.0.1:8080/voucher/active")
    parser.add_argument("--output", type=Path, default=Path("docs/verification/results"))
    args = parser.parse_args()
    if not args.url.startswith(("http://127.0.0.1:8080/", "http://localhost:8080/")):
        parser.error("only the loopback Gateway is allowed")
    if args.concurrency < 1 or args.requests < 1 or args.warmup < 0:
        parser.error("concurrency and requests must be positive; warmup must be nonnegative")

    local = threading.local()

    def hit(index):
        if not hasattr(local, "session"):
            local.session = requests.Session()
        start = time.perf_counter()
        try:
            response = local.session.get(args.url, timeout=15)
            body = response.json()
            passed = response.status_code == 200 and body.get("success") is True
            error = "" if passed else str(body.get("errorMsg") or body.get("msg") or response.status_code)[:120]
            status = response.status_code
        except Exception as exc:
            passed, status, error = False, 0, type(exc).__name__
        return {"request": index, "latency_ms": round((time.perf_counter() - start) * 1000, 3),
                "status": status, "success": passed, "error": error}

    for index in range(args.warmup):
        if not hit(-index - 1)["success"]:
            raise RuntimeError("warmup failed; verify the stack before measuring")
    started = datetime.now(timezone.utc)
    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        records = [future.result() for future in as_completed(
            pool.submit(hit, index) for index in range(args.requests))]
    elapsed = time.perf_counter() - start
    records.sort(key=lambda row: row["request"])
    latencies = [row["latency_ms"] for row in records]
    successes = sum(row["success"] for row in records)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    summary = {
        "started_utc": started.isoformat(), "git_commit": commit,
        "scenario": "GET active vouchers via Gateway; single client host; keep-alive per worker",
        "url": args.url, "concurrency": args.concurrency, "warmup_requests": args.warmup,
        "measured_requests": args.requests, "elapsed_seconds": round(elapsed, 3),
        "successes": successes, "failures": args.requests - successes,
        "success_rate": round(successes / args.requests, 5),
        "throughput_rps": round(args.requests / elapsed, 2),
        "latency_ms": {"mean": round(statistics.mean(latencies), 2),
                       "p50": round(percentile(latencies, .5), 2),
                       "p95": round(percentile(latencies, .95), 2),
                       "p99": round(percentile(latencies, .99), 2),
                       "max": max(latencies)},
        "client": {"python": platform.python_version(), "system": platform.platform(),
                   "processor": platform.processor()},
    }
    args.output.mkdir(parents=True, exist_ok=True)
    stamp = started.strftime("%Y%m%d-%H%M%S")
    prefix = args.output / f"voucher-read-{stamp}"
    with (prefix.with_suffix(".csv")).open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=records[0].keys())
        writer.writeheader()
        writer.writerows(records)
    prefix.with_suffix(".json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"raw: {prefix.with_suffix('.csv')}")
    if successes != args.requests:
        raise SystemExit("benchmark had failed requests")


if __name__ == "__main__":
    main()
