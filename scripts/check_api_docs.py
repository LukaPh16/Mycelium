#!/usr/bin/env python3
"""Every method MyceliumEngine.call() dispatches must appear in docs/api.md as call("<method>". Exit 1 otherwise."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
src = (ROOT / "engine/src/main/java/app/mycelium/engine/MyceliumEngine.kt").read_text()
doc = (ROOT / "docs/api.md").read_text()
body = src[src.index("fun call(method"):src.index("private fun vpnCall")]
methods = sorted(set(re.findall(r'^\s+"([a-z_]+)" ->', body, re.M)))
missing = [m for m in methods if f'call("{m}"' not in doc]
print(f"{len(methods)} methods dispatched, {len(missing)} undocumented" + (": " + ", ".join(missing) if missing else ""))
sys.exit(1 if missing else 0)
