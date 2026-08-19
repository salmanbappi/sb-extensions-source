from pathlib import Path
import re
import shutil

REPO_APK_DIR = Path("repo/apk")

shutil.rmtree(REPO_APK_DIR, ignore_errors=True)
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

artifacts_dir = Path.home().joinpath("apk-artifacts")
if artifacts_dir.is_dir():
    for apk in artifacts_dir.glob("**/*.apk"):
        apk_name = re.sub(r"-(release|debug)\.apk$", ".apk", apk.name)
        shutil.move(apk, REPO_APK_DIR.joinpath(apk_name))
