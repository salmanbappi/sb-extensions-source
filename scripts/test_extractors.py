#!/usr/bin/env python3
"""
Extractor Testing Tool for Aniyomi Extensions

A standalone, dependency-free Python 3 script to test video extractor logic 
(regex parsers, JS unpackers, live endpoint fetching) for various providers 
like Doodstream, StreamTape, FileMoon, MixDrop, and VidSrc.

Usage Examples for AI Agents:

1. Test a live Doodstream URL:
   python3 scripts/test_extractors.py https://dood.to/e/example123

2. Test regex on a downloaded HTML file:
   python3 scripts/test_extractors.py --file /path/to/page.html --provider filemoon

3. Run basic sanity tests on all mock extractors:
   python3 scripts/test_extractors.py --test-all
"""

import sys
import urllib.request
import urllib.parse
import re
import json
import argparse

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.5",
}

def fetch_html(url, headers=None, referer=None):
    if headers is None:
        headers = HEADERS.copy()
    if referer:
        headers['Referer'] = referer
        
    print(f"[INFO] Fetching: {url}")
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.read().decode('utf-8', errors='ignore')
    except Exception as e:
        print(f"[ERROR] Failed to fetch {url}: {e}")
        return ""

def extract_doodstream(html, url):
    print("[INFO] Running Doodstream extraction logic...")
    md5_match = re.search(r"(/pass_md5/[^']*)", html)
    if not md5_match:
        print("[ERROR] MD5 pass URL not found.")
        return None
        
    pass_url = urllib.parse.urljoin(url, md5_match.group(1))
    
    token_match = re.search(r"token=([^&']*)", html)
    token = token_match.group(1) if token_match else "unknown"
    
    print("[INFO] Note: Full Doodstream extraction requires fetching the pass_url, appending a random string, and using the token.")
    return {"pass_url": pass_url, "token": token}

def extract_streamtape(html, url):
    print("[INFO] Running StreamTape extraction logic...")
    robotlink_match = re.search(r"document\.getElementById\('robotlink'\)\.innerHTML\s*=\s*'([^']+)'", html)
    if robotlink_match:
        return {"stream_url_part": robotlink_match.group(1)}
    
    url_match = re.search(r"('|\")(/[^'\"]+)\1", html)
    if url_match:
        return {"stream_url_part": url_match.group(2)}
        
    print("[ERROR] StreamTape link not found in HTML.")
    return None

def extract_filemoon(html, url):
    print("[INFO] Running FileMoon extraction logic...")
    file_match = re.search(r'file:\s*["\'](https?://[^"\']+\.m3u8[^"\']*)["\']', html)
    if file_match:
        return {"hls_url": file_match.group(1)}
        
    if "eval(function(p,a,c,k,e,d)" in html:
        print("[INFO] Found packed JS. Usually contains the m3u8 link. Needs unpacking.")
        return {"packed": True}
             
    print("[ERROR] FileMoon m3u8 not found.")
    return None

def extract_mixdrop(html, url):
    print("[INFO] Running MixDrop extraction logic...")
    if "eval(function(p,a,c,k,e,d)" in html:
        print("[INFO] Found packed JS in MixDrop.")
        return {"packed": True}
        
    wurl_match = re.search(r"MDCore\.wurl\s*=\s*['\"]([^'\"]+)['\"]", html)
    if wurl_match:
        return {"video_url": f"https:{wurl_match.group(1)}"}
        
    print("[ERROR] MixDrop video URL not found.")
    return None

def extract_vidsrc(html, url):
    print("[INFO] Running VidSrc extraction logic...")
    iframe_match = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', html)
    if iframe_match:
        return {"iframe_src": iframe_match.group(1)}
    
    try:
        data = json.loads(html)
        return {"json_data": data}
    except json.JSONDecodeError:
        pass
        
    print("[ERROR] VidSrc data not found.")
    return None

def auto_detect_provider(url):
    url = url.lower()
    if "dood" in url: return "doodstream"
    if "streamtape" in url or "strcloud" in url: return "streamtape"
    if "filemoon" in url: return "filemoon"
    if "mixdrop" in url or "mixeno" in url: return "mixdrop"
    if "vidsrc" in url: return "vidsrc"
    return None

def extract(html, url, provider):
    if provider == "doodstream": return extract_doodstream(html, url)
    if provider == "streamtape": return extract_streamtape(html, url)
    if provider == "filemoon": return extract_filemoon(html, url)
    if provider == "mixdrop": return extract_mixdrop(html, url)
    if provider == "vidsrc": return extract_vidsrc(html, url)
    print(f"[ERROR] Unsupported provider: {provider}")
    return None

def main():
    parser = argparse.ArgumentParser(description="Extractor Testing Tool")
    parser.add_argument("url", nargs="?", help="URL of the embed to test")
    parser.add_argument("--file", help="Path to local HTML file to parse")
    parser.add_argument("--provider", help="Force provider (doodstream, streamtape, filemoon, mixdrop, vidsrc)")
    parser.add_argument("--test-all", action="store_true", help="Run basic sanity tests")
    
    args = parser.parse_args()
    
    if args.test_all:
        urls = [
            "https://dood.to/e/example123",
            "https://streamtape.com/e/example123",
            "https://filemoon.sx/e/example123",
            "https://mixdrop.co/e/example123",
            "https://vidsrc.to/embed/movie/tt123456"
        ]
        for u in urls:
            print(f"\n{'='*40}\nTesting: {u}\n{'='*40}")
            provider = auto_detect_provider(u)
            html = f"Mock HTML for {provider} containing standard patterns."
            if provider == "doodstream": html += " /pass_md5/xyz token=1234"
            if provider == "streamtape": html += " document.getElementById('robotlink').innerHTML = '/stream/xyz'"
            if provider == "filemoon": html += " file: 'https://example.com/video.m3u8'"
            if provider == "mixdrop": html += " MDCore.wurl = '//example.com/video.mp4'"
            if provider == "vidsrc": html += " <iframe src='https://vidsrc.stream/test'>"
            
            result = extract(html, u, provider)
            print(f"Result: {json.dumps(result, indent=2)}")
        sys.exit(0)
        
    if not args.url and not args.file:
        parser.print_help()
        sys.exit(1)
        
    if args.file:
        print(f"[INFO] Reading local file: {args.file}")
        with open(args.file, 'r', encoding='utf-8') as f:
            html = f.read()
        url = args.url or "file://" + args.file
    else:
        url = args.url
        html = fetch_html(url)
        
    provider = args.provider
    if not provider:
        provider = auto_detect_provider(url)
        if not provider:
            print("[ERROR] Could not auto-detect provider. Please use --provider.")
            sys.exit(1)
            
    print(f"\n{'='*40}\nTarget: {url}\nProvider: {provider}\n{'='*40}")
    
    if not html:
        print("[ERROR] No HTML content to parse.")
        sys.exit(1)
        
    result = extract(html, url, provider)
    
    print("\n--- Extraction Result ---")
    if result:
        print(json.dumps(result, indent=2))
    else:
        print("Failed to extract data.")

if __name__ == "__main__":
    main()
