"""Configuration loader and initializer for AI Quota Monitor."""
import json
import os
import re
import secrets
import shutil
import subprocess
import time
from typing import Any, Callable, Dict, Optional

DEFAULT_CONFIG_DIR = os.path.expanduser("~/.config/ai-quota-monitor")
DEFAULT_CONFIG_FILE = os.path.join(DEFAULT_CONFIG_DIR, "config.json")
DEFAULT_DB_PATH = os.path.expanduser("~/.local/share/ai-quota-monitor/history.db")
TAILSCALE_WAIT_SECONDS = 120
TAILSCALE_POLL_SECONDS = 2.0

_IPV4_RE = re.compile(r"^(?:\d{1,3}\.){3}\d{1,3}$")
_TAILSCALE_BINARIES = (
    "/opt/homebrew/bin/tailscale",
    "/usr/local/bin/tailscale",
    "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
)


def _parse_ipv4(text: str) -> Optional[str]:
    for line in (text or "").splitlines():
        candidate = line.strip()
        if not _IPV4_RE.match(candidate):
            continue
        try:
            octets = [int(part) for part in candidate.split(".")]
        except ValueError:
            continue
        if all(0 <= octet <= 255 for octet in octets):
            return candidate
    return None


def _tailscale_binaries() -> list:
    """Absolute Tailscale CLIs first. launchd's default PATH does not include Homebrew."""
    found = []
    for candidate in _TAILSCALE_BINARIES:
        if os.path.exists(candidate):
            found.append(candidate)
    env_path = os.pathsep.join([
        "/opt/homebrew/bin",
        "/usr/local/bin",
        os.environ.get("PATH", ""),
    ])
    which = shutil.which("tailscale", path=env_path)
    if which and which not in found:
        found.append(which)
    return found


def get_tailscale_ip() -> Optional[str]:
    """Return the Tailscale IPv4 address, or None when the CLI is missing or not up."""
    for binary in _tailscale_binaries():
        try:
            proc = subprocess.run(
                [binary, "ip", "-4"],
                capture_output=True,
                text=True,
                timeout=5,
            )
        except (OSError, subprocess.TimeoutExpired):
            continue
        if proc.returncode != 0:
            continue
        ip = _parse_ipv4(proc.stdout)
        if ip:
            return ip
    return None


def resolve_listen_host(
    host: str,
    *,
    wait_seconds: float = 0,
    interval_seconds: float = TAILSCALE_POLL_SECONDS,
    sleeper: Callable[[float], None] = time.sleep,
    clock: Callable[[], float] = time.monotonic,
    lookup: Optional[Callable[[], Optional[str]]] = None,
    on_wait: Optional[Callable[[], None]] = None,
) -> Optional[str]:
    """Resolve a bind address.

    The sentinel ``tailscale`` waits until an IPv4 appears or the deadline passes.
    It never falls back to loopback: a missed lookup must exit so launchd can retry
    after Tailscale has come up at login.
    """
    if host != "tailscale":
        return host
    lookup_fn = lookup or get_tailscale_ip
    deadline = clock() + max(0.0, float(wait_seconds))
    while True:
        ip = lookup_fn()
        if ip:
            return ip
        remaining = deadline - clock()
        if remaining <= 0:
            return None
        if on_wait is not None:
            on_wait()
        sleeper(min(float(interval_seconds), remaining))


