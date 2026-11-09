"""Healthchecks.io ping client.

The ping URL is a bearer-equivalent secret. This module never logs it.
"""
import logging
import re
import urllib.request
from typing import Optional
from urllib.parse import urlparse, urlunparse

logger = logging.getLogger("ai_quota_monitor.healthchecks")

_UUID = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
_TRAILING_SIGNALS = {"fail", "start", "log"}


def prepare_healthchecks_url(url: Optional[str]) -> Optional[str]:
    """Return the canonical https://hc-ping.com/<uuid> base, or None when disabled or invalid."""
    if url is None:
        return None
    text = str(url).strip()
    if not text:
        return None
    parsed = urlparse(text)
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "https":
        return None
    if host != "hc-ping.com" and not host.endswith(".hc-ping.com"):
        return None
    segments = [segment for segment in parsed.path.split("/") if segment]
    while segments and segments[-1] in _TRAILING_SIGNALS:
        segments.pop()
    if len(segments) != 1 or not _UUID.match(segments[0]):
        return None
    return urlunparse((parsed.scheme, parsed.netloc, "/" + segments[0], "", "", ""))


def ping_healthchecks(
    url: Optional[str],
    *,
    signal: str = "success",
    timeout: float = 5.0,
    urlopen=None,
) -> bool:
    """POST a Healthchecks signal. Returns False when the URL is empty, rejected, or the ping fails."""
    base = prepare_healthchecks_url(url)
    if base is None or signal not in {"success", "fail", "start"}:
        return False
    target = base if signal == "success" else f"{base}/{signal}"
    opener = urlopen or urllib.request.urlopen
    request = urllib.request.Request(target, data=b"", method="POST")
    request.add_header("User-Agent", "ai-quota-monitor-collector")
    try:
        with opener(request, timeout=timeout) as response:
            status = getattr(response, "status", None)
            if status is None:
                status = getattr(response, "code", 0)
            return 200 <= int(status) < 300
    except Exception as exc:
        logger.warning("healthchecks ping failed (%s)", type(exc).__name__)
        return False
