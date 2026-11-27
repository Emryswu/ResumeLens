"""Local-only browser settings. Never serves credentials or proxies inference."""
from html import escape
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import secrets
from urllib.parse import parse_qs

from filelock import FileLock

HOST = "127.0.0.1"
PORT = 8765


def page(profile, models, csrf, message="", error=False):
    connected = bool(profile.get("access_token") and
                     "chatgpt.tokens.use.direct" in profile.get("scopes", []))
    identity = escape(str(profile.get("email") or "已绑定的 ChatGPT 账号"))
    current = profile.get("selected_model")
    options = "".join(
        '<option value="' + escape(model["slug"], quote=True) + '"' +
        (" selected" if model["slug"] == current else "") + ">" +
        escape(model["display_name"]) + "</option>" for model in models)
    hidden = '<input type="hidden" name="csrf" value="' + escape(csrf, quote=True) + '">'
    def action(path, label):
        return f'<form method="post" action="/{path}">{hidden}<button>{label}</button></form>'
    account = identity if connected else "尚未授权订阅额度"
    controls = action("login", "重新授权" if connected else "使用 ChatGPT 登录")
    if connected:
        controls += action("logout", "断开 ChatGPT")
    picker = ""
    if connected and models:
        picker = (f'<form method="post" action="/model">{hidden}'
                  '<label for="model">开发评测模型</label>'
                  '<select id="model" name="model" required>'
                  '<option value="">请选择模型</option>' + options +
                  '</select><button>保存模型</button></form>')
    notice = f'<p role="status" class="{"error" if error else "notice"}">{escape(message)}</p>' if message else ""
    return f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ResumeLens · ChatGPT 设置</title>
<style>
body{{font:16px system-ui,sans-serif;color:#24312b;background:#f4f6f3;margin:0;padding:48px 20px}}
main{{max-width:580px;margin:auto;background:white;border:1px solid #dfe5e0;border-radius:16px;padding:30px}}
h1{{font-size:25px;margin:12px 0}} p{{line-height:1.65}} small{{color:#63736a}}
form{{margin:16px 0}} button,select{{font:inherit;padding:10px 14px;border-radius:8px;border:1px solid #bac7bd}}
button{{cursor:pointer;background:#285c43;color:white;border-color:#285c43}} select{{display:block;max-width:100%;margin:8px 0 12px}}
a{{color:#285c43}} .error{{color:#a12525}} .notice{{color:#285c43}}
</style></head>
<body><main><small>ResumeLens / 本地开发</small><h1>ChatGPT 订阅授权</h1>
<p>当前状态：{account}</p>
<p>用自己的 ChatGPT 订阅运行本地 Agent 开发集评测。登录将在官方浏览器页面完成，返回这里查看授权结果。不会读取聊天记录。</p>
{notice}{controls}{picker}
<p><a href="https://chatgpt.com/settings/usage" target="_blank" rel="noopener noreferrer">查看 ChatGPT 额度与授权</a></p>
<small>仅本机操作系统用户使用。原有网页 API Key 模式不变；模型设置用于本地评测。</small>
</main></body></html>"""


def settings_server(plan, port=PORT):
    csrf = secrets.token_urlsafe(32)

    class Settings(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def safe_host(self):
            return self.headers.get("Host") == authority

        def respond(self, status, body):
            encoded = body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Content-Security-Policy",
                             "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(encoded)

        def settings(self, message="", error=False):
            profile, models = {}, []
            try:
                with FileLock(str(plan.ROOT / "session.lock"), timeout=30):
                    profile = plan.load() or {}
                    if profile.get("access_token") and plan.PLAN_SCOPE in profile.get("scopes", []):
                        models = plan.available_models()
                        profile = plan.load()
            except Exception:
                message = message or "无法读取账号或模型，请重新授权后再试。"
                error = True
            self.respond(200, page(profile, models, csrf, message, error))

        def do_GET(self):
            if not self.safe_host():
                self.respond(403, "Invalid host")
            elif self.path != "/":
                self.respond(404, "Not found")
            else:
                self.settings()

        def do_POST(self):
            # Explicit same-origin form submission prevents a public website
            # from signing in, revoking credentials or changing the local model.
            if (not self.safe_host() or self.headers.get("Origin") != origin or
                    self.headers.get_content_type() != "application/x-www-form-urlencoded"):
                self.respond(403, "Forbidden")
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 4096:
                    raise ValueError()
                fields = parse_qs(self.rfile.read(size).decode("utf-8"), strict_parsing=True)
                if any(len(values) != 1 for values in fields.values()):
                    raise ValueError()
            except (ValueError, UnicodeError):
                self.respond(400, "Invalid form")
                return
            if not hmac.compare_digest(fields.get("csrf", [""])[0].encode("utf-8"), csrf.encode("ascii")):
                self.respond(403, "Forbidden")
                return
            try:
                with FileLock(str(plan.ROOT / "session.lock"), timeout=30):
                    if self.path == "/login":
                        plan.sign_in()
                        profile = plan.load() or {}
                        message = ("授权已完成。" if plan.PLAN_SCOPE in profile.get("scopes", [])
                                   else "已登录，但未授予订阅额度权限。")
                    elif self.path == "/logout":
                        plan.logout()
                        message = "本机授权已清理。"
                    elif self.path == "/model" and "model" in fields:
                        plan.select_model(fields["model"][0])
                        message = "评测模型已保存。"
                    else:
                        self.respond(404, "Not found")
                        return
            except Exception:
                self.settings("操作失败，请检查授权状态；如撤销未确认，可在 ChatGPT 设置中断开。", True)
                return
            self.settings(message)

    server = HTTPServer((HOST, port), Settings)
    authority = f"{HOST}:{server.server_port}"
    origin = "http://" + authority
    return server


def serve(plan):
    with settings_server(plan) as server:
        print(f"ResumeLens ChatGPT settings: http://{HOST}:{server.server_port}", flush=True)
        server.serve_forever()
