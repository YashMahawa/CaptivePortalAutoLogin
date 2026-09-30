# Hostel Wi-Fi for Linux

Requires Python 3 and NetworkManager (`nmcli`). No extra packages.

```bash
python3 hostel_wifi.py configure
python3 hostel_wifi.py login
python3 hostel_wifi.py watch
python3 hostel_wifi.py recheck
```

`watch` responds to NetworkManager portal events and checks once on startup. The configuration is stored with permission 0600; the password is readable by your Linux user account. Run `watch` as your user on login, e.g. with the included systemd user unit. It does not keep the router's WAN session alive when the computer is off.

The login function fetches the portal page on each attempt to include fresh hidden form fields and cookies. JavaScript-only flows, SSO, CAPTCHAs, multiple forms and router MAC authorization may need a portal-specific handler.
