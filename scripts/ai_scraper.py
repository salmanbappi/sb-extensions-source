#!/usr/bin/env python3
"""
AI-Powered HTML Reverse Engineering & Selector Generation Engine

Uses high-speed LLMs (Groq Llama 3.3 70B, Google Gemini Flash, OpenCode Zen DeepSeek)
to ingest raw HTML snippets / JS payloads and automatically generate:
1. CSS Selectors (Jsoup / BeautifulSoup).
2. Regex patterns for stream URLs / dynamic variables.
3. Kotlin parsing functions for Aniyomi extensions.
"""

import argparse
import json
import os
import re
import ssl
import sys
import time
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))

from scripts.secrets_loader import get_secret

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

DEFAULT_GEMINI_KEY = get_secret("GEMINI_API_KEY", "")
DEFAULT_GROQ_KEY = get_secret("GROQ_API_KEY", "")
DEFAULT_OPENCODE_KEY = get_secret("OPENCODE_API_KEY", "")

def call_groq(prompt: str, api_key: str = DEFAULT_GROQ_KEY, model: str = "llama-3.3-70b-versatile") -> Optional[str]:
    """Calls Groq Cloud API with ultra-low latency."""
    url = "https://api.groq.com/openai/v1/chat/completions"
    payload = json.dumps({
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": "You are an expert web scraping and Kotlin Aniyomi extension developer. Analyze HTML/JS and output structured, exact CSS selectors, regex, or Kotlin code."
            },
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.1
    }).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
    req = urllib.request.Request(url, data=payload, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15, context=SSL_CTX) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data["choices"][0]["message"]["content"]
    except Exception as e:
        print(f"  [!] Groq API Error: {e}", file=sys.stderr)
        return None

def call_gemini(prompt: str, api_key: str = DEFAULT_GEMINI_KEY, model: str = "gemini-flash-latest") -> Optional[str]:
    """Calls Google Gemini API."""
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    payload = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}]
    }).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
    req = urllib.request.Request(url, data=payload, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15, context=SSL_CTX) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            candidates = data.get("candidates", [])
            if candidates:
                parts = candidates[0].get("content", {}).get("parts", [])
                if parts:
                    return parts[0].get("text", "")
    except Exception as e:
        print(f"  [!] Gemini API Error: {e}", file=sys.stderr)
        return None

def call_opencode(prompt: str, api_key: str = DEFAULT_OPENCODE_KEY, model: str = "deepseek-v4-flash-free") -> Optional[str]:
    """Calls OpenCode Zen AI Gateway."""
    url = "https://opencode.ai/zen/v1/chat/completions"
    payload = json.dumps({
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": "You are an expert web scraping and Kotlin Aniyomi extension developer. Analyze HTML/JS and output structured, exact CSS selectors, regex, or Kotlin code."
            },
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.1
    }).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
    req = urllib.request.Request(url, data=payload, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20, context=SSL_CTX) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data["choices"][0]["message"]["content"]
    except Exception as e:
        print(f"  [!] OpenCode Zen Error: {e}", file=sys.stderr)
        return None

def prune_html_dom(raw_html: str, max_chars: int = 15000) -> str:
    """Prunes HTML DOM to reduce token size by 80%+ while preserving all critical selector attributes."""
    if not raw_html:
        return ""

    # 1. Remove comments
    html = re.sub(r'<!--[\s\S]*?-->', '', raw_html)
    # 2. Strip scripts (unless containing json), svgs, styles, audio/video tags, iframe bloat
    html = re.sub(r'<(style|svg|path|g|symbol|noscript|canvas|audio|video)\b[^>]*>[\s\S]*?<\/\1>', '', html, flags=re.IGNORECASE)
    # Remove scripts that don't look like JSON config blobs
    html = re.sub(r'<script\b(?![^>]*type=[\'"]application\/json[\'"])[^>]*>[\s\S]*?<\/script>', '', html, flags=re.IGNORECASE)
    # 3. Strip inline style & event handler attributes
    html = re.sub(r'\s+(?:style|onload|onclick|onerror|onmouseover|target|rel|aria-[a-z0-9-]+)=([\'\"]).*?\1', '', html, flags=re.IGNORECASE)
    # 4. Collapse consecutive whitespace & empty tags
    html = re.sub(r'[ \t]+', ' ', html)
    html = re.sub(r'\n\s*\n+', '\n', html)
    
    # 5. Token compression: collapse repeated sibling elements (e.g. 50 episode/movie list items down to 2)
    html = re.sub(r'(<li\b[^>]*>[\s\S]*?<\/li>\s*){3,}', r'\1\1<!-- ... additional sibling items collapsed for token optimization ... -->\n', html, flags=re.IGNORECASE)
    html = re.sub(r'(<tr\b[^>]*>[\s\S]*?<\/tr>\s*){3,}', r'\1\1<!-- ... additional table rows collapsed ... -->\n', html, flags=re.IGNORECASE)
    
    return html[:max_chars].strip()

