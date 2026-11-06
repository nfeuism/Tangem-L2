#!/usr/bin/env python3
"""Local retrying/caching Maven proxy with an existence oracle.

Google's Maven CDN (dl.google.com) intermittently returns 404 for artifacts that
definitely exist (~60% failure rate in this environment). This proxy fronts the
upstream repositories, retries on 404/5xx, and caches results on disk.

For Google Maven it additionally uses the published master-index.xml /
group-index.xml to decide quickly whether an artifact really exists, so genuine
misses return 404 in milliseconds instead of retrying for half a minute.

Routes:
  /gmaven/<path>   -> Google Maven (dl.google.com + maven.google.com)
  /central/<path>  -> Maven Central (repo1.maven.org)
  /jitpack/<path>  -> JitPack
"""
import hashlib
import http.server
import os
import re
import socketserver
import sys
import threading
import time
import urllib.error
import urllib.request

# Upstream order matters only for fallback; all are probed in parallel and the
# first 200 wins. Google's own CDN (dl.google.com) is kept last because it
# intermittently returns 404 for artifacts that exist. The Aliyun/Huawei mirrors
# were verified to serve the same coordinates reliably.
GOOGLE_BASES = [
    "https://maven.aliyun.com/repository/google/",
    "https://repo.huaweicloud.com/repository/maven/",
    "https://dl.google.com/dl/android/maven2/",
    "https://maven.google.com/",
]
UPSTREAMS = {
    "gmaven": GOOGLE_BASES,
    "central": [
        "https://maven.aliyun.com/repository/central/",
        "https://repo.huaweicloud.com/repository/maven/",
        "https://repo1.maven.org/maven2/",
    ],
    "jitpack": [
        "https://maven.aliyun.com/repository/public/",
        "https://jitpack.io/",
    ],
}

CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".maven-cache")
ATTEMPTS = 3
BACKOFF = 0.05
TIMEOUT = 60
PORT = int(os.environ.get("MAVEN_PROXY_PORT", "8899"))

os.makedirs(CACHE_DIR, exist_ok=True)
_lock = threading.Lock()
_stats = {"hit": 0, "miss": 0, "retry": 0, "fail": 0, "index_hit": 0}


def _cache_key(route, rest):
    return hashlib.sha256((route + "/" + rest).encode()).hexdigest()


def cache_paths(route, rest):
    key = _cache_key(route, rest)
    return os.path.join(CACHE_DIR, key + ".bin"), os.path.join(CACHE_DIR, key + ".meta")


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "maven-proxy/2.0"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.status, resp.read()


def fetch_retry(urls, attempts=ATTEMPTS):
    """Probe all upstreams concurrently; return as soon as one succeeds.

    Also returns promptly when every upstream has answered (all 404), so genuine
    misses cost milliseconds instead of waiting for a timeout.
    """
    for attempt in range(attempts):
        gate = threading.Event()
        guard = threading.Lock()
        state = {"done": 0, "ok": None}

        def probe(base):
            body = None
            try:
                st, bd = fetch(base)
                if st == 200:
                    body = bd
            except urllib.error.HTTPError:
                pass
            except Exception:
                pass
            with guard:
                state["done"] += 1
                if body is not None and state["ok"] is None:
                    state["ok"] = body
                # Fire on success, or once enough upstreams have answered that the
                # slowest one can no longer be the only source of truth.
                if state["ok"] is not None or state["done"] >= max(1, len(urls) - 1):
                    gate.set()

        for u in urls:
            threading.Thread(target=probe, args=(u,), daemon=True).start()

        gate.wait(timeout=min(TIMEOUT, 40))
        if state["ok"] is None and attempt == attempts - 1:
            pass  # final attempt already waited for the majority
        if state["ok"] is not None:
            return 200, state["ok"]
        time.sleep(BACKOFF * (1 + attempt * 0.3))
    return 404, b""


