#!/usr/bin/env python3
"""Copy the third-party notices into the APK so attribution ships with the app.

Several of the bundled components require their notice to appear in a place the *user* can reach
(SQLCipher's BSD licence in particular), not only in the repository. The app therefore ships
`THIRD_PARTY_NOTICES.md` as an asset and renders it in Settings → About → Open-source licences.

The asset is generated, never hand-edited: `--check` (run in CI) fails when the committed asset
differs from the repository's notices file, so the shipped attribution cannot drift.

Usage:
    python3 tools/build_licence_asset.py            # regenerate the asset
    python3 tools/build_licence_asset.py --check    # verify the asset is current (CI)
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "THIRD_PARTY_NOTICES.md"
ASSET = ROOT / "app" / "src" / "main" / "assets" / "licenses" / "THIRD_PARTY_NOTICES.md"

HEADER = """\
<!-- GENERATED FILE - DO NOT EDIT. Source: THIRD_PARTY_NOTICES.md (tools/build_licence_asset.py). -->
<!-- sha256:{digest} -->

"""


def render(source_text: str) -> str:
    digest = hashlib.sha256(source_text.encode("utf-8")).hexdigest()
    return HEADER.format(digest=digest) + source_text


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if the committed asset is not exactly what the generator would write",
    )
    args = parser.parse_args()

    if not SOURCE.exists():
        print(f"error: {SOURCE} is missing", file=sys.stderr)
        return 1

    expected = render(SOURCE.read_text(encoding="utf-8"))

    if args.check:
        if not ASSET.exists():
            print(f"error: {ASSET.relative_to(ROOT)} is missing; run the generator", file=sys.stderr)
            return 1
        if ASSET.read_text(encoding="utf-8") != expected:
            print(
                f"error: {ASSET.relative_to(ROOT)} is stale; run "
                "`python3 tools/build_licence_asset.py` and commit the result",
                file=sys.stderr,
            )
            return 1
        print(f"ok: in-app licence screen asset is current ({len(expected)} bytes)")
        return 0

    ASSET.parent.mkdir(parents=True, exist_ok=True)
    ASSET.write_text(expected, encoding="utf-8")
    print(f"wrote {ASSET.relative_to(ROOT)} ({len(expected)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
