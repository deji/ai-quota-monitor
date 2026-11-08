"""Unit tests for AI Quota Collector providers, pace analytics, smart prime, crypto, and server."""
import datetime
import json
import os
import secrets
import socket
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import MagicMock, patch

from collector.config import init_config, load_config
from collector.crypto_relay import decrypt_snapshot, encrypt_snapshot
from collector.pace import (
    calculate_burn_rate_per_hour,
    calculate_elapsed_percent,
    calculate_pace_delta,
    calculate_projected_remaining,
    determine_bucket_status,
    parse_iso_datetime,
)
from collector.providers import (
    AgyProvider,
    GrokProvider,
    OpencodeGoProvider,
    assemble_snapshot,
    normalize_bucket,
)
from collector.server import QuotaHTTPServer
from collector.smart_prime import _is_within_active_hours, evaluate_smart_prime
from collector.storage import QuotaStorage


class TestCollectorProviderParsers(unittest.TestCase):
    """Test defensive parsing across agy, opencode go, and grok providers."""

    def test_agy_parser(self):
        fixture = {
            "status": "SUCCESS",
            "command": {
                "name": "usage",
                "data": {
                    "groups": [
                        {
                            "name": "Gemini Models",
                            "buckets": [
                                {
                                    "id": "gemini-weekly",
                                    "name": "Weekly Limit Remaining",
                                    "window": "weekly",
                                    "remaining_fraction": 0.9869,
                                    "reset_time": "2026-11-08T06:29:51Z",
                                },
                                {
                                    "id": "gemini-5h",
                                    "name": "Five Hour Limit Remaining",
                                    "window": "5h",
                                    "remaining_fraction": 0.9789,
                                    "reset_time": "2026-11-01T20:18:55Z",
                                },
                            ],
                        },
                        {
                            "name": "Gemini 4 Argon",
                            "buckets": [
                                {
                                    "id": "gemini-argon-wtu-weekly",
                                    "name": "Gemini 4 Argon Weekly Limit",
                                    "window": "weekly",
                                    "remaining_fraction": 0.35,
                                    "reset_time": "2026-11-01T19:39:25Z",
                                },
                            ],
                        },
                    ],
                },
            },
        }

        buckets = AgyProvider.parse(fixture)
        self.assertEqual(len(buckets), 3)

        argon = next(b for b in buckets if b["id"] == "gemini-argon-wtu-weekly")
        self.assertEqual(argon["group"], "Gemini 4 Argon")
        self.assertEqual(argon["label"], "Gemini 4 Argon Weekly")
        self.assertEqual(argon["windowType"], "weekly")
        self.assertEqual(argon["windowDurationHours"], 168.0)
        self.assertEqual(argon["remainingPercent"], 35.0)
        self.assertEqual(argon["usedPercent"], 65.0)
        self.assertEqual(argon["resetsAt"], "2026-11-01T19:39:25Z")

        gemini_5h = next(b for b in buckets if b["id"] == "gemini-5h")
        self.assertEqual(gemini_5h["windowDurationHours"], 5.0)
        self.assertEqual(gemini_5h["windowType"], "rolling")
        self.assertEqual(gemini_5h["remainingPercent"], 97.9)
        self.assertEqual(gemini_5h["usedPercent"], 2.1)

    def test_agy_parser_skips_missing_remaining_fraction(self):
        """Low 10: verify buckets with missing or None remaining_fraction are skipped."""
        fixture = {
            "status": "SUCCESS",
            "command": {
                "name": "usage",
                "data": {
                    "groups": [
                        {
                            "name": "Gemini Models",
                            "buckets": [
                                {
                                    "id": "gemini-weekly",
                                    "name": "Weekly Limit Remaining",
                                    "window": "weekly",
                                    # missing remaining_fraction
                                    "reset_time": "2026-11-08T06:29:51Z",
                                },
                                {
                                    "id": "gemini-5h",
                                    "name": "Five Hour Limit Remaining",
                                    "window": "5h",
                                    "remaining_fraction": None,
                                    "reset_time": "2026-11-01T20:18:55Z",
                                },
                                {
                                    "id": "gemini-valid",
                                    "name": "Valid Bucket",
                                    "window": "weekly",
                                    "remaining_fraction": 0.85,
                                    "reset_time": "2026-11-08T06:29:51Z",
                                },
                            ],
                        },
                    ],
                },
            },
        }
        buckets = AgyProvider.parse(fixture)
        self.assertEqual(len(buckets), 1)
        self.assertEqual(buckets[0]["id"], "gemini-valid")
        self.assertEqual(buckets[0]["remainingPercent"], 85.0)

    def test_opencode_parser_standard(self):
        fixture = {
            "usage": {
                "rolling": {"status": "ok", "percent": 0, "resetsAt": "2026-11-01T21:27:47.468Z"},
                "weekly": {"status": "ok", "percent": 2, "resetsAt": "2026-11-02T00:00:00.000Z"},
                "monthly": {"status": "ok", "percent": 1, "resetsAt": "2026-11-29T08:26:02.000Z"},
            }
        }

        buckets = OpencodeGoProvider.parse(fixture)
        self.assertEqual(len(buckets), 3)

        weekly = next(b for b in buckets if b["id"] == "opencode-weekly")
        self.assertEqual(weekly["group"], "OpenCode Go")
        self.assertEqual(weekly["usedPercent"], 2.0)
        self.assertEqual(weekly["remainingPercent"], 98.0)
        self.assertEqual(weekly["windowDurationHours"], 168.0)
        self.assertEqual(weekly["resetsAt"], "2026-11-02T00:00:00.000Z")

    def test_opencode_parser_double_nested(self):
        """Verify handling of nested usage.usage response."""
        fixture = {
            "usage": {
                "usage": {
                    "rolling": {"status": "ok", "percent": 5, "resetsAt": "2026-11-01T21:27:47.468Z"},
                    "weekly": {"status": "ok", "percent": 10, "resetsAt": "2026-11-02T00:00:00.000Z"},
                    "monthly": {"status": "ok", "percent": 4, "resetsAt": "2026-11-29T08:26:02.000Z"},
                }
            }
        }

        buckets = OpencodeGoProvider.parse(fixture)
        self.assertEqual(len(buckets), 3)
        rolling = next(b for b in buckets if b["id"] == "opencode-rolling")
        self.assertEqual(rolling["usedPercent"], 5.0)
        self.assertEqual(rolling["remainingPercent"], 95.0)

    def test_grok_parser_standard(self):
        fixture = {
            "config": {
                "currentPeriod": {
                    "type": "USAGE_PERIOD_TYPE_WEEKLY",
                    "start": "2026-10-29T16:45:10.524836+00:00",
                    "end": "2026-11-05T16:45:10.524836+00:00",
                },
                "creditUsagePercent": 3.0,
                "onDemandCap": {"val": 0},
                "onDemandUsed": {"val": 0},
                "productUsage": [{"product": "GrokBuild", "usagePercent": 3.0}],
                "isUnifiedBillingUser": True,
                "prepaidBalance": {"val": 0},
                "topUpMethod": "TOP_UP_METHOD_SAVED_PAYMENT_METHOD",
                "billingPeriodStart": "2026-10-29T16:45:10.524836+00:00",
                "billingPeriodEnd": "2026-11-05T16:45:10.524836+00:00",
            }
        }

        buckets = GrokProvider.parse(fixture)
        self.assertEqual(len(buckets), 2)
        grok = buckets[0]
        self.assertEqual(grok["id"], "grok-weekly-credits")
        self.assertEqual(grok["usedPercent"], 3.0)
        self.assertEqual(grok["remainingPercent"], 97.0)
        self.assertEqual(grok["resetsAt"], "2026-11-05T16:45:10.524836+00:00")

        bot = buckets[1]
        self.assertEqual(bot["id"], "grok-bot-weekly")
        self.assertEqual(bot["group"], "Grok Bot")
        self.assertEqual(bot["label"], "Grok Bot Weekly")
        self.assertEqual(bot["usedPercent"], 3.0)
        self.assertEqual(bot["remainingPercent"], 97.0)
        self.assertEqual(bot["resetsAt"], "2026-11-05T16:45:10.524836+00:00")

    def test_grok_parser_grok_bot_product_usage(self):
        fixture = {
            "config": {
                "creditUsagePercent": 15.0,
                "productUsage": [
                    {"product": "GrokBuild", "usagePercent": 15.0},
                    {"product": "GrokBot", "usagePercent": 42.5},
                ],
                "billingPeriodEnd": "2026-04-20T00:00:00Z",
            }
        }

        buckets = GrokProvider.parse(fixture)
        self.assertEqual(len(buckets), 2)
        self.assertEqual(buckets[0]["id"], "grok-weekly-credits")
        self.assertEqual(buckets[0]["usedPercent"], 15.0)
        self.assertEqual(buckets[0]["remainingPercent"], 85.0)
        self.assertEqual(buckets[1]["id"], "grok-bot-weekly")
        self.assertEqual(buckets[1]["label"], "Grok Bot Weekly")
        self.assertEqual(buckets[1]["group"], "Grok Bot")
        self.assertEqual(buckets[1]["usedPercent"], 42.5)
        self.assertEqual(buckets[1]["remainingPercent"], 57.5)
        self.assertEqual(buckets[1]["resetsAt"], "2026-04-20T00:00:00Z")

    def test_grok_parser_bot_only_usage_still_emits_bucket(self):
        """A Grok Bot reading remains valid even when Grok Build usage is absent."""
        fixture = {
            "config": {
                "productUsage": [{"product": "grok-bot", "usagePercent": 25.0}],
                "billingPeriodEnd": "2026-04-20T00:00:00Z",
            }
        }

        buckets = GrokProvider.parse(fixture)
        self.assertEqual(len(buckets), 1)
        self.assertEqual(buckets[0]["id"], "grok-bot-weekly")
        self.assertEqual(buckets[0]["usedPercent"], 25.0)
        self.assertEqual(buckets[0]["remainingPercent"], 75.0)

    def test_grok_parser_fallback_and_bare_numbers(self):
        """Test productUsage fallback and bare numbers."""
        fixture = {
            "billing": {
                "config": {
                    "currentPeriod": {"end": "2026-11-05T16:45:10Z"},
                    "onDemandCap": 0,
                    "productUsage": [{"product": "GrokBuild", "usagePercent": 7.5}],
                }
            }
        }

        buckets = GrokProvider.parse(fixture)
        self.assertEqual(len(buckets), 1)
        grok = buckets[0]
        self.assertEqual(grok["usedPercent"], 7.5)
        self.assertEqual(grok["remainingPercent"], 92.5)

    def test_grok_parser_ignores_non_grokbuild_products(self):
        """GrokBuild product filter: only read usagePercent from items where product in ('GrokBuild', None, '')."""
        fixture = {
            "config": {
                "currentPeriod": {"end": "2026-11-05T16:45:10Z"},
                "productUsage": [
                    {"product": "UnrelatedProduct", "usagePercent": 99.0},
                    {"product": "GrokBuild", "usagePercent": 12.0},
                ],
            }
        }
        buckets = GrokProvider.parse(fixture)
        self.assertEqual(len(buckets), 1)
        self.assertEqual(buckets[0]["usedPercent"], 12.0)

    def test_grok_parser_no_numeric_usage_returns_empty(self):
        """If HTTP 200 body has no numeric usage, return empty buckets list."""
        fixture = {
            "config": {
                "currentPeriod": {"end": "2026-11-05T16:45:10Z"},
                "creditUsagePercent": None,
                "productUsage": [{"product": "UnrelatedProduct", "usagePercent": 50.0}],
            }
        }
        buckets = GrokProvider.parse(fixture)
        self.assertEqual(buckets, [])

    @patch("urllib.request.urlopen")
    def test_grok_collect_parse_error_on_200_without_numeric_usage(self, mock_urlopen):
        """GrokProvider.collect returns (None, 'parse_error') if HTTP 200 response body has no numeric usage."""
        mock_response = MagicMock()
        mock_response.status = 200
        mock_response.code = 200
        mock_response.read.return_value = json.dumps({"config": {"creditUsagePercent": None}}).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_response

        prov = GrokProvider()
        with patch("collector.providers.load_grok_auth", return_value={"key": "test-key"}):
            data, err = prov.collect()
            self.assertIsNone(data)
            self.assertEqual(err, "parse_error")

    @patch("collector.providers.AgyProvider.collect", return_value=({}, None))
    @patch("collector.providers.OpencodeGoProvider.collect", return_value=({}, None))
    @patch("collector.providers.GrokProvider.collect", return_value=(None, "parse_error"))
    def test_collect_all_quotas_sets_parse_error_status(self, mock_grok, mock_oc, mock_agy):
        """collect_all_quotas sets status = 'parse_error' when grok_err == 'parse_error'."""
        from collector.providers import collect_all_quotas
        snapshot = collect_all_quotas()
        grok_p = next(p for p in snapshot["providers"] if p["id"] == "grok")
        self.assertEqual(grok_p["status"], "parse_error")
        reviewer_role = next(r for r in snapshot["orcaRoles"] if r["role"] == "REVIEWER")
        self.assertEqual(reviewer_role["status"], "parse_error")


