import gzip
import html
import json
import os
import re
import shutil
import sys
from pathlib import Path

# Add script directory to import index_pb2
sys.path.insert(0, str(Path(__file__).resolve().parent))
import index_pb2
from google.protobuf import json_format

SIGNING_KEY_FINGERPRINT = "c7ebe223044970f2f9738f600dc25c180d3ed03994e088aaf5709338c57b93af"
REPO_NAME = "SalmanBappi Extensions"
REPO_BADGE = "SB"
REPO_SHORT_NAME = "salmanbappi"
REPO_WEBSITE = "https://github.com/salmanbappi/extensions-repo"
COMMUNITY_DISCORD = "https://discord.gg/G6g4vBmcXp"
# Serve mutable index files from GitHub raw (max-age=300) and immutable
# version-stamped APKs/icons from jsDelivr CDN, matching Yuzono/Secozzi.
RAW_BASE_URL = "https://raw.githubusercontent.com/salmanbappi/extensions-repo/repo"
CDN_BASE_URL = "https://cdn.jsdelivr.net/gh/salmanbappi/extensions-repo@repo"
BASE_URL = CDN_BASE_URL
INDEX_PB_URL = f"{RAW_BASE_URL}/index.pb"


def load_existing_pb_metadata(pb_path: Path) -> dict[str, dict]:
    """Extract fields from existing index.pb that legacy index.min.json omits."""
    if not pb_path.exists():
        return {}
    try:
        raw_bytes = gzip.decompress(pb_path.read_bytes())
        existing_index = index_pb2.Index()
        existing_index.ParseFromString(raw_bytes)
    except Exception as exc:
        print(f"Warning: could not read existing {pb_path.name}: {exc}")
        return {}

    meta_by_pkg: dict[str, dict] = {}
    for ext in existing_index.extensionList.extensions:
        meta_by_pkg[ext.packageName] = {
            "extensionLib": ext.extensionLib,
            "contentRating": int(ext.contentWarning),
            "mirrorUrlsBySourceId": {str(s.id): list(s.mirrorUrls) for s in ext.sources if s.mirrorUrls},
        }
    return meta_by_pkg


def normalize_extension_dict(entry: dict, base_url: str = BASE_URL) -> dict:
    """Normalize legacy dict entries into modern protobuf-ready extension dictionaries."""
    pkg = entry.get("pkg") or entry.get("packageName", "")
    apk_name = entry.get("apk", "")

    # Handle resources
    if "resources" in entry and isinstance(entry["resources"], dict):
        apk_url = entry["resources"].get("apkUrl", "")
        icon_url = entry["resources"].get("iconUrl", "")
    else:
        apk_url = f"{base_url}/apk/{apk_name}" if apk_name else ""
        icon_url = f"{base_url}/icon/{pkg}.png" if pkg else ""

    # Clean name: remove any legacy "Aniyomi: " or "Tachiyomi: " prefix for protobuf index
    raw_name = str(entry.get("name") or pkg)
    clean_name = re.sub(r"^(?:Aniyomi|Tachiyomi):\s*", "", raw_name).strip()

    version_str = str(entry.get("version") or entry.get("versionName") or "1.0")
    extension_lib = str(entry.get("extensionLib") or (version_str.split(".")[0] if "." in version_str else "16"))
    version_code = int(entry.get("versionCode") or entry.get("code") or 0)

    # Content warning: UNSPECIFIED=0, SAFE=1, MIXED=2, NSFW=3
    content_warning = int(
        entry.get("contentRating")
        or entry.get("contentWarning")
        or (3 if entry.get("nsfw") == 1 else 1)
    )

    # isTorrent flag
    is_torrent = bool(entry.get("isTorrent") or entry.get("torrent") or False)

    normalized_sources = []
    for s in entry.get("sources", []):
        try:
            s_id = int(s.get("id") or 0)
        except (ValueError, TypeError):
            s_id = 0
        normalized_sources.append({
            "id": s_id,
            "name": str(s.get("name", "")),
            "language": str(s.get("language") or s.get("lang") or ""),
            "homeUrl": str(s.get("homeUrl") or s.get("baseUrl") or ""),
            "mirrorUrls": list(s.get("mirrorUrls") or []),
        })

    return {
        "name": clean_name,
        "packageName": pkg,
        "resources": {
            "apkUrl": apk_url,
            "iconUrl": icon_url,
        },
        "extensionLib": extension_lib,
        "versionCode": version_code,
        "versionName": version_str,
        "contentWarning": content_warning,
        "isTorrent": is_torrent,
        "sources": normalized_sources,
        "apk": apk_name,
        "lang": entry.get("lang", ""),
        "nsfw": 1 if content_warning > 1 else 0,
    }


