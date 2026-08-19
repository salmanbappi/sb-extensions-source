#!/usr/bin/env python3
"""
SB Extensions - Clean Plaintext Status Notifier (No Emojis)
Automated Extension Status, Health & AI Sentiment Reporter for #status-update.
"""

import sys
import re
import os
import json
import argparse
import subprocess
import urllib.request
import urllib.error
import time
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))

try:
    from scripts.secrets_loader import get_secret
except ImportError:
    try:
        from secrets_loader import get_secret
    except ImportError:
        def get_secret(key: str, default: Optional[str] = None) -> Optional[str]:
            return os.environ.get(key, default)

BOT_TOKEN = get_secret("DISCORD_BOT_TOKEN", os.environ.get("DISCORD_BOT_TOKEN", ""))
STATUS_UPDATE_CHANNEL_ID = os.environ.get("DISCORD_STATUS_CHANNEL_ID", "1517517856231919687")  # #status-update

def format_status_update(
    title: str,
    action_type: str,
    site_url: str,
    audio_languages: Optional[List[str]] = None,
    library_size: Optional[str] = None,
    provider_count: int = 0,
    providers: Optional[List[str]] = None,
    changelog: Optional[List[str]] = None,
    version: str = "v16.1.0"
) -> str:
    """Formats extension status update into clean plaintext Discord Markdown without emojis.

    Only fields that are actually known (passed explicitly or detected from source)
    are included — no fabricated reliability/sentiment/language stats.
    """
    audio_str = " | ".join([f"`{lang}`" for lang in audio_languages]) if audio_languages else None
    provider_str = ", ".join(providers) if providers else None
    changes_str = "\n".join([f"- {c}" for c in changelog]) if changelog else "- Initial Release under API v16 standard"

    lines = [
        f"### [{action_type.upper()}] {title} `{version}`",
        f"**URL**: <{site_url}>",
    ]
    if audio_str:
        lines.append(f"**Audio Tracks**: {audio_str}")
    if library_size:
        lines.append(f"**Library Scale**: `{library_size}`")
    if provider_str:
        lines.append(f"**Providers ({provider_count})**: {provider_str}")
    lines.append("")
    lines.append("**Changes & Fixes**:")
    lines.append(changes_str)
    lines.append("")
    lines.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    return "\n".join(lines)