class TestPaceMath(unittest.TestCase):
    """Test pace math against verified numbers in docs/providers.md."""

    def test_verified_pace_example(self):
        captured_dt = parse_iso_datetime("2026-11-01T16:27:47Z")
        resets_dt = parse_iso_datetime("2026-11-01T19:39:25Z")
        window_hours = 168.0

        elapsed_pct = calculate_elapsed_percent(captured_dt, resets_dt, window_hours)
        self.assertEqual(elapsed_pct, 98.1)

        pace_delta = calculate_pace_delta(65.0, elapsed_pct)
        self.assertEqual(pace_delta, -33.1)

        elapsed_sec = (window_hours * 3600.0) - (resets_dt - captured_dt).total_seconds()
        elapsed_hours = elapsed_sec / 3600.0
        burn_rate = calculate_burn_rate_per_hour(65.0, elapsed_hours)
        self.assertEqual(burn_rate, 0.39)

        hours_to_reset = (resets_dt - captured_dt).total_seconds() / 3600.0
        projected = calculate_projected_remaining(35.0, burn_rate, hours_to_reset)
        self.assertEqual(projected, 33.8)

        status = determine_bucket_status(35.0, pace_delta, projected)
        self.assertEqual(status, "healthy")

    def test_pace_warning_and_critical(self):
        status_warn = determine_bucket_status(20.0, 30.0, 10.0)
        self.assertEqual(status_warn, "warning")

        status_crit = determine_bucket_status(8.0, 0.0, 5.0)
        self.assertEqual(status_crit, "critical")


