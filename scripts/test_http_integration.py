"""Exercise every public controller route against the isolated local stack.

Requires the sample SQL, Jack/123 seed account, and all seven services.
This test writes disposable products, shops, blogs, vouchers and orders to the
isolated databases. It must never target a production Gateway.
"""

import base64
import os
import subprocess
import time
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import quote

import requests


BASE = os.getenv("API_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
if BASE not in ("http://127.0.0.1:8080", "http://localhost:8080"):
    raise SystemExit("Integration test only permits the loopback Gateway")
session = requests.Session()
results = []


def demo_balance():
    mysql = os.getenv("MYSQL_CLIENT") or str(Path(os.getenv("ProgramFiles", "")) /
                 "MySQL/MySQL Server 8.0/bin/mysql.exe")
    query = subprocess.run([mysql, "-h", "127.0.0.1", "-P", os.getenv("DB_PORT", "3307"),
                            "-u", os.getenv("DB_USERNAME", "root"), "-D", "hm-user", "-N",
                            "-e", "SELECT balance FROM user WHERE id=1"],
                           env={**os.environ, "MYSQL_PWD": os.getenv("DB_PASSWORD", "")},
                           capture_output=True, text=True, check=True)
    return int(query.stdout.strip())


def call(label, method, path, *, body=None, files=None, expected=200):
    response = session.request(method, BASE + path, json=body if files is None else None,
                               files=files, timeout=25)
    try:
        data = response.json()
    except ValueError:
        data = response.text
    success = response.status_code == expected
    if success and expected == 200 and isinstance(data, dict):
        success = data.get("success") is not False and data.get("code", 200) == 200
    results.append((label, method, path, response.status_code, success))
    if not success:
        raise AssertionError(f"{label} {method} {path}: HTTP {response.status_code}, body={str(data)[:300]}")
    return data.get("data") if isinstance(data, dict) and data.get("success") is True else data


try:
    login = call("user.login", "POST", "/users/login", body={"username": "Jack", "password": "123"})
    session.headers["authorization"] = login["token"]
    call("user.code", "POST", "/users/code?phone=13900000000")
    profile = call("user.profile", "GET", "/users/profile/1")
    call("user.updateProfile", "PUT", "/users/me/profile", body={
        "nickName": profile["nickName"], "icon": profile.get("icon") or ""})
    call("user.deduct", "PUT", "/users/money/deduct?pw=123&amount=1")
    addresses = call("address.list", "GET", "/addresses")
    call("address.one", "GET", f"/addresses/{addresses[0]['id']}")

    call("item.page", "GET", "/items/page?pageNo=1&pageSize=2")
    call("item.byIds", "GET", "/items?ids=1070841")
    call("item.one", "GET", "/items/1070841")
    marker = f"integration-{int(time.time())}"
    call("item.create", "POST", "/items", body={"name": marker, "price": 1299, "stock": 5,
                                                "status": 1, "brand": "测试", "category": "联调"})
    found = call("search.list", "GET", f"/search/list?key={marker}&pageNo=1&pageSize=2")
    item_id = found["list"][0]["id"]
    call("item.status", "PUT", f"/items/status/{item_id}/1")
    call("item.update", "PUT", "/items", body={"id": item_id, "price": 1399})
    call("item.deduct", "PUT", "/items/stock/deduct", body=[{"itemId": item_id, "num": 1}])
    call("cart.add", "POST", "/carts", body={"itemId": item_id, "name": "forged", "price": 1})
    carts = call("cart.list", "GET", "/carts")
    row = next(row for row in carts if str(row["itemId"]) == str(item_id))
    assert row["price"] == 1399, "cart accepted client-supplied price"
    call("cart.update", "PUT", "/carts", body={"id": row["id"], "num": 2, "price": 1})
    call("cart.deleteOne", "DELETE", f"/carts/{row['id']}")
    call("cart.addAgain", "POST", "/carts", body={"itemId": item_id})
    call("cart.deleteBatch", "DELETE", f"/carts?ids={item_id}")
    call("item.delete", "DELETE", f"/items/{item_id}")

    order_id = call("order.create", "POST", "/orders", body={"paymentType": 3,
                     "details": [{"itemId": 1070841, "num": 1}]})
    order = call("order.one", "GET", f"/orders/{order_id}")
    assert order["status"] == 1 and order["totalFee"] == 8300
    pay_id = call("pay.apply", "POST", "/pay-orders", body={"bizOrderNo": order_id,
                  "amount": order["totalFee"], "payChannelCode": "balance", "payType": 5,
                  "orderInfo": "integration"})
    call("pay.list", "GET", "/pay-orders")
    bad_payment = call("pay.wrongPassword", "POST", f"/pay-orders/{pay_id}",
                       body={"pw": "wrong-test-password"}, expected=400)
    assert bad_payment["msg"] == "余额支付失败，请检查支付密码和账户余额"
    call("pay.balance", "POST", f"/pay-orders/{pay_id}", body={"pw": "123"})
    for _ in range(10):
        paid = session.get(BASE + f"/orders/{order_id}", timeout=10).json()
        if paid["status"] == 2:
            break
        time.sleep(0.3)
    assert paid["status"] == 2, "RabbitMQ did not update the trade order"

    shop = call("shop.one", "GET", "/shop/1")
    shop_id = call("shop.create", "POST", "/shop", body={"name": marker, "typeId": 1,
                   "images": "/imgs/icons/default-icon.png", "address": "联调测试路 1 号",
                   "x": 120.1, "y": 30.2, "sold": 0, "comments": 0, "score": 40})
    call("shop.update", "PUT", "/shop", body={"id": shop_id, "name": marker + "-updated"})
    call("shop.type", "GET", "/shop/of/type?typeId=1&current=1")
    call("shop.name", "GET", f"/shop/of/name?name={marker}")
    call("shopType.list", "GET", "/shop-type/list")

    blog_id = call("blog.create", "POST", "/blog", body={"shopId": shop_id,
                   "title": "联调探店记录", "images": "/imgs/icons/default-icon.png",
                   "content": "真实接口联调创建的测试笔记"})
    call("blog.like", "PUT", f"/blog/like/{blog_id}")
    call("blog.mine", "GET", "/blog/of/me?current=1")
    call("blog.hot", "GET", "/blog/hot?current=1")
    call("blog.one", "GET", f"/blog/{blog_id}")
    call("blog.likes", "GET", f"/blog/likes/{blog_id}")
    call("blog.byUser", "GET", "/blog/of/user?id=1&current=1")
    shop_blogs = call("blog.byShop", "GET", f"/blog/of/shop/{shop_id}")
    assert any(str(blog["id"]) == str(blog_id) for blog in shop_blogs)
    call("follow.add", "PUT", "/follow/2/true")
    call("follow.status", "GET", "/follow/or/not/2")
    call("follow.common", "GET", "/follow/common/2")
    call("blog.feed", "GET", "/blog/of/follow?offset=0")
    call("follow.remove", "PUT", "/follow/2/false")

    comment_id = call("comment.add", "POST", "/blog-comments", body={"blogId": blog_id,
                      "content": "联调评论"})
    comments = call("comment.list", "GET", f"/blog-comments/{blog_id}")
    assert any(str(c["id"]) == str(comment_id) for c in comments)
    call("localUser.me", "GET", "/user/me")
    call("localUser.profile", "GET", "/user/1")
    user_info = call("localUser.info", "GET", "/user/info/1")
    assert str(user_info["id"]) == "1" and str(user_info["userId"]) == "1"
    assert "account" not in user_info and "localLife" not in user_info
    call("localUser.updateInfo", "PUT", "/user/info", body={
        "city": user_info.get("city") or "", "introduce": user_info.get("introduce") or "",
        "gender": user_info.get("gender"), "birthday": user_info.get("birthday")})
    call("localUser.sign", "POST", "/user/sign")
    call("localUser.signCount", "GET", "/user/sign/count")
    call("localUser.logout", "POST", "/user/logout")

    normal_voucher_id = call("voucher.normal", "POST", "/voucher", body={"shopId": shop_id,
         "title": "联调普通券", "payValue": 100, "actualValue": 200, "type": 0})
    now = datetime.now()
    voucher_id = call("voucher.seckill", "POST", "/voucher/seckill", body={"shopId": shop_id,
         "title": "联调秒杀券", "payValue": 100, "actualValue": 200, "type": 1,
         "stock": 2, "beginTime": (now - timedelta(minutes=5)).isoformat(timespec="seconds"),
         "endTime": (now + timedelta(minutes=30)).isoformat(timespec="seconds")})
    call("voucher.list", "GET", f"/voucher/list/{shop_id}")
    active = call("voucher.active", "GET", "/voucher/active")
    assert any(str(row["id"]) == str(voucher_id) for row in active)
    normal_order_id = call("voucherOrder.buy", "POST", f"/voucher-order/buy/{normal_voucher_id}")
    voucher_order_id = call("voucherOrder.seckill", "POST", f"/voucher-order/seckill/{voucher_id}")
    my_vouchers = call("voucherOrder.me", "GET", "/voucher-order/me?current=1")
    assert any(str(row["id"]) == str(voucher_order_id) for row in my_vouchers)
    summary = call("voucherOrder.paymentSummary", "GET", f"/voucher-order/payment/{voucher_order_id}")
    assert summary["status"] == 1 and summary["totalFee"] == 100
    for label, voucher_order in (("regular", normal_order_id), ("seckill", voucher_order_id)):
        voucher_pay_id = call(f"voucherPay.apply.{label}", "POST", f"/pay-orders/voucher/{voucher_order}")
        call(f"voucherPay.balance.{label}", "POST", f"/pay-orders/{voucher_pay_id}", body={"pw": "123"})
        for _ in range(12):
            my_vouchers = session.get(BASE + "/voucher-order/me?current=1", timeout=10).json()["data"]
            if any(str(row["id"]) == str(voucher_order) and row["status"] == 2 for row in my_vouchers):
                break
            time.sleep(0.3)
        assert any(str(row["id"]) == str(voucher_order) and row["status"] == 2
                   for row in my_vouchers), f"RabbitMQ did not update {label} voucher order"
    paid_regular = next(row for row in my_vouchers if str(row["id"]) == str(normal_order_id))
    paid_seckill = next(row for row in my_vouchers if str(row["id"]) == str(voucher_order_id))
    assert len(paid_regular["redeemCode"]) == 10 and len(paid_seckill["redeemCode"]) == 10
    call("voucherOrder.merchantPermission", "GET", "/voucher-order/merchant/permission")
    call("voucherOrder.redeem", "POST", f'/voucher-order/redeem?code={paid_seckill["redeemCode"]}')
    call("voucherOrder.redeemAgain", "POST", f'/voucher-order/redeem?code={paid_seckill["redeemCode"]}', expected=400)
    call("voucherPay.refundUsed", "POST", f"/pay-orders/voucher/{voucher_order_id}/refund", expected=400)
    balance_before_refund = demo_balance()
    call("voucherPay.refund", "POST", f"/pay-orders/voucher/{normal_order_id}/refund")
    assert demo_balance() == balance_before_refund + 100, "refund did not restore balance"
    call("voucherPay.refundAgain", "POST", f"/pay-orders/voucher/{normal_order_id}/refund", expected=400)
    after_fulfillment = call("voucherOrder.afterFulfillment", "GET", "/voucher-order/me?current=1")
    assert next(row for row in after_fulfillment if str(row["id"]) == str(voucher_order_id))["status"] == 3
    refunded = next(row for row in after_fulfillment if str(row["id"]) == str(normal_order_id))
    assert refunded["status"] == 6 and "redeemCode" not in refunded

    cancel_voucher_id = call("voucher.cancelFixture", "POST", "/voucher", body={"shopId": shop_id,
         "title": "联调取消券", "payValue": 100, "actualValue": 200, "type": 0})
    cancel_order_id = call("voucherOrder.buyForCancel", "POST", f"/voucher-order/buy/{cancel_voucher_id}")
    call("voucherPay.applyForCancel", "POST", f"/pay-orders/voucher/{cancel_order_id}")
    call("voucherOrder.cancel", "POST", f"/voucher-order/{cancel_order_id}/cancel")
    call("voucherOrder.cancelAgain", "POST", f"/voucher-order/{cancel_order_id}/cancel", expected=400)
    call("voucherPay.applyCanceled", "POST", f"/pay-orders/voucher/{cancel_order_id}", expected=400)
    canceled = call("voucherOrder.afterCancel", "GET", "/voucher-order/me?current=1")
    assert next(row for row in canceled if str(row["id"]) == str(cancel_order_id))["status"] == 4
    call("voucher.hideTestCancel", "PUT", f"/voucher/{cancel_voucher_id}/status?status=2")
    call("voucher.hideTestActivity", "PUT", f"/voucher/{voucher_id}/status?status=2")
    call("voucher.hideTestRegular", "PUT", f"/voucher/{normal_voucher_id}/status?status=2")

    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lWsAAAAASUVORK5CYII=")
    image_name = call("upload.add", "POST", "/upload/blog", files={"file": ("test.png", png, "image/png")})
    call("upload.delete", "GET", f"/upload/blog/delete?name={quote(image_name)}")
except Exception:
    for label, method, path, status, ok in results:
        print(f"{'PASS' if ok else 'FAIL'} {label:24} {method:6} {path} -> {status}")
    raise

for label, method, path, status, ok in results:
    print(f"PASS {label:24} {method:6} {path} -> {status}")
print(f"PASS {len(results)} live route calls; order paid through RabbitMQ")
