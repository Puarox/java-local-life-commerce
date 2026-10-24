"""Create one live shop-1 voucher campaign in the local development database.

Uses the existing admin demo account and Gateway API. It never targets a remote URL.
Set DEMO_LOGIN_PASSWORD before running; the script prints no credential or token.
"""
import json
import os
from datetime import datetime, timedelta
from urllib.request import Request, urlopen

BASE = "http://127.0.0.1:8080"
TITLE = "到店体验券 · 演示活动"


def call(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["authorization"] = token
    request = Request(BASE + path, method=method, headers=headers,
                      data=json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None)
    with urlopen(request, timeout=10) as response:
        result = json.loads(response.read().decode("utf-8"))
    if isinstance(result, dict) and result.get("success") is False:
        raise RuntimeError(result.get("errorMsg") or "请求失败")
    return result.get("data") if isinstance(result, dict) and "success" in result else result


password = os.getenv("DEMO_LOGIN_PASSWORD")
if not password:
    raise SystemExit("请先设置 DEMO_LOGIN_PASSWORD 为本地演示账号密码")
account = call("POST", "/users/login", {"username": os.getenv("DEMO_LOGIN_USERNAME", "Jack"), "password": password})
token = account["token"]
now = datetime.now()
existing = call("GET", "/voucher/list/1", token=token) or []
for voucher in existing:
    if (voucher.get("title") == TITLE and voucher.get("type") == 1
            and voucher.get("stock", 0) > 0
            and voucher.get("endTime")
            and datetime.fromisoformat(voucher["endTime"]) > now):
        print(f"演示活动已存在：券 ID {voucher['id']}")
        break
else:
    voucher_id = call("POST", "/voucher/seckill", {
        "shopId": 1, "title": TITLE, "subTitle": "限量 20 张，每人限抢一张",
        "rules": "到店使用；演示活动不作为真实消费凭证",
        "payValue": 100, "actualValue": 5000, "type": 1, "status": 1, "stock": 20,
        "beginTime": (now - timedelta(minutes=5)).isoformat(timespec="seconds"),
        "endTime": (now + timedelta(days=14)).isoformat(timespec="seconds"),
    }, token=token)
    print(f"已创建演示活动：券 ID {voucher_id}，14 天内有效")