class TestSmartPrimeLogic(unittest.TestCase):
    """Test Grok Smart Prime window and token expiry logic with timezone independence."""

    def test_prime_near_expiry_inside_active_hours(self):
        tz_utc = datetime.timezone.utc
        now_dt = datetime.datetime(2026, 11, 1, 14, 0, 0, tzinfo=tz_utc)
        auth = {"expires_at": "2026-11-01T14:10:00Z", "key": "dummy"}
        res = evaluate_smart_prime(
            auth_data=auth,
            active_hours_start="08:00",
            active_hours_end="22:00",
            now=now_dt,
            tz=tz_utc,
            grok_bin="/bin/echo",
        )
        self.assertEqual(res["grokTokenRemainingSeconds"], 600)
        self.assertTrue(res["primed"])

    def test_prime_near_expiry_outside_active_hours(self):
        tz_utc = datetime.timezone.utc
        now_dt = datetime.datetime(2026, 11, 1, 3, 0, 0, tzinfo=tz_utc)
        auth = {"expires_at": "2026-11-01T03:10:00Z", "key": "dummy"}
        res = evaluate_smart_prime(
            auth_data=auth,
            active_hours_start="08:00",
            active_hours_end="22:00",
            now=now_dt,
            tz=tz_utc,
            grok_bin="/bin/echo",
        )
        self.assertEqual(res["grokTokenRemainingSeconds"], 600)
        self.assertFalse(res["primed"])
        self.assertEqual(res["reason"], "near_expiry_outside_active_hours")

    def test_prime_manual_refresh_outside_active_hours(self):
        """Medium 2: manual_refresh=True primes when 0 < remaining_seconds < 900 regardless of active_hours."""
        tz_utc = datetime.timezone.utc
        now_dt = datetime.datetime(2026, 11, 1, 3, 0, 0, tzinfo=tz_utc)
        auth = {"expires_at": "2026-11-01T03:10:00Z", "key": "dummy"}
        res = evaluate_smart_prime(
            auth_data=auth,
            active_hours_start="08:00",
            active_hours_end="22:00",
            manual_refresh=True,
            now=now_dt,
            tz=tz_utc,
            grok_bin="/bin/echo",
        )
        self.assertEqual(res["grokTokenRemainingSeconds"], 600)
        self.assertTrue(res["primed"])

    def test_prime_forced_outside_active_hours(self):
        tz_utc = datetime.timezone.utc
        now_dt = datetime.datetime(2026, 11, 1, 3, 0, 0, tzinfo=tz_utc)
        auth = {"expires_at": "2026-11-01T18:00:00Z", "key": "dummy"}
        res = evaluate_smart_prime(
            auth_data=auth,
            active_hours_start="08:00",
            active_hours_end="22:00",
            force=True,
            now=now_dt,
            tz=tz_utc,
            grok_bin="/bin/echo",
        )
        self.assertTrue(res["primed"])

    def test_prime_malformed_active_hours_fails_closed(self):
        """Low 11: Fail closed (return False) if active_hours string is malformed."""
        tz_utc = datetime.timezone.utc
        now_dt = datetime.datetime(2026, 11, 1, 12, 0, 0, tzinfo=tz_utc)
        self.assertFalse(_is_within_active_hours(now_dt, "invalid", "22:00"))
        self.assertFalse(_is_within_active_hours(now_dt, "08:00", "not_a_time"))
        self.assertFalse(_is_within_active_hours(now_dt, "25:00", "22:00"))

    def test_prime_expired_or_healthy_not_near_expiry(self):
        """Unified Smart Prime: Expired (remaining <= 0) tokens inside active hours prime; healthy tokens do not."""
        tz_utc = datetime.timezone.utc
        now_dt = datetime.datetime(2026, 11, 1, 14, 0, 0, tzinfo=tz_utc)
        # Expired token (remaining <= 0) inside active hours: must be primed!
        auth_expired = {"expires_at": "2026-11-01T13:50:00Z", "key": "dummy"}
        res_expired_inside = evaluate_smart_prime(
            auth_data=auth_expired,
            now=now_dt,
            tz=tz_utc,
            grok_bin="/bin/echo",
        )
        self.assertTrue(res_expired_inside["primed"])
        self.assertEqual(res_expired_inside["reason"], "primed_successfully")

        # Expired token outside active hours (03:00): not primed
        night_dt = datetime.datetime(2026, 11, 1, 3, 0, 0, tzinfo=tz_utc)
        auth_expired_night = {"expires_at": "2026-11-01T02:50:00Z", "key": "dummy"}
        res_expired_outside = evaluate_smart_prime(
            auth_data=auth_expired_night,
            now=night_dt,
            tz=tz_utc,
            grok_bin="/bin/echo",
        )
        self.assertFalse(res_expired_outside["primed"])
        self.assertEqual(res_expired_outside["reason"], "token_expired_outside_active_hours")

        # Healthy token (remaining >= 900): not primed
        auth_healthy = {"expires_at": "2026-11-01T15:00:00Z", "key": "dummy"}
        res_healthy = evaluate_smart_prime(
            auth_data=auth_healthy,
            now=now_dt,
            tz=tz_utc,
            grok_bin="/bin/echo",
        )
        self.assertFalse(res_healthy["primed"])
        self.assertEqual(res_healthy["reason"], "token_healthy")


