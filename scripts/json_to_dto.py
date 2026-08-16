#!/usr/bin/env python3
"""
JSON to Kotlinx Serialization DTO Generator & HAR Reverse-Engineering Engine
Converts live JSON responses, files, or HAR network traces into 100% v16 null-safe Kotlin DTOs.
"""

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple


KOTLIN_RESERVED_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while", "by", "companion", "constructor", "delegate",
    "dynamic", "file", "get", "init", "param", "property", "receiver",
    "set", "setparam", "where"
}


def to_camel_case(name: str) -> str:
    """Converts snake_case or kebab-case into camelCase and sanitizes Kotlin keywords."""
    clean = re.sub(r'[^a-zA-Z0-9_]', '_', name)
    words = [w for w in clean.split('_') if w]
    if not words:
        return "prop"
    first = words[0].lower()
    rest = ''.join(w.capitalize() for w in words[1:])
    result = first + rest
    # If starts with digit, prefix with prop
    if result and result[0].isdigit():
        result = "prop" + result
    if result in KOTLIN_RESERVED_KEYWORDS:
        result = f"{result}Value"
    return result


def to_pascal_case(name: str) -> str:
    """Converts snake_case or words into PascalCase."""
    clean = re.sub(r'[^a-zA-Z0-9_]', '_', name)
    words = [w for w in clean.split('_') if w]
    if not words:
        return "ItemDto"
    result = ''.join(w.capitalize() for w in words)
    if result and result[0].isdigit():
        result = "Item" + result
    if not result.endswith("Dto"):
        result += "Dto"
    return result


INVARIANT_NOUNS = {
    "series", "species", "news", "status", "release", "releases",
    "canvas", "address", "actress", "bonus", "virus", "bus", "corpus"
}


def singularize(name: str) -> str:
    """Safely singularizes a property name for list inner types."""
    if not name:
        return "Item"
    lower = name.lower()
    if lower in INVARIANT_NOUNS:
        return name
    if name.endswith("ies") and len(name) > 3:
        return name[:-3] + "y"
    if name.endswith("es") and len(name) > 3 and not name.endswith(("ses", "xes", "zes", "ches", "shes")):
        return name[:-2]
    if name.endswith("s") and len(name) > 3 and not name.endswith(("ss", "us", "is", "as", "os")):
        return name[:-1]
    return name


class JsonToDtoConverter:
    def __init__(self, root_class_name: str = "ApiResponseDto"):
        self.root_class_name = to_pascal_case(root_class_name)
        self.generated_classes: Dict[str, List[Tuple[str, str, str]]] = {}
        self.class_names: Set[str] = set()

    def infer_type(self, val: Any, prop_name: str) -> str:
        """Recursively infers Kotlin type from a Python JSON value."""
        if val is None:
            return "String?"
        elif isinstance(val, bool):
            return "Boolean?"
        elif isinstance(val, int):
            # Check if exceeds 32-bit signed int
            if abs(val) > 2147483647:
                return "Long?"
            return "Int?"
        elif isinstance(val, float):
            return "Double?"
        elif isinstance(val, str):
            return "String?"
        elif isinstance(val, list):
            if not val:
                return "List<String>?"
            item_sample = val[0]
            item_type = self.infer_type(item_sample, singularize(prop_name))
            # If item type was nullable (e.g. String?), strip ? for List<String>?
            inner = item_type.rstrip('?')
            return f"List<{inner}>?"
        elif isinstance(val, dict):
            nested_class = to_pascal_case(prop_name)
            self._process_object(val, nested_class)
            return f"{nested_class}?"
        return "String?"

    def _process_object(self, obj: Dict[str, Any], class_name: str):
        """Processes a JSON object into a Kotlin data class."""
        if class_name in self.generated_classes:
            return

        fields = []
        for raw_key, val in obj.items():
            camel_key = to_camel_case(raw_key)
            kt_type = self.infer_type(val, raw_key)
            fields.append((raw_key, camel_key, kt_type))

        self.generated_classes[class_name] = fields

    def generate_kotlin(self, json_data: Any) -> str:
        """Generates complete Kotlin source file content."""
        if isinstance(json_data, list):
            if not json_data:
                sample = {}
                self._process_object(sample, self.root_class_name)
            else:
                sample = json_data[0]
                if isinstance(sample, dict):
                    self._process_object(sample, self.root_class_name)
                else:
                    inner_type = self.infer_type(sample, "item").rstrip('?')
                    return f"// Root JSON is a primitive Array\ntypealias {self.root_class_name} = List<{inner_type}>\n"
        elif isinstance(json_data, dict):
            self._process_object(json_data, self.root_class_name)
        else:
            raise ValueError("Input JSON must be an Object or Array.")

        output_lines = [
            "import kotlinx.serialization.SerialName",
            "import kotlinx.serialization.Serializable",
            "",
        ]

        for cls_name, fields in self.generated_classes.items():
            output_lines.append("@Serializable")
            if not fields:
                output_lines.append(f"data class {cls_name}(")
                output_lines.append("    val extra: String? = null")
                output_lines.append(")")
            else:
                output_lines.append(f"data class {cls_name}(")
                param_lines = []
                for raw_key, camel_key, kt_type in fields:
                    clean_key = camel_key.strip("`")
                    has_alias = raw_key != clean_key
                    alias_annotation = f'@SerialName("{raw_key}") ' if has_alias else ""
                    param_lines.append(f"    {alias_annotation}val {camel_key}: {kt_type} = null")

                output_lines.append(",\n".join(param_lines))
                output_lines.append(")")
            output_lines.append("")

        return "\n".join(output_lines)


