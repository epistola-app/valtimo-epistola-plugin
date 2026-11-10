# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2
"""Environments and tokens for `vt`. Standard library only.

    vt_auth.py env-add NAME URL [--password]   register an environment (discovers its OIDC settings)
    vt_auth.py env-list
    vt_auth.py url ENV                          print the environment's base URL
    vt_auth.py token ENV                        print a valid access token (refreshes, or logs in)
    vt_auth.py login ENV                        log in again, interactively
    vt_auth.py logout ENV

An environment's OIDC settings are read from the Valtimo frontend's public `/assets/config.js`, so
any deployment of the test-app works without configuring an issuer by hand. Two ways to log in:

- `password`: the resource-owner password grant (local Keycloak: valtimo-console, admin/admin).
- `browser`: authorization code + PKCE with a loopback redirect (RFC 8252), for real IdPs. The
  client must allow `http://localhost:<port>/callback`; the port is VT_CALLBACK_PORT (8765).

Tokens are cached per environment under ~/.cache/vt (mode 600) and refreshed with the refresh token.
VT_TOKEN, when set, is used as-is and bypasses all of this.
"""

import base64
import hashlib
import http.server
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

CONFIG_DIR = os.path.join(os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")), "vt")
CACHE_DIR = os.path.join(os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")), "vt")
ENVS_FILE = os.path.join(CONFIG_DIR, "environments.json")
CALLBACK_PORT = int(os.environ.get("VT_CALLBACK_PORT", "8765"))

# The local test-app: backend on 8080, Keycloak on 8081, password grant.
LOCAL = {
    "url": "http://localhost:8080",
    "auth": {
        "type": "password",
        "issuer": "http://localhost:8081/realms/valtimo",
        "clientId": "valtimo-console",
        "scopes": "openid",
    },
}


def die(msg):
    print(f"vt: {msg}", file=sys.stderr)
    sys.exit(1)


def load_envs():
    envs = {"local": LOCAL}
    if os.path.exists(ENVS_FILE):
        with open(ENVS_FILE) as f:
            envs.update(json.load(f))
    return envs


def save_envs(envs):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    envs = {k: v for k, v in envs.items() if k != "local"}
    with open(ENVS_FILE, "w") as f:
        json.dump(envs, f, indent=2)


def env(name):
    envs = load_envs()
    if name not in envs:
        die(f"unknown environment '{name}' (known: {', '.join(envs)}); add it with: vt env add {name} <url>")
    e = dict(envs[name])
    if name == "local":
        # The old single-environment variables still work for the local stack.
        e["url"] = os.environ.get("VALTIMO_URL", e["url"])
        kc = os.environ.get("KEYCLOAK_URL")
        if kc:
            e["auth"] = dict(e["auth"], issuer=f"{kc}/realms/{os.environ.get('KEYCLOAK_REALM', 'valtimo')}")
    return e


def http_json(url, data=None, timeout=20):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:300]
        raise RuntimeError(f"HTTP {e.code} from {url}: {detail}") from None


def discover(url):
    """Read the frontend's runtime config, the same values the Angular app logs in with."""
    try:
        with urllib.request.urlopen(url.rstrip("/") + "/assets/config.js", timeout=20) as r:
            js = r.read().decode()
    except urllib.error.URLError as e:
        die(f"could not read {url}/assets/config.js ({e}); is this a Valtimo frontend URL?")
    cfg = dict(re.findall(r"window\['env'\]\['(\w+)'\]\s*=\s*'([^']*)'", js))
    if cfg.get("authProvider") == "authentik" or cfg.get("oidcIssuerUri"):
        return {"issuer": cfg["oidcIssuerUri"].rstrip("/"), "clientId": cfg.get("oidcClientId", "valtimo-console"),
                "scopes": cfg.get("oidcScopes", "openid profile email roles")}
    if cfg.get("keycloakUrl"):
        return {"issuer": f"{cfg['keycloakUrl'].rstrip('/')}/realms/{cfg.get('keycloakRealm', 'valtimo')}",
                "clientId": cfg.get("keycloakClientId", "valtimo-console"), "scopes": "openid"}
    die(f"{url}/assets/config.js names no OIDC issuer or Keycloak URL")


def endpoints(auth):
    return http_json(auth["issuer"] + "/.well-known/openid-configuration")


def cache_file(name):
    return os.path.join(CACHE_DIR, re.sub(r"[^A-Za-z0-9_.-]", "_", name) + ".json")


def store(name, tok):
    os.makedirs(CACHE_DIR, mode=0o700, exist_ok=True)
    tok["expires_at"] = time.time() + int(tok.get("expires_in", 60))
    path = cache_file(name)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(tok, f)
    return tok["access_token"]


def password_login(name, e):
    a = e["auth"]
    tok = http_json(endpoints(a)["token_endpoint"], {
        "grant_type": "password", "client_id": a["clientId"], "scope": a["scopes"],
        "username": os.environ.get("VALTIMO_USER", "admin"),
        "password": os.environ.get("VALTIMO_PASSWORD", "admin"),
    })
    return store(name, tok)


def browser_login(name, e):
    a = e["auth"]
    ep = endpoints(a)
    redirect = f"http://localhost:{CALLBACK_PORT}/callback"
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(24)
    url = ep["authorization_endpoint"] + "?" + urllib.parse.urlencode({
        "response_type": "code", "client_id": a["clientId"], "redirect_uri": redirect,
        "scope": a["scopes"] + " offline_access", "state": state,
        "code_challenge": challenge, "code_challenge_method": "S256",
    })
    result = {}

    class Callback(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if not self.path.startswith("/callback"):
                self.send_response(404); self.end_headers(); return
            result.update({k: v[0] for k, v in q.items()})
            ok = "code" in q and q.get("state", [""])[0] == state
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            msg = "Logged in. You can close this tab." if ok else "Login failed: " + q.get("error_description", q.get("error", ["unknown"]))[0]
            self.wfile.write(f"<p style='font-family:sans-serif'>vt: {msg}</p>".encode())

        def log_message(self, *args):
            pass

    try:
        server = http.server.HTTPServer(("127.0.0.1", CALLBACK_PORT), Callback)
    except OSError:
        die(f"port {CALLBACK_PORT} is in use; set VT_CALLBACK_PORT (the IdP must allow that redirect URI)")
    server.timeout = 300
    print(f"Opening the browser to log in to {e['url']}. If it does not open, visit:\n  {url}", file=sys.stderr)
    webbrowser.open(url)
    deadline = time.time() + 300
    while "code" not in result and "error" not in result and time.time() < deadline:
        server.handle_request()
    server.server_close()
    if result.get("state") != state or "code" not in result:
        hint = ""
        if "redirect" in (result.get("error_description", "") + result.get("error", "")).lower():
            hint = f" — the client must allow the redirect URI {redirect}"
        die(f"login failed: {result.get('error_description') or result.get('error') or 'timed out'}{hint}")
    tok = http_json(ep["token_endpoint"], {
        "grant_type": "authorization_code", "client_id": a["clientId"], "code": result["code"],
        "redirect_uri": redirect, "code_verifier": verifier,
    })
    return store(name, tok)


def login(name, explicit):
    e = env(name)
    if e["auth"]["type"] == "password":
        return password_login(name, e)
    if not explicit:
        # Never start a browser login as a side effect of an API call: in an agent's shell it would
        # wait unseen for minutes. Logging in is its own command.
        die(f"not logged in to '{name}', or the session expired. Run: vt -e {name} login "
            f"(opens a browser; waits up to 5 minutes)")
    return browser_login(name, e)


def token(name):
    if os.environ.get("VT_TOKEN"):
        return os.environ["VT_TOKEN"]
    path = cache_file(name)
    if os.path.exists(path):
        with open(path) as f:
            tok = json.load(f)
        if tok.get("expires_at", 0) - 30 > time.time():
            return tok["access_token"]
        if tok.get("refresh_token"):
            a = env(name)["auth"]
            try:
                new = http_json(endpoints(a)["token_endpoint"], {
                    "grant_type": "refresh_token", "client_id": a["clientId"],
                    "refresh_token": tok["refresh_token"],
                })
                new.setdefault("refresh_token", tok["refresh_token"])
                return store(name, new)
            except RuntimeError:
                pass  # expired or revoked: log in again below
    return login(name, explicit=False)


def main(argv):
    if not argv:
        die(__doc__)
    cmd, args = argv[0], argv[1:]
    try:
        if cmd == "env-add":
            if len(args) < 2:
                die("usage: vt env add NAME URL [--password]")
            name, url = args[0], args[1].rstrip("/")
            auth = discover(url)
            auth["type"] = "password" if "--password" in args else "browser"
            envs = load_envs()
            envs[name] = {"url": url, "auth": auth}
            save_envs(envs)
            print(f"added '{name}': {url} — {auth['type']} login via {auth['issuer']} (client {auth['clientId']})")
        elif cmd == "env-list":
            for n, e in load_envs().items():
                logged_in = os.path.exists(cache_file(n))
                print(f"{n:<12} {e['url']:<40} {e['auth']['type']:<9} {'logged in' if logged_in else ''}")
        elif cmd == "url":
            print(env(args[0])["url"])
        elif cmd == "token":
            print(token(args[0]))
        elif cmd == "login":
            login(args[0], explicit=True)
            print(f"logged in to '{args[0]}'", file=sys.stderr)
        elif cmd == "logout":
            p = cache_file(args[0])
            if os.path.exists(p):
                os.remove(p)
            print(f"logged out of '{args[0]}'", file=sys.stderr)
        else:
            die(f"unknown command {cmd}")
    except RuntimeError as e:
        die(str(e))


if __name__ == "__main__":
    main(sys.argv[1:])
