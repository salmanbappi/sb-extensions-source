#!/usr/bin/env python3
"""
GitHub Actions CI Monitor & Diagnostics for Aniyomi Extensions
Queries workflow runs, streams remote build progress, extracts Kotlin compilation errors,
and formats actionable failure diagnostics locally.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional, Tuple

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

def run_cmd(cmd: List[str]) -> Tuple[int, str, str]:
    """Runs a shell command non-interactively and returns (exit_code, stdout, stderr)."""
    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        return proc.returncode, proc.stdout.strip(), proc.stderr.strip()
    except Exception as e:
        return 1, "", str(e)

def get_latest_run_gh(limit: int = 1) -> Optional[dict]:
    """Fetches the latest workflow run using gh CLI if available."""
    code, stdout, _ = run_cmd([
        "gh", "run", "list",
        "-L", str(limit),
        "--json", "databaseId,status,conclusion,displayTitle,name,createdAt,updatedAt,event,headBranch,url"
    ])
    if code == 0 and stdout:
        try:
            data = json.loads(stdout)
            if data and isinstance(data, list):
                return data[0]
        except json.JSONDecodeError:
            pass
    return None

def get_run_failed_log_gh(run_id: str) -> Optional[str]:
    """Retrieves the failed log from gh CLI."""
    code, stdout, stderr = run_cmd(["gh", "run", "view", str(run_id), "--log-failed"])
    if code == 0 and stdout:
        return stdout
    return stderr if stderr else None

def extract_kotlin_compiler_errors(log_text: str) -> List[str]:
    """Extracts clean Kotlin compiler error diagnostics from a Gradle CI build log."""
    errors = []
    # Pattern: e: file:///.../file.kt:line:col Message
    pattern = re.compile(r"e:\s+file:///[^\n]+/src/([^\n]+):(\d+):(\d+)\s+([^\n]+)")
    for match in pattern.finditer(log_text):
        rel_file, line, col, msg = match.groups()
        errors.append(f"  ❌ {rel_file}:L{line}:{col} -> {msg.strip()}")

    # Fallback to general compilation errors
    if not errors:
        general_pattern = re.compile(r"(?:Compilation error|Execution failed for task '[^']+'|\* What went wrong:[^\n]+)")
        for match in general_pattern.finditer(log_text):
            errors.append(f"  ⚠️ {match.group(0).strip()}")

    return errors

def watch_ci(run_id: Optional[str] = None, poll_interval: int = 5, timeout: int = 360) -> bool:
    """Watches a remote CI workflow run until completion and reports status."""
    print("🔭 Watching GitHub Actions Remote CI Workflow...\n" + "=" * 60)

    start_time = time.time()
    last_status = None

    if not run_id:
        # Give GitHub a moment to register the newly pushed run
        for _ in range(6):
            run = get_latest_run_gh(1)
            if run and run.get("status") in ("in_progress", "queued", "waiting", "completed"):
                run_id = str(run["databaseId"])
                break
            time.sleep(2)

    if not run_id:
        print("❌ Unable to locate active GitHub Actions workflow run.")
        return False

    print(f"📌 Monitoring Workflow Run ID: {run_id}")

    while time.time() - start_time < timeout:
        run = get_latest_run_gh(1)
        if not run or str(run.get("databaseId")) != str(run_id):
            time.sleep(poll_interval)
            continue

        status = run.get("status", "unknown")
        conclusion = run.get("conclusion")
        title = run.get("displayTitle", "CI Workflow")
        elapsed = int(time.time() - start_time)

        if status != last_status:
            print(f"  ⏱️ [{elapsed}s] State: {status.upper()} | Title: \"{title}\"")
            last_status = status

        if status == "completed":
            print("\n" + "=" * 60)
            if conclusion == "success":
                print(f"🎉 Build PASSED! (Conclusion: SUCCESS, Elapsed: {elapsed}s)")
                print("📦 Remote APK generated and published to repository release.")
                return True
            else:
                print(f"❌ Build FAILED! (Conclusion: {conclusion.upper() if conclusion else 'FAILURE'})")
                print("🔍 Extracting Kotlin compilation failure logs...\n")
                log = get_run_failed_log_gh(run_id)
                if log:
                    errors = extract_kotlin_compiler_errors(log)
                    if errors:
                        print("Compilation Diagnostics:")
                        for err in errors:
                            print(err)
                    else:
                        # Print summary snippet
                        lines = log.splitlines()[-20:]
                        print("\n".join(lines))
                return False

        time.sleep(poll_interval)

    print(f"\n⚠️ Watch timed out after {timeout} seconds. Run continues in background.")
    return False

def show_ci_status(run_id: Optional[str] = None):
    """Displays the status and diagnostics for the latest or specified CI run."""
    run = get_latest_run_gh(1)
    if not run:
        print("❌ Could not retrieve CI workflow runs.")
        return

    rid = run_id or run.get("databaseId")
    status = run.get("status", "unknown")
    conclusion = run.get("conclusion") or "in progress"
    title = run.get("displayTitle", "Unknown")
    branch = run.get("headBranch", "master")
    url = run.get("url", "")

    icon = "✅" if conclusion == "success" else ("⏳" if status != "completed" else "❌")

    print(f"{icon} Latest CI Workflow Run:")
    print(f"  • ID: {rid}")
    print(f"  • Title: {title}")
    print(f"  • Branch: {branch}")
    print(f"  • Status: {status.upper()}")
    print(f"  • Conclusion: {conclusion.upper()}")
    if url:
        print(f"  • URL: {url}")

    if conclusion == "failure":
        print("\n🔍 Extracting Failure Diagnostics...")
        log = get_run_failed_log_gh(str(rid))
        if log:
            errors = extract_kotlin_compiler_errors(log)
            if errors:
                for err in errors:
                    print(err)

def main():
    parser = argparse.ArgumentParser(description="GitHub Actions Remote CI Workflow Monitor & Diagnostics")
    parser.add_argument("run_id", nargs="?", help="Specific GitHub Actions run ID (default: latest)")
    parser.add_argument("--watch", action="store_true", help="Stream progress until the workflow completes")
    parser.add_argument("--timeout", type=int, default=360, help="Max watch duration in seconds (default: 360)")

    args = parser.parse_args()

    if args.watch:
        success = watch_ci(run_id=args.run_id, timeout=args.timeout)
        sys.exit(0 if success else 1)
    else:
        show_ci_status(run_id=args.run_id)

if __name__ == "__main__":
    main()
