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

def _clean_ai_output(text: Optional[str]) -> Optional[str]:
    """Strips chain-of-thought reasoning tags (<think>...</think>) from model outputs."""
    if not text:
        return text
    cleaned = re.sub(r'<think>[\s\S]*?</think>', '', text, flags=re.DOTALL).strip()
    return cleaned if cleaned else text.strip()

def call_groq(prompt: str, api_key: str = DEFAULT_GROQ_KEY, model: Optional[str] = None) -> Optional[str]:
    """Calls Groq Cloud API with ultra-low latency and model fallback."""
    if not api_key:
        return None
    url = "https://api.groq.com/openai/v1/chat/completions"
    candidate_models = [model] if model else ["openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b", "llama-3.3-70b-versatile"]
    
    for candidate in candidate_models:
        payload = json.dumps({
            "model": candidate,
            "messages": [
                {
                    "role": "system",
                    "content": "You are an expert web scraping and Kotlin Aniyomi extension developer. Analyze HTML/JS and output structured, exact CSS selectors, regex, or Kotlin code."
                },
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.1,
            "max_tokens": 4096
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
                content = data["choices"][0]["message"]["content"]
                return _clean_ai_output(content)
        except Exception:
            continue
    return None

def call_gemini(prompt: str, api_key: str = DEFAULT_GEMINI_KEY, model: Optional[str] = None) -> Optional[str]:
    """Calls Google Gemini API with fallback models."""
    if not api_key:
        return None
    candidate_models = [model] if model else ["gemini-2.5-flash", "gemini-flash-latest", "gemini-2.5-flash-lite"]
    
    for candidate in candidate_models:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{candidate}:generateContent?key={api_key}"
        payload = json.dumps({
            "contents": [{"parts": [{"text": prompt}]}]
        }).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        }
        req = urllib.request.Request(url, data=payload, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30, context=SSL_CTX) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                candidates = data.get("candidates", [])
                if candidates:
                    parts = candidates[0].get("content", {}).get("parts", [])
                    if parts:
                        return _clean_ai_output(parts[0].get("text", ""))
        except Exception:
            continue
    return None

def call_opencode(prompt: str, api_key: str = DEFAULT_OPENCODE_KEY, model: Optional[str] = None) -> Optional[str]:
    """Calls OpenCode Zen AI Gateway with model fallback."""
    if not api_key:
        return None
    url = "https://opencode.ai/zen/v1/chat/completions"
    candidate_models = [model] if model else ["laguna-s-2.1-free", "nemotron-3.5-lightning-free", "deepseek-v4-flash-free"]
    
    for candidate in candidate_models:
        payload = json.dumps({
            "model": candidate,
            "messages": [
                {
                    "role": "system",
                    "content": "You are an expert web scraping and Kotlin Aniyomi extension developer. Analyze HTML/JS and output structured, exact CSS selectors, regex, or Kotlin code."
                },
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.1,
            "max_tokens": 4096
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
                choices = data.get("choices", [])
                if choices:
                    content = choices[0].get("message", {}).get("content", "")
                    if content:
                        return _clean_ai_output(content)
        except Exception:
            continue
    return None

def generate_selectors(target_html_or_url: str, provider: str = "auto") -> str:
    """Fetches HTML (if URL) and generates Jsoup CSS selectors & Kotlin code."""
    html_content = target_html_or_url
    if target_html_or_url.startswith("http://") or target_html_or_url.startswith("https://"):
        req = urllib.request.Request(target_html_or_url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
        try:
            with urllib.request.urlopen(req, timeout=10, context=SSL_CTX) as resp:
                html_content = resp.read().decode("utf-8", errors="replace")
        except Exception as e:
            return f"❌ Failed to fetch URL: {e}"

    prompt_builder = lambda snip: f"""
Given the following website HTML snippet, analyze the DOM structure and extract:
1. CSS Selector for the Anime / Movie Cards in popular/latest listing (e.g. `div.film-item`)
2. CSS Selector for Title, Link (href), and Poster Image (src / data-src)
3. CSS Selector for Details (synopsis, genres, status)
4. CSS Selector for Episode items strictly within the main series episode list (CRITICAL: AVOID matching sidebar/recent episode widgets like ul.list-episode-item-2 or latest updates)
5. Generate the complete, clean Kotlin Jsoup parsing snippet for an Aniyomi extension API v16 using:
   - `doc.select("...").map {{ SAnime.create().apply {{ ... }} }}`
   - Dynamic descending sort: `episodes.sortedByDescending {{ it.episode_number }}`
   - Verified library signatures: `PlaylistUtils.extractFromHls(playlistUrl, referer = ..., subtitleList = ..., videoNameGen = ...)`
   - Verified named Video constructor: `Video(videoUrl = ..., videoTitle = ..., headers = ...)`

HTML Snippet:
```html
{snip}
```
"""

    start_t = time.time()
    res = None
    used_provider = provider

    if provider == "groq":
        res = call_groq(prompt_builder(html_content[:6000]))
    elif provider == "gemini":
        res = call_gemini(prompt_builder(html_content[:30000]))
    elif provider == "opencode":
        res = call_opencode(prompt_builder(html_content[:15000]))
    else:  # auto cascade: Gemini Flash -> Groq -> OpenCode
        res = call_gemini(prompt_builder(html_content[:30000]))
        used_provider = "gemini-flash"
        if not res:
            res = call_groq(prompt_builder(html_content[:6000]))
            used_provider = "groq-llama3.3"
        if not res:
            res = call_opencode(prompt_builder(html_content[:15000]))
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
