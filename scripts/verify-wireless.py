#!/usr/bin/env python3
"""Pair and stream against explicitly owned Android AVDs; installs test APKs."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--client',required=True)
parser.add_argument('--target',required=True)
parser.add_argument('--host',required=True,help='Linux host address reachable from client, e.g. emulator host gateway')
parser.add_argument('--pair-forward',type=int,required=True)
parser.add_argument('--connect-forward',type=int,required=True)
parser.add_argument('--bridge-port',type=int,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--apk',type=Path,help='Use this built APK, e.g. the final signed release')
parser.add_argument('--variant',choices=['debug','play'],default='debug',help='Build matching instrumentation; Play requires its private signing environment')
parser.add_argument('--classic-port',type=int,default=5555)
parser.add_argument('--reverse',action='store_true',help='Use owned client adb reverse ports; set --host 127.0.0.1')
parser.add_argument('--modes',nargs='+',choices=['wrong','code','qr','classic'],default=['wrong','code','qr','classic'])
parser.add_argument('--screenshots',action='store_true',help='Capture actual form, remote, keyboard and settings screens inside the owned client (code mode)')
a=parser.parse_args()
a.output.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parent.parent
app=a.apk.resolve() if a.apk else root/f'app/build/outputs/apk/{a.variant}/app-{a.variant}.apk'
test=root/f'app/build/outputs/apk/androidTest/{a.variant}/app-{a.variant}-androidTest.apk'
fixture='/data/local/tmp/scrcpy-pairing-fixture-'+uuid.uuid4().hex+'.apk'

def adb(serial,*args):
    return subprocess.check_output(['adb','-s',serial,*map(str,args)],text=True,timeout=180)
def shell(serial,command):return adb(serial,'shell',command).strip()
def call(*args):return shell(a.target,'CLASSPATH='+fixture+' app_process / AdbPairingFixture '+' '.join(args))
def pairing_port():
    for _ in range(40):
        dump=shell(a.target,'dumpsys servicediscovery')
        active=set(re.findall(r'^\s+(\d+): Advertiser: serviceFullName=\S+\._adb-tls-pairing\._tcp',dump,re.M))
        # Android keeps newest-first history here. Never forward a stale port
        # from an earlier pairing attempt just because its log still exists.
        records=re.findall(r'Adding service name: [^,]+, type: _adb-tls-pairing\._tcp.*?port: (\d+).*? with ID (\d+) ',dump)
        for port,identifier in records:
            if identifier in active:return int(port)
        time.sleep(.25)
    raise RuntimeError('No pairing service published by owned target')
owned_forwards=set()
owned_reverse=set()
def forward(port):
    options=[] if a.pair_forward in owned_forwards else ['--no-rebind']
    adb(a.target,'forward',*options,'tcp:'+str(a.pair_forward),'tcp:'+str(port))
    owned_forwards.add(a.pair_forward)
class Bridge(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            if self.path=='/back-ready':
                # Pairing broadcasts may bring Android's own wireless settings
                # forward. Establish the Back baseline after pairing completes.
                shell(a.target,'input keyevent 3')
                shell(a.target,'am start -W -n com.android.settings/.Settings -f 0x10008000')
                self.send_response(200);self.end_headers();self.wfile.write(b'{}');return
            if self.path!='/qr':raise ValueError('Unknown route')
            length=int(self.headers['Content-Length'])
            if length>1024:raise ValueError('Oversized QR')
            payload=json.loads(self.rfile.read(length))['payload']
            match=re.fullmatch(r'WIFI:T:ADB;S:(studio-[0-9a-f]{16});P:([0-9a-f]{32});;',payload)
            if not match:raise ValueError('Unexpected displayed QR')
            call('qr',*match.groups());forward(pairing_port())
            self.send_response(200);self.end_headers();self.wfile.write(b'{}')
        except Exception:
            self.send_response(500);self.end_headers()
    def log_message(self,*args):pass # Pairing secrets must not enter request logs.
server=None
results=[]
# Always build before installation; a failed compile must not run a stale APK.
with (a.output/'build.log').open('w') as build_log:
    variant=a.variant.capitalize()
    subprocess.run([str(root/'gradlew'),f'-PscrcpyTestBuildType={a.variant}',f':app:assemble{variant}',f':app:assemble{variant}AndroidTest','--max-workers=2'],cwd=root,stdout=build_log,stderr=subprocess.STDOUT,check=True)
try:
    if shell(a.target,'id -u')!='0':
        raise RuntimeError('This fixture requires adb root on the explicitly owned debuggable target AVD; do not run it on a personal device')
    if a.reverse:
        if a.host!='127.0.0.1':raise ValueError('--reverse requires --host 127.0.0.1')
        for port in [a.pair_forward,a.connect_forward,a.bridge_port]:
            adb(a.client,'reverse','--no-rebind','tcp:'+str(port),'tcp:'+str(port));owned_reverse.add(port)
    adb(a.client,'install','-r',app);adb(a.client,'install','-r','-t',test);adb(a.target,'push',test,fixture)
    # Target must already have wireless debugging enabled and its Wi-Fi trusted.
    port=shell(a.target,'getprop service.adb.tls.port')
    if not port.isdigit() or int(port)<=0:raise RuntimeError('Enable wireless debugging on the owned target first')
    adb(a.target,'forward','--no-rebind','tcp:'+str(a.connect_forward),'tcp:'+port);owned_forwards.add(a.connect_forward)
    server=ThreadingHTTPServer(('127.0.0.1',a.bridge_port),Bridge)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    for mode in a.modes:
        code=None
        before_errors=shell(a.client,'logcat -d -s AdbPairing:W') if mode=='wrong' else ''
        current_port=shell(a.target,'getprop service.adb.tls.port') if mode!='classic' else str(a.classic_port)
        if not current_port.isdigit() or int(current_port)<=0:raise RuntimeError('Target wireless debugging stopped; check its Wi-Fi before continuing')
        adb(a.target,'forward','tcp:'+str(a.connect_forward),'tcp:'+current_port)
        if mode not in ('qr','classic'):
            before=re.findall(r'updateUIPairCode: (\d{6})',shell(a.target,'logcat -d -s AdbDebuggingManager:I'))
            call('code')
            for _ in range(30):
                codes=re.findall(r'updateUIPairCode: (\d{6})',shell(a.target,'logcat -d -s AdbDebuggingManager:I'))
                if codes and codes!=before:code=codes[-1];break
                time.sleep(.25)
            if code is None:raise RuntimeError('No system pairing code; fixture requires an owned debuggable AVD')
            if mode=='wrong':code=str((int(code)+1)%1000000).zfill(6)
            forward(pairing_port())
        command=['am','instrument','-w','-r','-e','mode',mode,'-e','host',a.host,'-e','pairPort',str(a.pair_forward),'-e','connectPort',str(a.connect_forward),'-e','bridge',f'http://{a.host}:{a.bridge_port}']
        if a.screenshots and mode=='code':command+=['-e','screenshots','true']
        if code is not None:command+=['-e','code',code]
        command+=['cn.ohmyterm.scrcpy.test/cn.ohmyterm.scrcpy.WirelessPairingTest']
        output=adb(a.client,'shell',*command)
        (a.output/(mode+'.txt')).write_text(output)
        if 'PASS:' not in output or 'FAIL:' in output:
            if mode!='classic' and shell(a.target,'getprop service.adb.tls.port')!=current_port:
                raise RuntimeError('Target wireless debugging stopped or changed port during the test; check its Wi-Fi')
            raise AssertionError(f'{mode} failed; see private output directory')
        if mode in ('code','qr'):
            for _ in range(20):
                focus=shell(a.target,'dumpsys activity activities')
                resumed=next((line for line in focus.splitlines() if 'topResumedActivity=' in line),'')
                if 'launcher' in resumed.lower():break
                time.sleep(.2)
            else:raise AssertionError('Local system Back did not reach the remote Settings activity')
            results.append(mode+'-system-back');print('PASS',mode+'-system-back',flush=True)
        if mode=='wrong':
            errors=shell(a.client,'logcat -d -s AdbPairing:W')
            fresh=errors[len(before_errors):] if errors.startswith(before_errors) else errors
            if not any(message in fresh for message in ['Pairing failed; check the pairing code','Pairing code rejected']):
                (a.output/'wrong-protocol.txt').write_text(fresh)
                raise AssertionError('Wrong-code UI must correspond to a cryptographic rejection, not a connection error')
        results.append(mode);print('PASS',mode,flush=True)
    names=call('devices')
    if 'PAIRED_NAME=My_Android_Client' not in names:raise AssertionError('Full local device name was not stored by Android')
    results.append('paired-name');print('PASS paired-name',flush=True)
finally:
    if server:server.shutdown();server.server_close()
    if a.reverse:
        for port in owned_reverse:
            try:adb(a.client,'reverse','--remove','tcp:'+str(port))
            except Exception:pass
    try:call('stop')
    except Exception:pass
    for local in owned_forwards:
        try:adb(a.target,'forward','--remove','tcp:'+str(local))
        except Exception:pass
    try: shell(a.target,'rm -f '+fixture)
    except Exception:pass
    (a.output/'result.json').write_text(json.dumps(results,indent=2))
