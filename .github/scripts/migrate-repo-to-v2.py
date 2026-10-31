#!/usr/bin/env python3
"""
Migration tool to backfill and publish index.pb (Protobuf v2) and repo.json
to salmanbappi/extensions-repo.

Usage:
    python3 .github/scripts/migrate-repo-to-v2.py [--repo-dir <path>] [--push]
"""

import argparse
import gzip
import json
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

# Add script directory to path to import index_pb2
SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

try:
    import index_pb2
except ImportError:
    print("❌ Error: index_pb2 module not found in .github/scripts.")
    sys.exit(1)

SIGNING_KEY_FINGERPRINT = "c7ebe223044970f2f9738f600dc25c180d3ed03994e088aaf5709338c57b93af"
REPO_NAME = "SalmanBappi Extensions"
REPO_BADGE = "SB"
REPO_SHORT_NAME = "salmanbappi"
REPO_WEBSITE = "https://github.com/salmanbappi/extensions-repo"
COMMUNITY_DISCORD = "https://discord.gg/G6g4vBmcXp"
RAW_BASE_URL = "https://raw.githubusercontent.com/salmanbappi/extensions-repo/main"
INDEX_PB_URL = f"{RAW_BASE_URL}/index.pb"


def build_protobuf_index(entries: list[dict]) -> tuple[bytes, dict]:
    proto_extensions = []
    for entry in entries:
        pkg = entry["pkg"]
        apk_name = entry.get("apk", "")
        apk_url = f"{RAW_BASE_URL}/apk/{apk_name}" if apk_name else ""
        icon_url = f"{RAW_BASE_URL}/icon/{pkg}.png"

        version_str = str(entry.get("version", "1.0"))
        extension_lib = str(entry.get("extensionLib") or (version_str.split(".")[0] if "." in version_str else "16"))
        version_code = int(entry.get("code", 0))

        content_warning = int(entry.get("contentRating") or (3 if entry.get("nsfw") == 1 else 1))
        is_torrent = bool(entry.get("isTorrent", False))

        proto_sources = []
        for s in entry.get("sources", []):
            try:
                s_id = int(s.get("id") or 0)
            except (ValueError, TypeError):
                s_id = 0
            proto_sources.append(
                index_pb2.Source(
                    id=s_id,
                    name=str(s.get("name", "")),
                    language=str(s.get("lang", "")),
                    homeUrl=str(s.get("baseUrl", "")),
                    mirrorUrls=list(s.get("mirrorUrls", [])),
                )
            )

        proto_extensions.append(
            index_pb2.Extension(
                name=str(entry.get("name", pkg)),
                packageName=pkg,
                resources=index_pb2.Resources(
                    apkUrl=apk_url,
                    iconUrl=icon_url,
                ),
                extensionLib=extension_lib,
                versionCode=version_code,
                versionName=version_str,
                contentWarning=content_warning,
                isTorrent=is_torrent,
                sources=proto_sources,
            )
        )

    index_proto = index_pb2.Index(
        name=REPO_NAME,
        badgeLabel=REPO_BADGE,
        signingKey=SIGNING_KEY_FINGERPRINT,
        contact=index_pb2.Contact(
            website=REPO_WEBSITE,
            discord=COMMUNITY_DISCORD,
        ),
        extensionList=index_pb2.ExtensionList(extensions=proto_extensions),
    )

    serialized = index_proto.SerializeToString(deterministic=True)
    gzipped = gzip.compress(serialized, mtime=0)

    repo_json = {
        "index_v2": INDEX_PB_URL,
        "meta": {
            "name": REPO_NAME,
            "shortName": REPO_SHORT_NAME,
            "website": REPO_WEBSITE,
            "discord": COMMUNITY_DISCORD,
            "signingKeyFingerprint": SIGNING_KEY_FINGERPRINT,
        },
    }

    return gzipped, repo_json


def main():
    parser = argparse.ArgumentParser(description="Migrate salmanbappi/extensions-repo to Protobuf v2")
    parser.add_argument("--repo-dir", help="Path to local checkout of extensions-repo", default=None)
    parser.add_argument("--push", action="store_true", help="Commit and push changes to remote")
    args = parser.parse_args()

    if args.repo_dir:
        repo_path = Path(args.repo_dir).resolve()
        if not (repo_path / "index.min.json").exists():
            print(f"❌ Error: index.min.json not found in {repo_path}")
            sys.exit(1)
        with (repo_path / "index.min.json").open("r", encoding="utf-8") as f:
            entries = json.load(f)
    else:
        print(f"🌐 Fetching live catalog from {RAW_BASE_URL}/index.min.json...")
        req = urllib.request.urlopen(f"{RAW_BASE_URL}/index.min.json")
        entries = json.loads(req.read().decode("utf-8"))
        repo_path = None

    print(f"📦 Loaded {len(entries)} extensions.")
    gzipped_pb, repo_json = build_protobuf_index(entries)
    print(f"✨ Successfully generated index.pb ({len(gzipped_pb):,} bytes compressed).")

    if repo_path:
        # Write to local repo directory
        pb_dest = repo_path / "index.pb"
        repo_json_dest = repo_path / "repo.json"

        with open(pb_dest, "wb") as f:
            f.write(gzipped_pb)
        with open(repo_json_dest, "w", encoding="utf-8") as f:
            json.dump(repo_json, f, indent=2)
            f.write("\n")

        print(f"✅ Written {pb_dest}")
        print(f"✅ Written {repo_json_dest}")

        if args.push:
            print("🚀 Committing and pushing to origin main and repo...")
            subprocess.run(["git", "add", "index.pb", "repo.json"], cwd=repo_path, check=True)
            subprocess.run(
                ["git", "commit", "-m", "Migrate repository to Extension Stores (Index v2 / Protobuf)"],
                cwd=repo_path,
                check=True,
            )
            subprocess.run(["git", "push", "origin", "HEAD:main", "HEAD:repo"], cwd=repo_path, check=True)
            print("🎉 Pushed to remote successfully!")
    else:
        out_dir = SCRIPT_DIR.parent.parent / "scratch" / "repo_migration"
        out_dir.mkdir(parents=True, exist_ok=True)
        with open(out_dir / "index.pb", "wb") as f:
            f.write(gzipped_pb)
        with open(out_dir / "repo.json", "w", encoding="utf-8") as f:
            json.dump(repo_json, f, indent=2)
            f.write("\n")
        print(f"✅ Saved generated artifacts to {out_dir}")
        print("💡 To deploy directly to extensions-repo, run with: --repo-dir <path-to-extensions-repo> --push")


if __name__ == "__main__":
    main()
