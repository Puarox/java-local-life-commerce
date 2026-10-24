"""Inject an outbox unique-key failure and verify Seata rolls back payment branches.

Only supports the isolated loopback Gateway and MySQL development instance.
The script uses Jack/123 and leaves a paid disposable test order for audit.
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
    raise SystemExit("Payment rollback injection only permits the loopback Gateway")
MYSQL = os.getenv("MYSQL_CLIENT") or shutil.which("mysql") or str(
    Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "MySQL/MySQL Server 8.0/bin/mysql.exe"
)
DB_PORT = os.getenv("DB_PORT", "3307")


def sql(schema, statement):
    env = os.environ.copy()
    if env.get("DB_PASSWORD"):
        env["MYSQL_PWD"] = env["DB_PASSWORD"]
    return subprocess.run(
        [MYSQL, "-h", "127.0.0.1", "-P", DB_PORT, "-u", env.get("DB_USERNAME", "root"),
         "-D", schema, "-N", "-e", statement],
        capture_output=True, text=True, check=True, env=env,
    ).stdout.strip()


def call(session, method, path, *, body=None, expected=200):
    response = session.request(method, BASE + path, json=body, timeout=30)
    if response.status_code != expected:
        raise AssertionError(f"{method} {path}: expected HTTP {expected}, got "
                             f"{response.status_code}: {response.text[:300]}")
    if expected != 200:
        return response
    if not response.content:
        return None
    payload = response.json()
    if isinstance(payload, dict) and payload.get("success") is False:
        raise AssertionError(f"{method} {path}: {payload}")
    return payload.get("data") if isinstance(payload, dict) and payload.get("success") is True else payload


session = requests.Session()
account = call(session, "POST", "/users/login", body={
    "username": "Jack", "password": os.getenv("DEMO_LOGIN_PASSWORD", "123")
})
session.headers["authorization"] = account["token"]
user_id = int(account["userId"])
marker = "seata-pay-rollback-" + uuid.uuid4().hex[:12]
item_id = None
pay_id = None
fixture_outbox = False

try:
    call(session, "POST", "/items", body={"name": marker, "price": 100, "stock": 2,
         "status": 1, "brand": "验证", "category": "隔离测试"})
    items = call(session, "GET", f"/search/list?key={marker}&pageNo=1&pageSize=10")
    item_id = int(items["list"][0]["id"])
    order_id = int(call(session, "POST", "/orders", body={"paymentType": 3,
                           "details": [{"itemId": item_id, "num": 1}]}))
    order = call(session, "GET", f"/orders/{order_id}")
    pay_id = int(call(session, "POST", "/pay-orders", body={
        "bizOrderNo": order_id, "amount": order["totalFee"], "payChannelCode": "balance",
        "payType": 5, "orderInfo": marker,
    }))

    balance_before = int(sql("hm-user", f"SELECT balance FROM user WHERE id={user_id}"))
    pay_before = int(sql("hm-pay", f"SELECT status FROM pay_order WHERE id={pay_id}"))
    assert pay_before == 1, f"expected WAIT_BUYER_PAY, found {pay_before}"
    # An already-published fixture occupies the unique key but won't be sent by the poller.
    sql("hm-pay", "INSERT INTO pay_outbox(pay_order_id,biz_order_no,routing_key,state,published_at) "
        f"VALUES({pay_id},{order_id},'pay.success',2,NOW(6))")
    fixture_outbox = True

    failed = call(session, "POST", f"/pay-orders/{pay_id}", body={"pw": "123"}, expected=500)
    time.sleep(1)
    balance_after = int(sql("hm-user", f"SELECT balance FROM user WHERE id={user_id}"))
    pay_after = int(sql("hm-pay", f"SELECT status FROM pay_order WHERE id={pay_id}"))
    order_after = int(sql("hm-trade", f"SELECT status FROM `order` WHERE id={order_id}"))
    outbox_rows = int(sql("hm-pay", f"SELECT COUNT(*) FROM pay_outbox WHERE pay_order_id={pay_id}"))
    assert balance_after == balance_before, (balance_before, balance_after)
    assert pay_after == pay_before, (pay_before, pay_after)
    assert order_after == 1, f"business order changed during failed global transaction: {order_after}"
    assert outbox_rows == 1, f"injected outbox fixture was unexpectedly modified: {outbox_rows}"
    print(f"PASS injected rollback: HTTP {failed.status_code}; balance/pay/order unchanged; fixture outbox retained")

    sql("hm-pay", f"DELETE FROM pay_outbox WHERE pay_order_id={pay_id}")
    fixture_outbox = False
    call(session, "POST", f"/pay-orders/{pay_id}", body={"pw": "123"})
    for _ in range(30):
        if int(sql("hm-trade", f"SELECT status FROM `order` WHERE id={order_id}")) == 2:
            break
        time.sleep(0.5)
    balance_after_retry = int(sql("hm-user", f"SELECT balance FROM user WHERE id={user_id}"))
    pay_after_retry = int(sql("hm-pay", f"SELECT status FROM pay_order WHERE id={pay_id}"))
    outbox_after_retry = int(sql("hm-pay", f"SELECT COUNT(*) FROM pay_outbox WHERE pay_order_id={pay_id}"))
    assert balance_after_retry == balance_before - order["totalFee"]
    assert pay_after_retry == 3
    assert outbox_after_retry == 1
    print("PASS recovery: retry paid exactly once; one outbox event; RabbitMQ updated business order")
finally:
    if fixture_outbox and pay_id is not None:
        sql("hm-pay", f"DELETE FROM pay_outbox WHERE pay_order_id={pay_id}")
    if item_id is not None:
        try:
            session.delete(BASE + f"/items/{item_id}", timeout=15)
        except requests.RequestException:
            pass
