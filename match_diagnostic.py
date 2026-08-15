"""Live, match-focused probe for the cstrsp provider chain.

This intentionally follows the Kotlin extractor's load -> loadLinks flow without
building the Android project.  It is a diagnostic, not a second scraper: each
provider is queried exactly as the extension does, then the returned player/feed
is fetched and checked for a usable shell or HLS/DASH manifest.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import html
import json
import re
import ssl
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Iterable


UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
TLS = ssl.create_default_context()
TLS.check_hostname = False
TLS.verify_mode = ssl.CERT_NONE


def fetch(url: str, referer: str | None = None, timeout: float = 12) -> tuple[int, str, str | None]:
    """Return (HTTP status, body, error).  TLS handling mirrors CloudStream's permissive client."""
    headers = {"User-Agent": UA, "Accept": "*/*"}
    if referer:
        headers["Referer"] = referer
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout, context=TLS) as res:
            return res.getcode(), res.read().decode("utf-8", "ignore"), None
    except Exception as exc:  # network failures are evidence, not a script failure
        return 0, "", f"{type(exc).__name__}: {exc}"


def json_fetch(url: str, **kwargs: Any) -> tuple[Any, int, str | None]:
    status, body, error = fetch(url, **kwargs)
    if error:
        return None, status, error
    try:
        return json.loads(body), status, None
    except Exception as exc:
        return None, status, f"JSONDecodeError: {exc}"


def short_url(url: str | None) -> str:
    if not url:
        return "-"
    try:
        p = urllib.parse.urlsplit(url)
        # Signed URLs are useful only as a yes/no diagnostic; never print tokens.
        return urllib.parse.urlunsplit((p.scheme, p.netloc, p.path, "<redacted>" if p.query else "", ""))
    except Exception:
        return url[:100]


def clean(value: Any) -> str:
    text = unicodedata.normalize("NFKD", str(value or "")).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def event_text(event: dict[str, Any]) -> str:
    teams = event.get("teams") or {}
    home = teams.get("home") or {}
    away = teams.get("away") or {}
    return clean(" ".join(str(x or "") for x in (
        event.get("title"), event.get("event"), event.get("homeTeam"), event.get("awayTeam"),
        home.get("name"), away.get("name"), event.get("name"), event.get("category_name"),
        event.get("uri_name"),
    )))


def is_target(event: dict[str, Any], wanted: list[str]) -> bool:
    haystack = event_text(event)
    # “manu” is how the request was phrased; accept the common upstream spellings.
    aliases = {
        "manu": ("manu", "manchester united", "manchester utd", "man utd", "united"),
        "manchester united": ("manu", "manchester united", "manchester utd", "man utd", "united"),
        "ac milan": ("ac milan", "milan"),
        "milan": ("ac milan", "milan"),
    }
    groups = [aliases.get(clean(part), (clean(part),)) for part in wanted]
    return all(any(alias in haystack for alias in group) for group in groups)


def title(event: dict[str, Any]) -> str:
    return str(event.get("title") or event.get("event") or event.get("name") or "(unnamed)").strip()


def line(source: str, status: str, detail: str = "") -> None:
    print(f"[{source:<10}] {status:<9} {detail}".rstrip())


def inspect_page(url: str, referer: str | None = None) -> dict[str, Any]:
    status, body, error = fetch(url, referer, timeout=15)
    iframe_match = re.search(r"<iframe[^>]+src\s*=\s*[\"']([^\"']+)[\"']", body, re.I)
    hls = re.findall(r"https?://[^\"'<>\s]+\.m3u8[^\"'<>\s]*", body, re.I)
    return {
        "status": status,
        "bytes": len(body),
        "error": error,
        "iframe": urllib.parse.urljoin(url, html.unescape(iframe_match.group(1))) if iframe_match else None,
        "shell": bool(re.search(r"id\s*=\s*[\"'](?:player|video_player)[\"']", body, re.I)),
        "hls": list(dict.fromkeys(hls)),
        "body": body,
    }