class TestCryptoRelay(unittest.TestCase):
    """Test AES-256-GCM encryption, decryption, and tamper detection."""

    def setUp(self):
        self.key_hex = secrets.token_hex(32)

    def test_encrypt_decrypt_round_trip(self):
        msg = json.dumps({"schemaVersion": 1, "test": "secret_quota_data"}).encode("utf-8")
        envelope = encrypt_snapshot(msg, self.key_hex, captured_at="2026-11-01T16:00:00Z")

        self.assertEqual(envelope["v"], 1)
        self.assertEqual(envelope["alg"], "AES-256-GCM")
        self.assertIn("nonce", envelope)
        self.assertIn("ciphertext", envelope)
        self.assertIn("tag", envelope)
        self.assertEqual(envelope["capturedAt"], "2026-11-01T16:00:00Z")

        decrypted = decrypt_snapshot(envelope, self.key_hex)
        self.assertEqual(decrypted, msg)

    def test_tamper_detection(self):
        msg = b"Authentic payload"
        envelope = encrypt_snapshot(msg, self.key_hex)

        import base64
        raw_ct = bytearray(base64.b64decode(envelope["ciphertext"]))
        raw_ct[0] ^= 1
        envelope["ciphertext"] = base64.b64encode(raw_ct).decode("ascii")

        with self.assertRaises(ValueError):
            decrypt_snapshot(envelope, self.key_hex)

    def test_deterministic_cross_language_golden_vector(self):
        """Cross-language golden test vector verified byte-for-byte in Python and Kotlin."""
        key_hex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        nonce = bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaab")
        payload = b'{"schemaVersion":1,"capturedAt":"2026-11-01T16:27:47Z","host":"collector","status":"healthy"}'
        envelope = encrypt_snapshot(payload, key_hex, captured_at="2026-11-01T16:27:47Z", nonce=nonce)

        self.assertEqual(envelope["v"], 1)
        self.assertEqual(envelope["alg"], "AES-256-GCM")
        self.assertEqual(envelope["nonce"], "oKGio6Slpqeoqaqr")
        self.assertEqual(
            envelope["ciphertext"],
            "nToPTi2ub940APWgbhWu/EqddTLx1jIY6XxD4j7fVzvwRHfNmQ9iDHKsNZw4TLnLcCFyfzjyNlwpMXgqhkqn09vQ6QpT0YuS1s7da/uvuM++KZGGraT4FpodR7jM",
        )
        self.assertEqual(envelope["tag"], "Dj/3Kj4fHyzmMwdjW4rKGQ==")
        self.assertEqual(envelope["capturedAt"], "2026-11-01T16:27:47Z")

        # Decrypt envelope with Python
        decrypted = decrypt_snapshot(envelope, key_hex)
        self.assertEqual(decrypted, payload)

        # Legacy alias test: 'iv' instead of 'nonce'
        envelope_iv = dict(envelope)
        envelope_iv["iv"] = envelope_iv.pop("nonce")
        decrypted_iv = decrypt_snapshot(envelope_iv, key_hex)
        self.assertEqual(decrypted_iv, payload)

    def test_captured_at_mismatch_fails(self):
        key_hex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        nonce = bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaab")
        payload = b'{"schemaVersion":1,"capturedAt":"2026-11-01T16:27:47Z","host":"collector","status":"healthy"}'
        envelope = encrypt_snapshot(payload, key_hex, captured_at="2026-11-01T16:27:47Z", nonce=nonce)
        envelope["capturedAt"] = "2026-11-01T19:00:00Z"
        with self.assertRaises(ValueError):
            decrypt_snapshot(envelope, key_hex)


