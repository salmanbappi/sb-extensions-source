#!/usr/bin/env python3

import sys
assert sys.version_info >= (3, 12), "Requires Python 3.12+"

import itertools
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import NoReturn

EXTENSION_REGEX = re.compile(r"^src/(?P<lang>\w+)/(?P<extension>\w+)")
MULTISRC_LIB_REGEX = re.compile(r"^lib-multisrc/(?P<multisrc>\w+)")
LIB_REGEX = re.compile(r"^lib/(?P<lib>[\w-]+)")
MODULE_REGEX = re.compile(r"^:src:(?P<lang>\w+):(?P<extension>\w+)$")
CORE_FILES_REGEX = re.compile(
    r"^(buildSrc/|core/|gradle/|build\.gradle\.kts|common\.gradle|gradle\.properties|settings\.gradle\.kts|utils/)"
)

def run_command(command: str) -> str:
    result = subprocess.run(command, capture_output=True, text=True, shell=True)
    if result.returncode != 0:
        print(result.stderr.strip())
        sys.exit(result.returncode)
    return result.stdout.strip()

def get_dependencies_from_dir(directory: Path) -> set[str]:
    dependencies = set()
    for filename in ["build.gradle", "build.gradle.kts"]:
        filepath = directory / filename
        if filepath.is_file():
            try:
                content = filepath.read_text(encoding="utf-8")
                for match in re.findall(r"project\(\s*['\"]([^'\"]+)['\"]\s*\)", content):
                    dependencies.add(match)
            except Exception:
                pass
    return dependencies

def find_dependents_python(modified_libs: set[str]) -> set[str]:
    deps = {}
    
    src_dir = Path("src")
    if src_dir.is_dir():
        for lang_dir in src_dir.iterdir():
            if not lang_dir.is_dir():
                continue
            for ext_dir in lang_dir.iterdir():
                if not ext_dir.is_dir():
                    continue
                proj_path = f":src:{lang_dir.name}:{ext_dir.name}"
                deps[proj_path] = get_dependencies_from_dir(ext_dir)
                
    multisrc_dir = Path("lib-multisrc")
    if multisrc_dir.is_dir():
        for m_dir in multisrc_dir.iterdir():
            if not m_dir.is_dir():
                continue
            proj_path = f":lib-multisrc:{m_dir.name}"
            deps[proj_path] = get_dependencies_from_dir(m_dir)
            
    lib_dir = Path("lib")
    if lib_dir.is_dir():
        for l_dir in lib_dir.iterdir():
            if not l_dir.is_dir():
                continue
            proj_path = f":lib:{l_dir.name}"
            deps[proj_path] = get_dependencies_from_dir(l_dir)

    dependent_modules = set()
    
    def find_all_dependents(target: str, visited: set[str]):
        if target in visited:
            return
        visited.add(target)
        for proj, proj_deps in deps.items():
            if target in proj_deps:
                if proj.startswith(":src:"):
                    dependent_modules.add(proj)
                else:
                    find_all_dependents(proj, visited)

    for lib in modified_libs:
        find_all_dependents(lib, set())
        
    return dependent_modules

def get_module_list(ref: str) -> tuple[list[str], list[str]]:
    changed_files = run_command(f"git diff --name-only {ref}").splitlines()

    modules = set()
    libs = set()
    deleted = set()
    core_files_changed = False

    for file in map(lambda x: Path(x).as_posix(), changed_files):
        if CORE_FILES_REGEX.search(file):
            core_files_changed = True
        elif match := EXTENSION_REGEX.search(file):
            lang = match.group("lang")
            extension = match.group("extension")
            if Path("src", lang, extension).is_dir():
                modules.add(f':src:{lang}:{extension}')
            deleted.add(f"{lang}.{extension}")
        elif match := MULTISRC_LIB_REGEX.search(file):
            multisrc = match.group("multisrc")
            if Path("lib-multisrc", multisrc).is_dir():
                libs.add(f":lib-multisrc:{multisrc}")
        elif match := LIB_REGEX.search(file):
            lib = match.group("lib")
            if Path("lib", lib).is_dir():
                libs.add(f":lib:{lib}")

    def is_extension_module(module: str) -> bool:
        if not (match := MODULE_REGEX.search(module)):
            return False
        lang = match.group("lang")
        extension = match.group("extension")
        deleted.add(f"{lang}.{extension}")
        return True

    if libs and not core_files_changed:
        for module in find_dependents_python(libs):
            if is_extension_module(module):
                modules.add(module)

    if os.getenv("IS_PR_CHECK") != "true" and not core_files_changed:
        with Path(".github/always_build.json").open() as always_build_file:
            always_build = json.load(always_build_file)
        for extension in always_build:
            modules.add(":src:" + extension.replace(".", ":"))
            deleted.add(extension)

    if core_files_changed:
        (all_modules, all_deleted) = get_all_modules()

        modules.update(all_modules)
        deleted.update(all_deleted)

    return list(modules), list(deleted)

def get_all_modules() -> tuple[list[str], list[str]]:
    modules = []
    deleted = []
    for lang in Path("src").iterdir():
        for extension in lang.iterdir():
            modules.append(f":src:{lang.name}:{extension.name}")
            deleted.append(f"{lang.name}.{extension.name}")
    return modules, deleted

def main() -> NoReturn:
    _, ref, build_type = sys.argv
    modules, deleted = get_module_list(ref)

    chunked = {
        "chunk": [
            {"number": i + 1, "modules": modules}
            for i, modules in
            enumerate(itertools.batched(
                map(lambda x: f"{x}:assemble{build_type}", modules),
                int(os.getenv("CI_CHUNK_SIZE", 65))
            ))
        ]
    }

    print(f"Module chunks to build:\n{json.dumps(chunked, indent=2)}\n\nModule to delete:\n{json.dumps(deleted, indent=2)}")

    if os.getenv("CI") == "true":
        with open(os.getenv("GITHUB_OUTPUT"), 'a') as out_file:
            out_file.write(f"matrix={json.dumps(chunked)}\n")
            out_file.write(f"delete={json.dumps(deleted)}\n")

if __name__ == '__main__':
    main()