def inspect_hls(url: str, referer: str | None = None) -> tuple[bool, int, int, str | None]:
    status, body, error = fetch(url, referer, timeout=15)
    variants = len(re.findall(r"#EXT-X-STREAM-INF", body))
    media = len(re.findall(r"#EXTINF", body))
    return bool(body.lstrip().startswith("#EXTM3U")), status, variants or media, error


STREAMED_DOMAINS = ["https://streamed.pk", "https://streamed.st", "https://streamed.su", "https://streami.su"]
PPV_DOMAINS = ["api.ppv.st", "api.ppv.is", "api.ppv.lc", "api.ppv.cx", "api.ppv.to"]


def streamed_probe(wanted: list[str]) -> bool:
    line("STREAMED", "probing", "all mirrors /api/matches/live (parallel, like checkAndGetDomain)")

    def probe(domain: str) -> tuple[str, Any, int, str | None]:
        data, status, error = json_fetch(f"{domain}/api/matches/live", timeout=10)
        return domain, data, status, error

    with concurrent.futures.ThreadPoolExecutor(max_workers=len(STREAMED_DOMAINS)) as pool:
        results = list(pool.map(probe, STREAMED_DOMAINS))
    healthy = [(d, data) for d, data, status, error in results if isinstance(data, list)]
    for domain, data, status, error in results:
        if isinstance(data, list):
            print(f"  mirror {domain}: HTTP {status}, {len(data)} live matches (healthy JSON)")
        else:
            print(f"  mirror {domain}: FAILED {error or ('HTTP ' + str(status))}")
    if not healthy:
        line("STREAMED", "FAILED", "no mirror returned a valid array")
        return False
    domain, live = healthy[0]
    all_today, status, error = json_fetch(f"{domain}/api/matches/all-today", timeout=10)
    candidates = [m for m in (live + (all_today if isinstance(all_today, list) else []))
                  if isinstance(m, dict) and is_target(m, wanted)]
    # Deduplicate the same id returned by live and all-today.
    candidates = list({str(m.get("id")): m for m in candidates}.values())
    if not candidates:
        line("STREAMED", "NOT FOUND", "target absent from live and all-today")
        return False
    for match in candidates:
        print(f"  match: {title(match)} id={match.get('id')}")
        sources = [s for s in (match.get("sources") or []) if s.get("source") and s.get("id")]
        print(f"  sources advertised: {len(sources)} ({', '.join(str(s.get('source')) for s in sources) or '-'})")
        for source in sources:
            streams, st, err = json_fetch(f"{domain}/api/stream/{source['source']}/{source['id']}", timeout=12)
            if not isinstance(streams, list):
                print(f"    {source['source']}: API FAILED {err or st}")
                continue
            embeds = [s for s in streams if isinstance(s, dict) and s.get("embedUrl")]
            print(f"    {source['source']}: HTTP {st}, {len(embeds)} embeds")
            for stream in embeds[:4]:
                page = inspect_page(stream["embedUrl"], referer=domain + "/")
                detail = f"HTTP {page['status']}, {page['bytes']} bytes, shell={'yes' if page['shell'] else 'no'}"
                if page["hls"]:
                    ok, hs, count, he = inspect_hls(page["hls"][0], referer=stream["embedUrl"])
                    detail += f", static HLS={'yes' if ok else 'no'} ({hs}, {count})"
                print(f"      embed {short_url(stream['embedUrl'])}: {detail}")
    line("STREAMED", "FOUND", f"{len(candidates)} matching event(s)")
    return True


def ppv_live(category: dict[str, Any], stream: dict[str, Any]) -> bool:
    if category.get("always_live") is True or stream.get("always_live") == 1:
        return True
    now = time.time()
    start = stream.get("starts_at") or 0
    end = stream.get("ends_at") or 0
    return not (start > 0 and now < start) and not (end > 0 and now > end + 1800)


