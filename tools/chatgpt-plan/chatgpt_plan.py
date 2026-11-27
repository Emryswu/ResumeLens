"""Local ResumeLens OAuth helper. Credentials stay in the OS credential vault."""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sys
import time
import tempfile
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit, parse_qs
from urllib.request import Request, build_opener, HTTPRedirectHandler

import jwt
import keyring
from filelock import FileLock
from cryptography.fernet import Fernet

ISSUER = "https://auth.openai.com"
AUTH = ISSUER + "/api/accounts/authorize"
TOKEN = ISSUER + "/api/accounts/oauth/token"
JWKS = ISSUER + "/.well-known/jwks.json"
RESOURCE = "https://api.openai.com/v1"
SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
PLAN_SCOPE = "chatgpt.tokens.use.direct"
SERVICE = "ResumeLens ChatGPT plan usage"
ACCOUNT = "local-profile"
ROOT = Path.home() / ".resumelens-chatgpt"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_json(url, fields=None, access=None):
    headers = {"Accept": "application/json"}
    data = None
    if fields is not None:
        data = urlencode(fields).encode("ascii")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if access is not None:
        headers["Authorization"] = "Bearer " + access
    try:
        with build_opener(NoRedirect()).open(Request(url, data=data, headers=headers), timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except HTTPError as error:
        # Never print token endpoint response bodies or authorization URLs.
        raise RuntimeError(f"OpenAI request failed (HTTP {error.code})") from None


def vault_cipher(create=False):
    # Windows Credential Manager has small per-entry limits. Keep only the
    # encryption key in the OS vault, and store the encrypted record on disk.
    key = keyring.get_password(SERVICE, ACCOUNT)
    if not key:
        if not create:
            raise RuntimeError("Local credential vault key is missing")
        key = Fernet.generate_key().decode("ascii")
        keyring.set_password(SERVICE, ACCOUNT, key)
    return Fernet(key.encode("ascii"))


def load():
    path = ROOT / "session.enc"
    if not path.exists():
        return None
    return json.loads(vault_cipher().decrypt(path.read_bytes()))


def save(profile):
    path = ROOT / "session.enc"
    encrypted = vault_cipher(create=not path.exists()).encrypt(json.dumps(profile).encode("utf-8"))
    fd, tmp = tempfile.mkstemp(dir=ROOT, prefix="session-", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as file:
            file.write(encrypted)
            file.flush()
            os.fsync(file.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def host_id():
    # Not a credential, but persist it before opening the browser.
    path = ROOT / "host-id"
    if not path.exists():
        fd, tmp = tempfile.mkstemp(dir=ROOT, prefix="host-")
        try:
            with os.fdopen(fd, "w", encoding="ascii") as file:
                file.write("urn:uuid:" + str(uuid.uuid4()))
                file.flush()
                os.fsync(file.fileno())
            os.replace(tmp, path)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)
    return path.read_text(encoding="ascii").strip()


def token_claims(raw, client_id, nonce):
    key = jwt.PyJWKClient(JWKS, timeout=15).get_signing_key_from_jwt(raw)
    claims = jwt.decode(raw, key.key, algorithms=["RS256"], audience=client_id,
                        issuer=ISSUER, options={"require": ["iss", "aud", "exp", "sub"]})
    if not isinstance(claims["sub"], str) or not claims["sub"]:
        raise RuntimeError("Invalid account identity")
    if nonce is not None and not hmac.compare_digest(str(claims.get("nonce", "")), nonce):
        raise RuntimeError("ID token nonce mismatch")
    aud = claims["aud"]
    if isinstance(aud, list) and len(aud) > 1 and claims.get("azp") != client_id:
        raise RuntimeError("ID token authorized party mismatch")
    if "azp" in claims and claims["azp"] != client_id:
        raise RuntimeError("ID token authorized party mismatch")
    return claims


def update_tokens(profile, reply, nonce=None, initial=False):
    client_id = profile["client_id"]
    if initial or "id_token" in reply:
        raw = reply["id_token"]
        claims = token_claims(raw, client_id, nonce)
        if profile.get("subject") and profile["subject"] != claims["sub"]:
            raise RuntimeError("ChatGPT account differs from saved registration")
        profile = dict(profile, subject=claims["sub"], email=claims.get("email"), id_token=raw)
    if reply.get("token_type", "").lower() != "bearer":
        raise RuntimeError("Invalid token type")
    scopes = reply["scope"].split() if "scope" in reply else profile.get("scopes", [])
    if initial and "scope" not in reply:
        raise RuntimeError("Missing granted scopes")
    expires = int(reply["expires_in"])
    if expires <= 0 or not reply.get("access_token"):
        raise RuntimeError("Invalid access token response")
    updated = dict(profile, access_token=reply["access_token"],
                   refresh_token=reply.get("refresh_token", profile.get("refresh_token")),
                   scopes=scopes, expires_at=time.time() + expires)
    save(updated)
    return updated


def sign_in():
    previous = load()
    state, nonce, verifier = (secrets.token_urlsafe(32) for _ in range(3))
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).decode("ascii").rstrip("=")
    result = {}

    class Callback(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            parsed = urlsplit(self.path)
            values = parse_qs(parsed.query, keep_blank_values=True)
            if parsed.path != "/auth/callback":
                self.send_error(404)
                return
            if any(len(v) != 1 for v in values.values()) or not hmac.compare_digest(
                    values.get("state", [""])[0].encode("utf-8"), state.encode("ascii")):
                self.send_error(400, "Invalid authorization state")
                return
            result.update({k: v[0] for k, v in values.items()})
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(b"Authorization returned. Check the ResumeLens terminal.")

    with HTTPServer(("127.0.0.1", 0), Callback) as server:
        redirect = f"http://127.0.0.1:{server.server_port}/auth/callback"
        params = {"client_id": previous["client_id"] if previous else "dynamic_agent_client",
                  "ext_agent_host_id": host_id(), "response_type": "code", "redirect_uri": redirect,
                  "scope": SCOPE, "resource": RESOURCE, "state": state, "nonce": nonce,
                  "code_challenge_method": "S256", "code_challenge": challenge}
        if previous:
            if previous.get("email"):
                params["login_hint"] = previous["email"]
        else:
            params["agent_name_hint"] = "ResumeLens"
        print("Continue with ChatGPT in your browser.", file=sys.stderr)
        if not webbrowser.open(AUTH + "?" + urlencode(params)):
            raise RuntimeError("Could not open the system browser")
        server.timeout = 1
        deadline = time.monotonic() + 180
        while not result and time.monotonic() < deadline:
            server.handle_request()
    if not result or "error" in result or not result.get("code"):
        raise RuntimeError("Authorization cancelled, rejected or timed out")
    issued = result.get("client_id") or (previous or {}).get("client_id")
    if not issued or issued == "dynamic_agent_client" or (previous and issued != previous["client_id"]):
        raise RuntimeError("Unexpected OAuth client registration")
    reply = request_json(TOKEN, {"grant_type": "authorization_code", "client_id": issued,
                                 "code": result["code"], "code_verifier": verifier,
                                 "redirect_uri": redirect, "resource": RESOURCE})
    profile = update_tokens(dict(previous or {}, client_id=issued), reply, nonce, initial=True)
    print("Connected." if PLAN_SCOPE in profile["scopes"] else "Signed in, but plan usage was not granted.",
          file=sys.stderr)


def access_token():
    profile = load()
    if not profile or PLAN_SCOPE not in profile.get("scopes", []):
        raise RuntimeError("Run login and authorize ChatGPT plan usage first")
    if profile["expires_at"] <= time.time() + 90:
        if not profile.get("refresh_token"):
            raise RuntimeError("Session expired; run login again")
        reply = request_json(TOKEN, {"grant_type": "refresh_token", "client_id": profile["client_id"],
                                     "refresh_token": profile["refresh_token"], "resource": RESOURCE})
        profile = update_tokens(profile, reply)
    if PLAN_SCOPE not in profile["scopes"]:
        raise RuntimeError("ChatGPT plan usage permission is unavailable")
    return profile["access_token"]


def logout():
    profile = load()
    if not profile:
        return
    confirmed = False
    try:
        if profile.get("refresh_token"):
            discovery = request_json(ISSUER + "/.well-known/openid-configuration")
            endpoint = discovery["revocation_endpoint"]
            if urlsplit(endpoint).scheme != "https" or urlsplit(endpoint).netloc != "auth.openai.com":
                raise RuntimeError("Unexpected revocation endpoint")
            request_json(endpoint, {"token": profile["refresh_token"], "token_type_hint": "refresh_token",
                                    "client_id": profile["client_id"]})
            confirmed = True
    finally:
        save({k: profile[k] for k in ("client_id", "subject", "email") if k in profile})
        if not confirmed:
            print("Local tokens cleared; remote revocation not confirmed. Disconnect ResumeLens in ChatGPT Settings.",
                  file=sys.stderr)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["login", "models", "logout", "token"])
    args = parser.parse_args()
    ROOT.mkdir(mode=0o700, exist_ok=True)
    # Serialize login, token refresh and logout across local JVMs.
    # Fail closed instead of using a plaintext alternative to the OS vault.
    backend = keyring.get_keyring()
    if not backend.__class__.__module__.startswith(("keyring.backends.Windows", "keyring.backends.macOS",
                                                     "keyring.backends.SecretService", "keyring.backends.kwallet")):
        raise RuntimeError("A supported OS credential vault is required")
    with FileLock(str(ROOT / "session.lock"), timeout=200 if args.command == "login" else 30):
        if args.command == "login":
            sign_in()
        elif args.command == "logout":
            logout()
        elif args.command == "models":
            models = request_json(RESOURCE + "/models", access=access_token())["models"]
            for model in models:
                if model["visibility"] == "list":
                    print(model["slug"] + "\t" + model["display_name"])
        else:
            # Private IPC: Java captures stdout directly. Never invoke this command to display a token.
            if sys.stdout.isatty():
                raise RuntimeError("Token output is only available through private IPC")
            sys.stdout.write(access_token())


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        # Exception messages from third-party libraries can include sensitive data.
        print("ChatGPT authorization failed: " + (str(exc) if type(exc) is RuntimeError else type(exc).__name__),
              file=sys.stderr)
        sys.exit(1)
