"""Repository hygiene test ensuring no secrets, tokens, or personal identifiers are committed."""
import os
import re
import shutil
import subprocess
import tempfile
import unittest

FORBIDDEN_EXTENSIONS = (
    ".har",
    ".token",
    ".jks",
    ".keystore",
    ".KeyStore",
    ".p12",
    ".pfx",
    ".pem",
    ".key",
    ".crt",
    ".cer",
    ".der",
    ".ovpn",
    ".db",
    ".sqlite",
    ".sqlite3",
    ".apk",
    ".aab",
    ".ap_",
    ".dex",
    ".hprof",
    ".log",
)

FORBIDDEN_FILENAMES = (
    "auth.json",
    "google-services.json",
    "local.properties",
    "keystore.properties",
    "signing.properties",
    "config.yaml",
    "config.yml",
    "config.json",
    "id_rsa",
    "id_ed25519",
    ".DS_Store",
    "Thumbs.db",
    ".env",
)

# Regex patterns matching potential leaked secrets, private keys, or personal home paths
PATTERN_PEM_KEY = re.compile(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")
PATTERN_JWT = re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")
PATTERN_XAI_KEY = re.compile(r"xai-[A-Za-z0-9_-]{16,}")
PATTERN_GOOGLE_KEY = re.compile(r"AIza[0-9A-Za-z_-]{35}")
PATTERN_GOOGLE_OAUTH = re.compile(r"ya29\.[A-Za-z0-9_-]{20,}")
PATTERN_OPENAI_KEY = re.compile(r"sk-[A-Za-z0-9_-]{20,}")
PATTERN_ANTHROPIC_KEY = re.compile(r"sk-ant-[A-Za-z0-9_-]{20,}")
PATTERN_GITHUB_TOKEN = re.compile(r"(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{20,})")
PATTERN_GITLAB_TOKEN = re.compile(r"glpat-[A-Za-z0-9_-]{20,}")
PATTERN_SLACK_TOKEN = re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}")
PATTERN_AWS_KEY = re.compile(r"AKIA[0-9A-Z]{16}")
# Personal home directory patterns (avoid literal / U s e r s or / h o m e in this test file)
PATTERN_MAC_USER_PATH = re.compile(r"/" + r"Users" + r"/[a-zA-Z0-9][a-zA-Z0-9._-]*")
PATTERN_LINUX_HOME_PATH = re.compile(r"/" + r"home" + r"/[a-zA-Z0-9][a-zA-Z0-9._-]*")
PATTERN_CONFLICT_MARKER = re.compile(r"^(?:<{7} |={7}$|>{7} )")
PATTERN_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z][A-Za-z0-9.-]*\.[A-Za-z]{2,}\b")
ALLOWED_EMAIL_DOMAINS = ("example.com", "example.org", "example.net", "users.noreply.github.com")
ALLOWED_EMAIL_EXACT = ("noreply@github.com",)


