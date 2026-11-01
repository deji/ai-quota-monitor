"""Pace, elapsed time, burn rate, and projection calculations for AI Quota Monitor."""
import datetime
from typing import Optional, Tuple


def parse_iso_datetime(iso_str: Optional[str]) -> Optional[datetime.datetime]:
    """Parse an ISO 8601 string into a UTC-aware datetime object.
    
    Handles 'Z', '+00:00', and variable decimal precision.
    """
    if not iso_str:
        return None
    cleaned = iso_str.strip().replace("Z", "+00:00")
    try:
        dt = datetime.datetime.fromisoformat(cleaned)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=datetime.timezone.utc)
        return dt.astimezone(datetime.timezone.utc)
    except Exception:
        return None


def calculate_elapsed_percent(
    captured_at: datetime.datetime,
    resets_at: Optional[datetime.datetime],
    window_hours: Optional[float],
) -> float:
    """Calculate elapsed percentage of the quota window (0.0 to 100.0)."""
    if resets_at is None or not window_hours or window_hours <= 0:
        return 0.0

    total_seconds = window_hours * 3600.0
    start_dt = resets_at - datetime.timedelta(seconds=total_seconds)
    elapsed_seconds = (captured_at - start_dt).total_seconds()

    if elapsed_seconds <= 0:
        return 0.0
    if elapsed_seconds >= total_seconds:
        return 100.0

    return round((elapsed_seconds / total_seconds) * 100.0, 1)


def calculate_pace_delta(used_percent: float, elapsed_percent: float) -> float:
    """Calculate pace delta: usedPercent - elapsedPercent.
    
    Negative values indicate consumption below uniform linear pace (healthy).
    Positive values indicate consumption ahead of uniform linear pace (warning/burn).
    """
    return round(float(used_percent) - float(elapsed_percent), 1)


def calculate_burn_rate_per_hour(
    used_percent: float,
    elapsed_hours: float,
    delta_used: Optional[float] = None,
    delta_hours: Optional[float] = None,
) -> float:
    """Calculate burn rate in percentage points per hour.
    
    If delta_used and delta_hours are provided and delta_hours >= 0.05 (3 minutes),
    computes the rolling delta burn rate. Otherwise falls back to window-average burn rate.
    """
    if delta_used is not None and delta_hours is not None and delta_hours >= 0.05 and delta_used >= 0:
        rate = delta_used / delta_hours
        return round(max(0.0, rate), 2)

    if elapsed_hours > 0:
        rate = used_percent / elapsed_hours
        return round(max(0.0, rate), 2)

    return 0.0


def calculate_projected_remaining(
    remaining_percent: float,
    burn_rate_per_hour: float,
    hours_to_reset: float,
) -> float:
    """Calculate projected remaining percentage at the moment of window reset."""
    if hours_to_reset <= 0 or burn_rate_per_hour <= 0:
        return round(float(remaining_percent), 1)

    projected_burn = burn_rate_per_hour * hours_to_reset
    projected = remaining_percent - projected_burn
    return round(max(0.0, min(100.0, projected)), 1)


def determine_bucket_status(
    remaining_percent: float,
    pace_delta_percent: float,
    projected_remaining: float,
) -> str:
    """Determine bucket status: 'healthy', 'warning', or 'critical'."""
    if remaining_percent <= 10.0 or projected_remaining <= 0.0:
        return "critical"
    if pace_delta_percent > 15.0 or remaining_percent <= 20.0:
        return "warning"
    return "healthy"