def generate_selectors(target_html_or_url: str, provider: str = "auto") -> str:
    """Fetches HTML (if URL), prunes DOM bloat, and generates Jsoup CSS selectors & Kotlin code."""
    html_content = target_html_or_url
    if target_html_or_url.startswith("http://") or target_html_or_url.startswith("https://"):
        req = urllib.request.Request(target_html_or_url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
        try:
            with urllib.request.urlopen(req, timeout=10, context=SSL_CTX) as resp:
                html_content = resp.read().decode("utf-8", errors="replace")
        except Exception as e:
            return f"❌ Failed to fetch URL: {e}"

    pruned_html = prune_html_dom(html_content)

    prompt_builder = lambda snip: f"""
Given the following website HTML snippet, analyze the DOM structure and extract:
1. CSS Selector for the Anime / Movie Cards in popular/latest listing (e.g. `div.film-item`)
2. CSS Selector for Title, Link (href), and Poster Image (src / data-src)
3. CSS Selector for Details (synopsis, genres, status)
4. CSS Selector for Episode items (episode list, episode title, link)
5. Generate the complete, clean Kotlin Jsoup parsing snippet for an Aniyomi extension using:
   `doc.select("...").map {{ SAnime.create().apply {{ ... }} }}`

HTML Snippet:
```html
{snip}
```
"""

    start_t = time.time()
    res = None
    used_provider = provider

    if provider == "groq":
        res = call_groq(prompt_builder(pruned_html[:6000]))
    elif provider == "gemini":
        res = call_gemini(prompt_builder(pruned_html[:15000]))
    elif provider == "opencode":
        res = call_opencode(prompt_builder(pruned_html[:10000]))
    else:  # auto cascade: Gemini Flash -> Groq -> OpenCode
        res = call_gemini(prompt_builder(pruned_html[:15000]))
        used_provider = "gemini-flash"
        if not res:
            res = call_groq(prompt_builder(pruned_html[:6000]))
            used_provider = "groq-llama3.3"
        if not res:
            res = call_opencode(prompt_builder(pruned_html[:10000]))
            used_provider = "opencode-deepseek"

    latency = (time.time() - start_t) * 1000
    if not res:
        return "❌ AI Provider failed to generate response."

    output = []
    output.append("=" * 80)
    output.append(f"🤖 AI SELECTOR & KOTLIN CODE GENERATOR ({used_provider.upper()} - {latency:.1f}ms)")
    output.append("=" * 80)
    output.append(res)
    output.append("=" * 80)
    return "\n".join(output)

def main():
    parser = argparse.ArgumentParser(description="AI-Powered HTML Reverse Engineering & Selector Generation Engine")
    parser.add_argument("target", help="HTML snippet, file path, or live website URL")
    parser.add_argument("--provider", choices=["groq", "gemini", "opencode", "auto"], default="auto", help="AI Provider (default: auto -> fastest)")
    args = parser.parse_args()

    content = args.target
    if os.path.exists(args.target):
        content = Path(args.target).read_text(encoding="utf-8", errors="ignore")

    print(generate_selectors(content, provider=args.provider))

if __name__ == "__main__":
    main()
