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

    def read_body(self):
        """Read the request body under either HTTP/1.1 framing.

        Content-Length alone is not enough. Spring's RestClient streams this POST with
        `Transfer-Encoding: chunked` and sends no Content-Length, so reading the header returned 0,
        the body was discarded unread, and the handler answered 400 for a request that was perfectly
        well formed. The platform surfaced that as SMS_PROVIDER_UNAVAILABLE -- "Verification messages
        are temporarily unavailable" -- which points at the provider being down rather than at this
        parser, and the sink logs nothing, so nothing anywhere named the real cause.

        Chunked is mandatory for an HTTP/1.1 server. A real SMS gateway accepts it, so a fake that
        does not is not a smaller gateway, it is a differently-behaving one -- and the difference
        only shows up as a failed OTP.
        """
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            chunks = []
            while True:
                # A chunk header may carry extensions after ";" -- size is the part before it.
                size = int(self.rfile.readline().split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()  # consume the trailer-terminating CRLF
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()  # consume the CRLF that ends this chunk
            return b"".join(chunks)
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def do_POST(self):
        if self.path != "/api/messages":
            return self.reply(404, "text/plain", b"not found")
        try:
            payload = json.loads(self.read_body())
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
