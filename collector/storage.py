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


def _format_iso(moment: datetime.datetime) -> str:
    """Format an aware datetime as a compact UTC ISO-8601 string."""
    return moment.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


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

    def get_activity_summary(self, now_iso: Optional[str] = None) -> Dict[str, Any]:
        """Aggregate historical readings into fixed-window activity bins.

        Returns four windows sized for the Android activity board:

        * ``window5h``: 20 x 15-minute bins covering the last 5 hours.
        * ``window24h``: 24 x 1-hour bins covering the last 24 hours.
        * ``window7d``: 28 x 6-hour bins covering the last 7 days.
        * ``window30d``: 35 x 1-day bins covering 5 Monday-aligned weeks
          through the current week.

        Each bin is ``{"startAt", "endAt", "deltas", "wallHits", "resets"}``.
        Deltas are positive ``used_percent`` movements between consecutive
        readings (rounded to one decimal and zero entries omitted); ``wallHits``
        flags buckets observed at or below 10% remaining; ``resets`` flags a
        drop of at least 10 percentage points or a reset timestamp moving
        forward by at least one hour.
        """
        now_dt = parse_iso_datetime(now_iso) if now_iso else None
        if now_dt is None:
            now_dt = datetime.datetime.now(datetime.timezone.utc)

        # Monday 00:00 UTC of the current week, then four weeks back gives five
        # full Monday..Sunday rows (35 daily bins) through the current week.
        current_monday = (now_dt - datetime.timedelta(days=now_dt.weekday())).replace(
            hour=0, minute=0, second=0, microsecond=0
        )

        window_specs = [
            ("window5h", now_dt - datetime.timedelta(hours=5), 20, datetime.timedelta(minutes=15)),
            ("window24h", now_dt - datetime.timedelta(hours=24), 24, datetime.timedelta(hours=1)),
            ("window7d", now_dt - datetime.timedelta(days=7), 28, datetime.timedelta(hours=6)),
            ("window30d", current_monday - datetime.timedelta(days=28), 35, datetime.timedelta(days=1)),
        ]

        bins_by_window: Dict[str, List[Dict[str, Any]]] = {}
        bin_ranges: Dict[str, List[Tuple[datetime.datetime, datetime.datetime]]] = {}
        delta_accum: Dict[str, List[Dict[str, float]]] = {}
        wall_hits: Dict[str, List[set]] = {}
        resets: Dict[str, List[set]] = {}

        for name, start, count, step in window_specs:
            bins: List[Dict[str, Any]] = []
            ranges: List[Tuple[datetime.datetime, datetime.datetime]] = []
            for index in range(count):
                bin_start = start + step * index
                bin_end = start + step * (index + 1)
                ranges.append((bin_start, bin_end))
                bins.append({
                    "startAt": _format_iso(bin_start),
                    "endAt": _format_iso(bin_end),
                    "deltas": {},
                    "wallHits": [],
                    "resets": [],
                })
            bins_by_window[name] = bins
            bin_ranges[name] = ranges
            delta_accum[name] = [{} for _ in range(count)]
            wall_hits[name] = [set() for _ in range(count)]
            resets[name] = [set() for _ in range(count)]

        earliest_start = min(start for _, start, _, _ in window_specs)
        query_start = _format_iso(earliest_start - datetime.timedelta(days=7))

        with self._get_connection() as conn:
            cur = conn.execute(
                """
                SELECT captured_at, bucket_id, remaining_percent, used_percent, resets_at
                FROM readings
                WHERE captured_at >= ?
                ORDER BY captured_at ASC, bucket_id ASC;
                """,
                (query_start,),
            )
            rows = cur.fetchall()

        previous: Dict[str, Tuple[float, Optional[datetime.datetime]]] = {}

        for row in rows:
            captured_dt = parse_iso_datetime(row["captured_at"])
            if captured_dt is None:
                continue

            bucket_id = row["bucket_id"]
            used_percent = float(row["used_percent"])
            remaining_percent = float(row["remaining_percent"])
            resets_dt = parse_iso_datetime(row["resets_at"])

            prev_used: Optional[float] = None
            prev_resets: Optional[datetime.datetime] = None
            if bucket_id in previous:
                prev_used, prev_resets = previous[bucket_id]
            previous[bucket_id] = (used_percent, resets_dt)

            reset_event = False
            if prev_used is not None and used_percent <= prev_used - 10.0:
                reset_event = True
            if prev_resets is not None and resets_dt is not None:
                if (resets_dt - prev_resets).total_seconds() >= 3600.0:
                    reset_event = True

            for name, _, _, _ in window_specs:
                ranges = bin_ranges[name]
                last_index = len(ranges) - 1
                for index, (bin_start, bin_end) in enumerate(ranges):
                    if index == last_index and bin_end == now_dt:
                        in_bin = bin_start <= captured_dt <= bin_end
                    else:
                        in_bin = bin_start <= captured_dt < bin_end
                    if not in_bin:
                        continue
                    if prev_used is not None:
                        delta = used_percent - prev_used
                        if delta > 0.0:
                            delta_accum[name][index][bucket_id] = (
                                delta_accum[name][index].get(bucket_id, 0.0) + delta
                            )
                    if remaining_percent <= 10.0:
                        wall_hits[name][index].add(bucket_id)
                    if reset_event:
                        resets[name][index].add(bucket_id)
                    break

        for name, _, _, _ in window_specs:
            for index, bucket in enumerate(bins_by_window[name]):
                bucket["deltas"] = {
                    bid: round(value, 1)
                    for bid, value in sorted(delta_accum[name][index].items())
                    if round(value, 1) != 0.0
                }
                bucket["wallHits"] = sorted(wall_hits[name][index])
                bucket["resets"] = sorted(resets[name][index])

        return bins_by_window

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
