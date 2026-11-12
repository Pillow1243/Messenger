#!/usr/bin/env python3
"""Runs the backend suite against a private, throw-away PostgreSQL instance.

`pip install pgserver` provides a self-contained PostgreSQL 16 build (no Docker, no system
packages), which is all this repository needs to execute the migrations and the RLS suite:

    python3 supabase/tests/local/embedded_pg.py

Everything happens in a temporary data directory that is deleted afterwards, so no state - and
in particular no Messenger or Luma data - can leak between runs.
"""
from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[3]


def _install_pgcrypto_stub_if_missing(pginstall: pathlib.Path) -> None:
    """The embedded build ships without contrib; the migrations only need `create extension
    pgcrypto` to succeed plus `gen_random_bytes()` for channel invite codes."""
    ext_dir = pginstall / "share" / "postgresql" / "extension"
    if (ext_dir / "pgcrypto.control").exists():
        return
    stub = ROOT / "supabase" / "tests" / "local" / "pgstub"
    for name in ("pgcrypto.control", "pgcrypto--1.3.sql"):
        shutil.copyfile(stub / name, ext_dir / name)
    print("installed pgcrypto test stub into", ext_dir)


def main() -> int:
    try:
        import pgserver  # type: ignore
    except ImportError:
        print(
            "pgserver is not installed.\n"
            "  pip install pgserver\n"
            "or run against an existing server with:\n"
            "  DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres "
            "supabase/tests/local/run.sh",
            file=sys.stderr,
        )
        return 2

    pginstall = pathlib.Path(pgserver.__file__).parent / "pginstall"
    _install_pgcrypto_stub_if_missing(pginstall)

    data_dir = tempfile.mkdtemp(prefix="mm-pg-")
    server = pgserver.get_server(data_dir, cleanup_mode="stop")
    try:
        bindir = pathlib.Path(pgserver.__file__).parent / "pginstall" / "bin"
        env = dict(os.environ)
        env["PATH"] = f"{bindir}{os.pathsep}{env.get('PATH', '')}"
        env["DATABASE_URL"] = server.get_uri()
        env["PSQL"] = str(bindir / "psql")
        return subprocess.call([str(ROOT / "supabase" / "tests" / "local" / "run.sh")], env=env)
    finally:
        try:
            server.cleanup()
        finally:
            shutil.rmtree(data_dir, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