def parse_har(har_content: str) -> List[Dict[str, Any]]:
    """Extracts JSON endpoints from a HAR network export."""
    har = json.loads(har_content)
    entries = har.get("log", {}).get("entries", [])
    results = []

    for entry in entries:
        req = entry.get("request", {})
        resp = entry.get("response", {})
        url = req.get("url", "")
        method = req.get("method", "GET")
        mime = resp.get("content", {}).get("mimeType", "")
        text = resp.get("content", {}).get("text", "")

        if "json" in mime.lower() and text:
            try:
                parsed_json = json.loads(text)
                results.append({
                    "url": url,
                    "method": method,
                    "json": parsed_json
                })
            except Exception:
                pass
    return results


def main():
    parser = argparse.ArgumentParser(description="JSON to Kotlinx Serialization DTO Generator & HAR Parser")
    parser.add_argument("source", help="URL, JSON file path, or HAR file path")
    parser.add_argument("--root", default="ApiResponseDto", help="Root data class name (default: ApiResponseDto)")
    parser.add_argument("--out", "-o", help="Optional output Kotlin file path")

    args = parser.parse_args()

    payload = None
    if args.source.strip().startswith("{") or args.source.strip().startswith("["):
        try:
            payload = json.loads(args.source)
        except Exception as e:
            print(f"❌ Failed to parse inline JSON string: {e}")
            sys.exit(1)
    elif args.source.startswith("http://") or args.source.startswith("https://"):
        print(f"📡 Fetching JSON from: {args.source}...")
        try:
            req = urllib.request.Request(
                args.source,
                headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                text = resp.read().decode("utf-8")
                payload = json.loads(text)
        except Exception as e:
            print(f"❌ Failed to fetch JSON from URL: {e}")
            sys.exit(1)
    else:
        src_path = Path(args.source)
        if not src_path.exists():
            print(f"❌ File not found: {args.source}")
            sys.exit(1)
        content = src_path.read_text(encoding="utf-8")
        if src_path.suffix.lower() == ".har":
            print(f"📦 Extracting JSON responses from HAR file: {src_path.name}...")
            har_entries = parse_har(content)
            if not har_entries:
                print("❌ No valid JSON API responses found in HAR file.")
                sys.exit(1)
            print(f"  Found {len(har_entries)} JSON endpoint(s):")
            for idx, entry in enumerate(har_entries, 1):
                print(f"    [{idx}] {entry['method']} {entry['url']}")
            payload = har_entries[0]["json"]
        else:
            try:
                payload = json.loads(content)
            except Exception as e:
                print(f"❌ Failed to parse JSON from file '{src_path.name}': {e}")
                sys.exit(1)

    converter = JsonToDtoConverter(root_class_name=args.root)
    kotlin_code = converter.generate_kotlin(payload)

    if args.out:
        out_file = Path(args.out)
        out_file.parent.mkdir(parents=True, exist_ok=True)
        out_file.write_text(kotlin_code, encoding="utf-8")
        print(f"🚀 Successfully generated Kotlin DTO models -> {out_file}")
    else:
        print("\n" + "=" * 60)
        print("Generated Kotlinx Serialization Models (v16 Null-Safe):")
        print("=" * 60)
        print(kotlin_code)


if __name__ == "__main__":
    main()
