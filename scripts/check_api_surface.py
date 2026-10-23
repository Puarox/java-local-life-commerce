"""Fail if controller mappings disappear or a mapped controller becomes empty."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
mapping = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b")
controllers = sorted(ROOT.rglob("*Controller.java"))
total = 0
errors = []
for controller in controllers:
    if "target" in controller.parts:
        continue
    source = controller.read_text(encoding="utf-8")
    count = len(mapping.findall(source))
    total += count
    if "@RestController" in source and "@RequestMapping" not in source:
        errors.append(f"{controller.relative_to(ROOT)}: missing class-level @RequestMapping")
    if controller.name != "BlogCommentsController.java" and "@RestController" in source and count == 0:
        errors.append(f"{controller.relative_to(ROOT)}: controller has no HTTP mapping")
    print(f"{count:2}  {controller.relative_to(ROOT)}")

print(f"HTTP mappings: {total}")
if total != 56:
    errors.append(f"expected 56 known mappings, found {total}; review docs/verification/api-test-matrix.md")
if errors:
    raise SystemExit("\n".join(errors))