def load_config(config_path: Optional[str] = None) -> Dict[str, Any]:
    """Load configuration from JSON file and merge with environment variable overrides."""
    path = config_path or os.environ.get("AQM_CONFIG_PATH") or DEFAULT_CONFIG_FILE
    cfg: Dict[str, Any] = {
        "host": "127.0.0.1",
        "port": 8741,
        "dbPath": DEFAULT_DB_PATH,
        "activeHoursStart": "08:00",
        "activeHoursEnd": "22:00",
        "bearerToken": "",
        "relayKeyHex": "",
        "gistId": "",
        "pollIntervalSeconds": 300,
        "healthchecksPingUrl": "",
        "healthchecksIntervalSeconds": 60,
    }

    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                loaded = json.load(f)
                if isinstance(loaded, dict):
                    cfg.update(loaded)
        except Exception:
            pass

    # Environment variables take precedence over config file values
    if os.environ.get("AQM_BEARER_TOKEN"):
        cfg["bearerToken"] = os.environ["AQM_BEARER_TOKEN"]
    if os.environ.get("AQM_RELAY_KEY_HEX"):
        cfg["relayKeyHex"] = os.environ["AQM_RELAY_KEY_HEX"]
    if os.environ.get("AQM_GIST_ID"):
        cfg["gistId"] = os.environ["AQM_GIST_ID"]
    if os.environ.get("AQM_DB_PATH"):
        cfg["dbPath"] = os.path.expanduser(os.environ["AQM_DB_PATH"])
    if os.environ.get("AQM_HOST"):
        cfg["host"] = os.environ["AQM_HOST"]
    if os.environ.get("AQM_PORT"):
        try:
            cfg["port"] = int(os.environ["AQM_PORT"])
        except ValueError:
            pass
    if os.environ.get("AQM_ACTIVE_HOURS_START"):
        cfg["activeHoursStart"] = os.environ["AQM_ACTIVE_HOURS_START"]
    if os.environ.get("AQM_ACTIVE_HOURS_END"):
        cfg["activeHoursEnd"] = os.environ["AQM_ACTIVE_HOURS_END"]
    if os.environ.get("AQM_POLL_INTERVAL_SECONDS"):
        try:
            cfg["pollIntervalSeconds"] = int(os.environ["AQM_POLL_INTERVAL_SECONDS"])
        except ValueError:
            pass
    if os.environ.get("AQM_HEALTHCHECKS_PING_URL"):
        cfg["healthchecksPingUrl"] = os.environ["AQM_HEALTHCHECKS_PING_URL"].strip()
    if os.environ.get("AQM_HEALTHCHECKS_INTERVAL_SECONDS"):
        try:
            cfg["healthchecksIntervalSeconds"] = int(os.environ["AQM_HEALTHCHECKS_INTERVAL_SECONDS"])
        except ValueError:
            pass

    return cfg


def save_config(cfg: Dict[str, Any], config_path: Optional[str] = None) -> None:
    """Atomically save configuration to JSON file with 0600 permissions."""
    path = config_path or os.environ.get("AQM_CONFIG_PATH") or DEFAULT_CONFIG_FILE
    parent_dir = os.path.dirname(path)
    if parent_dir and not os.path.exists(parent_dir):
        os.makedirs(parent_dir, mode=0o700, exist_ok=True)

    temp_path = f"{path}.tmp.{secrets.token_hex(4)}"
    with open(temp_path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    os.chmod(temp_path, 0o600)
    os.replace(temp_path, path)


def init_config(config_path: Optional[str] = None, force: bool = False) -> Dict[str, Any]:
    """Initialize a default configuration file with secure randomly generated keys.
    
    Creates ~/.config/ai-quota-monitor/config.json with 0600 permissions if not present.
    Ensures bearerToken and relayKeyHex are generated if missing.
    """
    path = config_path or os.environ.get("AQM_CONFIG_PATH") or DEFAULT_CONFIG_FILE
    parent_dir = os.path.dirname(path)
    if parent_dir and not os.path.exists(parent_dir):
        os.makedirs(parent_dir, mode=0o700, exist_ok=True)

    if os.path.exists(path) and not force:
        existing = load_config(path)
        changed = False
        if not existing.get("bearerToken") or not str(existing["bearerToken"]).strip():
            existing["bearerToken"] = secrets.token_hex(32)
            changed = True
        if not existing.get("relayKeyHex") or not str(existing["relayKeyHex"]).strip():
            existing["relayKeyHex"] = secrets.token_hex(32)
            changed = True
        if changed:
            save_config(existing, path)
        return existing

    new_cfg = {
        "host": "127.0.0.1",
        "port": 8741,
        "dbPath": DEFAULT_DB_PATH,
        "activeHoursStart": "08:00",
        "activeHoursEnd": "22:00",
        "bearerToken": secrets.token_hex(32),
        "relayKeyHex": secrets.token_hex(32),
        "gistId": "",
        "pollIntervalSeconds": 300,
        "healthchecksPingUrl": "",
        "healthchecksIntervalSeconds": 60,
    }

    save_config(new_cfg, path)
    return new_cfg
