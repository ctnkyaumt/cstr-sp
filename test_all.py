import json
import urllib.request
import time
import datetime
import re

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
}

def get_json(url, referer=None):
    req_headers = headers.copy()
    if referer:
        req_headers['Referer'] = referer
    req = urllib.request.Request(url, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.loads(response.read().decode('utf-8', errors='ignore'))
    except Exception as e:
        print(f"Error fetching JSON from {url}: {e}")
        return None

def test_wf():
    print("\n========================================")
    print("--- TESTING WATCHFOOTY (WF) ---")
    matches = get_json("https://api.watchfooty.st/api/v1/matches/all")
    if not matches:
        print("Failed to fetch WF matches.")
        return
    active = [m for m in matches if m.get("streams")]
    print(f"Found {len(matches)} matches, {len(active)} are active.")
    for m in active[:3]:
        title = m.get("title")
        streams = m.get("streams", [])
        print(f"- Match: '{title}' with {len(streams)} streams.")
        for s in streams[:2]:
            print(f"  * Source: {s.get('source')} | Lang: {s.get('language')} | Quality: {s.get('quality')}")

def test_ppv():
    print("\n========================================")
    print("--- TESTING PPV ---")
    ppv_domains = ["api.ppv.to", "api.ppv.st", "api.ppv.is", "api.ppv.lc", "api.ppv.cx"]
    res = None
    for domain in ppv_domains:
        url = f"https://{domain}/api/streams"
        res = get_json(url)
        if res and res.get("streams"):
            print(f"Success fetching from {domain}!")
            break
    if not res:
        print("Failed to fetch PPV streams.")
        return
    categories = res.get("streams", [])
    print(f"Found {len(categories)} PPV categories.")
    for cat in categories[:3]:
        name = cat.get("category_name") or cat.get("category")
        streams = cat.get("streams", [])
        print(f"- Category '{name}' with {len(streams)} streams.")
        for s in streams[:2]:
            iframe_part = s.get('iframe')[:60] if s.get('iframe') else 'None'
            print(f"  * Stream: {s.get('name')} | iframe: {iframe_part}...")

def get_text(url, referer=None):
    req_headers = headers.copy()
    if referer:
        req_headers['Referer'] = referer
    req = urllib.request.Request(url, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.read().decode('utf-8', errors='ignore')
    except Exception as e:
        print(f"Error fetching {url}: {e}")
        return None

def test_roxie():
    print("\n========================================")
    print("--- TESTING ROXIE (roxiestreams.su) ---")
    base = "https://roxiestreams.su"
    home = get_text(base + "/", referer=base + "/")
    if not home:
        print("Failed to fetch Roxie homepage.")
        return
    table = home.split('id="eventsTable"', 1)[-1].split("</table>", 1)[0]
    rows = re.findall(r'href="(/[^"]+)"[^>]*>([^<]+)</a>', table)
    print(f"Found {len(rows)} events.")
    for path, name in rows[:4]:
        page = get_text(base + path, referer=base + "/") or ""
        btns = re.findall(r'<button[^>]*onclick="([^"]*)"[^>]*>(.*?)</button>', page, re.S)
        m3u8 = sum(1 for h, _ in btns if "getRandomStream" in h)
        raw = sum(1 for h, _ in btns if "playIframePlayer" in h)
        print(f"- {name.strip()} ({path}): {m3u8} m3u8 + {raw} raw sources")

def test_streamed():
    print("\n========================================")
    print("--- TESTING STREAMED ---")
    api_url = "https://api.streamed.su"
    matches = get_json(f"{api_url}/matches/live")
    if not matches:
        print("Failed to fetch live Streamed matches.")
        return
    print(f"Found {len(matches)} live matches.")
    for match in matches[:3]:
        title = match.get("title")
        sources = match.get("sources", [])
        print(f"- Match: '{title}' with sources: {[s.get('source') for s in sources]}")

if __name__ == "__main__":
    test_wf()
    test_ppv()
    test_streamed()
    test_roxie()
