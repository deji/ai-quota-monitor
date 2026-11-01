"""Tailscale HTTP daemon and quota service with scrubbed error logging."""
import datetime
import json
import logging
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, Optional

from collector.config import save_config
from collector.crypto_relay import encrypt_snapshot, publish_to_gist
from collector.providers import collect_all_quotas
from collector.smart_prime import evaluate_smart_prime
from collector.storage import QuotaStorage

# Configure logger with scrubber filter
logger = logging.getLogger("ai_quota_monitor.server")


def _sanitize_log_text(text: str) -> str:
    """Scrub potential tokens and sensitive values from log output."""
    if not text:
        return text
    # Mask common token patterns
    import re
    scrubbed = re.sub(r"Bearer\s+[A-Za-z0-9_\-\.]+", "Bearer [REDACTED]", text, flags=re.IGNORECASE)
    scrubbed = re.sub(r"xai-[A-Za-z0-9_\-]+", "xai-[REDACTED]", scrubbed)
    scrubbed = re.sub(r"eyJ[A-Za-z0-9_\-\.]+", "eyJ[REDACTED]", scrubbed)
    return scrubbed


def _validate_hh_mm(val: Any) -> bool:
    """Validate that val is a string in HH:MM 24-hour format."""
    if not isinstance(val, str):
        return False
    parts = val.strip().split(":")
    if len(parts) != 2:
        return False
    try:
        h, m = int(parts[0]), int(parts[1])
        return 0 <= h <= 23 and 0 <= m <= 59
    except (ValueError, TypeError):
        return False


class ScrubbedHTTPHandler(BaseHTTPRequestHandler):
    """HTTP request handler with bearer auth enforcement and scrubbed logging."""

    server_start_time: float = time.time()
    bearer_token: str = ""
    storage: Optional[QuotaStorage] = None
    config: Dict[str, Any] = {}
    config_path: Optional[str] = None
    lock: Optional[threading.Lock] = None
    cached_snapshot: Optional[Dict[str, Any]] = None

    def log_message(self, format: str, *args: Any) -> None:
        """Override standard log_message to enforce scrubbed output."""
        message = format % args
        sys.stderr.write(f"[{self.log_date_time_string()}] {_sanitize_log_text(message)}\n")

    def _send_json_response(self, status_code: int, data: Dict[str, Any]) -> None:
        body = json.dumps(data, indent=2).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def _is_authenticated(self) -> bool:
        """Verify Authorization: Bearer <token> against configured bearer_token.
        
        Fails closed (returns False) if bearer_token is empty or whitespace.
        """
        if not self.bearer_token or not self.bearer_token.strip():
            return False

        auth_header = self.headers.get("Authorization", "")
        parts = auth_header.strip().split(" ", 1)
        if len(parts) == 2 and parts[0].lower() == "bearer":
            import hmac
            return hmac.compare_digest(parts[1].strip(), self.bearer_token.strip())
        return False

    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]

        if path == "/v1/health":
            uptime = int(time.time() - self.server_start_time)
            self._send_json_response(200, {"status": "ok", "uptimeSeconds": uptime})
            return

        if not self._is_authenticated():
            self._send_json_response(401, {"error": "Unauthorized"})
            return

        if path == "/v1/quota":
            if self.cached_snapshot is None:
                self.cached_snapshot = self._refresh_quota(publish_relay=False, manual_refresh=False, force_prime=False)
            self._send_json_response(200, self.cached_snapshot)
            return

        if path == "/v1/history":
            limit = 50
            if "?" in self.path:
                try:
                    import urllib.parse
                    qs = urllib.parse.parse_qs(self.path.split("?", 1)[1])
                    if "limit" in qs and qs["limit"]:
                        limit = int(qs["limit"][0])
                except Exception:
                    limit = 50
            history_data = self.storage.get_history(limit=limit) if self.storage else []
            self._send_json_response(200, {"history": history_data})
            return

        self._send_json_response(404, {"error": "Not Found"})

    def do_POST(self) -> None:
        path = self.path.split("?", 1)[0]

        if not self._is_authenticated():
            self._send_json_response(401, {"error": "Unauthorized"})
            return

        if path == "/v1/quota/refresh":
            snapshot = self._refresh_quota(publish_relay=True, manual_refresh=True, force_prime=False)
            self._send_json_response(200, snapshot)
            return

        if path == "/v1/grok/prime":
            # Explicit POST /v1/grok/prime triggers prime and re-collects/returns FULL canonical v1 snapshot
            snapshot = self._refresh_quota(publish_relay=True, manual_refresh=False, force_prime=True)
            self._send_json_response(200, snapshot)
            return

        if path == "/v1/config/active-hours":
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length <= 0:
                self._send_json_response(400, {"error": "Missing JSON request body"})
                return
            body_bytes = self.rfile.read(content_length)
            try:
                payload = json.loads(body_bytes.decode("utf-8"))
            except Exception:
                self._send_json_response(400, {"error": "Malformed JSON"})
                return

            if not isinstance(payload, dict):
                self._send_json_response(400, {"error": "JSON body must be an object"})
                return

            start_hhmm = payload.get("activeHoursStart")
            end_hhmm = payload.get("activeHoursEnd")
            if not _validate_hh_mm(start_hhmm) or not _validate_hh_mm(end_hhmm):
                self._send_json_response(400, {"error": "Invalid active hours format; expected HH:MM (00:00 to 23:59)"})
                return

            lock = getattr(self, "lock", None)
            if lock is not None:
                with lock:
                    self.config["activeHoursStart"] = start_hhmm
                    self.config["activeHoursEnd"] = end_hhmm
                    try:
                        save_config(self.config, self.config_path)
                    except Exception as e:
                        logger.warning("Failed to persist updated active hours to config: %s", type(e).__name__)
            else:
                self.config["activeHoursStart"] = start_hhmm
                self.config["activeHoursEnd"] = end_hhmm
                try:
                    save_config(self.config, self.config_path)
                except Exception as e:
                    logger.warning("Failed to persist updated active hours to config: %s", type(e).__name__)

            snapshot = self._refresh_quota(publish_relay=True, manual_refresh=False, force_prime=False)
            self._send_json_response(200, snapshot)
            return

        self._send_json_response(404, {"error": "Not Found"})

    def _refresh_quota(
        self,
        publish_relay: bool = True,
        manual_refresh: bool = False,
        force_prime: bool = False,
    ) -> Dict[str, Any]:
        """Collect fresh quota and optionally publish encrypted snapshot to Gist."""
        lock = getattr(self, "lock", None)
        if lock is not None:
            with lock:
                return self._refresh_quota_locked(publish_relay, manual_refresh, force_prime)
        return self._refresh_quota_locked(publish_relay, manual_refresh, force_prime)

    def _refresh_quota_locked(
        self,
        publish_relay: bool = True,
        manual_refresh: bool = False,
        force_prime: bool = False,
    ) -> Dict[str, Any]:
        snapshot = collect_all_quotas(
            storage=self.storage,
            force_prime=force_prime,
            manual_refresh=manual_refresh,
            active_hours_start=self.config.get("activeHoursStart", "08:00"),
            active_hours_end=self.config.get("activeHoursEnd", "22:00"),
        )

        relay_key_hex = self.config.get("relayKeyHex")
        gist_id = self.config.get("gistId")
        if publish_relay and relay_key_hex and gist_id:
            try:
                plaintext = json.dumps(snapshot).encode("utf-8")
                envelope = encrypt_snapshot(plaintext, relay_key_hex, snapshot.get("capturedAt"))
                publish_to_gist(envelope, gist_id=gist_id)
            except Exception as e:
                logger.warning("Failed to publish relay snapshot: %s", type(e).__name__)

        self.__class__.cached_snapshot = snapshot
        return snapshot


