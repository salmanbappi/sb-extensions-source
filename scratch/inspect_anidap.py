import urllib.request, re, json

headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
req = urllib.request.Request("https://anidap.lol/", headers=headers)
html = urllib.request.urlopen(req).read().decode()

assets = re.findall(r'/assets/[a-zA-Z0-9_-]+\.js', html)
print("Assets found:", assets)

for asset in set(assets):
    url = f"https://anidap.lol{asset}"
    req_a = urllib.request.Request(url, headers=headers)
    text = urllib.request.urlopen(req_a).read().decode()
    matches = re.findall(r'https?://[a-zA-Z0-9_.-]+/[a-zA-Z0-9_/?=&.-]+', text)
    filtered = [m for m in matches if not any(x in m for x in ["w3.org", "reactrouter", "github", "google", "cloudflare", "facebook", "schema.org", "cdn.jsdelivr"])]
    if filtered:
        print(f"\n--- {asset} ---")
        for f_url in set(filtered):
            print(" ", f_url)
