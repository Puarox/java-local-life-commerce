"""Race independent users against limited voucher stock on the local stack."""

import json
import os
import shutil
import subprocess
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests


BASE = os.getenv("API_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
if BASE not in ("http://127.0.0.1:8080", "http://localhost:8080"):
    raise SystemExit("Multi-user seckill test only permits the loopback Gateway")
MYSQL = os.getenv("MYSQL_CLIENT") or shutil.which("mysql") or str(
    Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "MySQL/MySQL Server 8.0/bin/mysql.exe"
)
DB_PORT = os.getenv("DB_PORT", "3307")
USER_COUNT = int(os.getenv("SECKILL_USERS", "20"))
INITIAL_STOCK = int(os.getenv("SECKILL_STOCK", "1"))
if USER_COUNT < INITIAL_STOCK or USER_COUNT > 200 or INITIAL_STOCK < 1:
    raise SystemExit("Use 1 <= stock <= users <= 200")


def sql(schema, statement):
    env = os.environ.copy()
    if env.get("DB_PASSWORD"):
        env["MYSQL_PWD"] = env["DB_PASSWORD"]
    result = subprocess.run(
        [MYSQL, "-h", "127.0.0.1", "-P", DB_PORT, "-u", env.get("DB_USERNAME", "root"),
         "-D", schema, "-N", "-e", statement],
        capture_output=True, text=True, check=True, env=env,
    )
    return result.stdout.strip()


def api(session, method, path, *, body=None):
    response = session.request(method, BASE + path, json=body, timeout=25)
    response.raise_for_status()
    payload = response.json() if response.content else None
    return payload.get("data") if isinstance(payload, dict) and payload.get("success") is True else payload


admin = requests.Session()
login = api(admin, "POST", "/users/login", body={
    "username": "Jack", "password": os.getenv("DEMO_LOGIN_PASSWORD", "123")
})
admin.headers["authorization"] = login["token"]
marker = "multiuser-" + uuid.uuid4().hex[:10]
password_hash = sql("hm-user", "SELECT password FROM user WHERE id=1")
usernames = [f"{marker}-{index:03d}" for index in range(USER_COUNT)]
user_ids = []
voucher_id = None
created_voucher = False

try:
    for index, username in enumerate(usernames):
        phone = f"139{uuid.uuid4().int % 10**8:08d}"
        sql("hm-user", "INSERT INTO user(username,password,phone,create_time,update_time,status,balance,nick_name,icon) "
            f"VALUES('{username}','{password_hash}','{phone}',NOW(),NOW(),1,0,'并发测试用户','')")
    ids = sql("hm-user", "SELECT id FROM user WHERE username LIKE "
              f"'{marker}-%' ORDER BY username").splitlines()
    user_ids = [int(value) for value in ids]
    if len(user_ids) != USER_COUNT:
        raise AssertionError(f"Created {len(user_ids)} test accounts, expected {USER_COUNT}")

    sessions = []
    for username in usernames:
        account = api(requests.Session(), "POST", "/users/login", body={"username": username, "password": "123"})
        session = requests.Session()
        session.headers["authorization"] = account["token"]
        sessions.append(session)

    now = datetime.now()
    voucher = api(admin, "POST", "/voucher/seckill", body={
        "shopId": 1, "title": f"多用户竞争 {marker}", "payValue": 100, "actualValue": 200,
        "type": 1, "stock": INITIAL_STOCK,
        "beginTime": (now - timedelta(minutes=2)).isoformat(timespec="seconds"),
        "endTime": (now + timedelta(minutes=20)).isoformat(timespec="seconds"),
    })
    voucher_id = int(voucher)
    created_voucher = True
    barrier = threading.Barrier(USER_COUNT)

    def claim(index):
        started = time.perf_counter()
        try:
            barrier.wait(timeout=10)
            response = sessions[index].post(BASE + f"/voucher-order/seckill/{voucher_id}", timeout=25)
            payload = response.json()
            return {"user_id": user_ids[index], "http_status": response.status_code,
                    "success": response.status_code == 200 and payload.get("success") is True,
                    "message": payload.get("errorMsg") or payload.get("msg") or "",
                    "latency_ms": round((time.perf_counter() - started) * 1000, 2)}
        except Exception as exc:
            return {"user_id": user_ids[index], "http_status": 0, "success": False,
                    "message": type(exc).__name__,
                    "latency_ms": round((time.perf_counter() - started) * 1000, 2)}

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=USER_COUNT) as pool:
        futures = [pool.submit(claim, index) for index in range(USER_COUNT)]
        records = [future.result() for future in as_completed(futures)]
    elapsed = time.perf_counter() - started
    remaining_stock = int(sql("local_life", f"SELECT stock FROM tb_seckill_voucher WHERE voucher_id={voucher_id}"))
    order_count = int(sql("local_life", f"SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id={voucher_id}"))
    successes = sum(record["success"] for record in records)
    summary = {
        "scenario": "different users concurrently compete for limited seckill stock",
        "voucher_id": voucher_id, "users": USER_COUNT, "initial_stock": INITIAL_STOCK,
        "attempts": USER_COUNT, "successes": successes, "remaining_stock": remaining_stock,
        "orders": order_count, "elapsed_seconds": round(elapsed, 3), "responses": records,
    }
    output = Path("docs/verification/results")
    output.mkdir(parents=True, exist_ok=True)
    path = output / f"seckill-multiuser-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in summary.items() if key != "responses"}, ensure_ascii=False))
    print(f"raw: {path}")

    assert all(record["http_status"] == 200 for record in records), "unexpected transport/server error"
    assert successes <= INITIAL_STOCK, f"oversold: successes={successes}, stock={INITIAL_STOCK}"
    assert remaining_stock == INITIAL_STOCK - successes, "inventory decrement does not match successful orders"
    assert order_count == successes, "success count does not match persisted voucher orders"
    assert remaining_stock >= 0
finally:
    if created_voucher:
        sql("local_life", f"DELETE FROM tb_voucher_order WHERE voucher_id={voucher_id}")
        sql("local_life", f"DELETE FROM tb_seckill_voucher WHERE voucher_id={voucher_id}")
        sql("local_life", f"DELETE FROM tb_voucher WHERE id={voucher_id}")
    sql("hm-user", f"DELETE FROM user WHERE username LIKE '{marker}-%'")
