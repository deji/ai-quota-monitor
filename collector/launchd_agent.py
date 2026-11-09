"""Render and bootstrap the per-user launchd agent for the collector."""
import os
import secrets
import subprocess
from typing import Callable, Optional, Sequence

LABEL = "com.aiquotamonitor.collector"
TEMPLATE_NAME = "com.aiquotamonitor.collector.plist"

RunCommand = Callable[[Sequence[str]], "subprocess.CompletedProcess[str]"]


def default_log_dir() -> str:
    return os.path.join(os.path.expanduser("~"), "Library", "Logs", "ai-quota-monitor")


def user_launch_agents_dir() -> str:
    return os.path.join(os.path.expanduser("~"), "Library", "LaunchAgents")


def display_path(path: str) -> str:
    """Render a path with the home directory replaced by ~ for operator output."""
    home = os.path.expanduser("~")
    if path == home or path.startswith(home + os.sep):
        return "~" + path[len(home):]
    return path


def _scrub(text: str) -> str:
    home = os.path.expanduser("~")
    return text.replace(home, "~")


def _xml_text(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def template_path() -> str:
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), TEMPLATE_NAME)


def render_launchd_plist(
    *,
    repo_root: str,
    python_bin: str,
    log_dir: str,
    template: Optional[str] = None,
) -> str:
    if template is None:
        with open(template_path(), "r", encoding="utf-8") as handle:
            template = handle.read()
    return (
        template.replace("__PYTHON__", _xml_text(python_bin))
        .replace("__REPO_ROOT__", _xml_text(repo_root))
        .replace("__LOG_DIR__", _xml_text(log_dir))
    )


def _under_launch_agents(target_path: str) -> bool:
    agents = os.path.realpath(user_launch_agents_dir())
    parent = os.path.realpath(os.path.dirname(os.path.abspath(target_path)))
    return parent == agents


def _default_runner(cmd: Sequence[str]) -> "subprocess.CompletedProcess[str]":
    return subprocess.run(list(cmd), capture_output=True, text=True)


def bootstrap_launch_agent(plist_path: str, runner: Optional[RunCommand] = None) -> None:
    """bootout, bootstrap, enable, and kickstart the collector agent in the user GUI domain."""
    run = runner or _default_runner
    uid = os.getuid()
    domain = f"gui/{uid}"
    target = f"{domain}/{LABEL}"
    run(["launchctl", "bootout", target])
    boot = run(["launchctl", "bootstrap", domain, plist_path])
    if boot.returncode != 0:
        detail = _scrub((boot.stderr or boot.stdout or "").strip())
        raise SystemExit(f"launchctl bootstrap failed ({boot.returncode}): {detail}")
    run(["launchctl", "enable", target])
    kick = run(["launchctl", "kickstart", "-k", target])
    if kick.returncode != 0:
        detail = _scrub((kick.stderr or kick.stdout or "").strip())
        raise SystemExit(f"launchctl kickstart failed ({kick.returncode}): {detail}")


def install_launch_agent(
    *,
    target_path: str,
    python_bin: str,
    repo_root: str,
    log_dir: Optional[str] = None,
    dry_run: bool = False,
    load: bool = True,
    runner: Optional[RunCommand] = None,
) -> str:
    """Write the plist. Bootstrap only when the destination is ~/Library/LaunchAgents."""
    logs = log_dir or default_log_dir()
    plist_content = render_launchd_plist(repo_root=repo_root, python_bin=python_bin, log_dir=logs)
    if dry_run:
        return plist_content

    parent = os.path.dirname(target_path)
    if parent and not os.path.exists(parent):
        os.makedirs(parent, mode=0o755, exist_ok=True)

    temp_target = f"{target_path}.tmp.{secrets.token_hex(4)}"
    with open(temp_target, "w", encoding="utf-8") as handle:
        handle.write(plist_content)
    os.chmod(temp_target, 0o644)
    os.replace(temp_target, target_path)

    if load and _under_launch_agents(target_path):
        os.makedirs(logs, mode=0o755, exist_ok=True)
        bootstrap_launch_agent(target_path, runner=runner)
    return plist_content