class QuotaHTTPServer:
    """Threaded HTTP daemon for Tailscale quota serving."""

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 8741,
        bearer_token: str = "",
        storage: Optional[QuotaStorage] = None,
        config: Optional[Dict[str, Any]] = None,
        config_path: Optional[str] = None,
    ):
        self.host = host
        self.port = port
        self.bearer_token = bearer_token
        self.storage = storage or QuotaStorage()
        self.config = config or {}
        self.config_path = config_path
        self.lock = threading.Lock()
        self._stop_event = threading.Event()
        self._bg_thread: Optional[threading.Thread] = None

        # Isolate handler class attributes per server instance
        class InstanceHTTPHandler(ScrubbedHTTPHandler):
            pass

        InstanceHTTPHandler.bearer_token = self.bearer_token
        InstanceHTTPHandler.storage = self.storage
        InstanceHTTPHandler.config = self.config
        InstanceHTTPHandler.config_path = self.config_path
        InstanceHTTPHandler.lock = self.lock
        InstanceHTTPHandler.server_start_time = time.time()
        InstanceHTTPHandler.cached_snapshot = None

        self.handler_cls = InstanceHTTPHandler
        self.server = ThreadingHTTPServer((self.host, self.port), InstanceHTTPHandler)

    def _background_poll_worker(self) -> None:
        """Periodic background poll that refreshes quotas and publishes to Gist."""
        poll_interval = self.config.get("pollIntervalSeconds", 300)
        while not self._stop_event.is_set():
            if self._stop_event.wait(timeout=poll_interval):
                break
            try:
                with self.lock:
                    snapshot = collect_all_quotas(
                        storage=self.storage,
                        force_prime=False,
                        manual_refresh=False,
                        active_hours_start=self.config.get("activeHoursStart", "08:00"),
                        active_hours_end=self.config.get("activeHoursEnd", "22:00"),
                    )
                    relay_key_hex = self.config.get("relayKeyHex")
                    gist_id = self.config.get("gistId")
                    if relay_key_hex and gist_id:
                        try:
                            plaintext = json.dumps(snapshot).encode("utf-8")
                            envelope = encrypt_snapshot(plaintext, relay_key_hex, snapshot.get("capturedAt"))
                            publish_to_gist(envelope, gist_id=gist_id)
                        except Exception as e:
                            logger.warning("Failed to publish relay snapshot in background loop: %s", type(e).__name__)
                    self.handler_cls.cached_snapshot = snapshot
            except Exception as e:
                logger.warning("Background poll error: %s", type(e).__name__)

    def serve_forever(self) -> None:
        """Start listening for incoming requests and run background polling daemon."""
        if self._bg_thread is None:
            self._bg_thread = threading.Thread(target=self._background_poll_worker, daemon=True)
            self._bg_thread.start()
        self.server.serve_forever()

    def shutdown(self) -> None:
        """Stop server, signal background thread, and release port."""
        self._stop_event.set()
        self.server.shutdown()
        self.server.server_close()
        if self._bg_thread and self._bg_thread.is_alive():
            self._bg_thread.join(timeout=1.0)
