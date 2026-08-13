#!/usr/bin/env python3
"""
Automated Maintenance Suite for Aniyomi Extensions
Performs full multi-step maintenance:
1. Syncs shared extractor modules from upstream repositories.
2. Audits and auto-fixes missing build.gradle extractor dependencies.
3. Runs static code analysis across all extension modules.
"""

import argparse
import subprocess
import sys
from pathlib import Path


def run_command(cmd: list) -> tuple[int, str]:
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        return res.returncode, res.stdout
    except Exception as e:
        return 1, str(e)


def main():
    parser = argparse.ArgumentParser(description="Automated Maintenance Suite")
    parser.add_argument("--upstream", default="yuzono", help="Upstream repo for extractor sync")
    parser.add_argument("--dry-run", action="store_true", help="Perform trial run without modifying files")

    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parent.parent

    print("🤖 Starting Automated Repository Maintenance Engine...\n" + "=" * 60)
    failed_steps = []

    # Step 1: Sync Extractors from Upstream
    print("1. Auditing & Syncing Shared Extractors (`lib/`)...")
    sync_cmd = [sys.executable, str(repo_root / "scripts" / "sync_lib.py"), "--all", "--upstream", args.upstream]
    if args.dry_run:
        sync_cmd.append("--dry-run")
    code1, output1 = run_command(sync_cmd)
    print(output1)
    if code1 != 0:
        failed_steps.append("1. Upstream Extractor Sync")

    # Step 2: Auto-Fix Missing Extractor Dependencies
    print("2. Scanning and Auto-Fixing Extractor Dependencies in `build.gradle`...")
    detect_cmd = [sys.executable, str(repo_root / "scripts" / "detect_extractors.py"), "--fix"]
    code2, output2 = run_command(detect_cmd)
    print(output2)
    if code2 != 0:
        failed_steps.append("2. Extractor Dependency Auto-Fix")

    # Step 3: Run Static Code Analysis Validation
    print("3. Executing Static Analysis Validation Across All Extension Modules...")
    val_cmd = [sys.executable, str(repo_root / "scripts" / "cli.py"), "validate"]
    code3, output3 = run_command(val_cmd)
    print(output3)
    if code3 != 0:
        failed_steps.append("3. Static Code Validation")

    print("=" * 60)
    if failed_steps:
        print(f"⚠️ Maintenance Completed with Issues in {len(failed_steps)} step(s):")
        for step in failed_steps:
            print(f"  - {step}")
        sys.exit(1)
    else:
        print("✅ Automated Maintenance Cycle Completed Successfully.")
        sys.exit(0)


if __name__ == "__main__":
    main()
