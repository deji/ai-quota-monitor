"""Defensive quota collectors and parsers for agy, opencode go, and grok providers."""
import datetime
import json
import os
import shutil
import sqlite3
import subprocess
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional, Tuple

from collector.pace import (
    calculate_elapsed_percent,
    calculate_pace_delta,
    determine_bucket_status,
    parse_iso_datetime,
)
from collector.smart_prime import evaluate_smart_prime, load_grok_auth
from collector.storage import QuotaStorage

DEFAULT_AGY_BIN = "/opt/homebrew/bin/agy"
DEFAULT_OPENCODE_DB = os.path.expanduser("~/.local/share/opencode/opencode.db")


def _safe_float(val: Any, default: float = 0.0) -> float:
    try:
        if isinstance(val, dict) and "val" in val:
            return float(val["val"])
        if val is not None:
            return float(val)
    except (ValueError, TypeError):
        pass
    return default


class AgyProvider:
    """Official CLI provider for Antigravity (Gemini & 3P Models)."""

    def __init__(self, bin_path: Optional[str] = None):
        self.bin_path = bin_path or shutil.which("agy") or (
            DEFAULT_AGY_BIN if os.path.exists(DEFAULT_AGY_BIN) else None
        )

    def collect(self, timeout: int = 60) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
        """Run agy CLI and return raw parsed output dict or error string."""
        if not self.bin_path or not os.path.exists(self.bin_path):
            return None, "agy binary not found"

        env = {**os.environ, "AGY_CLI_DISABLE_AUTO_UPDATE": "true"}
        cmd = [self.bin_path, "-p", "/usage", "--output-format", "json", "--print-timeout", "20s"]
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env)
            if proc.returncode != 0:
                return None, f"agy returned exit code {proc.returncode}"
            data = json.loads(proc.stdout)
            return data, None
        except subprocess.TimeoutExpired:
            return None, "agy timed out"
        except Exception as e:
            return None, f"agy invocation error: {type(e).__name__}"

    @staticmethod
    def parse(raw_data: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Defensively parse raw agy CLI output into bucket dictionaries."""
        if not raw_data or not isinstance(raw_data, dict):
            return []

        # Target: raw_data['command']['data']['groups'][].buckets[]
        groups = (
            raw_data.get("command", {})
            .get("data", {})
            .get("groups", [])
        )
        if not isinstance(groups, list):
            return []

        buckets: List[Dict[str, Any]] = []
        for g in groups:
            if not isinstance(g, dict):
                continue
            group_name = g.get("name", "Gemini Models")
            bucket_list = g.get("buckets", [])
            if not isinstance(bucket_list, list):
                continue

            for b in bucket_list:
                if not isinstance(b, dict):
                    continue
                bid = b.get("id", "")
                name = b.get("name", bid)
                window_raw = b.get("window", "")

                # Window duration hours
                if "5h" in bid or "5h" in window_raw:
                    window_hours = 5.0
                    window_type = "rolling"
                else:
                    window_hours = 168.0
                    window_type = "weekly"

                # Standard labels and groups
                label = name
                if bid == "gemini-argon-wtu-weekly":
                    label = "Gemini 4 Argon Weekly"
                    group_name = "Gemini 4 Argon"
                elif bid == "gemini-5h":
                    label = "Gemini 5-Hour"
                    group_name = "Gemini Models"
                elif bid == "gemini-weekly":
                    label = "Gemini Weekly"
                    group_name = "Gemini Models"
                elif bid == "3p-5h":
                    label = "Claude & GPT 5-Hour"
                    group_name = "Claude and GPT models"
                elif bid == "3p-weekly":
                    label = "Claude & GPT Weekly"
                    group_name = "Claude and GPT models"

                # Low 10: Do not default missing agy remaining_fraction to 1.0 (skip bucket if None or invalid)
                raw_frac = b.get("remaining_fraction")
                if raw_frac is None:
                    continue
                try:
                    if isinstance(raw_frac, dict) and "val" in raw_frac:
                        rem_fraction = float(raw_frac["val"])
                    else:
                        rem_fraction = float(raw_frac)
                except (ValueError, TypeError):
                    continue

                rem_fraction = max(0.0, min(1.0, rem_fraction))
                rem_percent = round(rem_fraction * 100.0, 1)
                used_percent = round(100.0 - rem_percent, 1)
                resets_at = b.get("reset_time")

                buckets.append({
                    "id": bid,
                    "group": group_name,
                    "label": label,
                    "windowType": window_type,
                    "windowDurationHours": window_hours,
                    "remainingPercent": rem_percent,
                    "usedPercent": used_percent,
                    "resetsAt": resets_at,
                })

        return buckets


class OpencodeGoProvider:
    """Provider for OpenCode Go (DeepSeek 4.1 Flash)."""

    def __init__(self, db_path: Optional[str] = None):
        self.db_path = db_path or DEFAULT_OPENCODE_DB

    def _get_api_key(self) -> Optional[str]:
        # Try sqlite db first
        if os.path.exists(self.db_path):
            try:
                uri = f"file:{self.db_path}?mode=ro"
                with sqlite3.connect(uri, uri=True) as conn:
                    cur = conn.execute(
                        """
                        SELECT value FROM credential
                        WHERE integration_id IN ('opencode-go', 'opencode')
                          AND coalesce(active, 1) = 1;
                        """
                    )
                    rows = cur.fetchall()
                    for (v,) in rows:
                        try:
                            j = json.loads(v)
                            key = j.get("key") or j.get("apiKey") or j.get("access")
                            if key:
                                return key
                        except Exception:
                            if v:
                                return v
            except Exception:
                pass

        return os.environ.get("OPENCODE_API_KEY")

    def collect(self, timeout: int = 20) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
        """Fetch usage from opencode API."""
        key = self._get_api_key()
        if not key:
            return None, "OpenCode credentials not found"

        req = urllib.request.Request(
            "https://opencode.ai/zen/go/v1/usage",
            headers={
                "User-Agent": "opencode/2.2.2",
                "Accept": "application/json",
                "Authorization": f"Bearer {key}",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                return data, None
        except urllib.error.HTTPError as e:
            return None, f"HTTP {e.code}"
        except Exception as e:
            return None, f"OpenCode request error: {type(e).__name__}"

    @staticmethod
    def parse(raw_data: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Defensively parse OpenCode Go response.
        
        Handles both usage.{rolling,weekly,monthly} and double-nested usage.usage.{rolling,weekly,monthly}.
        """
        if not raw_data or not isinstance(raw_data, dict):
            return []

        # Handle double nesting or single nesting
        usage = raw_data.get("usage", raw_data)
        if isinstance(usage, dict) and "usage" in usage and isinstance(usage["usage"], dict):
            usage = usage["usage"]

        if not isinstance(usage, dict):
            return []

        specs = [
            ("rolling", "opencode-rolling", "5-Hour Rolling", "rolling", 5.0),
            ("weekly", "opencode-weekly", "Weekly Pool", "weekly", 168.0),
            ("monthly", "opencode-monthly", "Monthly Pool", "monthly", 720.0),
        ]

        buckets: List[Dict[str, Any]] = []
        for key, bid, label, window_type, window_hours in specs:
            w_obj = usage.get(key)
            if not isinstance(w_obj, dict):
                continue

            # Fail closed: skip window if status != 'ok' or percent missing/None/non-numeric
            if w_obj.get("status") != "ok":
                continue

            raw_percent = w_obj.get("percent")
            if raw_percent is None:
                continue

            try:
                if isinstance(raw_percent, dict) and "val" in raw_percent:
                    used_pct = float(raw_percent["val"])
                else:
                    used_pct = float(raw_percent)
            except (ValueError, TypeError):
                continue

            used_pct = max(0.0, min(100.0, round(used_pct, 1)))
            rem_pct = round(100.0 - used_pct, 1)
            resets_at = w_obj.get("resetsAt")

            buckets.append({
                "id": bid,
                "group": "OpenCode Go",
                "label": label,
                "windowType": window_type,
                "windowDurationHours": window_hours,
                "remainingPercent": rem_pct,
                "usedPercent": used_pct,
                "resetsAt": resets_at,
            })

        return buckets


class GrokProvider:
    """Provider for Grok Build CLI (Grok 4.7 Credits)."""

    def __init__(self, auth_path: Optional[str] = None):
        self.auth_path = auth_path

    def collect(self, timeout: int = 20) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
        """Fetch billing config from Grok API."""
        auth = load_grok_auth(self.auth_path)
        if not auth or "key" not in auth:
            return None, "Grok auth credentials not found"

        headers = {
            "Authorization": f"Bearer {auth['key']}",
            "X-XAI-Token-Auth": "xai-grok-cli",
            "x-userid": str(auth.get("user_id", "")),
            "User-Agent": "opencode/2.2.2",
            "Accept": "application/json",
        }

        urls = [
            "https://cli-chat-proxy.grok.com/v1/billing?format=credits",
            "https://cli-chat-proxy.grok.com/v1/billing",
        ]

        had_200_no_usage = False
        for url in urls:
            req = urllib.request.Request(url, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    status = getattr(resp, "status", getattr(resp, "code", 200))
                    if status == 200:
                        data = json.loads(resp.read().decode("utf-8"))
                        buckets = GrokProvider.parse(data)
                        if not buckets:
                            had_200_no_usage = True
                            continue
                        return data, None
            except Exception:
                continue

        if had_200_no_usage:
            return None, "parse_error"
        return None, "Failed all Grok billing endpoints"

    @staticmethod
    def parse(raw_data: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Defensively parse Grok billing configuration.
        
        Handles billing.config, config, onDemandCap.val, and productUsage arrays.
        Fails closed with empty list if neither creditUsagePercent nor productUsage[].usagePercent is numeric.
        """
        if not raw_data or not isinstance(raw_data, dict):
            return []

        config = raw_data.get("billing", raw_data)
        if isinstance(config, dict) and "config" in config and isinstance(config["config"], dict):
            config = config["config"]

        if not isinstance(config, dict):
            return []

        # Find creditUsagePercent and productUsage[].usagePercent
        credit_pct: Optional[float] = None
        if "creditUsagePercent" in config and config["creditUsagePercent"] is not None:
            raw_c = config["creditUsagePercent"]
            try:
                if isinstance(raw_c, dict) and "val" in raw_c:
                    credit_pct = float(raw_c["val"])
                else:
                    credit_pct = float(raw_c)
            except (ValueError, TypeError):
                credit_pct = None

        prod_pct: Optional[float] = None
        if "productUsage" in config and isinstance(config["productUsage"], list):
            for prod in config["productUsage"]:
                if not isinstance(prod, dict):
                    continue
                # Only read usagePercent from items where item.get('product') in ('GrokBuild', None, '')
                if prod.get("product") not in ("GrokBuild", None, ""):
                    continue
                if "usagePercent" in prod and prod["usagePercent"] is not None:
                    raw_p = prod["usagePercent"]
                    try:
                        if isinstance(raw_p, dict) and "val" in raw_p:
                            p_val = float(raw_p["val"])
                        else:
                            p_val = float(raw_p)
                        if prod_pct is None or p_val > prod_pct:
                            prod_pct = p_val
                    except (ValueError, TypeError):
                        pass

        if credit_pct is None and prod_pct is None:
            # Mark parse_error with empty buckets if neither is numeric
            return []

        if credit_pct is not None and prod_pct is not None:
            used_pct = max(credit_pct, prod_pct)
        elif credit_pct is not None:
            used_pct = credit_pct
        else:
            used_pct = prod_pct

        used_pct = max(0.0, min(100.0, round(used_pct, 1)))
        rem_pct = round(100.0 - used_pct, 1)

        # Resets at end of period
        current_period = config.get("currentPeriod")
        resets_at = None
        if isinstance(current_period, dict):
            resets_at = current_period.get("end")
        if not resets_at:
            resets_at = config.get("billingPeriodEnd")

        return [{
            "id": "grok-weekly-credits",
            "group": "Grok Build",
            "label": "Grok 4.7 Weekly Credits",
            "windowType": "weekly",
            "windowDurationHours": 168.0,
            "remainingPercent": rem_pct,
            "usedPercent": used_pct,
            "resetsAt": resets_at,
        }]


def normalize_bucket(
    bucket_raw: Dict[str, Any],
    captured_at: str,
    storage: Optional[QuotaStorage] = None,
) -> Dict[str, Any]:
    """Normalize a raw bucket dict into the standard v1 schema with pace and burn analytics."""
    bid = bucket_raw["id"]
    group = bucket_raw.get("group", "")
    label = bucket_raw.get("label", bid)
    window_type = bucket_raw.get("windowType", "weekly")
    window_hours = float(bucket_raw.get("windowDurationHours", 168.0))
    rem_pct = float(bucket_raw.get("remainingPercent", 100.0))
    used_pct = float(bucket_raw.get("usedPercent", 0.0))
    resets_at = bucket_raw.get("resetsAt")

    captured_dt = parse_iso_datetime(captured_at) or datetime.datetime.now(datetime.timezone.utc)
    resets_dt = parse_iso_datetime(resets_at) if resets_at else None

    # Calculate elapsed percent & pace delta
    elapsed_pct = calculate_elapsed_percent(captured_dt, resets_dt, window_hours)
    pace_delta = calculate_pace_delta(used_pct, elapsed_pct)

    # Burn rate & projected remaining
    if storage:
        burn_rate, projected_rem = storage.get_burn_rate_and_projection(
            bucket_id=bid,
            current_used=used_pct,
            current_remaining=rem_pct,
            captured_at_str=captured_at,
            resets_at_str=resets_at,
            window_hours=window_hours,
        )
    else:
        # Fallback without storage
        elapsed_hours = 0.0
        if resets_dt and window_hours > 0:
            elapsed_sec = (window_hours * 3600.0) - (resets_dt - captured_dt).total_seconds()
            elapsed_hours = max(0.0, elapsed_sec / 3600.0)
        burn_rate = round(used_pct / elapsed_hours, 2) if elapsed_hours > 0 else 0.0

        hours_to_reset = max(0.0, (resets_dt - captured_dt).total_seconds() / 3600.0) if resets_dt else 0.0
        projected_rem = round(max(0.0, min(100.0, rem_pct - (burn_rate * hours_to_reset))), 1)

    status = determine_bucket_status(rem_pct, pace_delta, projected_rem)

    return {
        "id": bid,
        "group": group,
        "label": label,
        "windowType": window_type,
        "windowDurationHours": int(window_hours) if window_hours.is_integer() else window_hours,
        "remainingPercent": rem_pct,
        "usedPercent": used_pct,
        "resetsAt": resets_at,
        "elapsedPercent": elapsed_pct,
        "paceDeltaPercent": pace_delta,
        "burnRatePerHour": burn_rate,
        "projectedRemainingAtReset": projected_rem,
        "status": status,
    }


def assemble_snapshot(
    agy_buckets: List[Dict[str, Any]],
    opencode_buckets: List[Dict[str, Any]],
    grok_buckets: List[Dict[str, Any]],
    storage: Optional[QuotaStorage] = None,
    smart_prime_info: Optional[Dict[str, Any]] = None,
    host_name: Optional[str] = None,
    captured_at: Optional[str] = None,
    errors: Optional[Dict[str, str]] = None,
) -> Dict[str, Any]:
    """Assemble normalized v1 Quota Snapshot from provider buckets."""
    timestamp = captured_at or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    host = "collector"
    errs = errors or {}

    # Normalize buckets per provider
    norm_agy = [normalize_bucket(b, timestamp, storage) for b in agy_buckets]
    norm_oc = [normalize_bucket(b, timestamp, storage) for b in opencode_buckets]
    norm_grok = [normalize_bucket(b, timestamp, storage) for b in grok_buckets]

    # Map bucket IDs for role lookups
    all_buckets_map = {b["id"]: b for b in (norm_agy + norm_oc + norm_grok)}

    # Orca Roles resolution
    # PLANNER: Gemini 4 Argon
    planner_bucket = all_buckets_map.get("gemini-argon-wtu-weekly")
    planner_rem = planner_bucket["remainingPercent"] if planner_bucket else 0.0
    planner_status = planner_bucket["status"] if planner_bucket else "unknown"

    # BUILDER: min(gemini-5h, opencode-rolling)
    gemini_5h = all_buckets_map.get("gemini-5h")
    oc_rolling = all_buckets_map.get("opencode-rolling")

    if gemini_5h and oc_rolling:
        if gemini_5h["remainingPercent"] <= oc_rolling["remainingPercent"]:
            builder_bucket = gemini_5h
        else:
            builder_bucket = oc_rolling
    elif gemini_5h:
        builder_bucket = gemini_5h
    elif oc_rolling:
        builder_bucket = oc_rolling
    else:
        builder_bucket = None

    builder_rem = builder_bucket["remainingPercent"] if builder_bucket else 0.0
    builder_status = builder_bucket["status"] if builder_bucket else "unknown"
    builder_primary_id = builder_bucket["id"] if builder_bucket else "gemini-5h"

    # REVIEWER: Grok 4.7
    reviewer_bucket = all_buckets_map.get("grok-weekly-credits")
    reviewer_rem = reviewer_bucket["remainingPercent"] if reviewer_bucket else 0.0
    reviewer_status = "primed"
    rem_sec = smart_prime_info.get("grokTokenRemainingSeconds") if smart_prime_info else None
    if reviewer_bucket and reviewer_bucket["status"] in ("warning", "critical"):
        reviewer_status = reviewer_bucket["status"]
    elif errs.get("grok") == "parse_error":
        reviewer_status = "parse_error"
    elif rem_sec is not None and 0 < rem_sec < 900:
        reviewer_status = "near_expiry"

    orca_roles = [
        {
            "role": "PLANNER",
            "label": "Gemini 4 Argon",
            "primaryBucketId": "gemini-argon-wtu-weekly",
            "remainingPercent": planner_rem,
            "status": planner_status,
        },
        {
            "role": "BUILDER",
            "label": "Flash 3.8 / OpenCode",
            "primaryBucketId": builder_primary_id,
            "remainingPercent": builder_rem,
            "status": builder_status,
        },
        {
            "role": "REVIEWER",
            "label": "Grok 4.7 Build",
            "primaryBucketId": "grok-weekly-credits",
            "remainingPercent": reviewer_rem,
            "status": reviewer_status,
        },
    ]

    providers = [
        {
            "id": "agy",
            "displayName": "Antigravity (CLI)",
            "subtitle": "Gemini 4 Argon & 3.8 Flash",
            "status": errs.get("agy", "ok"),
            "buckets": norm_agy,
        },
        {
            "id": "opencode_go",
            "displayName": "OpenCode Go",
            "subtitle": "5h Rolling, Weekly & Monthly Pools",
            "status": errs.get("opencode_go", "ok"),
            "buckets": norm_oc,
        },
        {
            "id": "grok",
            "displayName": "Grok Build CLI",
            "subtitle": "Grok 4.7 Build",
            "status": errs.get("grok", "ok"),
            "buckets": norm_grok,
        },
    ]

    history = storage.get_history(limit=50) if storage else []

    smart_prime = smart_prime_info or {
        "policy": "active_hours_or_manual",
        "activeHoursStart": "08:00",
        "activeHoursEnd": "22:00",
        "grokTokenExpiresAt": None,
        "grokTokenRemainingSeconds": 0,
        "lastPrimedAt": None,
    }

    return {
        "schemaVersion": 1,
        "capturedAt": timestamp,
        "host": host,
        "smartPrime": smart_prime,
        "orcaRoles": orca_roles,
        "providers": providers,
        "history": history,
    }


def collect_all_quotas(
    storage: Optional[QuotaStorage] = None,
    force_prime: bool = False,
    manual_refresh: bool = False,
    active_hours_start: str = "08:00",
    active_hours_end: str = "22:00",
    grok_auth_path: Optional[str] = None,
    tz: Optional[datetime.tzinfo] = None,
) -> Dict[str, Any]:
    """Orchestrate quota collection from all providers, record to SQLite, and assemble snapshot."""
    # 1. Smart prime Grok if needed
    prime_info = evaluate_smart_prime(
        auth_path=grok_auth_path,
        active_hours_start=active_hours_start,
        active_hours_end=active_hours_end,
        force=force_prime,
        manual_refresh=manual_refresh,
        tz=tz,
    )

    # 2. Collect from providers
    agy_prov = AgyProvider()
    oc_prov = OpencodeGoProvider()
    grok_prov = GrokProvider(auth_path=grok_auth_path)

    agy_raw, agy_err = agy_prov.collect()
    oc_raw, oc_err = oc_prov.collect()
    grok_raw, grok_err = grok_prov.collect()

    errors = {}
    if agy_err:
        errors["agy"] = agy_err
    if oc_err:
        errors["opencode_go"] = oc_err
    if grok_err:
        errors["grok"] = grok_err

    agy_b = AgyProvider.parse(agy_raw)
    oc_b = OpencodeGoProvider.parse(oc_raw)
    grok_b = GrokProvider.parse(grok_raw)

    if not oc_err and oc_raw is not None and len(oc_b) == 0:
        errors["opencode_go"] = "parse_error"
    if grok_err == "parse_error" or (not grok_err and grok_raw is not None and len(grok_b) == 0):
        errors["grok"] = "parse_error"

    now_str = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    # Record readings in storage if available
    all_raw_buckets = agy_b + oc_b + grok_b
    if storage and all_raw_buckets:
        storage.record_readings(all_raw_buckets, captured_at=now_str)

    snapshot = assemble_snapshot(
        agy_buckets=agy_b,
        opencode_buckets=oc_b,
        grok_buckets=grok_b,
        storage=storage,
        smart_prime_info=prime_info,
        host_name="collector",
        captured_at=now_str,
        errors=errors,
    )

    return snapshot