def build_index_messages(entries: list[dict]) -> list[index_pb2.Extension]:
    """Convert normalized dictionary entries into Protobuf Extension messages."""
    proto_extensions = []
    for entry in entries:
        norm = normalize_extension_dict(entry)
        proto_sources = [
            index_pb2.Source(
                id=s["id"],
                name=s["name"],
                language=s["language"],
                homeUrl=s["homeUrl"],
                mirrorUrls=s.get("mirrorUrls", []),
            )
            for s in norm["sources"]
        ]
        proto_extensions.append(
            index_pb2.Extension(
                name=norm["name"],
                packageName=norm["packageName"],
                resources=index_pb2.Resources(
                    apkUrl=norm["resources"]["apkUrl"],
                    iconUrl=norm["resources"]["iconUrl"],
                ),
                extensionLib=norm["extensionLib"],
                versionCode=norm["versionCode"],
                versionName=norm["versionName"],
                contentWarning=norm["contentWarning"],
                isTorrent=norm["isTorrent"],
                sources=proto_sources,
            )
        )
    return proto_extensions


def main():
    if len(sys.argv) < 3:
        print("Usage: python merge-repo.py <to_delete_json> <local_repo_path>")
        sys.exit(1)

    remote_repo: Path = Path.cwd()
    local_repo: Path = remote_repo.parent.joinpath(sys.argv[2])
    to_delete: list[str] = json.loads(sys.argv[1])

    # 1. Clean up deleted modules
    for module in to_delete:
        apk_name = f"aniyomi-{module}-v*.*.apk"
        icon_name = f"eu.kanade.tachiyomi.animeextension.{module}.png"
        for file in remote_repo.joinpath("apk").glob(apk_name):
            print(f"Removing old APK: {file.name}")
            file.unlink(missing_ok=True)
        for file in remote_repo.joinpath("icon").glob(icon_name):
            print(f"Removing old icon: {file.name}")
            file.unlink(missing_ok=True)

    # 2. Copy new APKs and icons
    if local_repo.joinpath("apk").is_dir():
        shutil.copytree(src=local_repo.joinpath("apk"), dst=remote_repo.joinpath("apk"), dirs_exist_ok=True)
    if local_repo.joinpath("icon").is_dir():
        shutil.copytree(src=local_repo.joinpath("icon"), dst=remote_repo.joinpath("icon"), dirs_exist_ok=True)

    # 3. Read remote and local indices
    remote_index_path = remote_repo.joinpath("index.min.json")
    if remote_index_path.exists():
        with remote_index_path.open(encoding="utf-8") as remote_index_file:
            remote_index = json.load(remote_index_file)
    else:
        remote_index = []

    local_index_path = local_repo.joinpath("index.min.json")
    if local_index_path.exists():
        with local_index_path.open(encoding="utf-8") as local_index_file:
            local_index = json.load(local_index_file)
    else:
        local_index = []

    local_pkgs = {item.get("pkg") or item.get("packageName") for item in local_index}

    # Merge: remove deleted modules and replace any updated packages
    merged_raw = [
        item
        for item in remote_index
        if (item.get("pkg") or item.get("packageName")) not in local_pkgs
        and not any((item.get("pkg") or item.get("packageName", "")).endswith(f".{module}") for module in to_delete)
    ]
    merged_raw.extend(local_index)
    merged_raw.sort(key=lambda x: x.get("pkg") or x.get("packageName", ""))

    # Backfill fields omitted by legacy index.min.json for carried-over entries
    existing_pb_meta = load_existing_pb_metadata(remote_repo.joinpath("index.pb"))
    for item in merged_raw:
        pkg = item.get("pkg") or item.get("packageName")
        if not pkg or pkg in local_pkgs:
            continue
        pb_meta = existing_pb_meta.get(pkg)
        if not pb_meta:
            continue
        if not item.get("extensionLib") and pb_meta.get("extensionLib"):
            item["extensionLib"] = pb_meta["extensionLib"]
        if not item.get("contentRating") and pb_meta.get("contentRating"):
            item["contentRating"] = pb_meta["contentRating"]
        mirrors_by_id = pb_meta.get("mirrorUrlsBySourceId", {})
        for source in item.get("sources", []):
            if not source.get("mirrorUrls"):
                mirrors = mirrors_by_id.get(str(source.get("id", "")))
                if mirrors:
                    source["mirrorUrls"] = mirrors

    # Normalize entries
    normalized_entries = [normalize_extension_dict(item) for item in merged_raw]

    # 4. Drop unreferenced files (clean up orphans)
    repo_apk_dir = remote_repo / "apk"
    repo_icon_dir = remote_repo / "icon"
    if repo_apk_dir.is_dir() and repo_icon_dir.is_dir():
        referenced_apks = {item["apk"] for item in normalized_entries if item.get("apk")}
        referenced_icons = {f"{item['packageName']}.png" for item in normalized_entries if item.get("packageName")}
        for file in repo_apk_dir.iterdir():
            if file.name not in referenced_apks and file.is_file():
                print(f"Removing unreferenced APK: {file.name}")
                file.unlink(missing_ok=True)
        for file in repo_icon_dir.iterdir():
            if file.name not in referenced_icons and file.is_file():
                print(f"Removing unreferenced icon: {file.name}")
                file.unlink(missing_ok=True)

    # 5. Build Protobuf index
    proto_extensions = build_index_messages(normalized_entries)
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

    # 6. Generate modern Protobuf index.json (matching Secozzi, Yuzono, Keiyoushi)
    with remote_repo.joinpath("index.json").open("w", encoding="utf-8") as f_json:
        f_json.write(
            json_format.MessageToJson(
                index_proto,
                always_print_fields_with_no_presence=False,
                preserving_proto_field_name=True,
            )
        )

    # 7. Generate legacy index.min.json for backward compatibility with older Aniyomi clients
    legacy_json_index = [
        {
            "name": f"Aniyomi: {norm['name']}",
            "pkg": norm["packageName"],
            "apk": norm["apk"],
            "lang": norm["lang"],
            "code": norm["versionCode"],
            "version": norm["versionName"],
            "nsfw": norm["nsfw"],
            "torrent": 1 if norm["isTorrent"] else 0,
            "sources": [
                {
                    "name": s["name"],
                    "lang": s["language"],
                    "id": str(s["id"]),
                    "baseUrl": s["homeUrl"],
                }
                for s in norm["sources"]
            ],
        }
        for norm in normalized_entries
    ]
    with remote_repo.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
        json.dump(legacy_json_index, index_min_file, ensure_ascii=False, separators=(",", ":"))

    # 8. Generate HTML directory index
    with remote_repo.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
        index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>SalmanBappi Extensions</title>\n</head>\n<body>\n<pre>\n')
        for norm in normalized_entries:
            apk_escaped = 'apk/' + html.escape(norm["apk"])
            name_escaped = html.escape(norm["name"])
            index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
        index_html_file.write('</pre>\n</body>\n</html>\n')

    # 9. Deterministic gzip compression of index.pb with mtime=0
    serialized_proto = index_proto.SerializeToString(deterministic=True)
    with remote_repo.joinpath("index.pb").open("wb") as pb_file:
        pb_file.write(gzip.compress(serialized_proto, mtime=0))

    # 10. Update modern repo.json
    repo_json_data = {
        "index_v2": INDEX_PB_URL,
        "meta": {
            "name": REPO_NAME,
            "shortName": REPO_SHORT_NAME,
            "website": REPO_WEBSITE,
            "signingKeyFingerprint": SIGNING_KEY_FINGERPRINT,
        },
    }
    with remote_repo.joinpath("repo.json").open("w", encoding="utf-8") as f_repo:
        json.dump(repo_json_data, f_repo, indent=2)
        f_repo.write("\n")

    print(f"Successfully generated repo index with {len(proto_extensions)} extensions (Protobuf v2 + JSON).")


if __name__ == "__main__":
    main()
