"""Configuration loader and initializer for AI Quota Monitor."""
import json
import os
import secrets
import shutil
import subprocess
from typing import Any, Dict, Optional

DEFAULT_CONFIG_DIR = os.path.expanduser("~/.config/ai-quota-monitor")
DEFAULT_CONFIG_FILE = os.path.join(DEFAULT_CONFIG_DIR, "config.json")
DEFAULT_DB_PATH = os.path.expanduser("~/.local/share/ai-quota-monitor/history.db")


def get_tailscale_ip() -> Optional[str]:
    """Attempt to detect Tailscale IPv4 address via 'tailscale ip -4'."""
    candidates = ["tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale"]
    for bin_cand in candidates:
        tailscale_bin = shutil.which(bin_cand) or (bin_cand if os.path.exists(bin_cand) else None)
        if tailscale_bin:
            try:
                proc = subprocess.run([tailscale_bin, "ip", "-4"], capture_output=True, text=True, timeout=5)
                if proc.returncode == 0:
                    ip = proc.stdout.strip()
                    if ip:
                        return ip
            except Exception:
                pass
    return None


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

    if cfg.get("host") == "tailscale":
        ts_ip = get_tailscale_ip()
        cfg["host"] = ts_ip if ts_ip else "127.0.0.1"

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
    }

    save_config(new_cfg, path)
    return new_cfg