class TestRepoFreeOfSecretsAndPersonalIdentifiers(unittest.TestCase):
    """Verify repository is completely free of secrets, tokens, and personal identifiers."""

    def setUp(self):
        # Determine repository root
        cur_dir = os.path.dirname(os.path.abspath(__file__))
        self.repo_root = os.path.abspath(os.path.join(cur_dir, "..", ".."))

    def test_no_forbidden_files_exist(self):
        """Ensure no forbidden secret, keystore, binary, or database files are present in the repo."""
        forbidden_found = []
        for root, dirs, files in os.walk(self.repo_root):
            # Skip .git, local virtual environments, and build output directories
            for skip in (".git", ".venv", "__pycache__", "build", ".gradle", ".kotlin"):
                if skip in dirs:
                    dirs.remove(skip)

            for f in files:
                rel_path = os.path.relpath(os.path.join(root, f), self.repo_root)
                if f in FORBIDDEN_FILENAMES and f != ".env.example":
                    forbidden_found.append(rel_path)
                elif f.startswith(".env.") and f != ".env.example":
                    forbidden_found.append(rel_path)
                elif f.endswith((".local.json", ".local.yaml", ".local.yml")):
                    forbidden_found.append(rel_path)
                elif any(f.endswith(ext) for ext in FORBIDDEN_EXTENSIONS):
                    forbidden_found.append(rel_path)

        self.assertEqual(
            forbidden_found,
            [],
            f"Found forbidden credential/binary/database files: {forbidden_found}",
        )

    def test_no_secrets_or_personal_identifiers_in_code_or_docs(self):
        """Scan all text files for leaked tokens, keys, conflict markers, and personal home paths."""
        violations = []
        skip_dirs = {".git", ".venv", "__pycache__", ".agents", "build", ".gradle", ".kotlin"}
        skip_files = {
            "test_repo_hygiene.py",  # Contains regex definitions
            "pre-commit",            # Contains pre-commit hook regex definitions
            "commit-msg",            # Contains commit-msg hook regex definitions
            "pre-push",              # Contains pre-push hook regex definitions
        }

        text_extensions = (
            ".py",
            ".md",
            ".txt",
            ".json",
            ".yaml",
            ".yml",
            ".toml",
            ".sh",
            ".kt",
            ".kts",
            ".xml",
            ".gradle",
        )

        for root, dirs, files in os.walk(self.repo_root):
            dirs[:] = [d for d in dirs if d not in skip_dirs]

            for f in files:
                if f in skip_files:
                    continue
                if not any(f.endswith(ext) for ext in text_extensions):
                    continue

                full_path = os.path.join(root, f)
                rel_path = os.path.relpath(full_path, self.repo_root)

                try:
                    with open(full_path, "r", encoding="utf-8", errors="ignore") as fh:
                        lines = fh.readlines()
                except Exception:
                    continue

                for line_num, line in enumerate(lines, start=1):
                    # Check secrets & paths
                    for pat, name in [
                        (PATTERN_PEM_KEY, "PEM Private Key"),
                        (PATTERN_JWT, "JWT"),
                        (PATTERN_XAI_KEY, "xAI Key"),
                        (PATTERN_GOOGLE_KEY, "Google API Key"),
                        (PATTERN_GOOGLE_OAUTH, "Google OAuth Token"),
                        (PATTERN_OPENAI_KEY, "OpenAI Key"),
                        (PATTERN_ANTHROPIC_KEY, "Anthropic Key"),
                        (PATTERN_GITHUB_TOKEN, "GitHub Token"),
                        (PATTERN_GITLAB_TOKEN, "GitLab Token"),
                        (PATTERN_SLACK_TOKEN, "Slack Token"),
                        (PATTERN_AWS_KEY, "AWS Access Key"),
                        (PATTERN_MAC_USER_PATH, "macOS User Path"),
                        (PATTERN_LINUX_HOME_PATH, "Linux Home Path"),
                        (PATTERN_CONFLICT_MARKER, "Merge Conflict Marker"),
                    ]:
                        match = pat.search(line)
                        if match:
                            violations.append(
                                f"{rel_path}:{line_num} contains potential {name}: {match.group(0)}"
                            )

                    # Check for non-synthetic email addresses
                    for em in PATTERN_EMAIL.finditer(line):
                        raw_email = em.group(0).lower()
                        email_domain = raw_email.split("@", 1)[1]
                        if email_domain in ALLOWED_EMAIL_DOMAINS or raw_email in ALLOWED_EMAIL_EXACT:
                            continue
                        violations.append(
                            f"{rel_path}:{line_num} contains non-synthetic email address: {em.group(0)}"
                        )

        self.assertEqual(
            violations,
            [],
            f"Hygiene violations found:\n" + "\n".join(violations),
        )

    def test_git_hooks_block_secrets_binaries_and_bad_messages(self):
        """Verify .githooks/pre-commit, commit-msg, and pre-push exist, are executable, and block bad commits."""
        for hook_name in ("pre-commit", "commit-msg", "pre-push"):
            hook_path = os.path.join(self.repo_root, ".githooks", hook_name)
            self.assertTrue(os.path.isfile(hook_path), f"Missing git hook: {hook_name}")
            self.assertTrue(os.access(hook_path, os.X_OK), f"Git hook is not executable: {hook_name}")

        pre_commit_src = os.path.join(self.repo_root, ".githooks", "pre-commit")
        commit_msg_src = os.path.join(self.repo_root, ".githooks", "commit-msg")
        pre_push_src = os.path.join(self.repo_root, ".githooks", "pre-push")

        with tempfile.TemporaryDirectory() as tmpdir:
            subprocess.run(["git", "init", "-q"], cwd=tmpdir, check=True)
            subprocess.run(["git", "config", "user.name", "Test User"], cwd=tmpdir, check=True)
            subprocess.run(["git", "config", "user.email", "user@example.com"], cwd=tmpdir, check=True)

            hooks_dir = os.path.join(tmpdir, ".githooks")
            os.makedirs(hooks_dir, exist_ok=True)
            shutil.copy2(pre_commit_src, os.path.join(hooks_dir, "pre-commit"))
            shutil.copy2(commit_msg_src, os.path.join(hooks_dir, "commit-msg"))
            shutil.copy2(pre_push_src, os.path.join(hooks_dir, "pre-push"))
            subprocess.run(["git", "config", "core.hooksPath", ".githooks"], cwd=tmpdir, check=True)

            # 1. Clean file + synthetic email passes both pre-commit and commit-msg
            clean_file = os.path.join(tmpdir, "clean.txt")
            with open(clean_file, "w", encoding="utf-8") as fh:
                fh.write("Safe content with user@example.com\n")
            subprocess.run(["git", "add", "clean.txt"], cwd=tmpdir, check=True)
            res = subprocess.run(
                ["git", "commit", "-m", "Initial clean commit by user@example.com"],
                cwd=tmpdir,
                capture_output=True,
                text=True,
            )
            self.assertEqual(res.returncode, 0, f"Clean commit should succeed: {res.stderr}")
            clean_oid = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=tmpdir, text=True).strip()

            # 2. Nested forbidden file with spaces & uppercase (sub dir/CONFIG.JSON) is blocked
            os.makedirs(os.path.join(tmpdir, "sub dir"), exist_ok=True)
            nested_cfg = os.path.join(tmpdir, "sub dir", "CONFIG.JSON")
            with open(nested_cfg, "w", encoding="utf-8") as fh:
                fh.write("{}\n")
            subprocess.run(["git", "add", "-f", "sub dir/CONFIG.JSON"], cwd=tmpdir, check=True)
            res = subprocess.run(["sh", ".githooks/pre-commit"], cwd=tmpdir, capture_output=True, text=True)
            self.assertNotEqual(res.returncode, 0, "Nested sub dir/CONFIG.JSON must be blocked by pre-commit")
            subprocess.run(["git", "reset", "-q", "HEAD"], cwd=tmpdir, check=True)
            os.remove(nested_cfg)

            # 3. Forbidden binary with spaces & uppercase (Demo Build.APK) is blocked
            apk_file = os.path.join(tmpdir, "Demo Build.APK")
            with open(apk_file, "wb") as fh:
                fh.write(b"PK\x03\x04fake-apk")
            subprocess.run(["git", "add", "-f", "Demo Build.APK"], cwd=tmpdir, check=True)
            res = subprocess.run(["sh", ".githooks/pre-commit"], cwd=tmpdir, capture_output=True, text=True)
            self.assertNotEqual(res.returncode, 0, "APK binary must be blocked by pre-commit")
            subprocess.run(["git", "reset", "-q", "HEAD"], cwd=tmpdir, check=True)
            os.remove(apk_file)

            # 4. Leaked fine-grained GitHub PAT, leading '+', PEM key, macOS user path, real email, or conflict marker is blocked
            bad_payloads = [
                "token = github_pat_" + ("A" * 24) + "\n",
                "+ghp_" + ("C" * 32) + "\n",
                "-----BEGIN " + "PRIVATE KEY-----\n",
                "path = /" + "Users" + "/alice/secret\n",
                "contact = alice.personal@gmail.com\n",
                "contact = alice@gmail.com.example.com\n",
                "<" * 7 + " HEAD\nconflict\n" + ">" * 7 + " branch\n",
            ]
            for payload in bad_payloads:
                with open(clean_file, "w", encoding="utf-8") as fh:
                    fh.write(payload)
                subprocess.run(["git", "add", "clean.txt"], cwd=tmpdir, check=True)
                res = subprocess.run(["sh", ".githooks/pre-commit"], cwd=tmpdir, capture_output=True, text=True)
                self.assertNotEqual(
                    res.returncode,
                    0,
                    f"Payload should have been blocked by pre-commit: {payload.strip()}",
                )
                subprocess.run(["git", "checkout", "--", "clean.txt"], cwd=tmpdir, check=True)

            # 5. Commit message or Markdown heading (## [PLAN]) containing a leaked path or real email is blocked by commit-msg
            msg_file = os.path.join(tmpdir, "COMMIT_EDITMSG")
            for bad_msg in [
                "Fix path /" + "Users" + "/alice/project",
                "## [PLAN] Leak on heading /" + "Users" + "/alice/project",
                "Token ghp_" + ("B" * 32),
                "Signed-off-by: Real Person <real.person@gmail.com>",
            ]:
                with open(msg_file, "w", encoding="utf-8") as fh:
                    fh.write(bad_msg + "\n")
                res = subprocess.run(
                    ["sh", ".githooks/commit-msg", msg_file],
                    cwd=tmpdir,
                    capture_output=True,
                    text=True,
                )
                self.assertNotEqual(
                    res.returncode,
                    0,
                    f"Commit message should have been blocked by commit-msg: {bad_msg}",
                )

            # 6. pre-push inspects stdin ref range and blocks a --no-verify commit containing a secret
            with open(clean_file, "w", encoding="utf-8") as fh:
                fh.write("bypassed = github_pat_" + ("D" * 24) + "\n")
            subprocess.run(["git", "add", "clean.txt"], cwd=tmpdir, check=True)
            subprocess.run(["git", "commit", "--no-verify", "-m", "Bypassed commit #1"], cwd=tmpdir, check=True)
            bad_oid = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=tmpdir, text=True).strip()
            # Even if working tree is reverted in a subsequent commit, pre-push scans the pushed range
            stdin_line = f"refs/heads/main {bad_oid} refs/heads/main {clean_oid}\n"
            res = subprocess.run(
                ["sh", ".githooks/pre-push"],
                cwd=tmpdir,
                input=stdin_line,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(res.returncode, 0, "pre-push must block a pushed commit range containing a secret")
            subprocess.run(["git", "reset", "--hard", clean_oid], cwd=tmpdir, check=True)

            # 6. When AQM_REQUIRE_ISSUE_REF=1 (or origin is set), commit-msg blocks messages without #<issue-number>
            with open(msg_file, "w", encoding="utf-8") as fh:
                fh.write("Add foreground polling without issue reference\n")
            env_with_req = os.environ.copy()
            env_with_req["AQM_REQUIRE_ISSUE_REF"] = "1"
            res = subprocess.run(
                ["sh", ".githooks/commit-msg", msg_file],
                cwd=tmpdir,
                env=env_with_req,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(
                res.returncode,
                0,
                "Commit message without #<issue> should be blocked when AQM_REQUIRE_ISSUE_REF=1",
            )

            with open(msg_file, "w", encoding="utf-8") as fh:
                fh.write("Add foreground polling (Refs #1)\n")
            res = subprocess.run(
                ["sh", ".githooks/commit-msg", msg_file],
                cwd=tmpdir,
                env=env_with_req,
                capture_output=True,
                text=True,
            )
            self.assertEqual(
                res.returncode,
                0,
                f"Commit message with #1 should pass when AQM_REQUIRE_ISSUE_REF=1: {res.stderr}",
            )

    def test_learnings_and_docs_sync_gate(self):
        """Ensure living documentation, skills, and defect-to-lessons cross-references stay in sync."""
        required_files = [
            "AGENTS.md",
            "README.md",
            "docs/plan.md",
            "docs/providers.md",
            "docs/lessons.md",
            "docs/bugs/index.md",
            "docs/status.md",
            "docs/workers.md",
            ".agents/skills/orca-quota-engineering/SKILL.md",
            "scripts/verify-all.sh",
            "scripts/review-plan-and-code.sh",
            "scripts/gh-issue-workflow.sh",
            "scripts/deploy-pixel8pro.sh",
        ]
        for rel in required_files:
            path = os.path.join(self.repo_root, rel)
            self.assertTrue(os.path.isfile(path), f"Missing required doc/skill/script file: {rel}")
            self.assertGreater(os.path.getsize(path), 0, f"Required file is empty: {rel}")

        for rel_script in ("scripts/gh-issue-workflow.sh", "scripts/review-plan-and-code.sh", "scripts/deploy-pixel8pro.sh"):
            script_path = os.path.join(self.repo_root, rel_script)
            self.assertTrue(os.access(script_path, os.X_OK), f"{rel_script} must be executable")

        bugs_path = os.path.join(self.repo_root, "docs/bugs/index.md")
        lessons_path = os.path.join(self.repo_root, "docs/lessons.md")
        with open(bugs_path, "r", encoding="utf-8") as fh:
            bugs_text = fh.read()
        with open(lessons_path, "r", encoding="utf-8") as fh:
            lessons_text = fh.read()

        bug_ids = re.findall(r"\|\s*\*\*([A-Z]+-[A-Z0-9]+)", bugs_text)
        self.assertTrue(len(bug_ids) > 0, "Expected defect IDs in docs/bugs/index.md")
        missing = [bid for bid in bug_ids if bid not in lessons_text]
        self.assertEqual(
            missing,
            [],
            f"Defect IDs in docs/bugs/index.md missing from docs/lessons.md: {missing}",
        )

    def test_architectural_and_code_lint_rules(self):
        """Enforce project-specific static lint rules (AQM-LINT-001 through AQM-LINT-007)."""
        # AQM-LINT-001: Zero WebViews allowed in app/src/main
        app_main = os.path.join(self.repo_root, "app", "src", "main")
        for root, _, files in os.walk(app_main):
            for f in files:
                if not f.endswith((".kt", ".xml")):
                    continue
                full_path = os.path.join(root, f)
                with open(full_path, "r", encoding="utf-8") as fh:
                    text = fh.read()
                self.assertNotIn("android.webkit.WebView", text, f"AQM-LINT-001: WebView forbidden in {f}")
                self.assertNotIn("<WebView", text, f"AQM-LINT-001: <WebView> forbidden in {f}")

        # AQM-LINT-002: Android Lint config (app/lint.xml) must enforce MutatingSharedPrefs & CommitPrefEdits as error
        lint_xml_path = os.path.join(self.repo_root, "app", "lint.xml")
        self.assertTrue(os.path.isfile(lint_xml_path), "AQM-LINT-002: Missing app/lint.xml")
        with open(lint_xml_path, "r", encoding="utf-8") as fh:
            lint_xml = fh.read()
        self.assertIn('id="MutatingSharedPrefs" severity="error"', lint_xml)
        self.assertIn('id="CommitPrefEdits" severity="error"', lint_xml)

        # AQM-LINT-003: PreferencesStore.kt must check prefs.contains(KEY_HIDDEN_BUCKETS) and return defensive HashSet copies
        prefs_kt_path = os.path.join(
            self.repo_root, "app", "src", "main", "java", "com", "aiquotamonitor", "data", "local", "PreferencesStore.kt"
        )
        with open(prefs_kt_path, "r", encoding="utf-8") as fh:
            prefs_kt = fh.read()
        self.assertIn("prefs.contains(KEY_HIDDEN_BUCKETS)", prefs_kt, "AQM-LINT-003: Must distinguish unset vs emptySet()")
        self.assertIn("HashSet(", prefs_kt, "AQM-LINT-003: Must use defensive HashSet copies for StringSet preferences")

        # AQM-LINT-004: ConnectionSettingsForm.kt must never key remember(...) on the whole composite `settings` object
        form_kt_path = os.path.join(
            self.repo_root,
            "app",
            "src",
            "main",
            "java",
            "com",
            "aiquotamonitor",
            "ui",
            "components",
            "ConnectionSettingsForm.kt",
        )
        with open(form_kt_path, "r", encoding="utf-8") as fh:
            form_kt = fh.read()
        self.assertIsNone(
            re.search(r"\bremember\s*\(\s*settings\s*\)", form_kt),
            "AQM-LINT-004: Do not key remember(settings) on the whole SyncSettings object in ConnectionSettingsForm.kt",
        )

        # AQM-LINT-005: QuotaRepositoryImpl.kt must never persist SeedData.sampleSnapshot into saveCachedSnapshot
        repo_kt_path = os.path.join(
            self.repo_root,
            "app",
            "src",
            "main",
            "java",
            "com",
            "aiquotamonitor",
            "data",
            "repository",
            "QuotaRepositoryImpl.kt",
        )
        with open(repo_kt_path, "r", encoding="utf-8") as fh:
            repo_kt = fh.read()
        self.assertIsNone(
            re.search(r"saveCachedSnapshot\s*\(\s*SeedData", repo_kt),
            "AQM-LINT-005: Never persist SeedData to cached_snapshot preferences",
        )

        # AQM-LINT-006: BurnVelocityChart.kt must never substitute 100.0 for missing bucket history points
        chart_kt_path = os.path.join(
            self.repo_root,
            "app",
            "src",
            "main",
            "java",
            "com",
            "aiquotamonitor",
            "ui",
            "components",
            "BurnVelocityChart.kt",
        )
        with open(chart_kt_path, "r", encoding="utf-8") as fh:
            chart_kt = fh.read()
        self.assertIsNone(
            re.search(r"\?:\s*100\.0", chart_kt),
            "AQM-LINT-006: Never default missing history points to 100.0 in BurnVelocityChart.kt",
        )

        # AQM-LINT-007: .github/workflows/release.yml must build both assembleDebug and assembleRelease
        release_yml_path = os.path.join(self.repo_root, ".github", "workflows", "release.yml")
        with open(release_yml_path, "r", encoding="utf-8") as fh:
            release_yml = fh.read()
        self.assertIn("assembleDebug", release_yml, "AQM-LINT-007: release.yml must run assembleDebug")
        self.assertIn("assembleRelease", release_yml, "AQM-LINT-007: release.yml must run assembleRelease")

        # AQM-LINT-008: ModelFilterSheet.kt must not call onToggleBucket inside the pin action before onSetPinnedBucket
        filter_sheet_path = os.path.join(
            self.repo_root,
            "app",
            "src",
            "main",
            "java",
            "com",
            "aiquotamonitor",
            "ui",
            "components",
            "ModelFilterSheet.kt",
        )
        with open(filter_sheet_path, "r", encoding="utf-8") as fh:
            filter_sheet_kt = fh.read()
        self.assertIsNone(
            re.search(r"onToggleBucket\s*\([^)]+\)\s*\n\s*onSetPinnedBucket", filter_sheet_kt),
            "AQM-LINT-008: Never call onToggleBucket before onSetPinnedBucket (setPinnedBucket unhides atomically under settingsMutex)",
        )


if __name__ == "__main__":
    unittest.main()