class TestStorage(unittest.TestCase):
    """Test SQLite storage insertion, permissions, and rolling pace queries."""

    def test_storage_recording_and_history(self):
        storage = QuotaStorage(db_path=":memory:")

        readings_t0 = [
            {"id": "gemini-5h", "remainingPercent": 100.0, "usedPercent": 0.0, "windowDurationHours": 5.0},
            {"id": "opencode-rolling", "remainingPercent": 98.0, "usedPercent": 2.0, "windowDurationHours": 5.0},
        ]
        storage.record_readings(readings_t0, captured_at="2026-11-01T15:00:00Z")

        readings_t1 = [
            {"id": "gemini-5h", "remainingPercent": 90.0, "usedPercent": 10.0, "windowDurationHours": 5.0},
            {"id": "opencode-rolling", "remainingPercent": 95.0, "usedPercent": 5.0, "windowDurationHours": 5.0},
        ]
        storage.record_readings(readings_t1, captured_at="2026-11-01T16:00:00Z")

        history = storage.get_history()
        self.assertEqual(len(history), 2)
        self.assertEqual(history[0]["capturedAt"], "2026-11-01T15:00:00Z")
        self.assertEqual(history[0]["bucketRemaining"]["gemini-5h"], 100.0)
        self.assertEqual(history[1]["capturedAt"], "2026-11-01T16:00:00Z")
        self.assertEqual(history[1]["bucketRemaining"]["gemini-5h"], 90.0)

        # Burn rate between 15:00 and 16:00 (1h diff, 10% delta)
        burn_rate, _ = storage.get_burn_rate_and_projection(
            bucket_id="gemini-5h",
            current_used=10.0,
            current_remaining=90.0,
            captured_at_str="2026-11-01T16:00:00Z",
            window_hours=5.0,
        )
        self.assertEqual(burn_rate, 10.0)

    def test_storage_chmod_0600(self):
        """Low 9: verify chmod 0o600 on SQLite DB file upon creation."""
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = os.path.join(tmpdir, "test_perms.db")
            _storage = QuotaStorage(db_path=db_path)
            self.assertTrue(os.path.exists(db_path))
            st = os.stat(db_path)
            self.assertEqual(oct(st.st_mode & 0o777), oct(0o600))


