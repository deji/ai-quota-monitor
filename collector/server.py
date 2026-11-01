"""Tailscale HTTP daemon and quota service with scrubbed error logging."""
import datetime
import json
import logging
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, Optional

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


class ScrubbedHTTPHandler(BaseHTTPRequestHandler):
    """HTTP request handler with bearer auth enforcement and scrubbed logging."""

    server_start_time: float = time.time()
    bearer_token: str = ""
    storage: Optional[QuotaStorage] = None
    config: Dict[str, Any] = {}
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
            self.cached_snapshot = snapshot
            self._send_json_response(200, snapshot)
            return

        if path == "/v1/grok/prime":
            # Explicit POST /v1/grok/prime triggers prime and re-collects/returns FULL canonical v1 snapshot
            snapshot = self._refresh_quota(publish_relay=True, manual_refresh=False, force_prime=True)
            self.cached_snapshot = snapshot
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
    ):
        self.host = host
        self.port = port
        self.bearer_token = bearer_token
        self.storage = storage or QuotaStorage()
        self.config = config or {}

        # Isolate handler class attributes per server instance
        class InstanceHTTPHandler(ScrubbedHTTPHandler):
            pass

        InstanceHTTPHandler.bearer_token = self.bearer_token
        InstanceHTTPHandler.storage = self.storage
        InstanceHTTPHandler.config = self.config
        InstanceHTTPHandler.server_start_time = time.time()
        InstanceHTTPHandler.cached_snapshot = None

        self.server = ThreadingHTTPServer((self.host, self.port), InstanceHTTPHandler)

    def serve_forever(self) -> None:
        """Start listening for incoming requests."""
        self.server.serve_forever()

    def shutdown(self) -> None:
        """Stop server and release port."""
        self.server.shutdown()
        self.server.server_close()
