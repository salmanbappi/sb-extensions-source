import gzip
import html
import json
import shutil
import sys
from pathlib import Path

# Add script directory to import index_pb2
sys.path.insert(0, str(Path(__file__).resolve().parent))
import index_pb2

REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath(sys.argv[2])

to_delete: list[str] = json.loads(sys.argv[1])

SIGNING_KEY_FINGERPRINT = "c7ebe223044970f2f9738f600dc25c180d3ed03994e088aaf5709338c57b93af"
REPO_NAME = "SalmanBappi Extensions"
REPO_BADGE = "SB"
REPO_SHORT_NAME = "salmanbappi"
REPO_WEBSITE = "https://github.com/salmanbappi/extensions-repo"
COMMUNITY_DISCORD = "https://discord.gg/G6g4vBmcXp"
RAW_BASE_URL = "https://raw.githubusercontent.com/salmanbappi/extensions-repo/main"
INDEX_PB_URL = f"{RAW_BASE_URL}/index.pb"

# 1. Clean up deleted modules
for module in to_delete:
    apk_name = f"aniyomi-{module}-v*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.animeextension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(f"Removing old APK: {file.name}")
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(f"Removing old icon: {file.name}")
        file.unlink(missing_ok=True)

# 2. Copy new APKs and icons
if LOCAL_REPO.joinpath("apk").is_dir():
    shutil.copytree(src=LOCAL_REPO.joinpath("apk"), dst=REMOTE_REPO.joinpath("apk"), dirs_exist_ok=True)
if LOCAL_REPO.joinpath("icon").is_dir():
    shutil.copytree(src=LOCAL_REPO.joinpath("icon"), dst=REMOTE_REPO.joinpath("icon"), dirs_exist_ok=True)

# 3. Read remote and local indices
remote_index_path = REMOTE_REPO.joinpath("index.min.json")
if remote_index_path.exists():
    with remote_index_path.open(encoding="utf-8") as remote_index_file:
        remote_index = json.load(remote_index_file)
else:
    remote_index = []

local_index_path = LOCAL_REPO.joinpath("index.min.json")
if local_index_path.exists():
    with local_index_path.open(encoding="utf-8") as local_index_file:
        local_index = json.load(local_index_file)
else:
    local_index = []

local_pkgs = {item["pkg"] for item in local_index}

# Merge: remove deleted modules and replace any updated packages
index = [
    item
    for item in remote_index
    if item["pkg"] not in local_pkgs and not any(item["pkg"].endswith(f".{module}") for module in to_delete)
]
index.extend(local_index)
index.sort(key=lambda x: x["pkg"])

# 4. Generate legacy files (index.json, index.min.json, index.html)
with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    json.dump(index, index_file, ensure_ascii=False, indent=2)

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
    json.dump(index, index_min_file, ensure_ascii=False, separators=(",", ":"))

with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>SalmanBappi Extensions</title>\n</head>\n<body>\n<pre>\n')
    for entry in index:
        apk_escaped = 'apk/' + html.escape(entry["apk"])
        name_escaped = html.escape(entry["name"])
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write('</pre>\n</body>\n</html>\n')

# 5. Build and serialize modern Protobuf index (index.pb)
proto_extensions = []
for entry in index:
    pkg = entry["pkg"]
    apk_name = entry.get("apk", "")
    apk_url = f"{RAW_BASE_URL}/apk/{apk_name}" if apk_name else ""
    icon_url = f"{RAW_BASE_URL}/icon/{pkg}.png"

    version_str = str(entry.get("version", "1.0"))
    extension_lib = str(entry.get("extensionLib") or (version_str.split(".")[0] if "." in version_str else "16"))
    version_code = int(entry.get("code", 0))

    # ContentWarning: UNSPECIFIED=0, SAFE=1, MIXED=2, NSFW=3
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

# Deterministic gzip compression with mtime=0 to eliminate git noise
serialized_proto = index_proto.SerializeToString(deterministic=True)
with REMOTE_REPO.joinpath("index.pb").open("wb") as pb_file:
    pb_file.write(gzip.compress(serialized_proto, mtime=0))

# 6. Update modern repo.json
repo_json_data = {
    "index_v2": INDEX_PB_URL,
    "meta": {
        "name": REPO_NAME,
        "shortName": REPO_SHORT_NAME,
        "website": REPO_WEBSITE,
        "discord": COMMUNITY_DISCORD,
        "signingKeyFingerprint": SIGNING_KEY_FINGERPRINT,
    },
}
with REMOTE_REPO.joinpath("repo.json").open("w", encoding="utf-8") as f_repo:
    json.dump(repo_json_data, f_repo, indent=2)
    f_repo.write("\n")

print(f"Successfully generated repo index with {len(proto_extensions)} extensions (Protobuf + JSON).")
