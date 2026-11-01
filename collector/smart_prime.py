"""Smart Prime policy engine for Grok CLI token refresh."""
import datetime
import json
import os
import shutil
import subprocess
from typing import Any, Dict, Optional, Tuple

from collector.pace import parse_iso_datetime

DEFAULT_GROK_AUTH_PATH = os.path.expanduser("~/.grok/auth.json")
DEFAULT_GROK_BIN = "/opt/homebrew/bin/grok"


def _is_within_active_hours(
    now_local: datetime.datetime,
    start_str: str = "08:00",
    end_str: str = "22:00",
) -> bool:
    """Check if the given local datetime falls within start_str..end_str (HH:MM).
    
    Fails closed (returns False) if active_hours string is malformed or invalid.
    """
    try:
        if not isinstance(start_str, str) or not isinstance(end_str, str):
            return False
        sh_part, sm_part = start_str.strip().split(":", 1)
        eh_part, em_part = end_str.strip().split(":", 1)
        sh, sm = int(sh_part), int(sm_part)
        eh, em = int(eh_part), int(em_part)
        start_time = datetime.time(sh, sm)
        end_time = datetime.time(eh, em)
        cur_time = now_local.time()

        if start_time <= end_time:
            return start_time <= cur_time <= end_time
        else:
            # Wraps past midnight
            return cur_time >= start_time or cur_time <= end_time
    except Exception:
        return False


def load_grok_auth(auth_path: Optional[str] = None) -> Optional[Dict[str, Any]]:
    """Safely load Grok auth JSON without exposing or printing secrets."""
    path = auth_path or DEFAULT_GROK_AUTH_PATH
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            return None
        # Prefer the key starting with https://auth.x.ai
        target_entry = next((v for k, v in data.items() if k.startswith("https://auth.x.ai")), None)
        if not target_entry and data:
            target_entry = next(iter(data.values()))
        return target_entry if isinstance(target_entry, dict) else None
    except Exception:
        return None


def evaluate_smart_prime(
    auth_data: Optional[Dict[str, Any]] = None,
    auth_path: Optional[str] = None,
    active_hours_start: str = "08:00",
    active_hours_end: str = "22:00",
    force: bool = False,
    manual_refresh: bool = False,
    now: Optional[datetime.datetime] = None,
    grok_bin: Optional[str] = None,
    last_primed_at: Optional[str] = None,
    tz: Optional[datetime.tzinfo] = None,
) -> Dict[str, Any]:
    """Evaluate Grok token expiry and conditionally execute priming pulse.
    
    Rules:
    - force=True: prime regardless of expiry or active hours.
    - manual_refresh=True: prime when 0 < remaining_seconds < 900 regardless of active hours.
    - background poll (default): prime when 0 < remaining_seconds < 900 AND within active hours.
    """
    now_utc = now or datetime.datetime.now(datetime.timezone.utc)
    if now_utc.tzinfo is None:
        now_utc = now_utc.replace(tzinfo=datetime.timezone.utc)

    # Resolve local time for active hours evaluation
    if tz is not None:
        now_local = now_utc.astimezone(tz)
    else:
        now_local = now_utc.astimezone()

    auth = auth_data or load_grok_auth(auth_path)
    expires_at_str = auth.get("expires_at") if auth else None
    expires_at_dt = parse_iso_datetime(expires_at_str) if expires_at_str else None

    remaining_seconds: Optional[float] = None
    if expires_at_dt:
        remaining_seconds = (expires_at_dt - now_utc).total_seconds()

    # Low 6: Only mark near_expiry when remaining_seconds is known and > 0 and < 900
    is_near_expiry = remaining_seconds is not None and 0 < remaining_seconds < 900
    is_in_active_hours = _is_within_active_hours(now_local, active_hours_start, active_hours_end)

    if force:
        should_prime = True
    elif manual_refresh:
        should_prime = is_near_expiry
    else:
        should_prime = is_near_expiry and is_in_active_hours

    primed = False
    reason = "ok"
    if not auth:
        reason = "no_auth_found"
    elif should_prime:
        # Find grok binary
        bin_path = grok_bin or shutil.which("grok") or (
            DEFAULT_GROK_BIN if os.path.exists(DEFAULT_GROK_BIN) else None
        )
        if bin_path and os.path.exists(bin_path):
            try:
                proc = subprocess.run(
                    [bin_path, "-p", "reply with ok"],
                    capture_output=True,
                    text=True,
                    timeout=120,
                )
                if proc.returncode == 0:
                    primed = True
                    reason = "primed_successfully"
                    last_primed_at = now_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
                    # Reload auth to reflect updated token expiry if reading from disk
                    if auth_path is not None or auth_data is None:
                        refreshed_auth = load_grok_auth(auth_path)
                        if refreshed_auth and refreshed_auth.get("expires_at"):
                            expires_at_str = refreshed_auth.get("expires_at")
                            new_dt = parse_iso_datetime(expires_at_str)
                            if new_dt:
                                remaining_seconds = (new_dt - now_utc).total_seconds()
                else:
                    reason = f"prime_subprocess_failed_exit_{proc.returncode}"
            except subprocess.TimeoutExpired:
                reason = "prime_subprocess_timeout"
            except Exception as e:
                reason = f"prime_subprocess_error_{type(e).__name__}"
        else:
            reason = "grok_binary_not_found"
    else:
        if remaining_seconds is None:
            reason = "no_expires_at"
        elif remaining_seconds <= 0:
            reason = "token_expired"
        elif is_near_expiry and not is_in_active_hours:
            reason = "near_expiry_outside_active_hours"
        else:
            reason = "token_healthy"

    return {
        "policy": "active_hours_or_manual",
        "activeHoursStart": active_hours_start,
        "activeHoursEnd": active_hours_end,
        "grokTokenExpiresAt": expires_at_str,
        "grokTokenRemainingSeconds": int(remaining_seconds) if remaining_seconds is not None else 0,
        "lastPrimedAt": last_primed_at,
        "primed": primed,
        "reason": reason,
    }
