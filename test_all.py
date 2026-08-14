import json
import base64
import urllib.request
import urllib.parse
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
    domains = ["https://streamed.pk", "https://streamed.st", "https://streamed.su", "https://streami.su"]
    healthy = []
    for d in domains:
        result = get_json(f"{d}/api/matches/live")
        # An empty array is a healthy mirror during a live-event lull.
        if isinstance(result, list):
            healthy.append((d, result))
            print(f"Healthy mirror: {d} ({len(result)} live matches)")
    if not healthy:
        print("Failed to fetch a valid match array from every Streamed mirror.")
        return
    domain, matches = healthy[0]
    # Kotlin keeps only entries with id + title.
    matches = [m for m in matches if m.get("id") and m.get("title")]
    print(f"Found {len(matches)} live matches.")
    for match in matches[:3]:
        sources = match.get("sources", [])
        print(f"- Match: '{match.get('title')}' with sources: {[s.get('source') for s in sources]}")

    sample = next((m for m in matches if m.get("sources")), None)
    if sample:
        source = sample["sources"][0]
        streams = get_json(f"{domain}/api/stream/{source.get('source')}/{source.get('id')}")
        valid = [s for s in (streams or []) if s.get("embedUrl")]
        print(f"Representative stream endpoint returned {len(valid)} embeds.")
        if valid:
            page = get_text(valid[0]["embedUrl"], referer=domain + "/") or ""
            player_shell = '<div id="player"' in page
            print(f"Player shell: {'healthy' if player_shell else 'unexpected'} ({len(page)} bytes)")


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
    ppv_domains = ["api.ppv.st", "api.ppv.is", "api.ppv.lc", "api.ppv.cx", "api.ppv.to"]
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

    sample = next((s for c in categories for s in (c.get("streams") or [])
                   if _ppv_is_live(c, s) and s.get("iframe")), None)
    if sample:
        page = get_text(sample["iframe"], referer="https://embedindia.st/") or ""
        player_shell = 'id="player"' in page and "bundle-jw.js" in page
        print(f"Representative EmbedIndia player shell: {'healthy' if player_shell else 'unexpected'} ({len(page)} bytes)")


# --- WatchFooty — mirrors fetchWFMatches + wfListable (status 'in' + non-SD stream) ---
def _wf_has_hd(match):
    return any(s.get("url") and (s.get("quality") or "").strip().upper() != "SD"
               for s in (match.get("streams") or []))


def _wf_is_live(match):
    return (match.get("status") or "").strip().lower() in ("in", "live")


_IFRAME_SRC = re.compile(r'<iframe[^>]+src\s*=\s*["\']([^"\']+)["\']', re.I)


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

    if listable:
        stream = next((s for s in (listable[0].get("streams") or [])
                       if s.get("url") and (s.get("quality") or "").upper() != "SD"), None)
        if stream:
            wrapper = get_text(stream["url"], referer="https://api.watchfooty.st/") or ""
            match = _IFRAME_SRC.search(wrapper)
            inner = urllib.parse.urljoin(stream["url"], match.group(1).replace("&amp;", "&")) if match else None
            inner_page = get_text(inner, referer=stream["url"]) if inner else None
            player_shell = bool(
                (inner_page and 'id="player"' in inner_page)
                or 'id="video_player"' in wrapper
                or 'id="player"' in wrapper
            )
            print(f"Player route: {inner or stream['url']}")
            print(f"Player shell: {'healthy' if player_shell else 'unexpected'}")


# --- StreamSports (cdnlivetv.tv) — mirrors fetchCdnEvents + isLiveCdn + prune ---------
CDN_NOT_LIVE = {
    "ns", "tbd", "canc", "cancl", "cancelled", "canceled", "pst", "postp", "postponed",
    "abd", "abandoned", "susp", "suspended", "wo", "awd", "ft", "aet", "pen",
    "fin", "finished", "ended",
}