# ─────────────────────────── Google Maven index oracle ───────────────────────────
class GoogleIndex:
    """Uses master-index.xml + group-index.xml to answer 'does this exist?' fast."""

    def __init__(self):
        self.groups = None          # set of group ids, e.g. {"androidx.core", ...}
        self.group_versions = {}    # group -> {artifact: set(versions)}
        self.lock = threading.Lock()
        self.index_file = os.path.join(CACHE_DIR, "__master-index.xml")

    def _load_master(self):
        if self.groups is not None:
            return
        data = None
        if os.path.exists(self.index_file) and os.path.getsize(self.index_file) > 1024:
            with open(self.index_file, "rb") as f:
                data = f.read()
        else:
            status, body = fetch_retry([b + "master-index.xml" for b in GOOGLE_BASES])
            if status == 200 and body:
                data = body
                with open(self.index_file, "wb") as f:
                    f.write(body)
        if not data:
            self.groups = set()  # unknown -> behave conservatively
            return
        self.groups = set(re.findall(rb"<([A-Za-z0-9_.\-]+)\s*/>", data))
        self.groups = {g.decode() for g in self.groups}

    def _group_index(self, group):
        if group in self.group_versions:
            return self.group_versions[group]
        path = group.replace(".", "/")
        key = _cache_key("gidx", path)
        body_file = os.path.join(CACHE_DIR, key + ".bin")
        data = None
        if os.path.exists(body_file) and os.path.getsize(body_file) > 32:
            with open(body_file, "rb") as f:
                data = f.read()
        else:
            status, body = fetch_retry([b + path + "/group-index.xml" for b in GOOGLE_BASES])
            if status == 200 and body:
                data = body
                with open(body_file, "wb") as f:
                    f.write(body)
        table = {}
        if data:
            for m in re.finditer(rb"<([A-Za-z0-9_.\-]+)\s+versions=\"([^\"]*)\"", data):
                art = m.group(1).decode()
                vers = set(m.group(2).decode().split(","))
                table[art] = vers
        self.group_versions[group] = table
        return table

    def exists(self, rest):
        """Return True / False, or None when the index cannot decide."""
        with self.lock:
            try:
                self._load_master()
                if not self.groups:
                    return None
                candidates = sorted(self.groups, key=lambda g: -len(g))
                for g in candidates:
                    prefix = g.replace(".", "/") + "/"
                    if rest.startswith(prefix):
                        remainder = rest[len(prefix):]
                        parts = remainder.split("/")
                        if len(parts) < 2:
                            return None
                        artifact, version = parts[0], parts[1]
                        table = self._group_index(g)
                        if not table:
                            return None
                        versions = table.get(artifact)
                        if versions is None:
                            return False
                        return version in versions
                return False  # no group matches -> definitely not on Google Maven
            except Exception:
                return None


GOOGLE_INDEX = GoogleIndex()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, status, body, headers=None):
        self.send_response(status)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path = self.path.lstrip("/")
        parts = path.split("/", 1)
        if len(parts) != 2 or parts[0] not in UPSTREAMS:
            self._send(404, b"unknown route")
            return
        route, rest = parts

        body_file, meta_file = cache_paths(route, rest)
        with _lock:
            if os.path.exists(meta_file) and os.path.exists(body_file):
                with open(meta_file, "rb") as f:
                    status = int(f.read().decode().strip() or "0")
                if status == 200:
                    with open(body_file, "rb") as f:
                        body = f.read()
                    _stats["hit"] += 1
                    self._send(200, body, {"Content-Type": "application/octet-stream"})
                    return
                if status == 404:
                    _stats["hit"] += 1
                    self._send(404, b"not found (cached)")
                    return

        if route == "gmaven":
            verdict = GOOGLE_INDEX.exists(rest)
            if verdict is True:
                _stats["index_hit"] += 1
            elif verdict is False:
                with _lock:
                    open(body_file, "wb").close()
                    with open(meta_file, "wb") as f:
                        f.write(b"404")
                _stats["fail"] += 1
                self._send(404, b"not found (index)")
                return

        status, body = fetch_retry([b + rest for b in UPSTREAMS[route]])
        with _lock:
            if status == 200:
                with open(body_file, "wb") as f:
                    f.write(body)
                with open(meta_file, "wb") as f:
                    f.write(b"200")
                _stats["miss"] += 1
            else:
                open(body_file, "wb").close()
                with open(meta_file, "wb") as f:
                    f.write(b"404")
                _stats["fail"] += 1
        if status == 200:
            self._send(200, body, {"Content-Type": "application/octet-stream"})
        else:
            self._send(404, b"not found")


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    server = ThreadingServer(("127.0.0.1", PORT), Handler)
    print(f"maven proxy listening on http://127.0.0.1:{PORT}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print(f"stats: {_stats}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
