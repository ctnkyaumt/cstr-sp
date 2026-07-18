import json
import urllib.request
import ssl
import re
import time

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
}

# Some upstream hosts negotiate TLS with a mismatched SNI (api.ppv.to throws
# TLSV1_UNRECOGNIZED_NAME under Python's default verification). CloudStream's OkHttp
# tolerates it; mirror that here so a working source isn't reported as dead.
_ctx = ssl.create_default_context()
_ctx.check_hostname = False
_ctx.verify_mode = ssl.CERT_NONE


def _fetch(url, referer=None, timeout=10):
    req_headers = headers.copy()
    if referer:
        req_headers['Referer'] = referer
    req = urllib.request.Request(url, headers=req_headers)
    with urllib.request.urlopen(req, timeout=timeout, context=_ctx) as response:
        return response.getcode(), response.read().decode('utf-8', errors='ignore')


def get_json(url, referer=None, timeout=10):
    try:
        _, body = _fetch(url, referer, timeout)
        return json.loads(body)
    except Exception as e:
        print(f"Error fetching JSON from {url}: {e}")
        return None


def get_text(url, referer=None, timeout=12):
    try:
        _, body = _fetch(url, referer, timeout)
        return body
    except Exception as e:
        print(f"Error fetching {url}: {e}")
        return None


def hdr(title):
    print("\n========================================")
    print(f"--- {title} ---")


# --- Streamed (streamed.pk) — mirrors Cstrsp.checkAndGetDomain / fetchMatches ---------
def test_streamed():
    hdr("TESTING STREAMED (streamed.pk)")
    domains = ["https://streamed.pk", "https://streamed.st"]
    matches = None
    for d in domains:
        matches = get_json(f"{d}/api/matches/live")
        if matches:
            print(f"Success fetching from {d}!")
            break
    if not matches:
        print("Failed to fetch live Streamed matches.")
        return
    # Kotlin keeps only entries with id + title.
    matches = [m for m in matches if m.get("id") and m.get("title")]
    print(f"Found {len(matches)} live matches.")
    for match in matches[:3]:
        sources = match.get("sources", [])
        print(f"- Match: '{match.get('title')}' with sources: {[s.get('source') for s in sources]}")


# --- PPV — mirrors fetchPPVApi + isLivePpv -------------------------------------------
def _ppv_is_live(cat, stream):
    if cat.get("always_live") is True or stream.get("always_live", 0) == 1:
        return True
    now = time.time()
    start = stream.get("starts_at") or 0
    end = stream.get("ends_at") or 0
    if start > 0 and now < start:
        return False
    if end > 0 and now > end + 1800:
        return False
    return True


def test_ppv():
    hdr("TESTING PPV")
    ppv_domains = ["api.ppv.to", "api.ppv.st", "api.ppv.is", "api.ppv.lc", "api.ppv.cx"]
    res = None
    for domain in ppv_domains:
        res = get_json(f"https://{domain}/api/streams")
        if res and res.get("streams"):
            print(f"Success fetching from {domain}!")
            break
    if not res:
        print("Failed to fetch PPV streams.")
        return
    categories = res.get("streams", [])
    live = sum(1 for c in categories for s in (c.get("streams") or []) if _ppv_is_live(c, s))
    print(f"Found {len(categories)} PPV categories, {live} live streams.")
    for cat in categories[:3]:
        name = cat.get("category_name") or cat.get("category")
        streams = [s for s in (cat.get("streams") or []) if _ppv_is_live(cat, s)]
        print(f"- Category '{name}' with {len(streams)} live streams.")
        for s in streams[:2]:
            iframe = (s.get('iframe') or '')[:60] or 'None'
            print(f"  * {s.get('name')} | iframe: {iframe}...")


# --- WatchFooty — mirrors fetchWFMatches + wfListable (status 'in' + non-SD stream) ---
def _wf_has_hd(match):
    return any(s.get("url") and (s.get("quality") or "").strip().upper() != "SD"
               for s in (match.get("streams") or []))


def _wf_is_live(match):
    return (match.get("status") or "").strip().lower() in ("in", "live")