def ppv_probe(wanted: list[str]) -> bool:
    line("PPV", "probing", "all API mirrors /api/streams")

    def probe(domain: str) -> tuple[str, Any, int, str | None]:
        return (domain, *json_fetch(f"https://{domain}/api/streams", timeout=12))

    with concurrent.futures.ThreadPoolExecutor(max_workers=len(PPV_DOMAINS)) as pool:
        results = list(pool.map(probe, PPV_DOMAINS))
    healthy = [(d, data) for d, data, status, error in results
               if isinstance(data, dict) and isinstance(data.get("streams"), list)]
    for domain, data, status, error in results:
        if isinstance(data, dict) and isinstance(data.get("streams"), list):
            count = sum(len(c.get("streams") or []) for c in data["streams"] if isinstance(c, dict))
            print(f"  mirror {domain}: HTTP {status}, {len(data['streams'])} categories / {count} streams (healthy JSON)")
        else:
            print(f"  mirror {domain}: FAILED {error or ('HTTP ' + str(status))}")
    if not healthy:
        line("PPV", "FAILED", "no mirror returned a valid streams array")
        return False
    _, response = healthy[0]
    matches: list[tuple[dict[str, Any], dict[str, Any]]] = []
    for category in response["streams"]:
        if not isinstance(category, dict):
            continue
        for stream in category.get("streams") or []:
            if isinstance(stream, dict) and is_target({**stream, "category_name": category.get("category_name")}, wanted):
                matches.append((category, stream))
    if not matches:
        line("PPV", "NOT FOUND", "target absent from all categories")
        return False
    for category, stream in matches:
        iframe = stream.get("iframe") or ""
        print(f"  match: {stream.get('name')} live={'yes' if ppv_live(category, stream) else 'no'}")
        if not iframe:
            print("    no iframe URL")
            continue
        page = inspect_page(iframe, referer="https://embedindia.st/")
        print(f"    iframe {short_url(iframe)}: HTTP {page['status']}, {page['bytes']} bytes, shell={'yes' if page['shell'] else 'no'}")
    line("PPV", "FOUND", f"{len(matches)} matching stream(s)")
    return True


def wf_live(match: dict[str, Any]) -> bool:
    return clean(match.get("status")) in ("in", "live")


def wf_probe(wanted: list[str]) -> bool:
    line("WATCHFOOTY", "probing", "/api/v1/matches/all and each non-SD stream")
    matches, status, error = json_fetch("https://api.watchfooty.st/api/v1/matches/all", timeout=15)
    if not isinstance(matches, list):
        line("WATCHFOOTY", "FAILED", error or str(status))
        return False
    candidates = [m for m in matches if isinstance(m, dict) and is_target(m, wanted)]
    if not candidates:
        line("WATCHFOOTY", "NOT FOUND", f"{len(matches)} events searched")
        return False
    for match in candidates:
        streams = [s for s in (match.get("streams") or []) if s.get("url") and clean(s.get("quality")) != "sd"]
        print(f"  match: {title(match)} status={match.get('status')} live={'yes' if wf_live(match) else 'no'}, non-SD streams={len(streams)}")
        for stream in streams[:4]:
            wrapper = inspect_page(stream["url"], referer="https://api.watchfooty.st/")
            inner = inspect_page(wrapper["iframe"], referer=stream["url"]) if wrapper["iframe"] else None
            page = inner or wrapper
            detail = f"wrapper {wrapper['status']}/{wrapper['bytes']} bytes"
            if wrapper["iframe"]:
                detail += f", iframe {short_url(wrapper['iframe'])} -> HTTP {page['status']}/{page['bytes']} bytes"
            detail += f", shell={'yes' if page['shell'] else 'no'}"
            if page["hls"]:
                ok, hs, count, he = inspect_hls(page["hls"][0], referer=stream["url"])
                detail += f", HLS={'yes' if ok else 'no'} ({hs}, {count})"
            print(f"    {stream.get('source') or stream.get('quality')}: {detail}")
    line("WATCHFOOTY", "FOUND", f"{len(candidates)} matching event(s)")
    return True


