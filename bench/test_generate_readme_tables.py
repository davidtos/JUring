#!/usr/bin/env python3
"""
Unit tests for bench/generate_readme_tables.py
Verifies:
- 10% relative error stability rule (Step 4)
- README generated section --check and --write modes (Step 7)
"""

import os
import sys
import unittest
import tempfile
import subprocess

# Ensure repo root is on sys.path
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from bench.generate_readme_tables import (
    fmt_stat, fmt_mb, fmt_speedup, is_unstable, update_readme, MAX_REL_ERROR
)

class TestStabilityRule(unittest.TestCase):
    def test_max_rel_error_is_ten_percent(self):
        self.assertEqual(MAX_REL_ERROR, 0.10)

    def test_five_percent_error_prints_number_and_speedup(self):
        # 5% relative error (stable)
        score = 1000.0
        err = 50.0 # 5%
        self.assertFalse(is_unstable(score, err))
        stat_str = fmt_stat(score, err)
        self.assertIn("1,000.0 ± 50.0", stat_str)
        self.assertNotIn("unstable", stat_str)

        mb_str = fmt_mb(score, 4096, err)
        self.assertNotEqual(mb_str, "—")
        self.assertIn("MB/s", mb_str)

        speedup_str = fmt_speedup(score, err, 500.0, 20.0)
        self.assertNotEqual(speedup_str, "—")
        self.assertEqual(speedup_str, "2.0x")

    def test_fifty_percent_error_prints_unstable_and_no_speedup(self):
        # 50% relative error (unstable)
        score = 1000.0
        err = 500.0 # 50%
        self.assertTrue(is_unstable(score, err))
        stat_str = fmt_stat(score, err)
        self.assertEqual(stat_str, "unstable (±50%)")

        mb_str = fmt_mb(score, 4096, err)
        self.assertEqual(mb_str, "—")

        speedup_str = fmt_speedup(score, err, 500.0, 20.0)
        self.assertEqual(speedup_str, "—")

        # Baseline unstable also yields "—"
        speedup_base_unstable = fmt_speedup(500.0, 20.0, score, err)
        self.assertEqual(speedup_base_unstable, "—")


class TestReadmeCheckAndWrite(unittest.TestCase):
    def setUp(self):
        self.script = os.path.join(REPO_ROOT, "bench", "generate_readme_tables.py")
        self.results_dir = os.path.join(REPO_ROOT, "results", "2026-11-02")

    def test_update_readme_function(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            readme_path = os.path.join(tmpdir, "README.md")
            with open(readme_path, "w", encoding="utf-8") as f:
                f.write("# Title\n\n<!-- BEGIN GENERATED: latency -->\noutdated latency content\n<!-- END GENERATED: latency -->\n\nFooter\n")

            # Out of date check should return False
            self.assertFalse(update_readme(readme_path, self.results_dir, do_write=False))

            # Write should update and return True
            self.assertTrue(update_readme(readme_path, self.results_dir, do_write=True))

            with open(readme_path, "r", encoding="utf-8") as f:
                updated_content = f.read()
            self.assertNotIn("outdated latency content", updated_content)
            self.assertIn("<!-- BEGIN GENERATED: latency -->", updated_content)

            # Check after write should return True
            self.assertTrue(update_readme(readme_path, self.results_dir, do_write=False))

    def test_cli_check_and_write_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            readme_path = os.path.join(tmpdir, "README.md")
            with open(readme_path, "w", encoding="utf-8") as f:
                f.write("# Title\n\n<!-- BEGIN GENERATED: latency -->\noutdated content\n<!-- END GENERATED: latency -->\n")

            # 1. --check exits with code 1 when outdated
            proc_check_fail = subprocess.run(
                [sys.executable, self.script, "--check", readme_path, self.results_dir],
                capture_output=True, text=True
            )
            self.assertEqual(proc_check_fail.returncode, 1)
            self.assertIn("Check FAILED", proc_check_fail.stderr)

            # 2. --write updates the file and exits with 0
            proc_write = subprocess.run(
                [sys.executable, self.script, "--write", readme_path, self.results_dir],
                capture_output=True, text=True
            )
            self.assertEqual(proc_write.returncode, 0)
            self.assertIn("Successfully updated", proc_write.stdout)

            # 3. --check exits with code 0 when up to date
            proc_check_pass = subprocess.run(
                [sys.executable, self.script, "--check", readme_path, self.results_dir],
                capture_output=True, text=True
            )
            self.assertEqual(proc_check_pass.returncode, 0)
            self.assertIn("Check OK", proc_check_pass.stdout)

if __name__ == "__main__":
    unittest.main()
