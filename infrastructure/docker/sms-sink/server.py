import html
import json
import os
import sqlite3
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

LOCK = Lock()
DB = os.environ.get("SMS_DB", "/tmp/sms.db")


def database():
    connection = sqlite3.connect(DB)
    connection.row_factory = sqlite3.Row
    return connection


with database() as connection:
    connection.execute("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, destination TEXT NOT NULL, code TEXT NOT NULL, created_at TEXT NOT NULL)")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, "text/plain", b"ok")
        if self.path == "/api/messages":
            with LOCK:
                with database() as connection:
                    rows = connection.execute("SELECT destination AS 'to', code, created_at AS createdAt FROM messages ORDER BY id DESC LIMIT 200").fetchall()
                body = json.dumps([dict(row) for row in rows]).encode()
            return self.reply(200, "application/json", body)
        if self.path != "/":
            return self.reply(404, "text/plain", b"not found")
        with LOCK:
            with database() as connection:
                messages = connection.execute("SELECT destination, code, created_at FROM messages ORDER BY id DESC LIMIT 200").fetchall()
            rows = "".join(
                f"<tr><td>{html.escape(item['createdAt'])}</td>"
                f"<td>{html.escape(item['destination'])}</td><td><strong>{html.escape(item['code'])}</strong></td></tr>"
                for item in ({"createdAt": row["created_at"], "destination": row["destination"], "code": row["code"]} for row in messages)
            )
        page = f"""<!doctype html><html><head><meta charset=utf-8><title>RAMALS SMS Inbox</title>
<meta http-equiv=refresh content=5><style>body{{font:16px system-ui;margin:2rem;max-width:900px}}
table{{border-collapse:collapse;width:100%}}th,td{{padding:.7rem;border-bottom:1px solid #ddd;text-align:left}}
strong{{font-size:1.3rem;letter-spacing:.15rem}}</style></head><body><h1>RAMALS SMS Inbox</h1>
<p>Development-only OTP messages. Refreshes every five seconds.</p>
<table><thead><tr><th>Received (UTC)</th><th>Mobile</th><th>Code</th></tr></thead><tbody>{rows}</tbody></table>
</body></html>""".encode()
        return self.reply(200, "text/html; charset=utf-8", page)

    def do_POST(self):
        if self.path != "/api/messages":
            return self.reply(404, "text/plain", b"not found")
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length))
            destination, code = str(payload["to"]), str(payload["code"])
            if not destination or len(code) != 6 or not code.isdigit():
                raise ValueError()
        except (ValueError, KeyError, TypeError, json.JSONDecodeError):
            return self.reply(400, "application/json", b'{"error":"invalid message"}')
        with LOCK:
            with database() as connection:
                connection.execute("INSERT INTO messages(destination, code, created_at) VALUES (?, ?, ?)",
                                   (destination, code, datetime.now(timezone.utc).isoformat()))
                connection.execute("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT 200)")
        return self.reply(202, "application/json", b'{"accepted":true}')

    def reply(self, status, content_type, body):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