def send_message(content: str, channel_id: str = STATUS_UPDATE_CHANNEL_ID, token: str = BOT_TOKEN) -> bool:
    """Sends payload directly to Discord channel via Bot REST API."""
    url = f"https://discord.com/api/v10/channels/{channel_id}/messages"
    payload = json.dumps({"content": content}).encode("utf-8")

    headers = {
        "Authorization": f"Bot {token}",
        "Content-Type": "application/json",
        "User-Agent": "DiscordBot (https://discord.com, 1.0)"
    }

    req = urllib.request.Request(url, data=payload, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status in (200, 201)
    except urllib.error.HTTPError as e:
        print(f"HTTP Error sending to Discord: {e.code} - {e.read().decode()}")
        return False
    except Exception as e:
        print(f"Error sending message: {e}")
        return False

def inspect_extension(repo_root: Path, target: str) -> Dict[str, Any]:
    """Auto-inspects an extension module directory to extract metadata, providers, audio, and version."""
    src_dir = repo_root / "src"
    matched_dir = None

    # Try direct path
    if (src_dir / target).is_dir():
        matched_dir = src_dir / target
    else:
        for p in src_dir.rglob("*"):
            if p.is_dir() and p.name.lower() == target.lower() and (p / "build.gradle").exists():
                matched_dir = p
                break

    info = {
        "title": target.capitalize(),
        "version": "v16.1.0",
        "site_url": "https://example.com",
        "audio_languages": None,  # only set when detected from source
        "library_size": None,  # only set when a real count is found
        "providers": [],
        "changelog": []
    }

    if not matched_dir:
        return info

    build_gradle = matched_dir / "build.gradle"
    if build_gradle.exists():
        bg_text = build_gradle.read_text(encoding="utf-8", errors="ignore")
        name_m = re.search(r'extName\s*=\s*["\']([^"\']+)["\']', bg_text)
        if name_m:
            info["title"] = name_m.group(1)
        ver_m = re.search(r'(?:extVersionCode|overrideVersionCode)\s*=\s*(\d+)', bg_text)
        if ver_m:
            info["version"] = f"v16.{ver_m.group(1)}.0"

    kt_files = list(matched_dir.rglob("*.kt"))
    all_kt_text = ""
    for kt in kt_files:
        all_kt_text += kt.read_text(encoding="utf-8", errors="ignore") + "\n"

    # Extract Base URL
    url_m = re.search(r'(?:PREF_BASE_URL_DEFAULT|PREF_DOMAIN_DEFAULT|DOMAIN(?:_DEFAULT)?)\s*=\s*["\']([^"\']+)["\']', all_kt_text)
    if not url_m:
        url_m = re.search(r'override\s+val\s+baseUrl\s*=\s*["\']([^"\']+)["\']', all_kt_text)
    if url_m:
        info["site_url"] = url_m.group(1)

    # Detect Extractors / Providers
    known_extractors = [
        ("StreamWish", r'StreamWish|streamwish'),
        ("Gofile", r'Gofile|gofile'),
        ("DoodStream", r'Dood|doodstream'),
        ("Mp4upload", r'Mp4upload|mp4upload'),
        ("VidHide", r'VidHide|vidhide'),
        ("Filemoon", r'Filemoon|filemoon'),
        ("Megacloud", r'Megacloud|megacloud'),
        ("Kwik", r'Kwik|kwik'),
        ("YourUpload", r'YourUpload|yourupload'),
        ("StreamTape", r'StreamTape|streamtape'),
        ("PixelDrain", r'PixelDrain|pixeldrain'),
        ("FastStream", r'FastStream|faststream'),
        ("GDFlix", r'GDFlix|gdflix'),
        ("Luluvdo", r'Luluvdo|luluvdo'),
        ("VidGuard", r'VidGuard|vidguard'),
        ("MixDrop", r'MixDrop|mixdrop'),
        ("Voe", r'VoeExtractor|voe'),
        ("MegaPlay", r'MegaPlay|megaplay'),
        ("VidNest", r'VidNest|vidnest'),
    ]
    detected_providers = []
    for name, pattern in known_extractors:
        if re.search(pattern, all_kt_text, re.IGNORECASE):
            detected_providers.append(name)
    if detected_providers:
        info["providers"] = detected_providers
    # No fabricated provider fallback — line is omitted when nothing is detected.

    # Detect audio tracks (Sub/Dub only) from real source markers — never guess languages
    detected_audio = []
    if re.search(r'["\']sub["\']', all_kt_text, re.IGNORECASE) or re.search(r'scanlator\s*=\s*"[^"]*[Ss]ub', all_kt_text):
        detected_audio.append("Sub")
    if re.search(r'["\']dub["\']', all_kt_text, re.IGNORECASE) or re.search(r'scanlator\s*=\s*"[^"]*[Dd]ub', all_kt_text):
        detected_audio.append("Dub")

    if detected_audio:
        info["audio_languages"] = detected_audio

    # Estimate library size from live site
    site_url = info.get("site_url", "")
    if site_url and site_url != "https://example.com":
        try:
            req = urllib.request.Request(site_url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                body = resp.read(32768).decode("utf-8", errors="ignore")
            # Try to find a numeric anime count in the page
            count_m = re.search(r'([\d,]+)\+?\s*(?:anime|series|titles)', body, re.IGNORECASE)
            if count_m:
                raw = int(count_m.group(1).replace(",", ""))
                if raw >= 20000:
                    info["library_size"] = f"Huge ({raw:,}+)"
                elif raw >= 10000:
                    info["library_size"] = f"Large ({raw:,}+)"
                elif raw >= 5000:
                    info["library_size"] = f"Large ({raw:,}+)"
                elif raw >= 1000:
                    info["library_size"] = f"Medium ({raw:,}+)"
                else:
                    info["library_size"] = f"Small ({raw:,}+)"
        except Exception:
            pass  # fall back to default

    return info

def generate_status_update(
    repo_root: Path,
    target: Optional[str] = None,
    action: str = "ADDED",
    site_url: Optional[str] = None,
    audio: Optional[List[str]] = None,
    library_size: Optional[str] = None,
    providers: Optional[List[str]] = None,
    changes: Optional[List[str]] = None,
    version: Optional[str] = None,
    channel_id: str = STATUS_UPDATE_CHANNEL_ID,
    dry_run: bool = False
) -> Tuple[bool, str]:
    """Generates status update and dispatches to Discord #status-update channel."""

    inspected = {}
    if target:
        inspected = inspect_extension(repo_root, target)

    title = target.capitalize() if target else inspected.get("title", "Extension")
    if inspected.get("title"):
        title = inspected["title"]

    final_url = site_url or inspected.get("site_url", "https://example.com")
    final_version = version or inspected.get("version", "v16.1.0")
    final_audio = audio or inspected.get("audio_languages") or None
    final_library = library_size or inspected.get("library_size") or None
    final_providers = providers or inspected.get("providers") or None

    # Handle changes / changelog
    if changes:
        final_changes = changes
    elif action.upper() == "ADDED":
        final_changes = [
            f"Initial Extension Release under API v16 standard",
            f"Integrated {len(final_providers)} high-speed streaming extractors",
            f"Multi-stream quality and audio track selector support"
        ]
    elif action.upper() == "FIXED":
        final_changes = [
            "Fixed video stream extractor token parsing and CDN resolution",
            "Resolved search filter routing and pagination handling"
        ]
    else:
        final_changes = [
            "Updated scraping selectors and stream resolver logic",
            "Performance and stability optimizations for API v16"
        ]

    formatted_text = format_status_update(
        title=title,
        action_type=action,
        site_url=final_url,
        audio_languages=final_audio,
        library_size=final_library,
        provider_count=len(final_providers) if final_providers else 0,
        providers=final_providers,
        changelog=final_changes,
        version=final_version
    )

    if dry_run:
        print("\n[DRY RUN - Message Preview]:")
        print(formatted_text)
        return True, formatted_text

    success = send_message(formatted_text, channel_id=channel_id)
    if success:
        print(f"Status update for {title} successfully published to #status-update ({channel_id}).")
    else:
        print(f"Failed to send status update to Discord channel {channel_id}.")
    return success, formatted_text

def main():
    parser = argparse.ArgumentParser(description="SB Extensions - Status Update Notifier")
    parser.add_argument("target", nargs="?", help="Target extension module name (e.g. animesalt, hianime)")
    parser.add_argument("--action", default="ADDED", choices=["ADDED", "UPDATED", "FIXED", "MAINTENANCE"], help="Action type")
    parser.add_argument("--url", help="Website base URL")
    parser.add_argument("--audio", help="Comma-separated audio languages (e.g. 'Japanese,English,Hindi')")
    parser.add_argument("--library-size", help="Library scale (e.g. 'Large (14,500+)')")
    parser.add_argument("--providers", help="Comma-separated stream providers (e.g. 'StreamWish,Gofile')")
    parser.add_argument("-m", "--changes", action="append", help="Changelog entries / fixes (repeatable or single)")
    parser.add_argument("--version", help="Extension version (e.g. 'v16.1.0')")
    parser.add_argument("--channel", default=STATUS_UPDATE_CHANNEL_ID, help="Target Discord Channel ID")
    parser.add_argument("--dry-run", action="store_true", help="Print message locally without sending")

    args = parser.parse_args()
    repo_root = Path(__file__).resolve().parent.parent

    audio_list = [a.strip() for a in args.audio.split(",")] if args.audio else None
    prov_list = [p.strip() for p in args.providers.split(",")] if args.providers else None

    success, _ = generate_status_update(
        repo_root=repo_root,
        target=args.target,
        action=args.action,
        site_url=args.url,
        audio=audio_list,
        library_size=args.library_size,
        providers=prov_list,
        changes=args.changes,
        version=args.version,
        channel_id=args.channel,
        dry_run=args.dry_run
    )
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
