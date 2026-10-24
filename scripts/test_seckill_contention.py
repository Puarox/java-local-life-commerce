"""Local one-user contention check: stock=1, 20 simultaneous claim attempts."""

import json
import os
import shutil
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests


BASE = os.getenv("API_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
if BASE not in ("http://127.0.0.1:8080", "http://localhost:8080"):
    raise SystemExit("Contention test only permits the loopback Gateway")
MYSQL = os.getenv("MYSQL_CLIENT") or shutil.which("mysql") or str(
    Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "MySQL/MySQL Server 8.0/bin/mysql.exe"
)


def scalar(statement):
    env = os.environ.copy()
    if env.get("DB_PASSWORD"):
        env["MYSQL_PWD"] = env["DB_PASSWORD"]
    result = subprocess.run([MYSQL, "-h", "127.0.0.1", "-P", env.get("DB_PORT", "3307"),
                             "-u", env.get("DB_USERNAME", "root"), "-N", "-e", statement],
                            capture_output=True, text=True, check=True, env=env)
    return int(result.stdout.strip())


admin = requests.Session()
login = admin.post(BASE + "/users/login", json={"username": "Jack", "password":
                   os.getenv("DEMO_LOGIN_PASSWORD", "123")}, timeout=15)
login.raise_for_status()
token = login.json()["token"]
admin.headers["authorization"] = token
now = datetime.now()
create = admin.post(BASE + "/voucher/seckill", json={
    "shopId": 1, "title": "并发验证券 " + now.strftime("%H%M%S"),
    "payValue": 100, "actualValue": 200, "type": 1, "stock": 1,
    "beginTime": (now - timedelta(minutes=1)).isoformat(timespec="seconds"),
    "endTime": (now + timedelta(minutes=10)).isoformat(timespec="seconds"),
}, timeout=15)
create.raise_for_status()
body = create.json()
assert body["success"], body
voucher_id = int(body["data"])


def claim(index):
    start = time.perf_counter()
    try:
        response = requests.post(BASE + f"/voucher-order/seckill/{voucher_id}",
                                 headers={"authorization": token}, timeout=20)
        payload = response.json()
        return {"attempt": index, "http_status": response.status_code,
                "success": payload.get("success") is True,
                "message": payload.get("errorMsg") or payload.get("msg") or "",
                "latency_ms": round((time.perf_counter() - start) * 1000, 2)}
    except Exception as exc:
        return {"attempt": index, "http_status": 0, "success": False,
                "message": type(exc).__name__, "latency_ms": round((time.perf_counter() - start) * 1000, 2)}


try:
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=20) as pool:
        records = [future.result() for future in as_completed(pool.submit(claim, i) for i in range(20))]
    elapsed = time.perf_counter() - started
    successes = sum(record["success"] for record in records)
    stock = scalar(f"SELECT stock FROM local_life.tb_seckill_voucher WHERE voucher_id={voucher_id}")
    orders = scalar(f"SELECT COUNT(*) FROM local_life.tb_voucher_order WHERE voucher_id={voucher_id}")
    summary = {"scenario": "one user, 20 concurrent claims, initial stock 1",
               "voucher_id": voucher_id, "attempts": 20, "successes": successes,
               "remaining_stock": stock, "orders": orders,
               "elapsed_seconds": round(elapsed, 3), "responses": records}
    output = Path("docs/verification/results")
    output.mkdir(parents=True, exist_ok=True)
    path = output / f"seckill-contention-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in summary.items() if key != "responses"}, ensure_ascii=False))
    print(f"raw: {path}")
    assert successes == 1 and stock == 0 and orders == 1, "contention invariant failed"
    assert all(row["http_status"] == 200 for row in records), "unexpected transport error"
finally:
    admin.put(BASE + f"/voucher/{voucher_id}/status", params={"status": 2}, timeout=15)
