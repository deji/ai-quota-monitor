"""Repository hygiene test ensuring no secrets, tokens, or personal identifiers are committed."""
import os
import re
import unittest

FORBIDDEN_EXTENSIONS = (
    ".har",
    ".token",
    ".jks",
    ".keystore",
    ".pem",
    ".key",
    ".db",
    ".sqlite",
    ".sqlite3",
)

FORBIDDEN_FILENAMES = (
    "auth.json",
    "local.properties",
    "keystore.properties",
    "signing.properties",
    ".env",
)

# Regex patterns matching potential leaked secrets or private macOS home paths
PATTERN_JWT = re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")
PATTERN_XAI_KEY = re.compile(r"xai-[A-Za-z0-9_-]{16,}")
PATTERN_GOOGLE_KEY = re.compile(r"AIza[0-9A-Za-z_-]{35}")
PATTERN_OPENAI_KEY = re.compile(r"sk-[A-Za-z0-9]{20,}")
PATTERN_GITHUB_TOKEN = re.compile(r"ghp_[A-Za-z0-9]{30,}")
PATTERN_GITLAB_TOKEN = re.compile(r"glpat-[A-Za-z0-9_-]{20,}")
# Personal macOS home directory pattern (avoid literal / U s e r s in this test file)
PATTERN_MAC_USER_PATH = re.compile(r"/" + r"Users" + r"/[a-zA-Z0-9][a-zA-Z0-9._-]*")
PATTERN_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
ALLOWED_EMAIL_DOMAINS = ("example.com", "example.org", "example.net")
ALLOWED_EMAIL_EXACT = ("noreply@github.com",)


class TestRepoFreeOfSecretsAndPersonalIdentifiers(unittest.TestCase):
    """Verify repository is completely free of secrets, tokens, and personal identifiers."""

    def setUp(self):
        # Determine repository root
        cur_dir = os.path.dirname(os.path.abspath(__file__))
        self.repo_root = os.path.abspath(os.path.join(cur_dir, "..", ".."))

    def test_no_forbidden_files_exist(self):
        """Ensure no forbidden secret, keystore, or database files are present in the repo."""
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
                elif any(f.endswith(ext) for ext in FORBIDDEN_EXTENSIONS):
                    forbidden_found.append(rel_path)

        self.assertEqual(
            forbidden_found,
            [],
            f"Found forbidden credential/database files: {forbidden_found}",
        )

    def test_no_secrets_or_personal_identifiers_in_code_or_docs(self):
        """Scan all text files for leaked tokens, keys, and personal home paths."""
        violations = []
        skip_dirs = {".git", ".venv", "__pycache__", ".agents", "build", ".gradle", ".kotlin"}
        skip_files = {
            "test_repo_hygiene.py",  # Contains regex definitions
            "pre-commit",            # Contains pre-commit hook regex definitions
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
                    # Check secrets
                    for pat, name in [
                        (PATTERN_JWT, "JWT"),
                        (PATTERN_XAI_KEY, "xAI Key"),
                        (PATTERN_GOOGLE_KEY, "Google API Key"),
                        (PATTERN_OPENAI_KEY, "OpenAI Key"),
                        (PATTERN_GITHUB_TOKEN, "GitHub Token"),
                        (PATTERN_GITLAB_TOKEN, "GitLab Token"),
                        (PATTERN_MAC_USER_PATH, "macOS User Path"),
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
                        if email_domain in ALLOWED_EMAIL_DOMAINS or any(email_domain.endswith("." + d) for d in ALLOWED_EMAIL_DOMAINS):
                            continue
                        if raw_email in ALLOWED_EMAIL_EXACT or email_domain.endswith("noreply.github.com"):
                            continue
                        violations.append(
                            f"{rel_path}:{line_num} contains non-synthetic email address: {em.group(0)}"
                        )

        self.assertEqual(
            violations,
            [],
            f"Hygiene violations found:\n" + "\n".join(violations),
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
            "scripts/deploy-pixel8pro.sh",
        ]
        for rel in required_files:
            path = os.path.join(self.repo_root, rel)
            self.assertTrue(os.path.isfile(path), f"Missing required doc/skill/script file: {rel}")
            self.assertGreater(os.path.getsize(path), 0, f"Required file is empty: {rel}")

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


if __name__ == "__main__":
    unittest.main()
