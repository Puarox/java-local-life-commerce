"""Prove Seata AT rollback across trade and cart after stock deduction fails.

Only runs against the isolated loopback stack. Creates and removes one test item.
Requires the local MySQL CLI and the Jack development account.
"""

import os
import shutil
import subprocess
import time
import uuid
from pathlib import Path

import requests


BASE = os.getenv("API_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
if BASE not in ("http://127.0.0.1:8080", "http://localhost:8080"):
    raise SystemExit("Rollback test only permits the loopback Gateway")
MYSQL = os.getenv("MYSQL_CLIENT") or shutil.which("mysql") or str(
    Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "MySQL/MySQL Server 8.0/bin/mysql.exe"
)
DB_PORT = os.getenv("DB_PORT", "3307")


def sql_count(statement):
    env = os.environ.copy()
    if env.get("DB_PASSWORD"):
        env["MYSQL_PWD"] = env["DB_PASSWORD"]
    result = subprocess.run(
        [MYSQL, "-h", "127.0.0.1", "-P", DB_PORT, "-u", env.get("DB_USERNAME", "root"),
         "-N", "-e", statement],
        capture_output=True, text=True, check=True, env=env,
    )
    return int(result.stdout.strip())


session = requests.Session()
login = session.post(BASE + "/users/login", json={"username": "Jack", "password":
                     os.getenv("DEMO_LOGIN_PASSWORD", "123")}, timeout=15)
login.raise_for_status()
account = login.json()
session.headers["authorization"] = account["token"]
user_id = int(account["userId"])
marker = "seata-rollback-" + uuid.uuid4().hex[:12]
item_id = None
try:
    create = session.post(BASE + "/items", json={
        "name": marker, "price": 100, "stock": 1, "status": 1,
        "brand": "验证", "category": "隔离测试",
    }, timeout=15)
    create.raise_for_status()
    search = session.get(BASE + "/search/list", params={"key": marker, "pageNo": 1, "pageSize": 10}, timeout=15)
    search.raise_for_status()
    item_id = search.json()["list"][0]["id"]
    add = session.post(BASE + "/carts", json={"itemId": item_id}, timeout=15)
    add.raise_for_status()
    before_order = sql_count(f"SELECT COUNT(*) FROM `hm-trade`.`order` WHERE user_id={user_id}")
    before_detail = sql_count("SELECT COUNT(*) FROM `hm-trade`.`order_detail`")
    before_cart = session.get(BASE + "/carts", timeout=15).json()
    assert any(str(row["itemId"]) == str(item_id) for row in before_cart)

    # Trade and cart write first; item-service rejects quantity 2 against stock 1.
    failed = session.post(BASE + "/orders", json={
        "paymentType": 3, "details": [{"itemId": item_id, "num": 2}],
    }, timeout=30)
    assert failed.status_code == 400, (failed.status_code, failed.text[:300])
    time.sleep(1)
    after_order = sql_count(f"SELECT COUNT(*) FROM `hm-trade`.`order` WHERE user_id={user_id}")
    after_detail = sql_count("SELECT COUNT(*) FROM `hm-trade`.`order_detail`")
    after_cart = session.get(BASE + "/carts", timeout=15).json()
    item = session.get(BASE + f"/items/{item_id}", timeout=15).json()
    assert after_order == before_order, (before_order, after_order)
    assert after_detail == before_detail, (before_detail, after_detail)
    assert any(str(row["itemId"]) == str(item_id) for row in after_cart), "cart row was not restored"
    assert item["stock"] == 1, "item stock changed"
    print("PASS Seata AT rollback: HTTP 400; trade/order_detail unchanged; cart restored; stock=1")
finally:
    if item_id is not None:
        session.delete(BASE + "/carts", params={"ids": item_id}, timeout=15)
        session.delete(BASE + f"/items/{item_id}", timeout=15)
