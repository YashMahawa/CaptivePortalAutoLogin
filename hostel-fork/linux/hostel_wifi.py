#!/usr/bin/env python3
"""Event-driven captive portal helper for NetworkManager-based Linux."""
import argparse, getpass, html.parser, json, os, pathlib, subprocess, sys, time
import urllib.parse, urllib.request, urllib.error, http.cookiejar

CONFIG = pathlib.Path.home() / '.config' / 'hostel-wifi' / 'config.json'

class Forms(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(); self.forms=[]; self.current=None
    def handle_starttag(self, tag, attrs):
        a=dict(attrs)
        if tag=='form':
            self.current={'action':a.get('action',''), 'method':a.get('method','get').upper(), 'inputs':[]}
            self.forms.append(self.current)
        if tag=='input' and self.current is not None:
            self.current['inputs'].append(a)
    def handle_endtag(self, tag):
        if tag=='form': self.current=None

def load():
    if not CONFIG.exists(): raise SystemExit('Run: hostel_wifi.py configure')
    return json.loads(CONFIG.read_text())
def save(c):
    CONFIG.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    fd=os.open(CONFIG,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as f: json.dump(c,f,indent=2)
    os.chmod(CONFIG,0o600)
def configure():
    c={'url':input('Portal page URL (http/https): ').strip(), 'username':input('Username: ').strip(), 'password':getpass.getpass('Password: ')}
    if urllib.parse.urlsplit(c['url']).scheme not in ('http','https'): raise SystemExit('Use an http(s) URL')
    save(c); print('Saved to',CONFIG,'(permissions 0600)')
def login(c):
    cookies=http.cookiejar.CookieJar()
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    req=urllib.request.Request(c['url'],headers={'User-Agent':'Mozilla/5.0 HostelWiFi/1.0'})
    with opener.open(req,timeout=10) as r:
        page=r.read(2_000_000).decode(r.headers.get_content_charset() or 'utf-8','replace')
        base=r.url
    forms=Forms(); forms.feed(page)
    found=[]
    for f in forms.forms:
        passwords=[i for i in f['inputs'] if i.get('type','').lower()=='password' and i.get('name')]
        users=[i for i in f['inputs'] if i.get('name') and i.get('type','text').lower() in ('text','email')]
        if passwords and users: found.append((f,users[0]['name'],passwords[0]['name']))
    if len(found)!=1: raise RuntimeError(f'Expected one login form; found {len(found)}. Open portal manually if it uses JavaScript or multiple forms.')
    f,username,password=found[0]
    action=urllib.parse.urljoin(base,f['action'])
    if urllib.parse.urlsplit(action).scheme not in ('http','https'): raise RuntimeError('Unsafe form action')
    fields={i['name']:i.get('value','') for i in f['inputs'] if i.get('name') and i.get('type','').lower() in ('hidden','submit')}
    fields.update({username:c['username'],password:c['password']})
    data=urllib.parse.urlencode(fields).encode()
    if f['method']=='POST': request=urllib.request.Request(action,data=data,headers={'User-Agent':'Mozilla/5.0 HostelWiFi/1.0'})
    elif f['method']=='GET': request=urllib.request.Request(action+'?'+data.decode(),headers={'User-Agent':'Mozilla/5.0 HostelWiFi/1.0'})
    else: raise RuntimeError('Unsupported form method '+f['method'])
    with opener.open(request,timeout=12) as r: print('Login submitted, HTTP',r.status)
    subprocess.run(['nmcli','networking','connectivity','check'],check=False,timeout=15)

def connectivity(check=False):
    args=['nmcli','-t','networking','connectivity']+(['check'] if check else [])
    return subprocess.check_output(args,text=True,timeout=15).strip()
def watch(c):
    print('Watching NetworkManager connectivity changes; Ctrl+C to quit',flush=True)
    last=0
    def attempt():
        nonlocal last
        if time.monotonic()-last<120: return
        last=time.monotonic()
        try: login(c)
        except Exception as e: print('Login failed:',e,file=sys.stderr,flush=True)
    if connectivity()=='portal': attempt()
    while True:
        with subprocess.Popen(['nmcli','monitor'],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1) as p:
            for line in p.stdout:
                if "Connectivity is now 'portal'" in line: attempt()
        time.sleep(2)

def main():
    p=argparse.ArgumentParser(description='Hostel Wi-Fi captive portal helper')
    p.add_argument('command',choices=['configure','login','watch','recheck'])
    a=p.parse_args()
    if a.command=='configure': configure()
    elif a.command=='recheck': print(connectivity(True))
    elif a.command=='login': login(load())
    else: watch(load())
if __name__=='__main__': main()