_PLAYER_SOURCE = re.compile(r'<source[^>]+src\s*=\s*["\']([^"\']+\.m3u8[^"\']*)["\']', re.I | re.S)
_PLAYER_SOURCE_VAR = re.compile(r'source\s*:\s*\{\s*src\s*:\s*([A-Za-z_$][\w$]*)', re.I)
_JS_STRING_VAR = re.compile(r"var\s+([A-Za-z_$][\w$]*)\s*=\s*'([A-Za-z0-9_-]+)'\s*;")


def _decode_player_source(page, player_url):
    source = _PLAYER_SOURCE.search(page)
    if source:
        return urllib.parse.urljoin(player_url, source.group(1).replace("&amp;", "&"))

    source_var = _PLAYER_SOURCE_VAR.search(page)
    if not source_var:
        return None
    values = dict(_JS_STRING_VAR.findall(page))
    expression = re.search(rf"var\s+{re.escape(source_var.group(1))}\s*=\s*([^;]+);", page)
    if not expression:
        return None
    refs = re.findall(r"\(([A-Za-z_$][\w$]*)\)", expression.group(1))
    try:
        pieces = []
        for ref in refs:
            encoded = values[ref]
            pieces.append(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode("utf-8"))
        return "".join(pieces)
    except (KeyError, ValueError, UnicodeDecodeError):
        return None


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

    if sample:
        channel = next(c for c in (sample[0][1].get("channels") or []) if c.get("url"))
        player = get_text(channel["url"], referer="https://cdnlivetv.tv/") or ""
        playlist_url = _decode_player_source(player, channel["url"])
        playlist = get_text(playlist_url, referer="https://cdnlivetv.tv/") if playlist_url else None
        print(f"Signed HLS playlist: {'healthy' if playlist and playlist.lstrip().startswith('#EXTM') else 'failed'}")


# --- Roxie (roxiestreams.su) — mirrors fetchRoxieEvents + fetchRoxieSources -----------
_ROW = re.compile(r'href="(/[^"]+)"[^>]*>([^<]+)</a>')
_ROXIE_STREAM = re.compile(r"getRandomStream\(\s*'([^']+)'(?:\s*,\s*'([^']+)')?")
_ROXIE_DOMAINS = re.compile(r'domainsz\d+\.txt')


def _is_hls(url, referer=None, timeout=5):
    try:
        _, body = _fetch(url, referer=referer, timeout=timeout)
        return body.lstrip().startswith("#EXTM")
    except Exception:
        return False


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
    browser_headers = {
        "Origin": base,
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "cross-site",
    }
    old_headers = headers.copy()
    headers.update(browser_headers)
    for path, name in events[:4]:
        page = get_text(base + path, referer=base + "/") or ""
        btns = re.findall(r'<button[^>]*onclick="([^"]*)"[^>]*>(.*?)</button>', page, re.S)
        m3u8 = sum(1 for h, _ in btns if "getRandomStream" in h)
        raw = sum(1 for h, _ in btns if "playIframePlayer" in h)
        sources = _ROXIE_STREAM.findall(page)
        domains_match = _ROXIE_DOMAINS.search(page)
        domains = []
        if domains_match:
            domains_text = get_text(base + "/" + domains_match.group(0), referer=base + "/") or ""
            domains = list(dict.fromkeys(domains_text.split()))
        working = []
        for stream_path, subdomain in sources:
            for domain in domains:
                url = f"https://{subdomain or 'ataide0'}.{domain}/{stream_path}"
                if _is_hls(url, referer=base + "/"):
                    working.append(url)
                    break
        print(f"- {name} ({path}): {m3u8} button m3u8 + {raw} raw; {len(working)} direct HLS working")
    headers.clear()
    headers.update(old_headers)


def test_trt():
    hdr("TESTING TRT")
    url = "https://tv-trt1.medya.trt.com.tr/master.m3u8"
    body = get_text(url, timeout=15)
    variants = len(re.findall(r"#EXT-X-STREAM-INF", body or ""))
    print(f"TRT master playlist: {'healthy' if body and body.lstrip().startswith('#EXTM') else 'failed'} ({variants} variants)")


if __name__ == "__main__":
    test_streamed()
    test_ppv()
    test_wf()
    test_cdn()
    test_roxie()
    test_trt()
