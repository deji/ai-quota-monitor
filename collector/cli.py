"""Command-line interface for AI Quota Monitor collector daemon."""
import argparse
import json
import os
import secrets
import sys

from collector.config import DEFAULT_CONFIG_FILE, get_tailscale_ip, init_config, load_config
from collector.crypto_relay import encrypt_snapshot, publish_to_gist
from collector.providers import collect_all_quotas
from collector.server import QuotaHTTPServer
from collector.smart_prime import evaluate_smart_prime
from collector.storage import QuotaStorage


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="ai-quota-collector",
        description="macOS Companion Quota Collector & Encrypted Relay for AI Quota Monitor",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # 1. collect
    collect_parser = subparsers.add_parser("collect", help="Collect quota from providers and print snapshot")
    collect_parser.add_argument("--json", action="store_true", help="Output raw normalized JSON")
    collect_parser.add_argument("--publish", action="store_true", help="Publish encrypted snapshot to GitHub Gist")
    collect_parser.add_argument("--force-prime", action="store_true", help="Force Grok token priming turn")
    collect_parser.add_argument("--db-path", type=str, default=None, help="Path to SQLite history.db")
    collect_parser.add_argument("--config", type=str, default=None, help="Path to config.json")

    # 2. serve
    serve_parser = subparsers.add_parser("serve", help="Run authenticated Tailscale HTTP server daemon")
    serve_parser.add_argument("--host", type=str, default=None, help="Bind host (default: 127.0.0.1 or 'tailscale')")
    serve_parser.add_argument("--port", type=int, default=None, help="Bind port (default: 8741)")
    serve_parser.add_argument("--db-path", type=str, default=None, help="Path to SQLite history.db")
    serve_parser.add_argument("--config", type=str, default=None, help="Path to config.json")

    # 3. prime
    prime_parser = subparsers.add_parser("prime", help="Evaluate Grok token expiry and optionally trigger priming")
    prime_parser.add_argument("--force", action="store_true", help="Force priming regardless of expiry or active hours")
    prime_parser.add_argument("--config", type=str, default=None, help="Path to config.json")

    # 4. init-config
    init_parser = subparsers.add_parser("init-config", help="Initialize ~/.config/ai-quota-monitor/config.json")
    init_parser.add_argument("--path", type=str, default=None, help="Config path override")
    init_parser.add_argument("--force", action="store_true", help="Overwrite existing config")

    args = parser.parse_args()

    if args.command == "init-config":
        cfg = init_config(args.path, force=args.force)
        print("Initialized configuration file with generated keys.")
        return

    config_path = getattr(args, "config", None)
    cfg = load_config(config_path)

    if args.command == "collect":
        db_path = args.db_path or cfg.get("dbPath")
        storage = QuotaStorage(db_path=db_path)
        snapshot = collect_all_quotas(
            storage=storage,
            force_prime=args.force_prime,
            active_hours_start=cfg.get("activeHoursStart", "08:00"),
            active_hours_end=cfg.get("activeHoursEnd", "22:00"),
        )

        if args.publish:
            relay_key = cfg.get("relayKeyHex")
            gist_id = cfg.get("gistId")
            if not relay_key or not gist_id:
                print("Error: --publish requires relayKeyHex and gistId in config or env", file=sys.stderr)
            else:
                plaintext = json.dumps(snapshot).encode("utf-8")
                envelope = encrypt_snapshot(plaintext, relay_key, snapshot.get("capturedAt"))
                res = publish_to_gist(envelope, gist_id=gist_id)
                if not res.get("ok"):
                    print(f"Relay publish warning: {res.get('error')}", file=sys.stderr)

        print(json.dumps(snapshot, indent=2))

    elif args.command == "serve":
        host = args.host or cfg.get("host", "127.0.0.1")
        if host == "tailscale":
            host = get_tailscale_ip() or "127.0.0.1"
        port = args.port or cfg.get("port", 8741)
        db_path = args.db_path or cfg.get("dbPath")
        storage = QuotaStorage(db_path=db_path)
        bearer_token = (cfg.get("bearerToken") or "").strip()
        if not bearer_token:
            bearer_token = secrets.token_hex(32)
            cfg["bearerToken"] = bearer_token
            # Persist to config if file exists
            target_cfg_path = config_path or DEFAULT_CONFIG_FILE
            try:
                if os.path.exists(target_cfg_path):
                    with open(target_cfg_path, "w", encoding="utf-8") as f:
                        json.dump(cfg, f, indent=2)
                    os.chmod(target_cfg_path, 0o600)
            except Exception:
                pass

        server = QuotaHTTPServer(
            host=host,
            port=port,
            bearer_token=bearer_token,
            storage=storage,
            config=cfg,
        )
        print(f"Starting Tailscale Quota Daemon on http://{host}:{port}")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server...")
            server.shutdown()

    elif args.command == "prime":
        result = evaluate_smart_prime(
            active_hours_start=cfg.get("activeHoursStart", "08:00"),
            active_hours_end=cfg.get("activeHoursEnd", "22:00"),
            force=args.force,
        )
        print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
