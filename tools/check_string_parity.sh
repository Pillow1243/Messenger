#!/usr/bin/env bash
# Fails when the English and the Persian resource files disagree.
#
# Messenger ships two complete locales (values/ and values-fa/). A missing translation is a user
# facing bug - Persian users would see an English string, or worse, a placeholder crash - so the
# sets of string names must match exactly. Only names are compared: the values are different
# languages by definition.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

python3 - <<'PY'
import sys
import xml.etree.ElementTree as ET

PAIRS = [("app/src/main/res/values/strings.xml", "app/src/main/res/values-fa/strings.xml")]

def names(path):
    root = ET.parse(path).getroot()
    seen = {}
    for node in root.findall("string"):
        name = node.get("name")
        if name in seen:
            print(f"::error::{path}: duplicate string name '{name}'")
        seen[name] = (node.text or "")
    return seen

status = 0
for en_path, fa_path in PAIRS:
    en, fa = names(en_path), names(fa_path)
    missing_fa = sorted(set(en) - set(fa))
    missing_en = sorted(set(fa) - set(en))
    empty = sorted(n for n, v in en.items() if not v.strip())
    for name in missing_fa:
        print(f"::error::missing Persian translation for '{name}'")
    for name in missing_en:
        print(f"::error::missing English string for '{name}' (present in {fa_path})")
    for name in empty:
        print(f"::error::empty English string '{name}'")
    if missing_fa or missing_en or empty:
        status = 1
    else:
        print(f"string parity OK: {len(en)} keys in {en_path} and {fa_path}")
sys.exit(status)
PY