FIXTURE_SYNTHETIC_AGY = {
    "status": "SUCCESS",
    "command": {
        "name": "usage",
        "data": {
            "groups": [
                {
                    "name": "Gemini Models",
                    "buckets": [
                        {
                            "id": "gemini-5h",
                            "name": "Five Hour Limit Remaining",
                            "window": "5h",
                            "remaining_fraction": 0.95,
                            "reset_time": "2026-11-01T20:18:55Z",
                        },
                    ],
                },
                {
                    "name": "Gemini 4 Argon",
                    "buckets": [
                        {
                            "id": "gemini-argon-wtu-weekly",
                            "name": "Gemini 4 Argon Weekly Limit",
                            "window": "weekly",
                            "remaining_fraction": 0.50,
                            "reset_time": "2026-11-01T19:39:25Z",
                        },
                    ],
                },
            ],
        },
    },
}

FIXTURE_SYNTHETIC_OPENCODE = {
    "usage": {
        "rolling": {"status": "ok", "percent": 0, "resetsAt": "2026-11-01T21:27:47Z"},
        "weekly": {"status": "ok", "percent": 5, "resetsAt": "2026-11-02T00:00:00Z"},
        "monthly": {"status": "ok", "percent": 1, "resetsAt": "2026-11-29T08:26:02Z"},
    }
}

FIXTURE_SYNTHETIC_GROK = {
    "config": {
        "currentPeriod": {
            "type": "USAGE_PERIOD_TYPE_WEEKLY",
            "end": "2026-11-05T16:45:10Z",
        },
        "creditUsagePercent": 5.0,
        "productUsage": [{"product": "GrokBuild", "usagePercent": 5.0}],
    }
}

FIXTURE_SYNTHETIC_GROK_AUTH = {
    "expires_at": "2026-11-01T14:10:00Z",
    "key": "synthetic_grok_token_fixture",
    "user_id": "00000000-0000-0000-0000-000000000000",
}


