#!/usr/bin/env python3
"""Checks that the Edge Functions only name tables, columns and RPC arguments that exist.

The functions talk to the database through supabase-js with string literals, so a misspelt or
renamed column is not a type error: the query fails at run time, the code sees `data === null`,
and - as happened twice in this repository - quietly takes the "not found" branch. This script
reads every `supabase/functions/**/*.ts`, extracts the `.from("table")` chains and `.rpc("fn", {…})`
calls, and compares them with a migrated database.

Usage (the database must already have the migrations applied):

    python3 tools/check_edge_function_schema.py --database-url "$DATABASE_URL" [--psql psql]

Exit status 1 lists every unknown table, column, function or argument.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FUNCTIONS = ROOT / "supabase" / "functions"

FILTERS = ("eq", "neq", "gt", "gte", "lt", "lte", "like", "ilike", "is", "in", "contains", "order")
FROM_RE = re.compile(r"""\.from\(\s*["'`]([A-Za-z_][A-Za-z0-9_]*)["'`]\s*\)""")
RPC_RE = re.compile(r"""\.rpc\(\s*["'`]([A-Za-z_][A-Za-z0-9_]*)["'`]\s*(,)?""")
SELECT_RE = re.compile(r"""\.select\(\s*["'`]([^"'`]*)["'`]""")
FILTER_RE = re.compile(r"""\.(%s)\(\s*["'`]([A-Za-z_][A-Za-z0-9_]*)["'`]""" % "|".join(FILTERS))
OR_RE = re.compile(r"""\.or\(\s*[`"']([^`"']*)[`"']""")
OR_TERM_RE = re.compile(r"""(?:^|[,(])\s*([A-Za-z_][A-Za-z0-9_]*)\.(?:%s)\.""" % "|".join(FILTERS))
WRITE_RE = re.compile(r"""\.(insert|update|upsert)\(\s*\{""")


def query(psql: str, url: str, sql: str) -> list[list[str]]:
    out = subprocess.run(
        [psql, url, "-X", "-A", "-t", "-F", "\t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        check=True, capture_output=True, text=True,
    ).stdout
    return [line.split("\t") for line in out.splitlines() if line.strip()]


def strip_comments(source: str) -> str:
    """Blanks out // and /* */ comments while keeping offsets (and therefore line numbers)."""
    def blank(match: re.Match[str]) -> str:
        text = match.group(0)
        if text.startswith(("'", '"', "`")):
            return text
        return re.sub(r"[^\n]", " ", text)
    pattern = r"""("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`|//[^\n]*|/\*.*?\*/)"""
    return re.sub(pattern, blank, source, flags=re.S)


def balanced(source: str, start: int, open_ch: str, close_ch: str) -> int:
    """Index just after the bracket that closes the one at `start`."""
    depth, i, quote = 0, start, ""
    while i < len(source):
        ch = source[i]
        if quote:
            if ch == "\\":
                i += 1
            elif ch == quote:
                quote = ""
        elif ch in "\"'`":
            quote = ch
        elif ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return len(source)


def chain_end(source: str, start: int) -> int:
    """End of the statement that starts at `start` (first `;` outside brackets and strings)."""
    depth, i, quote = 0, start, ""
    while i < len(source):
        ch = source[i]
        if quote:
            if ch == "\\":
                i += 1
            elif ch == quote:
                quote = ""
        elif ch in "\"'`":
            quote = ch
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return i
            depth -= 1
        elif ch == ";" and depth == 0:
            return i
        i += 1
    return len(source)


def split_top_level(text: str) -> list[str]:
    parts, depth, current = [], 0, []
    for ch in text:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    parts.append("".join(current))
    return [p.strip() for p in parts if p.strip()]


def select_references(table: str, spec: str) -> list[tuple[str, str]]:
    """(table, column) pairs named by a PostgREST select list, following embedded resources."""
    refs: list[tuple[str, str]] = []
    for item in split_top_level(spec):
        embedded = re.match(r"^(?:[A-Za-z_][A-Za-z0-9_]*:)?([A-Za-z_][A-Za-z0-9_]*)(?:![A-Za-z_]+)*\((.*)\)$", item, re.S)
        if embedded:
            refs.append((embedded.group(1), "*"))
            refs.extend(select_references(embedded.group(1), embedded.group(2)))
            continue
        if item == "*":
            continue
        column = item.split(":")[-1].split("::")[0].split("->")[0].strip()
        refs.append((table, column))
    return refs


def object_keys(literal: str) -> list[str]:
    """Top-level property names of a `{ … }` literal (shorthand properties included)."""
    inner, depth, quote, flat = literal[1:-1], 0, "", []
    for ch in inner:
        if quote:
            flat.append(" ")
            if ch == quote:
                quote = ""
            continue
        if ch in "\"'`":
            quote = ch
            flat.append(" ")
        elif ch in "([{":
            depth += 1
            flat.append(" ")
        elif ch in ")]}":
            depth -= 1
            flat.append(" ")
        else:
            flat.append(ch if depth == 0 else " ")
    keys = []
    for part in "".join(flat).split(","):
        name = part.split(":")[0].strip()
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            keys.append(name)
    return keys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--psql", default="psql")
    args = parser.parse_args()

    columns: dict[str, set[str]] = {}
    for table, column in query(
        args.psql, args.database_url,
        "select table_name, column_name from information_schema.columns where table_schema = 'public'",
    ):
        columns.setdefault(table, set()).add(column)
    functions: dict[str, list[set[str]]] = {}
    for name, argnames in query(
        args.psql, args.database_url,
        "select p.proname, coalesce(array_to_string(p.proargnames, ','), '') from pg_proc p "
        "join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public'",
    ):
        functions.setdefault(name, []).append({a for a in argnames.split(",") if a})

    problems: list[str] = []
    checked = 0
    sources = sorted(FUNCTIONS.rglob("*.ts"))
    if not sources:
        print("no Edge Function sources found", file=sys.stderr)
        return 1

    for path in sources:
        source = strip_comments(path.read_text(encoding="utf-8"))
        rel = path.relative_to(ROOT)

        def where(offset: int) -> str:
            return f"{rel}:{source.count(chr(10), 0, offset) + 1}"

        def need(table: str, column: str, offset: int) -> None:
            nonlocal checked
            checked += 1
            if table not in columns:
                problems.append(f"{where(offset)}: table public.{table} does not exist")
            elif column != "*" and column not in columns[table]:
                problems.append(f"{where(offset)}: column {table}.{column} does not exist")

        for match in FROM_RE.finditer(source):
            table = match.group(1)
            if not table.startswith("mm_"):
                continue  # storage buckets (`storage.from("mm-media")` never matches: hyphen)
            chain = source[match.end():chain_end(source, match.end())]
            base = match.end()
            need(table, "*", match.start())
            for sel in SELECT_RE.finditer(chain):
                for ref_table, ref_column in select_references(table, sel.group(1)):
                    need(ref_table, ref_column, base + sel.start())
            for flt in FILTER_RE.finditer(chain):
                need(table, flt.group(2), base + flt.start())
            for either in OR_RE.finditer(chain):
                for term in OR_TERM_RE.finditer(either.group(1)):
                    need(table, term.group(1), base + either.start())
            for write in WRITE_RE.finditer(chain):
                brace = write.end() - 1
                literal = chain[brace:balanced(chain, brace, "{", "}")]
                for key in object_keys(literal):
                    need(table, key, base + write.start())

        for match in RPC_RE.finditer(source):
            name = match.group(1)
            checked += 1
            if name not in functions:
                problems.append(f"{where(match.start())}: function public.{name} does not exist")
                continue
            if not match.group(2):
                continue
            rest = source[match.end():]
            brace = len(rest) - len(rest.lstrip())
            if not rest[brace:brace + 1] == "{":
                continue
            literal = rest[brace:balanced(rest, brace, "{", "}")]
            given = set(object_keys(literal))
            if not any(given <= overload for overload in functions[name]):
                unknown = sorted(given - set().union(*functions[name]))
                problems.append(
                    f"{where(match.start())}: function public.{name} has no argument(s) "
                    f"{', '.join(unknown) or sorted(given)}"
                )

    if problems:
        print("Edge Function / schema mismatches:", file=sys.stderr)
        for problem in problems:
            print(f"  ✗ {problem}", file=sys.stderr)
        return 1
    print(f"Edge Functions reference only existing tables, columns and RPC arguments "
          f"({checked} references in {len(sources)} files).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