CDN_NOT_LIVE = {"ns", "tbd", "canc", "cancl", "cancelled", "canceled", "pst", "postp", "postponed",
                "abd", "abandoned", "susp", "suspended", "wo", "awd", "ft", "aet", "pen", "fin", "finished", "ended"}


def cdn_live(event: dict[str, Any]) -> bool:
    status = clean(event.get("status"))
    return not status or status not in CDN_NOT_LIVE


def decode_cdn_source(page: str, player_url: str) -> str | None:
    direct = re.search(r"<source[^>]+src\s*=\s*[\"']([^\"']+\.m3u8[^\"']*)[\"']", page, re.I | re.S)
    if direct:
        return urllib.parse.urljoin(player_url, html.unescape(direct.group(1)))
    var = re.search(r"source\s*:\s*\{\s*src\s*:\s*([A-Za-z_$][\w$]*)", page, re.I)
    if not var:
        return None
    values = dict(re.findall(r"var\s+([A-Za-z_$][\w$]*)\s*=\s*'([A-Za-z0-9_-]+)'\s*;", page))
    expression = re.search(rf"var\s+{re.escape(var.group(1))}\s*=\s*([^;]+);", page)
    if not expression:
        return None
    try:
        decoded = "".join(base64.urlsafe_b64decode(values[name] + "=" * (-len(values[name]) % 4)).decode()
                           for name in re.findall(r"\(([A-Za-z_$][\w$]*)\)", expression.group(1)))
        return decoded if decoded.startswith("http") else None
    except (KeyError, ValueError, UnicodeDecodeError):
        return None


def cdn_probe(wanted: list[str]) -> bool:
    line("STREAMSPORTS", "probing", "events/sports + signed channel page + HLS playlist")
    response, status, error = json_fetch("https://api.cdnlivetv.tv/api/v1/events/sports/?user=cdnlivetv&plan=free", timeout=20)
    data = response.get("cdn-live-tv", {}) if isinstance(response, dict) else {}
    events = [e for values in data.values() if isinstance(values, list) for e in values if isinstance(e, dict)]
    if not events:
        line("STREAMSPORTS", "FAILED", error or f"HTTP {status}, no event arrays")
        return False
    candidates = [e for e in events if is_target(e, wanted)]
    if not candidates:
        line("STREAMSPORTS", "NOT FOUND", f"{len(events)} events searched")
        return False
    for event in candidates:
        channels = [c for c in event.get("channels") or [] if c.get("url")]
        print(f"  match: {title(event)} status={event.get('status') or '-'} live={'yes' if cdn_live(event) else 'no'}, channels={len(channels)}")
        for channel in channels[:8]:  # same cap as loadLinks
            page = inspect_page(channel["url"], referer="https://cdnlivetv.tv/")
            manifest = decode_cdn_source(page["body"], channel["url"])
            if manifest:
                ok, hs, count, he = inspect_hls(manifest, referer="https://cdnlivetv.tv/")
                print(f"    {channel.get('channel_name') or 'Channel'}: player HTTP {page['status']}/{page['bytes']}, HLS={'yes' if ok else 'no'} ({hs}, {count}) {short_url(manifest)}")
            else:
                print(f"    {channel.get('channel_name') or 'Channel'}: player HTTP {page['status']}/{page['bytes']}, signed URL not decoded")
    line("STREAMSPORTS", "FOUND", f"{len(candidates)} matching event(s)")
    return True


ROXIE_ROW = re.compile(r'href="(/[^"]+)"[^>]*>([^<]+)</a>')
ROXIE_STREAM = re.compile(r"getRandomStream\(\s*'([^']+)'(?:\s*,\s*'([^']+)')?")
ROXIE_RAW = re.compile(r"playIframePlayer\(\s*'([^']+)'\s*\)")
ROXIE_DOMAIN = re.compile(r"domainsz\d+\.txt")


