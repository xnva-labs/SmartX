"""Thin SQLite wrapper: per-thread connections, WAL, nested transactions."""
from __future__ import annotations

import os
import sqlite3
import threading
from contextlib import contextmanager
from typing import Any, Iterable, List, Optional, Sequence


class Database:
    def __init__(self, path: str, schema: Sequence[str], name: str):
        self.path, self.schema, self.name = path, list(schema), name
        self._local = threading.local()
        if path != ":memory:":
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)

    def conn(self) -> sqlite3.Connection:
        c = getattr(self._local, "c", None)
        if c is None:
            c = sqlite3.connect(self.path, isolation_level=None, timeout=10)
            c.row_factory = sqlite3.Row
            for pragma in (
                "PRAGMA journal_mode=WAL",
                "PRAGMA foreign_keys=ON",
                "PRAGMA synchronous=FULL",
                "PRAGMA busy_timeout=10000",
                "PRAGMA secure_delete=ON",
            ):
                c.execute(pragma)
            self._local.c = c
            self._local.depth = 0
        return c

    def close(self) -> None:
        c = getattr(self._local, "c", None)
        if c is not None:
            c.close()
            self._local.c = None

    def migrate(self) -> int:
        c = self.conn()
        c.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)")
        row = c.execute("SELECT MAX(version) AS v FROM schema_version").fetchone()
        current = row["v"] or 0
        for i, script in enumerate(self.schema, start=1):
            if i <= current:
                continue
            with self.tx():
                for stmt in script.split(";\n"):
                    if stmt.strip():
                        c.execute(stmt)
                c.execute("INSERT INTO schema_version(version) VALUES(?)", (i,))
        return len(self.schema)

    @contextmanager
    def tx(self):
        c = self.conn()
        depth = self._local.depth
        c.execute("BEGIN IMMEDIATE" if depth == 0 else f"SAVEPOINT sp{depth}")
        self._local.depth = depth + 1
        try:
            yield c
        except BaseException:
            self._local.depth = depth
            if depth == 0:
                c.execute("ROLLBACK")
            else:
                c.execute(f"ROLLBACK TO sp{depth}")
                c.execute(f"RELEASE sp{depth}")
            raise
        else:
            self._local.depth = depth
            c.execute("COMMIT" if depth == 0 else f"RELEASE sp{depth}")

    def one(self, sql: str, args: Iterable[Any] = ()) -> Optional[sqlite3.Row]:
        return self.conn().execute(sql, tuple(args)).fetchone()

    def all(self, sql: str, args: Iterable[Any] = ()) -> List[sqlite3.Row]:
        return self.conn().execute(sql, tuple(args)).fetchall()

    def run(self, sql: str, args: Iterable[Any] = ()) -> int:
        return self.conn().execute(sql, tuple(args)).rowcount

    def scalar(self, sql: str, args: Iterable[Any] = (), default: Any = None) -> Any:
        row = self.conn().execute(sql, tuple(args)).fetchone()
        return default if row is None or row[0] is None else row[0]

    def integrity_ok(self) -> bool:
        return self.conn().execute("PRAGMA integrity_check").fetchone()[0] == "ok"

    def backup_to(self, dest_path: str) -> None:
        os.makedirs(os.path.dirname(os.path.abspath(dest_path)), exist_ok=True)
        dest = sqlite3.connect(dest_path)
        try:
            self.conn().backup(dest)
        finally:
            dest.close()
