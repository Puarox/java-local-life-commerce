"""Headless browser smoke test for the copied static frontend."""
import os
from playwright.sync_api import sync_playwright
from pathlib import Path

BASE_URL = os.getenv("FRONTEND_URL", "http://127.0.0.1:8090")
ROUTES = [
    "/", "/login.html", "/login2.html", "/index.html", "/shop-list.html",
    "/shop-detail.html", "/blog-detail.html", "/blog-edit.html", "/info.html",
    "/info-edit.html", "/other-info.html",
]
EDGE = os.getenv("EDGE_EXECUTABLE")
if not EDGE:
    for candidate in (
        Path(os.getenv("PROGRAMFILES(X86)", r"C:\Program Files (x86)")) / "Microsoft/Edge/Application/msedge.exe",
        Path(os.getenv("PROGRAMFILES", r"C:\Program Files")) / "Microsoft/Edge/Application/msedge.exe",
    ):
        if candidate.is_file():
            EDGE = str(candidate)
            break

with sync_playwright() as playwright:
    launch = {"headless": True}
    if EDGE:
        launch["executable_path"] = EDGE
    browser = playwright.chromium.launch(**launch)
    failures = []
    for route in ROUTES:
        page = browser.new_page()
        page.on("pageerror", lambda error, route=route: failures.append(f"{route}: {error}"))
        response = page.goto(BASE_URL + route, wait_until="networkidle", timeout=30000)
        if response is None or response.status != 200:
            failures.append(f"{route}: HTTP {response.status if response else 'no response'}")
        else:
            print(f"PASS {route} HTTP {response.status} title={page.title()!r}")
        page.close()
    browser.close()

if failures:
    print("\nBrowser errors:")
    print("\n".join(failures))
    raise SystemExit(1)