def roxie_probe(wanted: list[str]) -> bool:
    line("ROXIE", "probing", "homepage -> event page -> direct HLS/raw manifest")
    base = "https://roxiestreams.su"
    status, home, error = fetch(base + "/", referer=base + "/", timeout=15)
    if not home:
        line("ROXIE", "FAILED", error or str(status))
        return False
    table = home.split('id="eventsTable"', 1)[-1].split("</table>", 1)[0]
    events = []
    seen: set[str] = set()
    for path, name in ROXIE_ROW.findall(table):
        if path not in seen:
            seen.add(path)
            events.append({"path": path, "name": name.strip()})
    candidates = [e for e in events if is_target(e, wanted)]
    if not candidates:
        line("ROXIE", "NOT FOUND", f"{len(events)} event pages searched")
        return False
    for event in candidates:
        st, page, err = fetch(base + event["path"], referer=base + "/", timeout=15)
        buttons = re.findall(r'<button[^>]*onclick="([^"]*)"[^>]*>(.*?)</button>', page, re.S)
        direct = ROXIE_STREAM.findall(page)
        raw = ROXIE_RAW.findall(page)
        domains_file = ROXIE_DOMAIN.search(page)
        domains = []
        if domains_file:
            _, body, _ = fetch(base + "/" + domains_file.group(0), referer=base + "/")
            domains = list(dict.fromkeys(body.split()))
        print(f"  match: {event['name']} HTTP {st}, buttons={len(buttons)}, direct={len(direct)}, raw={len(raw)}, domains={len(domains)}")
        for stream_path, subdomain in direct:
            working = False
            for domain in domains:
                candidate = f"https://{subdomain or 'ataide0'}.{domain}/{stream_path}"
                ok, hs, count, he = inspect_hls(candidate, referer=base + "/")
                if ok:
                    print(f"    direct HLS: healthy ({hs}, {count}) {short_url(candidate)}")
                    working = True
                    break
            if not working:
                print(f"    direct HLS {stream_path}: no working CDN host")
        for raw_path in raw:
            raw_url = raw_path if raw_path.startswith("http") else base + raw_path
            raw_page = inspect_page(raw_url, referer=base + event["path"])
            manifest = re.search(r"\.load\(\s*[\"']([^\"']+)[\"']", raw_page["body"])
            if manifest:
                manifest_url = urllib.parse.urljoin(raw_url, manifest.group(1))
                ok, hs, count, he = inspect_hls(manifest_url, referer=base + "/")
                print(f"    raw {short_url(raw_url)}: manifest {'healthy' if ok else 'failed'} ({hs}, {count})")
            else:
                print(f"    raw {short_url(raw_url)}: HTTP {raw_page['status']}, no static manifest")
    line("ROXIE", "FOUND", f"{len(candidates)} matching event page(s)")
    return True


def trt_probe() -> None:
    line("TRT", "probing", "master.m3u8")
    ok, status, count, error = inspect_hls("https://tv-trt1.medya.trt.com.tr/master.m3u8")
    line("TRT", "HEALTHY" if ok else "FAILED", f"HTTP {status}, {count} variants/media" if not error else error)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("terms", nargs="*", default=["manu", "ac milan"], help="team names to require")
    args = parser.parse_args()
    print(f"Target aliases: {', '.join(args.terms)}")
    print("URLs are redacted; a source is HEALTHY only after its API and returned player/feed are checked.\n")
    results = [
        streamed_probe(args.terms),
        ppv_probe(args.terms),
        wf_probe(args.terms),
        cdn_probe(args.terms),
        roxie_probe(args.terms),
    ]
    print()
    trt_probe()
    found = sum(results)
    print(f"\nMatch probe complete: {found}/{len(results)} provider APIs contained the target.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
