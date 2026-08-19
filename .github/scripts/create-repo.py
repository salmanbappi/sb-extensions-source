import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.animeextension.nsfw' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-(?:320|480|640|240|160):'([^']+)'", re.MULTILINE)
APPLICATION_ICON_FALLBACK_REGEX = re.compile(r"(?:application-icon(?:-\d+)?|icon)='([^']+)'")
LANGUAGE_REGEX = re.compile(r"aniyomi-([^.]+)")
sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
ANDROID_BUILD_TOOLS = None
if sdk_root and Path(sdk_root).is_dir():
    build_tools_dir = Path(sdk_root) / "build-tools"
    if build_tools_dir.is_dir():
        tools_versions = sorted([d for d in build_tools_dir.iterdir() if d.is_dir()])
        if tools_versions:
            ANDROID_BUILD_TOOLS = tools_versions[-1]

AAPT_BIN = str(ANDROID_BUILD_TOOLS / "aapt") if ANDROID_BUILD_TOOLS else (shutil.which("aapt") or "aapt")
REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"

REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

inspector_data = {}
if Path("output.json").is_file():
    with open("output.json", encoding="utf-8") as f:
        try:
            inspector_data = json.load(f)
        except Exception:
            inspector_data = {}

index_min_data = []

if REPO_APK_DIR.is_dir():
    for apk in sorted(REPO_APK_DIR.glob("*.apk")):
        badging = subprocess.check_output(
            [
                AAPT_BIN,
                "dump",
                "--include-meta-data",
                "badging",
                apk,
            ]
        ).decode()

        package_info_lines = [x for x in badging.splitlines() if x.startswith("package: ")]
        if not package_info_lines:
            continue
        package_info = package_info_lines[0]

        pkg_match = PACKAGE_NAME_REGEX.search(package_info)
        if not pkg_match:
            continue
        package_name = pkg_match[1]

        icon_match = APPLICATION_ICON_320_REGEX.search(badging) or APPLICATION_ICON_FALLBACK_REGEX.search(badging)
        if icon_match:
            icon_path = icon_match[1]
            try:
                with ZipFile(apk) as z, z.open(icon_path) as i, (
                    REPO_ICON_DIR / f"{package_name}.png"
                ).open("wb") as f:
                    f.write(i.read())
            except Exception:
                pass

        lang_match = LANGUAGE_REGEX.search(apk.name)
        language = lang_match[1] if lang_match else "all"
        sources = inspector_data.get(package_name, [])

        if len(sources) == 1:
            source_language = sources[0].get("lang")

            if (
                source_language
                and source_language != language
                and source_language not in {"all", "other"}
                and language not in {"all", "other"}
            ):
                language = source_language

        label_match = APPLICATION_LABEL_REGEX.search(badging)
        app_name = label_match[1] if label_match else package_name
        code_match = VERSION_CODE_REGEX.search(package_info)
        version_code = int(code_match[1]) if code_match else 0
        ver_match = VERSION_NAME_REGEX.search(package_info)
        version_name = ver_match[1] if ver_match else "1.0"
        nsfw_match = IS_NSFW_REGEX.search(badging)
        is_nsfw = int(nsfw_match[1]) if nsfw_match else 0

        common_data = {
            "name": app_name,
            "pkg": package_name,
            "apk": apk.name,
            "lang": language,
            "code": version_code,
            "version": version_name,
            "nsfw": is_nsfw,
        }
        min_data = {
            **common_data,
            "sources": [],
        }

        for source in sources:
            min_data["sources"].append(
                {
                    "name": source.get("name", ""),
                    "lang": source.get("lang", ""),
                    "id": source.get("id", ""),
                    "baseUrl": source.get("baseUrl", ""),
                }
            )

        index_min_data.append(min_data)

with REPO_DIR.joinpath("index.min.json").open("w", encoding="utf-8") as index_file:
    json.dump(index_min_data, index_file, ensure_ascii=False, separators=(",", ":"))
