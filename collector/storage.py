"""SQLite time-series storage and rolling burn-rate analytics for AI Quota Monitor."""
import datetime
import os
import sqlite3
from typing import Any, Dict, List, Optional, Tuple

from collector.pace import (
    calculate_burn_rate_per_hour,
    calculate_elapsed_percent,
    calculate_projected_remaining,
    parse_iso_datetime,
)

DEFAULT_DB_PATH = os.path.expanduser("~/.local/share/ai-quota-monitor/history.db")


class QuotaStorage:
    """Manages SQLite storage of historical bucket readings and computes burn rates."""

    def __init__(self, db_path: Optional[str] = None):
        self.db_path = db_path or DEFAULT_DB_PATH
        self._memory_conn: Optional[sqlite3.Connection] = None

        if self.db_path == ":memory:":
            self._memory_conn = sqlite3.connect(":memory:", check_same_thread=False)
            self._memory_conn.row_factory = sqlite3.Row
        else:
            parent_dir = os.path.dirname(self.db_path)
            if parent_dir and not os.path.exists(parent_dir):
                os.makedirs(parent_dir, mode=0o700, exist_ok=True)

        self._init_db()

    def _get_connection(self) -> sqlite3.Connection:
        if self._memory_conn is not None:
            return self._memory_conn
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self) -> None:
        with self._get_connection() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS readings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    captured_at TEXT NOT NULL,
                    bucket_id TEXT NOT NULL,
                    remaining_percent REAL NOT NULL,
                    used_percent REAL NOT NULL,
                    window_duration_hours REAL,
                    resets_at TEXT
                );
                """
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_readings_bucket_captured ON readings(bucket_id, captured_at);"
            )
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_readings_captured ON readings(captured_at);"
            )

        if self.db_path != ":memory:" and os.path.exists(self.db_path):
            try:
                os.chmod(self.db_path, 0o600)
            except OSError:
                pass

    def record_readings(
        self,
        readings: List[Dict[str, Any]],
        captured_at: Optional[str] = None,
    ) -> None:
        """Insert timestamped bucket readings into SQLite."""
        if not readings:
            return

        timestamp = captured_at or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

        rows = []
        for r in readings:
            bucket_id = r.get("id")
            if not bucket_id:
                continue
            rows.append((
                timestamp,
                bucket_id,
                float(r.get("remainingPercent", 0.0)),
                float(r.get("usedPercent", 0.0)),
                float(r["windowDurationHours"]) if r.get("windowDurationHours") is not None else None,
                r.get("resetsAt"),
            ))

        with self._get_connection() as conn:
            conn.executemany(
                """
                INSERT INTO readings (
                    captured_at, bucket_id, remaining_percent, used_percent, window_duration_hours, resets_at
                ) VALUES (?, ?, ?, ?, ?, ?);
                """,
                rows,
            )

    def get_burn_rate_and_projection(
        self,
        bucket_id: str,
        current_used: float,
        current_remaining: float,
        captured_at_str: str,
        resets_at_str: Optional[str] = None,
        window_hours: Optional[float] = None,
    ) -> Tuple[float, float]:
        """Compute rolling burnRatePerHour and projectedRemainingAtReset.
        
        Uses historical readings in SQLite if available. If insufficient history,
        falls back to uniform window-average burn rate.
        """
        captured_dt = parse_iso_datetime(captured_at_str) or datetime.datetime.now(datetime.timezone.utc)
        resets_dt = parse_iso_datetime(resets_at_str) if resets_at_str else None

        # Calculate time remaining until reset
        hours_to_reset = 0.0
        if resets_dt:
            sec_rem = (resets_dt - captured_dt).total_seconds()
            hours_to_reset = max(0.0, sec_rem / 3600.0)

        # Elapsed hours in window
        elapsed_hours = 0.0
        if window_hours and window_hours > 0 and resets_dt:
            elapsed_sec = (window_hours * 3600.0) - (resets_dt - captured_dt).total_seconds()
            elapsed_hours = max(0.0, elapsed_sec / 3600.0)

        # Try to find historical reading from SQLite (e.g. between 15m and 6h ago)
        delta_used: Optional[float] = None
        delta_hours: Optional[float] = None

        with self._get_connection() as conn:
            cur = conn.execute(
                """
                SELECT captured_at, used_percent
                FROM readings
                WHERE bucket_id = ? AND captured_at < ?
                ORDER BY captured_at DESC
                LIMIT 20;
                """,
                (bucket_id, captured_at_str),
            )
            rows = cur.fetchall()

        if rows:
            # Pick a prior reading with suitable delta (ideally >= 0.05h / 3m)
            for row in rows:
                prior_dt = parse_iso_datetime(row["captured_at"])
                if not prior_dt:
                    continue
                diff_sec = (captured_dt - prior_dt).total_seconds()
                h = diff_sec / 3600.0
                if h >= 0.05:
                    prior_used = float(row["used_percent"])
                    if current_used >= prior_used:
                        delta_used = current_used - prior_used
                        delta_hours = h
                        break

        burn_rate = calculate_burn_rate_per_hour(
            used_percent=current_used,
            elapsed_hours=elapsed_hours,
            delta_used=delta_used,
            delta_hours=delta_hours,
        )

        projected = calculate_projected_remaining(
            remaining_percent=current_remaining,
            burn_rate_per_hour=burn_rate,
            hours_to_reset=hours_to_reset,
        )

        return burn_rate, projected

    def get_history(self, limit: int = 50) -> List[Dict[str, Any]]:
        """Retrieve recent historical snapshots grouped by capturedAt."""
        with self._get_connection() as conn:
            # Get distinct recent captured_at timestamps
            cur = conn.execute(
                """
                SELECT DISTINCT captured_at
                FROM readings
                ORDER BY captured_at DESC
                LIMIT ?;
                """,
                (limit,),
            )
            timestamps = [row["captured_at"] for row in cur.fetchall()]

            if not timestamps:
                return []

            # Retrieve readings for these timestamps
            placeholders = ",".join("?" for _ in timestamps)
            cur = conn.execute(
                f"""
                SELECT captured_at, bucket_id, remaining_percent
                FROM readings
                WHERE captured_at IN ({placeholders})
                ORDER BY captured_at ASC;
                """,
                timestamps,
            )
            all_rows = cur.fetchall()

        history_map: Dict[str, Dict[str, float]] = {}
        for row in all_rows:
            ts = row["captured_at"]
            if ts not in history_map:
                history_map[ts] = {}
            history_map[ts][row["bucket_id"]] = float(row["remaining_percent"])

        # Format sorted chronologically
        result = [
            {"capturedAt": ts, "bucketRemaining": history_map[ts]}
            for ts in sorted(history_map.keys())
        ]
        return result
