#!/usr/bin/env python3
"""Local fixture only: no real college account or credential is used."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs


class Portal(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'''<!doctype html><title>Recorder test login</title>
        <form action="/submit" method="post">
        <input type="hidden" name="csrf" value="fresh-token">
        <input name="username" autocomplete="username">
        <input name="password" type="password">
        <button type="submit" name="login" value="yes">Log in</button></form>'''
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Set-Cookie", "session=fixture; Path=/; HttpOnly")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        fields = parse_qs(self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode())
        valid = self.path == "/submit" and fields == {
            "csrf": ["fresh-token"], "username": ["smoke-user"],
            "password": ["smoke-password"], "login": ["yes"]}
        valid = valid and "session=fixture" in self.headers.get("Cookie", "")
        valid = valid and self.headers.get("Origin") == "http://10.0.2.2:8765"
        valid = valid and self.headers.get("Referer") == "http://10.0.2.2:8765/login"
        self.send_response(204 if valid else 403)
        self.end_headers()


HTTPServer(("0.0.0.0", 8765), Portal).serve_forever()