def test_wf():
    hdr("TESTING WATCHFOOTY (WF)")
    matches = get_json("https://api.watchfooty.st/api/v1/matches/all")
    if not matches:
        print("Failed to fetch WF matches.")
        return
    listable = [m for m in matches if m.get("matchId") and _wf_is_live(m) and _wf_has_hd(m)]
    print(f"Found {len(matches)} matches, {len(listable)} live & HD (listable).")
    for m in listable[:3]:
        streams = m.get("streams", [])
        print(f"- '{m.get('title')}' [{m.get('sport')}] with {len(streams)} streams.")
        for s in streams[:2]:
            print(f"  * {s.get('source')} | {s.get('language')} | {s.get('quality')}")


# --- StreamSports (cdnlivetv.tv) — mirrors fetchCdnEvents + isLiveCdn + prune ---------
CDN_NOT_LIVE = {
    "ns", "tbd", "canc", "cancl", "cancelled", "canceled", "pst", "postp", "postponed",
    "abd", "abandoned", "susp", "suspended", "wo", "awd", "ft", "aet", "pen",
    "fin", "finished", "ended",
}


def _cdn_is_live(ev):
    s = (ev.get("status") or "").strip().lower()
    return s == "" or s not in CDN_NOT_LIVE


def test_cdn():
    hdr("TESTING STREAMSPORTS (cdnlivetv.tv)")
    res = get_json("https://api.cdnlivetv.tv/api/v1/events/sports/?user=cdnlivetv&plan=free", timeout=15)
    if not res:
        print("Failed to fetch StreamSports events.")
        return
    data = res.get("cdn-live-tv", {})
    total = live = 0
    sample = []
    for sport, value in data.items():
        if not isinstance(value, list):
            continue  # skip scalar metadata keys (total_events_*, cached, timestamp)
        playable = [e for e in value
                    if e.get("gameID") and any(c.get("url") for c in (e.get("channels") or []))]
        total += len(playable)
        live_here = [e for e in playable if _cdn_is_live(e)]
        live += len(live_here)
        for e in live_here[:1]:
            sample.append((sport, e))
    print(f"Found {total} events with a playable channel, {live} currently live.")
    if not sample:
        print("(No events are in-play right now - all upcoming/finished. Endpoint healthy.)")
    for sport, e in sample[:3]:
        title = e.get("event") or f"{e.get('homeTeam')} vs {e.get('awayTeam')}"
        chans = [c.get("channel_name") for c in (e.get("channels") or []) if c.get("url")]
        print(f"- [{sport}] '{title}' channels: {chans[:3]}")


# --- Roxie (roxiestreams.su) — mirrors fetchRoxieEvents + fetchRoxieSources -----------
_ROW = re.compile(r'href="(/[^"]+)"[^>]*>([^<]+)</a>')


def test_roxie():
    hdr("TESTING ROXIE (roxiestreams.su)")
    base = "https://roxiestreams.su"
    home = get_text(base + "/", referer=base + "/")
    if not home:
        print("Failed to fetch Roxie homepage.")
        return
    table = home.split('id="eventsTable"', 1)[-1].split("</table>", 1)[0]
    rows = _ROW.findall(table)
    # Kotlin dedups by path; two named rows can share one page (e.g. both -> /f1).
    seen, events = set(), []
    for path, name in rows:
        if path not in seen:
            seen.add(path)
            events.append((path, name.strip()))
    print(f"Found {len(rows)} rows, {len(events)} distinct event pages.")
    for path, name in events[:4]:
        page = get_text(base + path, referer=base + "/") or ""
        btns = re.findall(r'<button[^>]*onclick="([^"]*)"[^>]*>(.*?)</button>', page, re.S)
        m3u8 = sum(1 for h, _ in btns if "getRandomStream" in h)
        raw = sum(1 for h, _ in btns if "playIframePlayer" in h)
        print(f"- {name} ({path}): {m3u8} m3u8 + {raw} raw sources")


if __name__ == "__main__":
    test_streamed()
    test_ppv()
    test_wf()
    test_cdn()
    test_roxie()