class TestHTTPServer(unittest.TestCase):
    """Hermetic unit tests for HTTP server endpoints, bearer auth, and canonical snapshots."""

    @classmethod
    def setUpClass(cls):
        # Pick free port
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        cls.port = s.getsockname()[1]
        s.close()

        cls.bearer_token = "synthetic_test_bearer_token_abc123"
        cls.storage = QuotaStorage(db_path=":memory:")

        # Hermetic mocks: stub all subprocess and network calls so test finishes in <1s
        cls.patches = [
            patch.object(AgyProvider, "collect", return_value=(FIXTURE_SYNTHETIC_AGY, None)),
            patch.object(OpencodeGoProvider, "collect", return_value=(FIXTURE_SYNTHETIC_OPENCODE, None)),
            patch.object(GrokProvider, "collect", return_value=(FIXTURE_SYNTHETIC_GROK, None)),
            patch("collector.providers.load_grok_auth", return_value=FIXTURE_SYNTHETIC_GROK_AUTH),
            patch("collector.smart_prime.load_grok_auth", return_value=FIXTURE_SYNTHETIC_GROK_AUTH),
            patch("collector.smart_prime.subprocess.run", return_value=MagicMock(returncode=0)),
            patch("collector.server.publish_to_gist", return_value={"ok": True}),
        ]
        for p in cls.patches:
            p.start()

        cls.server = QuotaHTTPServer(
            host="127.0.0.1",
            port=cls.port,
            bearer_token=cls.bearer_token,
            storage=cls.storage,
            config={"activeHoursStart": "00:00", "activeHoursEnd": "23:59"},
        )
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        time.sleep(0.01)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        for p in reversed(cls.patches):
            p.stop()

    def test_health_endpoint(self):
        url = f"http://127.0.0.1:{self.port}/v1/health"
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(data["status"], "ok")
            self.assertIn("uptimeSeconds", data)

    def test_quota_unauthorized(self):
        url = f"http://127.0.0.1:{self.port}/v1/quota"
        # No auth header
        req = urllib.request.Request(url)
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(ctx.exception.code, 401)

        # Invalid token
        req_bad = urllib.request.Request(url, headers={"Authorization": "Bearer wrong_token"})
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req_bad, timeout=5)
        self.assertEqual(ctx.exception.code, 401)

    def test_quota_authorized(self):
        url = f"http://127.0.0.1:{self.port}/v1/quota"
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {self.bearer_token}"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(data["schemaVersion"], 1)
            self.assertIn("orcaRoles", data)
            self.assertIn("providers", data)

    def test_quota_refresh_authorized(self):
        url = f"http://127.0.0.1:{self.port}/v1/quota/refresh"
        req = urllib.request.Request(url, data=b"{}", headers={"Authorization": f"Bearer {self.bearer_token}"}, method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(data["schemaVersion"], 1)
            self.assertIn("orcaRoles", data)

    def test_grok_prime_authorized(self):
        """Medium 2 & 3: POST /v1/grok/prime triggers prime and returns full canonical snapshot."""
        url = f"http://127.0.0.1:{self.port}/v1/grok/prime"
        req = urllib.request.Request(url, data=b"{}", headers={"Authorization": f"Bearer {self.bearer_token}"}, method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(data["schemaVersion"], 1)
            self.assertIn("orcaRoles", data)
            self.assertIn("providers", data)
            self.assertIn("smartPrime", data)
            self.assertEqual(data["smartPrime"]["policy"], "active_hours_or_manual")

    def test_active_hours_endpoint(self):
        """High 4: POST /v1/config/active-hours updates hours and returns canonical QuotaSnapshot."""
        url = f"http://127.0.0.1:{self.port}/v1/config/active-hours"
        payload = json.dumps({"activeHoursStart": "07:30", "activeHoursEnd": "21:45"}).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=payload,
            headers={
                "Authorization": f"Bearer {self.bearer_token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(data["schemaVersion"], 1)
            self.assertEqual(data["smartPrime"]["activeHoursStart"], "07:30")
            self.assertEqual(data["smartPrime"]["activeHoursEnd"], "21:45")

        # Invalid format fails with 400
        bad_req = urllib.request.Request(
            url,
            data=b'{"activeHoursStart":"invalid"}',
            headers={
                "Authorization": f"Bearer {self.bearer_token}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(bad_req, timeout=5)
        self.assertEqual(ctx.exception.code, 400)

        # Unauthorized request fails with 401
        unauth_req = urllib.request.Request(url, data=payload, method="POST")
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(unauth_req, timeout=5)
        self.assertEqual(ctx.exception.code, 401)

    def test_history_authorized(self):
        """Medium 3: GET /v1/history returns {'history': storage.get_history(...)}."""
        url = f"http://127.0.0.1:{self.port}/v1/history"
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {self.bearer_token}"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode("utf-8"))
            self.assertIn("history", data)
            self.assertIsInstance(data["history"], list)

    def test_history_unauthorized(self):
        url = f"http://127.0.0.1:{self.port}/v1/history"
        req = urllib.request.Request(url)
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(ctx.exception.code, 401)

    def test_auth_fail_closed_empty_bearer_token(self):
        """High 1: _is_authenticated must fail closed if bearer_token is empty or whitespace."""
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        unauth_port = s.getsockname()[1]
        s.close()

        unauth_server = QuotaHTTPServer(
            host="127.0.0.1",
            port=unauth_port,
            bearer_token="",  # Empty token -> must fail closed
            storage=QuotaStorage(db_path=":memory:"),
        )
        t = threading.Thread(target=unauth_server.serve_forever, daemon=True)
        t.start()
        time.sleep(0.01)
        try:
            url = f"http://127.0.0.1:{unauth_port}/v1/quota"
            # Access without token
            req = urllib.request.Request(url)
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(req, timeout=5)
            self.assertEqual(ctx.exception.code, 401)

            # Access with a token header when server has empty token -> still 401
            req_with_token = urllib.request.Request(url, headers={"Authorization": "Bearer some_token"})
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(req_with_token, timeout=5)
            self.assertEqual(ctx.exception.code, 401)
        finally:
            unauth_server.shutdown()


class TestCLIAndConfig(unittest.TestCase):
    """Test collector configuration initialization, safe defaults, and token generation."""

    def test_init_config(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            cfg_path = os.path.join(tmpdir, "config.json")
            cfg = init_config(cfg_path)
            self.assertTrue(os.path.exists(cfg_path))
            self.assertEqual(len(cfg["relayKeyHex"]), 64)
            self.assertEqual(len(cfg["bearerToken"]), 64)
            self.assertEqual(cfg["host"], "127.0.0.1")

            # Check permissions 0600
            st = os.stat(cfg_path)
            self.assertEqual(oct(st.st_mode & 0o777), oct(0o600))

            # Test load_config
            loaded = load_config(cfg_path)
            self.assertEqual(loaded["relayKeyHex"], cfg["relayKeyHex"])
            self.assertEqual(loaded["bearerToken"], cfg["bearerToken"])
            self.assertEqual(loaded["host"], "127.0.0.1")

    def test_init_config_generates_missing_token_on_existing_file(self):
        """High 1 & Medium 5: ensure init_config generates secure token if missing in existing config."""
        with tempfile.TemporaryDirectory() as tmpdir:
            cfg_path = os.path.join(tmpdir, "config.json")
            # Write config with empty bearerToken
            with open(cfg_path, "w", encoding="utf-8") as f:
                json.dump({"host": "127.0.0.1", "port": 8741, "bearerToken": ""}, f)

            cfg = init_config(cfg_path, force=False)
            self.assertTrue(len(cfg["bearerToken"]) >= 32)

            loaded = load_config(cfg_path)
            self.assertEqual(loaded["bearerToken"], cfg["bearerToken"])

    def test_default_host_is_loopback(self):
        """Medium 5: default host must be 127.0.0.1, not 0.0.0.0."""
        with tempfile.TemporaryDirectory() as tmpdir:
            non_existent = os.path.join(tmpdir, "missing.json")
            cfg = load_config(non_existent)
            self.assertEqual(cfg["host"], "127.0.0.1")

    @patch("collector.cli.QuotaHTTPServer")
    def test_cmd_serve_with_preconfigured_bearer_token(self, mock_server_cls):
        """Item 1: serve does not raise NameError when bearerToken is already set."""
        from collector import cli
        mock_server = MagicMock()
        mock_server.serve_forever.side_effect = KeyboardInterrupt
        mock_server_cls.return_value = mock_server

        with tempfile.TemporaryDirectory() as tmpdir:
            cfg_path = os.path.join(tmpdir, "config.json")
            with open(cfg_path, "w", encoding="utf-8") as f:
                json.dump({"bearerToken": "already-configured-secret", "host": "127.0.0.1", "port": 8741}, f)

            with patch("sys.argv", ["ai-quota-collector", "serve", "--config", cfg_path]):
                cli.main()

            mock_server_cls.assert_called_once()
            _, kwargs = mock_server_cls.call_args
            self.assertEqual(kwargs.get("bearer_token"), "already-configured-secret")
            self.assertEqual(kwargs.get("config_path"), cfg_path)

    def test_install_launchd_includes_repo_root_and_python_path(self):
        """Item 4: launchd plist includes WorkingDirectory and PYTHONPATH pointing to repo root."""
        from collector import cli
        with tempfile.TemporaryDirectory() as tmpdir:
            target_plist = os.path.join(tmpdir, "launchd.plist")
            with patch("sys.argv", [
                "ai-quota-collector",
                "install-launchd",
                "--plist-path", target_plist,
                "--python-path", "/usr/local/bin/python3"
            ]):
                cli.main()

            self.assertTrue(os.path.exists(target_plist))
            with open(target_plist, "r", encoding="utf-8") as f:
                content = f.read()

            self.assertNotIn("__REPO_ROOT__", content)
            self.assertIn("<key>WorkingDirectory</key>", content)
            self.assertIn("<key>EnvironmentVariables</key>", content)
            self.assertIn("<key>PYTHONPATH</key>", content)
            self.assertIn("<string>/usr/local/bin/python3</string>", content)


if __name__ == "__main__":
    unittest.main()
