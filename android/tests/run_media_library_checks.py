"""Exercise the shared media library rules and the Emby views API against a local HTTP server."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import os
import subprocess
import tempfile
import threading
from urllib.parse import urlsplit

root = Path(__file__).resolve().parents[2]
jdk = Path(os.environ.get("MARCOMOVIE_JDK") or os.environ["JAVA_HOME"]) / "bin"
jar = root / "android/tests/lib/json-20240303.jar"
errors = []
views = [
    {"Id": "v1", "Name": "电影", "CollectionType": "movies"},
    {"Id": "v2", "Name": "剧集", "CollectionType": "tvshows"},
    {"Id": "v3", "Name": "纪录片", "CollectionType": "documentaries"},
]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/media/emby/Users/AuthenticateByName":
            if body.get("Username") != "fixture" or body.get("Pw") != "fixture-password":
                errors.append("authentication body")
            self.reply({"AccessToken": "fixture-token", "User": {"Id": "u1"}})
            return
        errors.append("unexpected POST path")
        self.reply({})

    def do_GET(self):
        path = urlsplit(self.path).path
        if self.headers.get("X-Emby-Token") != "fixture-token":
            errors.append("missing authorization")
        if path == "/media/emby/Users/u1/Views":
            self.reply({"Items": views})
        else:
            errors.append("unexpected path")
            self.reply({})

    def reply(self, body):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps(body).encode())


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with tempfile.TemporaryDirectory() as output:
        subprocess.run([jdk/"javac", "-encoding", "UTF-8", "-cp", jar, "-d", output,
                        root/"shared/java/com/pilipala/emby/EmbyLibrary.java",
                        root/"shared/java/com/pilipala/emby/MediaLibrarySettings.java",
                        root/"android/tests/MediaLibraryChecks.java"], check=True)
        subprocess.run([jdk/"java", "-cp", output+os.pathsep+str(jar), "MediaLibraryChecks",
                        f"http://127.0.0.1:{server.server_port}/media"], check=True)
    if errors:
        raise AssertionError(errors)
finally:
    server.shutdown()
