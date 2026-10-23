"""Publish the checked-in development configs to a local Nacos instance."""

from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import os


ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("NACOS_HTTP_URL", "http://127.0.0.1:8848").rstrip("/")
for path in sorted((ROOT / "deploy" / "nacos").glob("*.yaml")):
    payload = urlencode({
        "dataId": path.name,
        "group": "DEFAULT_GROUP",
        "type": "yaml",
        "content": path.read_text(encoding="utf-8"),
    }).encode("utf-8")
    request = Request(f"{BASE}/nacos/v1/cs/configs", data=payload, method="POST")
    with urlopen(request, timeout=15) as response:
        result = response.read().decode("utf-8")
    if result.strip().lower() != "true":
        raise RuntimeError(f"Nacos rejected {path.name}: {result}")
    print(f"published {path.name}")
