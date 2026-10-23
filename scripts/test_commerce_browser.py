"""Browser checkout against a running isolated stack; mutates the test database."""

import os
import re
import json
from pathlib import Path
from playwright.sync_api import sync_playwright


BASE = os.getenv("FRONTEND_URL", "http://127.0.0.1:8090").rstrip("/")
USERNAME = os.environ["TEST_USERNAME"]
PASSWORD = os.environ["TEST_PASSWORD"]
SEARCH = os.getenv("TEST_SEARCH", "安佳Anchor")
EDGE = os.getenv("EDGE_EXECUTABLE", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
SCREENSHOT = Path("reports/screenshots/commerce-checkout.png")
SCREENSHOT.parent.mkdir(parents=True, exist_ok=True)

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True, executable_path=EDGE)
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    errors = []
    pay_responses = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.on("response", lambda response: pay_responses.append((response.status, response.url)) if "/api/pay-orders" in response.url else None)
    login = page.request.post(f"{BASE}/api/users/login", data={"username": USERNAME, "password": PASSWORD})
    assert login.status == 200, f"login HTTP {login.status}: {login.text()[:200]}"
    token = login.json()["token"]
    page.add_init_script(script=f"sessionStorage.setItem('token', {json.dumps(token)})")
    response = page.goto(f"{BASE}/commerce.html", wait_until="domcontentloaded")
    assert response.status == 200
    page.locator(".goods-card").first.wait_for(timeout=30000)
    page.locator("#search-input").fill(SEARCH)
    page.get_by_role("button", name="查找商品").click()
    page.locator(".goods-card").first.wait_for(timeout=30000)
    assert page.locator(".goods-card").count() > 0
    page.locator(".goods-card").first.get_by_role("button", name="放入购物车").click()
    page.locator(".cart-row").first.wait_for(timeout=15000)
    page.get_by_role("button", name="创建订单").click()
    page.locator("#payment:visible").wait_for(timeout=20000)
    order_text = page.locator("#order-number").inner_text()
    order_id = re.search(r"订单 (\d+)", order_text).group(1)
    page.locator("#pay-password").fill(PASSWORD)
    page.get_by_role("button", name="确认余额支付").click()
    try:
        page.locator("#payment").wait_for(state="hidden", timeout=20000)
    except Exception:
        page.screenshot(path=str(SCREENSHOT), full_page=True)
        raise AssertionError(f"payment stayed open: {page.locator('#notice').inner_text()}, responses={pay_responses}")
    order = page.request.get(f"{BASE}/api/orders/{order_id}", headers={"authorization": token})
    assert order.status == 200, f"order HTTP {order.status}"
    assert order.json()["status"] == 2, f"order not paid: {order.text()[:200]}"
    assert not errors, f"browser errors: {errors}"
    page.screenshot(path=str(SCREENSHOT), full_page=True)
    print(f"PASS browser: search -> cart -> order {order_id} -> balance pay -> status 2")
    print(f"Screenshot: {SCREENSHOT.resolve()}")
    browser.close()
