"""Fail one real payment consumer update, then redrive its DLQ event after repair."""

import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

import requests


BASE = os.getenv("API_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
MGMT = os.getenv("RABBITMQ_MANAGEMENT_URL", "http://127.0.0.1:15672").rstrip("/")
if BASE not in ("http://127.0.0.1:8080", "http://localhost:8080"):
    raise SystemExit("DLQ replay test only permits the loopback Gateway")
if MGMT not in ("http://127.0.0.1:15672", "http://localhost:15672"):
    raise SystemExit("DLQ replay test only permits loopback RabbitMQ Management")
MYSQL = os.getenv("MYSQL_CLIENT") or shutil.which("mysql") or str(
    Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "MySQL/MySQL Server 8.0/bin/mysql.exe"
)
DB_PORT = os.getenv("DB_PORT", "3307")
VHOST = quote(os.getenv("RABBITMQ_VHOST", "/"), safe="")
QUEUE = "trade.pay.success.dlq"


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
    response = session.request(method, BASE + path, json=body, timeout=30)
    response.raise_for_status()
    payload = response.json() if response.content else None
    if isinstance(payload, dict) and payload.get("success") is False:
        raise AssertionError(f"{method} {path}: {payload}")
    return payload.get("data") if isinstance(payload, dict) and payload.get("success") is True else payload


management = requests.Session()
management.auth = (os.getenv("RABBITMQ_USER", "guest"), os.getenv("RABBITMQ_PASSWORD", "guest"))
management.headers["content-type"] = "application/json"
queue_url = f"{MGMT}/api/queues/{VHOST}/{QUEUE}"
queue_state = management.get(queue_url, timeout=10)
queue_state.raise_for_status()
ready_before = queue_state.json()["messages_ready"]
if ready_before:
    # Only clear synthetic test events left by the companion finite-retry test.
    prior = management.post(queue_url + "/get", json={
        "count": ready_before, "ackmode": "ack_requeue_false", "encoding": "auto", "truncate": 50000,
    }, timeout=10)
    prior.raise_for_status()
    prior_ids = [(message.get("properties") or {}).get("message_id", "") for message in prior.json()]
    if not all(message_id.startswith("dlq-test-") for message_id in prior_ids):
        raise SystemExit(f"Refusing to remove non-test DLQ messages: {prior_ids}")

account_session = requests.Session()
account = api(account_session, "POST", "/users/login", body={
    "username": "Jack", "password": os.getenv("DEMO_LOGIN_PASSWORD", "123")
})
account_session.headers["authorization"] = account["token"]
user_id = int(account["userId"])
marker = "dlq-replay-" + uuid.uuid4().hex[:10]
trigger = "test_dlq_" + uuid.uuid4().hex[:10]
item_id = None
trigger_created = False

try:
    balance_before = int(sql("hm-user", f"SELECT balance FROM user WHERE id={user_id}"))
    api(account_session, "POST", "/items", body={"name": marker, "price": 100, "stock": 1,
       "status": 1, "brand": "验证", "category": "隔离测试"})
    found = api(account_session, "GET", f"/search/list?key={marker}&pageNo=1&pageSize=10")
    item_id = int(found["list"][0]["id"])
    order_id = int(api(account_session, "POST", "/orders", body={"paymentType": 3,
                    "details": [{"itemId": item_id, "num": 1}]}))
    order = api(account_session, "GET", f"/orders/{order_id}")
    pay_id = int(api(account_session, "POST", "/pay-orders", body={
        "bizOrderNo": order_id, "amount": order["totalFee"], "payChannelCode": "balance",
        "payType": 5, "orderInfo": marker,
    }))

    # A temporary DB trigger makes only this short test window reject consumer updates.
    sql("hm-trade", f"CREATE TRIGGER `{trigger}` BEFORE UPDATE ON `order` FOR EACH ROW "
        "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='temporary DLQ replay verification failure'")
    trigger_created = True
    api(account_session, "POST", f"/pay-orders/{pay_id}", body={"pw": "123"})
    event_id = sql("hm-pay", f"SELECT id FROM pay_outbox WHERE pay_order_id={pay_id}")
    if not event_id:
        raise AssertionError("Payment outbox event was not created")

    deadline = time.monotonic() + 35
    dead_letter = None
    while time.monotonic() < deadline:
        state_response = management.get(queue_url, timeout=10)
        state_response.raise_for_status()
        ready = state_response.json()["messages_ready"]
        if ready:
            peek = management.post(queue_url + "/get", json={
                "count": ready, "ackmode": "ack_requeue_true", "encoding": "auto", "truncate": 50000,
            }, timeout=10)
            peek.raise_for_status()
            dead_letter = next((message for message in peek.json()
                                if (message.get("properties") or {}).get("message_id") == event_id), None)
            if dead_letter:
                break
        time.sleep(0.5)
    if dead_letter is None:
        raise AssertionError("Real payment notification did not reach the DLQ")
    x_death = (dead_letter.get("properties") or {}).get("headers", {}).get("x-death", [])
    if not x_death or x_death[0].get("reason") != "rejected":
        raise AssertionError(f"Expected rejected dead-letter provenance, got {x_death}")

    sql("hm-trade", f"DROP TRIGGER `{trigger}`")
    trigger_created = False
    subprocess.run([sys.executable, "scripts/replay_rabbitmq_dead_letter.py", QUEUE, event_id],
                   check=True, env=os.environ.copy())
    for _ in range(40):
        current = api(account_session, "GET", f"/orders/{order_id}")
        if current["status"] == 2:
            break
        time.sleep(0.5)
    balance_after = int(sql("hm-user", f"SELECT balance FROM user WHERE id={user_id}"))
    pay_status = int(sql("hm-pay", f"SELECT status FROM pay_order WHERE id={pay_id}"))
    queue_after = None
    for _ in range(40):
        response = management.get(queue_url, timeout=10)
        response.raise_for_status()
        queue_after = response.json()
        if queue_after["messages_ready"] == 0 and queue_after["messages_unacknowledged"] == 0:
            break
        probe = management.post(queue_url + "/get", json={
            "count": queue_after["messages_ready"], "ackmode": "ack_requeue_true",
            "encoding": "auto", "truncate": 50000,
        }, timeout=10)
        probe.raise_for_status()
        if not probe.json() and queue_after["messages_unacknowledged"] == 0:
            # RabbitMQ's management counters can lag an acknowledged message briefly.
            queue_after["messages_ready"] = 0
            break
        time.sleep(0.25)
    assert current["status"] == 2, "replayed notification did not update the business order"
    assert balance_after == balance_before - order["totalFee"], (balance_before, balance_after)
    assert pay_status == 3
    assert queue_after["messages_ready"] == 0, queue_after["messages_ready"]

    summary = {
        "date": datetime.now(timezone.utc).date().isoformat(),
        "scenario": "real outbox payment event failed in consumer, dead-lettered, then replayed after repair",
        "businessOrderId": order_id, "payOrderId": pay_id, "outboxEventId": event_id,
        "deadLetterProvenance": x_death[0], "failureInjection": "temporary trade DB trigger",
        "afterReplay": {"businessOrderStatus": current["status"], "payOrderStatus": pay_status,
                        "balanceDeductedExactlyOnce": balance_after == balance_before - order["totalFee"],
                        "dlqEmpty": queue_after["messages_ready"] == 0},
    }
    output = Path("docs/verification/results")
    output.mkdir(parents=True, exist_ok=True)
    path = output / f"rabbitmq-dlq-replay-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    print(f"raw: {path}")
finally:
    if trigger_created:
        sql("hm-trade", f"DROP TRIGGER IF EXISTS `{trigger}`")
    if item_id is not None:
        try:
            account_session.delete(BASE + f"/items/{item_id}", timeout=15)
        except requests.RequestException:
            pass
