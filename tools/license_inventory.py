#!/usr/bin/env python3
"""Fails when a declared Gradle dependency has no entry in THIRD_PARTY_NOTICES.md.

The notices file is the attribution that has to ship with the APK, so it must list every
component the build declares - including ksp/test-only ones, because those are still part of the
build's supply chain. Run from the repository root:

    python3 tools/license_inventory.py
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUILD = ROOT / "app" / "build.gradle.kts"
NOTICES = ROOT / "THIRD_PARTY_NOTICES.md"

# `implementation("group:artifact:version")`, `ksp("g:a:v")`, `platform("g:a:v")`,
# `coreLibraryDesugaring("g:a:v")` - the configuration name does not change the obligation.
DEP = re.compile(r'(?:implementation|ksp|platform|testImplementation|testRuntimeOnly|'
                 r'androidTestImplementation|debugImplementation|coreLibraryDesugaring)'
                 r'\("([a-zA-Z0-9_.\-]+:[a-zA-Z0-9_.\-]+)(?::[^"]*)?"\)')

# Everything the build declares but that is not a runtime component of the APK.
TEST_ONLY = {
    "junit:junit",
    "org.jetbrains.kotlinx:kotlinx-coroutines-test",
    "com.squareup.okhttp3:mockwebserver",
    "org.signal:libsignal-client",
    "androidx.test.ext:junit",
    "androidx.test:runner",
    "androidx.room:room-testing",
    "androidx.compose.ui:ui-test-manifest",
}

# The Compose BOM is a version catalogue, not a component; each member is listed individually.
CATALOGUE_ONLY = {"androidx.compose:compose-bom"}


def declared() -> list[tuple[str, bool]]:
    text = BUILD.read_text()
    found: list[tuple[str, bool]] = []
    seen: set[str] = set()
    for match in DEP.finditer(text):
        coordinate = match.group(1)
        if coordinate in seen or coordinate in CATALOGUE_ONLY:
            continue
        seen.add(coordinate)
        found.append((coordinate, coordinate in TEST_ONLY))
    return sorted(found)


def main() -> int:
    notices = NOTICES.read_text()
    missing = []
    total = 0
    for coordinate, test_only in declared():
        total += 1
        if coordinate in notices:
            continue
        # Compose members are grouped in one row: "androidx.compose:compose-bom (ui, material3, ...)"
        if coordinate.startswith("androidx.compose:") and "androidx.compose:compose-bom" in notices:
            continue
        missing.append(f"{coordinate}{' (test only)' if test_only else ''}")

    print(f"checked {total} declared dependencies against {NOTICES.name}")
    if missing:
        for item in missing:
            print(f"::error::missing licence entry for {item}")
        return 1
    print("licence inventory complete")
    return 0


if __name__ == "__main__":
    sys.exit(main())
