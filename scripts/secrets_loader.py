#!/usr/bin/env python3
"""
Secure Local Secrets & Configuration Loader

Loads API keys and credentials from untracked local sources:
1. Environment Variables (os.environ).
2. .secrets.json in REPO_ROOT.
3. .env in REPO_ROOT.
4. ~/.config/aniyomi/secrets.json (User home directory).

Never exposes or hardcodes API keys in git-tracked code.
"""

import json
import os
from pathlib import Path
from typing import Optional

REPO_ROOT = Path(__file__).resolve().parent.parent


def get_secret(key: str, default: Optional[str] = None) -> Optional[str]:
    """Retrieves a secret from environment or untracked local files."""
    # 1. Environment Variable
    if key in os.environ and os.environ[key].strip():
        return os.environ[key].strip()

    # 2. Local .secrets.json in REPO_ROOT
    local_secrets = REPO_ROOT / ".secrets.json"
    if local_secrets.exists():
        try:
            data = json.loads(local_secrets.read_text(encoding="utf-8"))
            if key in data and data[key]:
                return str(data[key]).strip()
        except Exception:
            pass

    # 3. Local .env file
    local_env = REPO_ROOT / ".env"
    if local_env.exists():
        try:
            for line in local_env.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line.startswith(f"{key}="):
                    val = line.split("=", 1)[1].strip().strip('"').strip("'")
                    if val:
                        return val
        except Exception:
            pass

    # 4. User Config in ~/.config/aniyomi/secrets.json
    home_secrets = Path.home() / ".config" / "aniyomi" / "secrets.json"
    if home_secrets.exists():
        try:
            data = json.loads(home_secrets.read_text(encoding="utf-8"))
            if key in data and data[key]:
                return str(data[key]).strip()
        except Exception:
            pass

    return default
