"""Local Redis/Gateway smoke check for concurrent shop-cache misses."""

import os
import socket
import threading
from concurrent.futures import ThreadPoolExecutor

import requests


BASE = os.getenv("API_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
if BASE not in ("http://127.0.0.1:8080", "http://localhost:8080"):
    raise SystemExit("Cache concurrency smoke test only permits the loopback Gateway")

REDIS_HOST = os.getenv("REDIS_HOST", "127.0.0.1")
if REDIS_HOST not in ("127.0.0.1", "localhost"):
    raise SystemExit("Cache concurrency smoke test only permits loopback Redis")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
SHOP_ID = os.getenv("SHOP_ID", "1")
CALLERS = int(os.getenv("CACHE_TEST_CALLERS", "50"))


def redis_command(*parts):
    payload = f"*{len(parts)}\r\n" + "".join(
        f"${len(str(part).encode())}\r\n{part}\r\n" for part in parts
    )
    with socket.create_connection((REDIS_HOST, REDIS_PORT), timeout=3) as connection:
        connection.sendall(payload.encode())
        response = connection.recv(4096).decode(errors="replace")
    if response.startswith("-"):
        raise RuntimeError(response.strip())
    return response.strip()


cache_key = f"cache:shop:{SHOP_ID}"
lock_key = f"lock:shop:{SHOP_ID}"
redis_command("DEL", cache_key, lock_key)
barrier = threading.Barrier(CALLERS)


def get_shop(_):
    barrier.wait(timeout=10)
    response = requests.get(f"{BASE}/shop/{SHOP_ID}", timeout=15)
    response.raise_for_status()
    body = response.json()
    if not body.get("success") or str(body.get("data", {}).get("id")) != SHOP_ID:
        raise AssertionError(f"Unexpected shop response: {body}")
    return response.status_code


with ThreadPoolExecutor(max_workers=CALLERS) as pool:
    statuses = list(pool.map(get_shop, range(CALLERS)))

cache_exists = redis_command("EXISTS", cache_key).endswith("1")
lock_exists = redis_command("EXISTS", lock_key).endswith("1")
if not cache_exists:
    raise AssertionError("Shop cache was not populated")
if lock_exists:
    raise AssertionError("Shop cache lock remained after all requests completed")

print(f"PASS: {len(statuses)}/{CALLERS} shop responses succeeded; cache populated; lock released")
