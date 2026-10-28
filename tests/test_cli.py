import json
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

from scripts.cli import clean_workspace


class CleanWorkspaceTests(unittest.TestCase):
    def test_dry_run_does_not_delete_and_reports_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cache = root / "pkg" / "__pycache__"
            cache.mkdir(parents=True)
            artifact = root / "temp_favicon.png"
            artifact.write_bytes(b"x")

            output = StringIO()
            with redirect_stdout(output):
                self.assertTrue(clean_workspace(root, quiet=True, dry_run=True))

            self.assertTrue(cache.exists())
            self.assertTrue(artifact.exists())
            self.assertIn("Would clean: 2 item(s) planned", output.getvalue())

    def test_dry_run_json_is_machine_readable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "__pycache__").mkdir()
            output = StringIO()
            with redirect_stdout(output):
                clean_workspace(root, as_json=True, dry_run=True)
            payload = json.loads(output.getvalue())
            self.assertTrue(payload["success"])
            self.assertEqual(payload["data"]["planned"], 1)
            self.assertEqual(payload["data"]["purged"], 0)
            self.assertTrue(payload["data"]["dry_run"])

    def test_normal_cleanup_still_removes_targets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cache = root / "__pycache__"
            cache.mkdir()
            (root / "x.pyc").write_bytes(b"x")
            clean_workspace(root, quiet=True)
            self.assertFalse(cache.exists())
            self.assertFalse((root / "x.pyc").exists())


if __name__ == "__main__":
    unittest.main()
