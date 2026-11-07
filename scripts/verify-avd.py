#!/usr/bin/env python3
"""Exercise the actual AVD app against an ADB-connected target (no mock transport)."""
import argparse
import sys
import json
import re
import shlex
import subprocess
import time
import threading
import queue
import socket
import struct
import math
import xml.etree.ElementTree as ET
from pathlib import Path

P = argparse.ArgumentParser(description=__doc__)
P.add_argument('--client', required=True, help='AVD adb serial')
P.add_argument('--target', required=True, help='target adb serial, already authorized on this host')
P.add_argument('--host', required=True, help='target address reachable from AVD')
P.add_argument('--port', type=int, default=5555)
P.add_argument('--phase', choices=['all', 'lifecycle', 'ui', 'controls', 'keyboard', 'configs', 'insets', 'appearance', 'dialogs', 'text-dialogs', 'landscape', 'rotation', 'audio', 'runtime', 'language', 'language-live', 'audio-runtime'], default='all',
               help='Run core, lifecycle, UI, controls, keyboard, configs, insets, appearance, or dialogs checks')
P.add_argument('--output', required=True, type=Path, help='artifacts directory outside the repository')
a = P.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
ROOT = Path(__file__).resolve().parent.parent
PKG = 'cn.felix021.scrcpy'
PROBE = PKG + '.probe'
results = []
(a.output / "result.json").write_text("[]")
test_name = "AVD-validation-" + str(time.time_ns() // 1000000)
rotation_settings = {}
for serial in [a.client, a.target]:
    for key in ["accelerometer_rotation", "user_rotation"]:
        rotation_settings[(serial,key)] = subprocess.check_output(["adb","-s",serial,"shell","settings","get","system",key],text=True,timeout=10).strip()


def adb(serial, *args, binary=False):
    return subprocess.check_output(['adb', '-s', serial, *map(str, args)], timeout=25,
                                   **({} if binary else {'text': True}))


def shell(serial, *args):
    return adb(serial, 'shell', *args)


def record(name):
    results.append(name)
    print('PASS', name, flush=True)
    (a.output / 'result.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))


def dump():
    # uiautomator may exit 0 with a null root during activity transitions. Never
    # consume the previous XML file unless this invocation confirms a fresh dump.
    for attempt in range(4):
        time.sleep(.35)
        output = shell(a.client, 'uiautomator', 'dump', '/sdcard/scrcpy-validation.xml')
        if 'dumped to:' in output:
            root = ET.fromstring(shell(a.client, 'cat', '/sdcard/scrcpy-validation.xml'))
            if root.find('node') is not None:
                return root
    raise AssertionError('UI hierarchy did not become available')


def find(text=None, desc=None, root=None, rid=None):
    if root is None:
        root = dump()
    for n in root.iter('node'):
        if text is not None and n.get('text') == text:
            return n
        if desc is not None and n.get('content-desc') == desc:
            return n
        if rid is not None and n.get('resource-id','').endswith('/'+rid):
            return n
    return None


def click(node):
    assert node is not None, 'UI element missing'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    shell(a.client, 'input', 'tap', (x1 + x2) // 2, (y1 + y2) // 2)
    time.sleep(.5)


def begin_disconnect():
    click(find(rid='runtime_settings'))
    tap('断开连接')


def tap(text):
    if text == '断开':
        begin_disconnect()
        tap('确认断开')
        return
    if text in ['省流','均衡','高清'] and find(text=text) is None:
        click(find(rid='quality_picker'))
    end = time.monotonic() + 12
    while time.monotonic() < end:
        node = find(text=text)
        if node is not None:
            click(node)
            return
    raise AssertionError(f'UI element missing: {text}')


def bottom():
    # Wait for the actual form and verify the scroll result. Fixed swipe counts
    # can run before activity startup finishes and leave the form at its top.
    end = time.monotonic() + 25
    while time.monotonic() < end:
        root = dump()
        if find(text='充电时保持远端唤醒', root=root) is not None and find(rid='scroll_hint',root=root) is None:
            return root
        if any(n.get('package') == PKG for n in root.iter('node')):
            l,t,r,b=map(int,re.findall(r'\d+',find(rid='form_scroll',root=root).get('bounds')))
            shell(a.client,'input','swipe',(l+r)//2,b-40,(l+r)//2,t+40,350)
        time.sleep(.3)
    raise AssertionError('Form did not scroll to connection button')


def top():
    end = time.monotonic() + 25
    while time.monotonic() < end:
        root = dump()
        if find(text='远程控制', root=root) is not None or find(text='Remote control',root=root) is not None:
            return root
        l,t,r,b=map(int,re.findall(r'\d+',find(rid='form_scroll',root=root).get('bounds')))
        shell(a.client,'input','swipe',(l+r)//2,t+40,(l+r)//2,b-40,350)
        time.sleep(.3)
    raise AssertionError('Form did not scroll to device fields')


def edit(desc, value):
    click(find(desc=desc))
    shell(a.client, 'input', 'keyevent', 'KEYCODE_MOVE_END')
    shell(a.client, 'input', 'keyevent', *(['KEYCODE_DEL'] * 64))
    shell(a.client, 'input', 'text', value)
    time.sleep(.5)
    shell(a.client, 'input', 'keyevent', 'KEYCODE_BACK')


def screenshot(name):
    (a.output / (name + '.png')).write_bytes(adb(a.client, 'exec-out', 'screencap', '-p', binary=True))


def wait_connected():
    end = time.monotonic() + 60
    while time.monotonic() < end:
        root = dump()
        if any('已连接' in n.get('text', '') or (n.get('text', '').startswith('● ') and '仅观看' in n.get('text', '')) for n in root.iter('node')):
            return root
        if any('连接结束：' in n.get('text','') for n in root.iter('node')) or find(text='连接设备   →', root=root) is not None:
            raise AssertionError('Connection returned to form: ' + str([n.get('text') for n in root.iter('node')]))
        time.sleep(.5)
    raise AssertionError('No rendered frame within 60 seconds')


def connect():
    bottom()
    tap('连接设备   →')
    wait_connected()


def start_probe():
    shell(a.target,'cmd','statusbar','collapse')
    shell(a.target, 'am', 'force-stop', PROBE)
    shell(a.target, 'am', 'start', '-W', '-n', PROBE + '/.ProbeActivity')
    time.sleep(.5)


def logs():
    return adb(a.target, 'logcat', '-d', '-s', 'ScrcpyProbe:I', '*:S')


def resumed():
    return shell(a.target, 'dumpsys', 'activity', 'activities')


def assert_log(value):
    time.sleep(.4)
    assert value in logs(), f'Missing remote event: {value}'


def verify_ui():
    def bounds(node):
        assert node is not None
        return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))
    def stay_connected():
        assert find(rid='runtime_settings') is not None, 'Local Back or gesture exited remote control'
    def swipe(edge):
        x1,y1,x2,y2 = bounds(find(desc='远控屏幕'))
        w,h = x2-x1,y2-y1
        gestures = {
            'TOP': (x1+w//2,y1+2,x1+w//2,y1+h//3),
            'BOTTOM': (x1+w//2,y2-3,x1+w//2,y2-h//3),
            'LEFT': (x1+2,y1+h//4,x1+w//2,y1+h//4),
            'RIGHT': (x2-3,y1+h//4,x1+w//2,y1+h//4),
        }
        previous = adb(a.client,'logcat','-d','-s','RemoteGesture:D','*:S')
        shell(a.client,'input','swipe',*gestures[edge],400)
        time.sleep(.5)
        current = adb(a.client,'logcat','-d','-s','RemoteGesture:D','*:S')
        assert current.count('EDGE_ACTION_'+edge) > previous.count('EDGE_ACTION_'+edge), f'{edge} gesture did not reach the video surface'
        assert current.count('LOCAL_BACK_FORWARD') == previous.count('LOCAL_BACK_FORWARD'), 'Local Back substituted for a remote swipe'
        assert current.count('CANCEL') == previous.count('CANCEL'), 'Local system intercepted and cancelled the swipe'
        stay_connected()
    def target_in_probe():
        end = time.monotonic() + 10
        while time.monotonic() < end:
            if PROBE in next((line for line in resumed().splitlines() if 'topResumedActivity=' in line), ''):
                return
            time.sleep(.3)
        raise AssertionError('Remote probe did not become the resumed activity')
    root = dump()
    assert not any('easytier' in n.get('text','').lower() for n in root.iter('node'))
    if find(text='展示虚拟按键',root=root).get('checked') != 'true':
        tap('展示虚拟按键')
    record('network-neutral UI and virtual-key option available')
    click(find(desc='视频编码'))
    root = dump()
    assert find(text='H.264',root=root) is not None and find(text='H.265',root=root) is not None
    tap('H.265')
    assert find(text='H.265') is not None
    click(find(desc='视频编码')); tap('H.264')
    record('video codec dropdown exposes both selectable options')
    # A shorter viewport forces scrolling without changing the normal-size layout.
    top()
    shell(a.client,'wm','size','1080x1600'); time.sleep(.6)
    before = dump(); button_before = bounds(find(text='连接设备   →',root=before))
    before_fields = [n.get('bounds') for n in before.iter('node') if n.get('class') == 'android.widget.EditText']
    shell(a.client,'input','swipe',1030,1250,1030,400,400)
    after = dump()
    assert bounds(find(text='连接设备   →',root=after)) == button_before
    assert [n.get('bounds') for n in after.iter('node') if n.get('class') == 'android.widget.EditText'] != before_fields
    screenshot('08-fixed-connect')
    shell(a.client,'wm','size','reset'); time.sleep(.6); bottom()
    record('connect button stays fixed while settings scroll')
    tap('省流')
    tap('展示虚拟按键')
    connect()
    root = dump()
    assert all(find(desc=name,root=root) is None for name in ['Back','Home','最近任务'])
    x1,y1,x2,y2 = bounds(find(desc='远控屏幕',root=root))
    assert y2-y1 > 2340*.8, 'Video does not use available height'
    screenshot('09-gesture-only')
    record('virtual keys hidden and video fills height above bottom row and safe area')
    start_probe(); target_in_probe(); adb(a.target,'logcat','-c')
    before_back = adb(a.client,'logcat','-d','-s','RemoteGesture:D','*:S').count('LOCAL_BACK_FORWARD')
    shell(a.client,'input','keyevent','KEYCODE_BACK')
    assert_log('BACK 4'); stay_connected()
    assert adb(a.client,'logcat','-d','-s','RemoteGesture:D','*:S').count('LOCAL_BACK_FORWARD') > before_back, 'Back diagnostics missing from installed APK'
    record('local Back reaches remote without disconnecting')
    start_probe(); target_in_probe()
    swipe('TOP')
    windows = shell(a.target,'dumpsys','window')
    assert re.search(r'mCurrentFocus=.*(NotificationShade|StatusBar)',windows,re.I), 'Remote shade did not open'
    screenshot('10-top-swipe')
    record('video top-down swipe opens remote quick settings')
    shell(a.target,'cmd','statusbar','collapse')
    start_probe(); target_in_probe()
    swipe('BOTTOM')
    assert 'launcher' in next(line for line in resumed().splitlines() if 'topResumedActivity=' in line).lower()
    record('video bottom-up swipe returns remote to Home')
    for edge in ['LEFT','RIGHT']:
        start_probe(); target_in_probe()
        swipe(edge)
        assert PROBE not in next(line for line in resumed().splitlines() if 'topResumedActivity=' in line)
        record(f'video {edge.lower()} inward swipe performs remote Back')
    tap('断开'); bottom()
    shell(a.client,'am','force-stop',PKG)
    shell(a.client,'am','start','-n',PKG+'/.MainActivity'); bottom()
    assert find(text='展示虚拟按键').get('checked') == 'false'
    tap('展示虚拟按键'); connect()
    root = dump()
    assert all(find(desc=name,root=root) is not None for name in ['Back','Home','最近任务'])
    screenshot('11-compact-status-bar')
    record('virtual-key preference persists and keys can be restored')
    shell(a.client,'settings','put','system','accelerometer_rotation',0)
    shell(a.client,'settings','put','system','user_rotation',1)
    time.sleep(.7)
    wait_connected()
    root = dump()
    bounds_video = bounds(find(desc='远控屏幕',root=root))
    assert 0 <= bounds_video[0] < bounds_video[2] <= 2340
    assert 0 <= bounds_video[1] < bounds_video[3] < 1080
    screenshot('12-landscape-safe-area')
    record('landscape recomputes safe areas without losing connection')
    tap('断开')


def verify_controls():
    def bounds(node):
        assert node is not None, 'UI element missing'
        return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
    def key(desc):
        click(find(desc=desc))
    def rotate(serial, rotation):
        shell(serial,'settings','put','system','accelerometer_rotation',0)
        shell(serial,'settings','put','system','user_rotation',rotation)
        time.sleep(1)
    def input_logs():
        return adb(a.target,'logcat','-d','-s','ScrcpyInput:I','*:S')
    def target_tree():
        output=shell(a.target,'uiautomator','dump','/sdcard/scrcpy-validation.xml')
        assert 'dumped to:' in output
        return ET.fromstring(shell(a.target,'cat','/sdcard/scrcpy-validation.xml'))
    def expect_text(value):
        root=target_tree()
        node=find(desc='验证输入框',root=root)
        assert node is not None and node.get('text')==value, 'Remote text differs: '+str(node.attrib if node is not None else None)
    def wait_video_shape(landscape):
        end=time.monotonic()+15
        while time.monotonic()<end:
            l,t,r,b=bounds(find(desc='远控屏幕'))
            if ((r-l)>(b-t))==landscape:return
        raise AssertionError('Remote rotation did not reach displayed video')
    def gesture(edge, outside=False, duration=250):
        root=dump(); l,t,r,b=bounds(find(desc='远控屏幕',root=root))
        ol,ot,orr,ob=bounds(find(desc='远控触控区',root=root))
        # Cross the displayed remote boundary from its black letterbox when present.
        d=-8 if outside else 2
        points={'LEFT':(l+d,t+(b-t)//3,l+(r-l)//2,t+(b-t)//3),
                'RIGHT':(r-d-1,t+(b-t)//3,l+(r-l)//2,t+(b-t)//3),
                'TOP':(l+(r-l)//2,t+d,l+(r-l)//2,t+(b-t)//2),
                'BOTTOM':(l+(r-l)//2,b-d-1,l+(r-l)//2,t+(b-t)//2)}
        x,y,xx,yy=points[edge]
        assert ol<=x<orr and ot<=y<ob, ('No letterbox for gesture',edge,(l,t,r,b),(ol,ot,orr,ob))
        before=adb(a.client,'logcat','-d','-s','RemoteGesture:D','*:S')
        shell(a.client,'input','swipe',x,y,xx,yy,duration)
        time.sleep(.5)
        after=adb(a.client,'logcat','-d','-s','RemoteGesture:D','*:S')
        for marker in ['LOCAL_BACK_FORWARD','CANCEL']:
            assert after.count(marker)==before.count(marker), marker+' intercepted edge action'
        assert after.count('EDGE_ACTION_'+edge)>before.count('EDGE_ACTION_'+edge), edge+' action missing'
        assert find(rid='runtime_settings') is not None
    overlays=shell(a.target,'cmd','overlay','list')
    nav=next((line.split()[-1] for line in overlays.splitlines() if line.startswith('[x]') and '.navbar.' in line),None)
    try:
        # Target three-button navigation proves actions don't depend on its swipe setup.
        shell(a.target,'cmd','overlay','enable-exclusive','--category','com.android.internal.systemui.navbar.threebutton')
        rotate(a.client,0); rotate(a.target,0)
        tap('省流'); connect()
        root=dump()
        n=[bounds(find(desc=d,root=root)) for d in ['Back','Home','最近任务']]
        status=next(node for node in root.iter('node') if '已连接' in node.get('text',''))
        exit_bounds=bounds(find(rid='runtime_settings',root=root))
        assert len({v[1] for v in n})==1 and all(abs((v[1]+v[3])//2-(exit_bounds[1]+exit_bounds[3])//2)<12 for v in n)
        assert bounds(status)[2]<=n[0][0] and exit_bounds[0]>=n[-1][2]
        screenshot('13-bottom-navigation')
        record('three navigation buttons centered in bottom row, status left and disconnect right')
        if a.phase == 'controls':
            start_probe(); adb(a.target,'logcat','-c')
            l,t,r,b=bounds(find(desc='远控屏幕'))
            shell(a.client,'input','swipe',(l+r)//2,t+(b-t)//3,(l+r)//2,t+(b-t)*2//3,300)
            assert_log('TOUCH_DOWN'); assert_log('TOUCH 1')
            record('ordinary video drag retains remote DOWN and UP')
            for edge in ['LEFT','RIGHT']:
                start_probe(); adb(a.target,'logcat','-c')
                gesture(edge,True)
                assert PROBE not in next(line for line in resumed().splitlines() if 'topResumedActivity=' in line)
                assert 'TOUCH' not in logs(), 'Edge navigation leaked raw touch'
                record(edge.lower()+' letterbox-to-video gesture performs remote Back in three-button mode')
            start_probe();gesture('LEFT',False,1000)
            assert PROBE not in next(line for line in resumed().splitlines() if 'topResumedActivity=' in line)
            record('slow inward swipe remains eligible after tap timeout')
            start_probe();rotate(a.target,1);wait_video_shape(True)
            gesture('TOP',True)
            shade=target_tree()
            texts=' '.join(n.get('text','')+' '+n.get('content-desc','') for n in shade.iter('node'))
            assert 'Display brightness' in texts and 'Edit order of settings' in texts and 'Power menu' in texts, 'Quick settings not expanded: '+texts
            screenshot('14-control-center')
            shell(a.target,'cmd','statusbar','collapse'); start_probe()
            gesture('BOTTOM',True)
            assert 'launcher' in next(line for line in resumed().splitlines() if 'topResumedActivity=' in line).lower()
            record('top and bottom letterbox gestures expand quick settings and return Home')
            start_probe();rotate(a.target,0);wait_video_shape(False);adb(a.target,'logcat','-c')
            root=dump();l,t,r,b=bounds(find(desc='远控屏幕',root=root));ol,ot,orr,ob=bounds(find(desc='远控触控区',root=root))
            shell(a.client,'input','tap',(ol+l)//2,(t+b)//2)
            assert 'TOUCH ' not in logs()
            record('black letterbox taps do not become remote edge touches')
            shell(a.client,'input','keyevent',4); assert_log('BACK 4')
            assert find(rid='runtime_settings') is not None
            record('local Back still reaches target and keeps session connected')
        shell(a.target,'cmd','statusbar','collapse')
        shell(a.target,'am','start','-W','-n',PROBE+'/.InputActivity')
        key('虚拟键盘')
        for char in 'hello': key('字符 '+char)
        key('Shift');key('字符 A')
        key('Caps Lock');key('字符 B');key('Shift');key('字符 c');key('Caps Lock')
        expect_text('helloABc')
        key('Ctrl');key('字符 a');key('字符 b');expect_text('b')
        record('full keyboard, Shift, Caps+Shift and one-shot Ctrl+A edit remote text')
        l,t,r,b=bounds(find(desc='Ctrl'))
        shell(a.client,'input','swipe',(l+r)//2,(t+b)//2,(l+r)//2,(t+b)//2,700)
        key('字符 a');key('字符 c')
        assert find(desc='Ctrl').get('selected')=='true', 'Locked Ctrl lost its state'
        key('Ctrl');key('字符 b');expect_text('b')
        record('long press locks Ctrl across multiple keys and tap unlocks it')
        key('Shift');key('字符 <');expect_text('b<')
        key('切换符号');key('Shift');key('字符 {');expect_text('b<{')
        key('切换符号')
        key('Fn');key('F1');key('Fn');key('F11');key('Home 键')
        remote_logs=input_logs()
        assert all('KEY '+str(code)+' META ' in remote_logs for code in [131,141,122])
        key('Alt');key('Left')
        assert any(int(meta)&0x12==0x12 for meta in re.findall(r'KEY 21 META (\d+)',input_logs())), 'Alt modifier missing'
        record('shifted punctuation, Fn function/navigation keys and Alt modifier reach remote')
        screenshot('15-keyboard-portrait')
        key('Ctrl');key('字符 a');key('输入文字')
        helper='/data/local/tmp/scrcpy-validation.apk'
        adb(a.client,'push',ROOT/'validation/probe/build/outputs/apk/debug/probe-debug.apk',helper)
        value='中文输入🙂 ' + '0123456789'*600
        command='CLASSPATH='+helper+' app_process / '+PROBE+'.SetText '+shlex.quote('发送文本')+' '+shlex.quote(value)
        output=shell(a.client,command)
        assert 'Exception' not in output, output
        tap('发送');expect_text(value)
        shell(a.client,'rm',helper)
        record('Unicode and 6 KB text dialog paste survives ADB packet fragmentation')
        l,t,r,b=bounds(find(desc='Backspace'))
        shell(a.client,'input','swipe',(l+r)//2,(t+b)//2,(l+r)//2,(t+b)//2,800)
        short=find(desc='验证输入框',root=target_tree()).get('text')
        assert value.startswith(short) and len(value)-50<len(short)<len(value)
        time.sleep(.3);expect_text(short)
        record('held Backspace repeats and stops when released')
        rotate(a.client,1)
        root=wait_connected();v=bounds(find(desc='远控屏幕',root=root));k=bounds(find(desc='完整虚拟键盘',root=root))
        assert v[2]<=k[0] and v[2]>v[0] and k[3]>k[1], (v,k)
        key('Ctrl');key('字符 a');key('字符 x');expect_text('x')
        screenshot('16-keyboard-landscape')
        record('landscape keyboard sits beside video and remains usable without reconnecting')
        key('收起键盘');rotate(a.target,1);wait_video_shape(True)
        root=wait_connected();v=bounds(find(desc='远控屏幕',root=root))
        assert v[2]-v[0]>v[3]-v[1]
        screenshot('17-both-landscape')
        record('both devices landscape: aspect ratio, safe areas and side rail retained')
        tap('断开');rotate(a.client,0);rotate(a.target,0);bottom();tap('展示虚拟按键');connect()
        root=dump();assert all(find(desc=d,root=root) is None for d in ['Back','Home','最近任务'])
        assert find(desc='虚拟键盘',root=root) is not None
        start_probe();gesture('LEFT',True)
        assert PROBE not in next(line for line in resumed().splitlines() if 'topResumedActivity=' in line)
        tap('断开');bottom();tap('展示虚拟按键')
        record('navigation can be hidden while full keyboard and edge actions remain available')
    finally:
        if nav:shell(a.target,'cmd','overlay','enable-exclusive','--category',nav)


def verify_landscape():
    def bounds(node):
        assert node is not None
        return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
    def rotate(value):
        shell(a.client,'settings','put','system','accelerometer_rotation',0)
        shell(a.client,'settings','put','system','user_rotation',value)
    def shape(landscape):
        end=time.monotonic()+15
        while time.monotonic()<end:
            root=wait_connected();v=bounds(find(desc='远控屏幕',root=root))
            if ((v[2]-v[0])>(v[3]-v[1]))==landscape:return root
        raise AssertionError('Remote did not follow local orientation')
    def rails(name):
        root=shape(True)
        status=bounds(find(desc='横屏连接状态',root=root));rail=bounds(find(desc='横屏操作栏',root=root))
        video=bounds(find(desc='远控屏幕',root=root))
        keys=[bounds(find(desc=d,root=root)) for d in ['Back','Home','最近任务','虚拟键盘','断开连接']]
        assert status[2]<=video[0] and video[2]<=rail[0],(status,video,rail)
        assert len({(k[0]+k[2])//2 for k in keys})==1,keys
        assert all(keys[i][3]<=keys[i+1][1] for i in range(len(keys)-1)),keys
        area=bounds(find(desc='远控触控区',root=root))
        assert area[3]-area[1]>1080*.83,area
        windows=shell(a.client,'dumpsys','window','displays')
        (a.output/(name+'-window.txt')).write_text(windows)
        camera_rects=[]
        for section in re.findall(r'boundingRect=\{Bounds=\[(.*?)\]\}',windows):
            for coords in re.findall(r'Rect\((\d+), (\d+) - (\d+), (\d+)\)',section):
                c=tuple(map(int,coords))
                if c[2]>c[0] and c[3]>c[1]:camera_rects.append(c)
        if 'middle-camera' in name:
            assert any(c[1]<540<c[3] for c in camera_rects),camera_rects
        status_text=next(n for n in root.iter('node') if '已连接' in n.get('text',''))
        for item in keys+[bounds(status_text)]:
            for c in camera_rects:
                assert item[2]<=c[0] or c[2]<=item[0] or item[3]<=c[1] or c[3]<=item[1],(item,c)
        screenshot(name)
        return root
    device_overlay=next((line.split()[-1] for line in shell(a.client,'cmd','overlay','list').splitlines()
                         if line.startswith('[x]') and '.emulation.pixel_' in line),None)
    previous_overlay=next((line.split()[-1] for line in shell(a.client,'cmd','overlay','list').splitlines()
                           if line.startswith('[x]') and '.cutout.' in line),None)
    rotate(0);shell(a.target,'settings','put','system','accelerometer_rotation',0)
    shell(a.target,'settings','put','system','user_rotation',0)
    tap('省流');connect();start_probe();adb(a.client,'logcat','-c')
    try:
        for i,value in enumerate([1,0,3,0]):
            rotate(value);root=shape(value!=0)
            if value:rails('30-landscape-'+str(value))
            else:
                nav=[bounds(find(desc=d,root=root)) for d in ['Back','Home','最近任务']]
                assert len({n[1] for n in nav})==1
                assert abs((nav[0][0]+nav[-1][2])/2-540)<3,nav
            assert_log('ROTATION '+str(2 if value else 1))
        record('local portrait/landscape events rotate target both ways without reconnecting; side rails and centered portrait dock survive both landscape directions')
        before=adb(a.client,'logcat','-d','-s','RemoteRotation:D','*:S').count('SET_ORIENTATION')
        time.sleep(3.5)
        assert adb(a.client,'logcat','-d','-s','RemoteRotation:D','*:S').count('SET_ORIENTATION')==before
        record('stable frames do not cause repeated rotation commands')
        # Changing a framework resource overlay recreates Activities, so apply
        # the simulated hardware before making a new connection.
        tap('断开')
        if device_overlay:shell(a.client,'cmd','overlay','disable',device_overlay)
        shell(a.client,'cmd','overlay','enable-exclusive','--category','com.android.internal.display.cutout.emulation.tall')
        time.sleep(2)
        shell(a.client,'am','force-stop',PKG);shell(a.client,'am','start','-n',PKG+'/.MainActivity')
        connect()
        rotate(1);rails('31-middle-camera-left')
        rotate(3);rails('32-middle-camera-right')
        record('landscape rails avoid central camera cutout in both directions')
        click(find(desc='虚拟键盘'));root=dump()
        keyboard=bounds(find(desc='完整虚拟键盘',root=root));video=bounds(find(desc='远控屏幕',root=root))
        assert video[2]<=keyboard[0]
        first=[bounds(find(desc=d,root=root)) for d in ['Escape','Ctrl','Home 键','Up','End','PgUp','输入文字','Delete']]
        second=[bounds(find(desc=d,root=root)) for d in ['Tab','Alt','Left','Down','Right','PgDn','粘贴','收起键盘']]
        assert len({n[1] for n in first})==len({n[1] for n in second})==1
        assert first[0][3]<=second[0][1]
        screenshot('33-landscape-two-control-rows')
        click(find(desc='收起键盘'));begin_disconnect();tap('取消');wait_connected()
        record('two control rows retain their navigation cross in landscape; disconnect cancel keeps session')
        rotate(0);shape(False);tap('断开');bottom();tap('仅观看');connect()
        rotate(1);time.sleep(1)
        root=shape(False)
        assert find(desc='虚拟键盘',root=root).get('enabled')=='false'
        assert 'ROTATION 2' not in logs().split('ROTATION 1')[-1]
        record('view-only does not change remote orientation')
        tap('断开');rotate(0);bottom();tap('仅观看')
    finally:
        shell(a.client,'cmd','overlay','disable','com.android.internal.display.cutout.emulation.tall')
        if previous_overlay:shell(a.client,'cmd','overlay','enable-exclusive','--category',previous_overlay)
        if device_overlay:shell(a.client,'cmd','overlay','enable-exclusive','--category',device_overlay)


def verify_rotation_restoration():
    def settings():
        return tuple(shell(a.target,'settings','get','system',key).strip() for key in ['accelerometer_rotation','user_rotation'])
    def expect(values):
        deadline=time.monotonic()+10
        while time.monotonic()<deadline:
            actual=settings()
            if actual==values:return
            time.sleep(.2)
        raise AssertionError(('Remote rotation settings differ',values,actual))
    def local(value):
        shell(a.client,'wm','user-rotation','lock',value)
    def video(landscape):
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            root=wait_connected();node=find(desc='远控屏幕',root=root)
            l,t,r,b=map(int,re.findall(r'\d+',node.get('bounds')))
            if ((r-l)>(b-t))==landscape:return
        raise AssertionError('Target did not follow explicit local orientation')
    shell(a.target,'settings','put','system','user_rotation',0)
    shell(a.target,'settings','put','system','accelerometer_rotation',1)
    local(0);tap('省流');connect();expect(('0','0'));start_probe();video(False);expect(('0','0'))
    local(1);video(True);expect(('0','1'))
    local(0);video(False);expect(('0','0'))
    tap('断开');expect(('1','0'))
    record('auto-rotating target follows explicit local portrait/landscape; disconnect restores original rotation and auto-rotate')
    connect();start_probe();local(1);video(True);expect(('0','1'));pid=core_pid()
    shell(a.client,'am','force-stop',PKG);expect(('1','0'))
    deadline=time.monotonic()+10
    while pid in shell(a.target,'ps','-A','-o','PID').split():
        assert time.monotonic()<deadline,'Remote video process survived client termination'
        time.sleep(.2)
    record('force-stopping client restores remote rotation and terminates its video process through remote shell EXIT cleanup')
    local(0);shell(a.client,'am','start','-n',PKG+'/.MainActivity');bottom();tap('仅观看')
    original=settings();connect();local(1);wait_connected();time.sleep(.5)
    assert settings()==original
    record('view-only leaves both remote rotation settings unchanged')
    tap('断开');local(0);bottom();tap('仅观看')


def verify_audio(live=False):
    def sound(text,enabled):
        bottom();node=find(text=text)
        assert node is not None and node.get('enabled')=='true',text
        if node.get('checked')!=str(enabled).lower():click(node)
    def audio_logs():
        return adb(a.client,'logcat','-d','-s','ScrcpyAudio:I','*:S')
    def wait_mic():
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            logs=audio_logs()
            if 'MIC_STARTED' in logs:return
            assert 'MIC_FAILED' not in logs,logs
            time.sleep(.2)
        raise AssertionError('Local microphone did not start')
    media_volumes={}
    for serial in [a.client,a.target]:
        value=shell(serial,'cmd','media_session','volume','--stream',3,'--get')
        media_volumes[serial]=int(re.search(r'volume is (\d+)',value)[1])
    original_audio=find(text='播放远端声音').get('checked')=='true'
    original_mic=find(text='转发本机麦克风').get('checked')=='true'
    shell(a.client,'am','force-stop',PKG)
    shell(a.client,'pm','revoke',PKG,'android.permission.RECORD_AUDIO')
    shell(a.client,'pm','clear-permission-flags',PKG,'android.permission.RECORD_AUDIO','user-set','user-fixed')
    shell(a.client,'am','start','-n',PKG+'/.MainActivity')
    bottom()
    if find(text='仅观看').get('checked')=='true':tap('仅观看')
    sound('播放远端声音',False);sound('转发本机麦克风',True)
    adb(a.client,'logcat','-c')
    if live:
        sound('转发本机麦克风',False);connect();live_pid=core_pid();runtime_option('forwardMic',True)
    else:tap('连接设备   →')
    root=dump()
    deny=next((n for n in root.iter('node') if n.get('resource-id','').endswith('/permission_deny_button')),None)
    assert deny is not None,'Mic permission was not requested'
    click(deny)
    if live:
        assert core_pid()==live_pid
        runtime_close();wait_connected()
    else:
        assert find(text='连接设备   →') is not None
        sound('转发本机麦克风',False)
    assert 'MIC_STARTED' not in audio_logs()
    record('microphone permission is requested only when enabled; denial never starts recording and retains the '+('video session' if live else 'form'))
    shell(a.client,'pm','grant',PKG,'android.permission.RECORD_AUDIO')
    shell(a.target,'pm','grant',PROBE,'android.permission.RECORD_AUDIO')
    source_apk='/data/local/tmp/scrcpy-mic-source-validation.apk'
    capture_apk='/data/local/tmp/scrcpy-audio-validation.apk'
    name='scrcpy_mic_validation_'+str(time.time_ns())
    adb(a.client,'push',ROOT/'app/build/outputs/apk/debug/app-debug.apk',source_apk)
    adb(a.client,'push',ROOT/'validation/probe/build/outputs/apk/debug/probe-debug.apk',capture_apk)
    process=subprocess.Popen(['adb','-s',a.client,'shell','CLASSPATH='+source_apk+' app_process / '+PKG+'.MicServer '+name+' '+source_apk],
                             stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    lines=queue.Queue()
    def read_lines():
        for line in process.stdout:lines.put(line)
        lines.put('EOF')
    threading.Thread(target=read_lines,daemon=True).start()
    forwarded=None;connection=None;producer=None;stopping=threading.Event()
    try:
        deadline=time.monotonic()+15;messages=[]
        while time.monotonic()<deadline:
            line=lines.get(timeout=max(.1,deadline-time.monotonic()));messages.append(line)
            if 'MIC_READY' in line:break
            assert line!='EOF',''.join(messages)
        else:raise AssertionError('Source mic injector did not start')
        forwarded=int(adb(a.client,'forward','tcp:0','localabstract:'+name).strip())
        connection=socket.create_connection(('127.0.0.1',forwarded))
        def produce():
            count=0;start=time.monotonic()
            try:
                while not stopping.is_set():
                    samples=[int(math.sin(2*math.pi*997*(count+i)/16000)*12000) for i in range(320)]
                    count+=len(samples);data=struct.pack('<'+'h'*len(samples),*samples)
                    connection.sendall(struct.pack('>H',len(data))+data)
                    stopping.wait(max(0,start+count/16000-time.monotonic()))
            except OSError:
                if not stopping.is_set():raise
        producer=threading.Thread(target=produce,daemon=True);producer.start()
        previous_mic=False
        for playback,mic in [(False,False),(True,False),(False,True),(True,True)]:
            adb(a.client,'logcat','-c');adb(a.target,'logcat','-c')
            # Normalize both outputs before creating playback tracks. Starting a
            # REMOTE_SUBMIX measurement can otherwise retain their earlier gain.
            for serial in media_volumes:shell(serial,'cmd','media_session','volume','--stream',3,'--set',15)
            if live:
                runtime_option('forwardAudio',playback);runtime_option('forwardMic',mic)
                screenshot('40-audio-options-'+str(int(playback))+str(int(mic)));runtime_close()
                assert core_pid()==live_pid
            else:
                sound('播放远端声音',playback);sound('转发本机麦克风',mic)
                screenshot('40-audio-options-'+str(int(playback))+str(int(mic)));connect()
            if mic and (not live or not previous_mic):wait_mic()
            previous_mic=mic
            shell(a.target,'am','force-stop',PROBE)
            shell(a.target,'am','start','-W','-n',PROBE+'/.AudioActivity','--es','mode','both','--ei','frequency',1499)
            output=shell(a.client,'CLASSPATH='+capture_apk+' app_process / '+PROBE+'.CaptureOutput')
            (a.output/('audio-'+str(int(playback))+str(int(mic))+'.txt')).write_text(output+'\n'+audio_logs())
            match=re.search(r'PLAYBACK_TONE1499=(\d+)',output);assert match,output
            amplitude=int(match[1]);assert (amplitude>100)==playback,(playback,mic,amplitude,output,audio_logs())
            remote=adb(a.target,'logcat','-d','-s','ScrcpyAudioProbe:I','*:S')
            (a.output/('remote-audio-'+str(int(playback))+str(int(mic))+'.txt')).write_text(remote)
            tones=[int(n) for n in re.findall(r'tone997=(\d+)',remote)]
            assert tones,'No target AudioRecord measurements'
            assert (max(tones)>1000)==mic,(playback,mic,max(tones),audio_logs())
            if not live:
                assert ('MIC_STARTED' in audio_logs())==mic
                assert ('PLAYBACK_PCM' in audio_logs())==playback
            record('audio independent switches playback='+str(playback)+' mic='+str(mic)+': actual client output tone1499='+str(amplitude)+', remote recorder tone997='+str(max(tones)))
            if not live:tap('断开')
            shell(a.target,'am','force-stop',PROBE)
        if live:
            runtime_option('forwardMic',False);runtime_option('forwardAudio',False);runtime_close()
            assert core_pid()==live_pid
            deadline=time.monotonic()+8
            while time.monotonic()<deadline:
                processes=shell(a.target,'ps','-A','-o','ARGS')
                if '.MicServer ' not in processes and 'video=false audio=true' not in processes:break
                time.sleep(.2)
            else:raise AssertionError('Disabled audio services remain running: '+processes)
            record('all audio combinations changed live while the original video process and connection remained active')
            tap('断开')
        record('both directions work simultaneously; disabled microphone is never captured and disabled playback produces no forwarded output')
        sound('播放远端声音',original_audio);sound('转发本机麦克风',original_mic)
        connect();tap('断开')
    finally:
        stopping.set()
        if connection:connection.close()
        if producer:producer.join(2)
        if forwarded:adb(a.client,'forward','--remove','tcp:'+str(forwarded))
        if process.stdin:process.stdin.close()
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:process.terminate()
        shell(a.client,'am','force-stop',PKG)
        shell(a.client,'rm','-f',source_apk,capture_apk)
        for serial,volume in media_volumes.items():shell(serial,'cmd','media_session','volume','--stream',3,'--set',volume)


def bounds(node):
    assert node is not None
    return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))


def runtime_open():
    root=dump()
    if find(rid='runtime_scroll',root=root) is None:click(find(rid='runtime_settings',root=root))


def runtime_option(key,value):
    runtime_open()
    for attempt in range(12):
        root=dump();node=find(rid='option_'+key,root=root)
        if node is not None and bounds(node)[3]-bounds(node)[1]>=80:
            assert node.get('enabled')=='true',key
            if node.get('checked')!=str(value).lower():click(node)
            return
        l,t,r,b=bounds(find(rid='runtime_scroll',root=root))
        if attempt<5:shell(a.client,'input','swipe',(l+r)//2,b-15,(l+r)//2,t+15,220)
        else:shell(a.client,'input','swipe',(l+r)//2,t+15,(l+r)//2,b-15,220)
    raise AssertionError('Runtime setting missing: '+key)


def runtime_close():
    click(find(rid='runtime_done'))


def core_pid():
    lines=shell(a.target,'ps','-A','-o','PID,ARGS').splitlines()
    found=[line.split()[0] for line in lines if re.match(r'\s*\d+\s+(?:\S*/)?app_process(?:32|64)? / com\.genymobile\.scrcpy\.Server ',line) and 'video=true' in line]
    assert len(found)==1,found
    return found[0]


def verify_runtime():
    global old_awake
    old_awake=shell(a.target,'settings','get','global','stay_on_while_plugged_in').strip()
    def target_settings(auto,rotation,wake):
        end=time.monotonic()+10
        while time.monotonic()<end:
            actual=tuple(shell(a.target,'settings','get',namespace,key).strip() for namespace,key in [('system','accelerometer_rotation'),('system','user_rotation'),('global','stay_on_while_plugged_in')])
            if actual==(str(auto),str(rotation),str(wake)):return
            time.sleep(.2)
        raise AssertionError(('Device settings differ',actual,(auto,rotation,wake)))
    def pose(value):
        shell(a.client,'wm','user-rotation','lock',value);time.sleep(.6)
    try:
        shell(a.target,'settings','put','system','accelerometer_rotation',1)
        shell(a.target,'settings','put','system','user_rotation',0)
        shell(a.target,'settings','put','global','stay_on_while_plugged_in',0)
        bottom()
        for name in ['播放远端声音','转发本机麦克风','充电时保持远端唤醒','同步远端屏幕方向']:
            node=find(text=name)
            if node.get('checked')=='true':click(node)
        tap('省流');top();tap('保存配置');edit('配置名称',test_name);tap('保存')
        connect();pid=core_pid();target_settings(1,0,0)
        pose(1);wait_connected();target_settings(1,0,0)
        record('rotation forwarding can be disabled before connecting; local landscape leaves remote rotation untouched')
        runtime_option('syncRotation',True);target_settings(0,1,0)
        runtime_option('stayAwake',True);target_settings(0,1,7)
        runtime_option('syncRotation',False);target_settings(1,0,7)
        runtime_option('stayAwake',False);target_settings(1,0,0)
        runtime_option('syncRotation',True);target_settings(0,1,0)
        assert core_pid()==pid
        screenshot('50-runtime-landscape')
        runtime_close();pose(0);wait_connected();target_settings(0,0,0)
        record('live orientation and keep-awake controls operate independently, restore originals when off and retain the video process')
        runtime_option('showNavigation',False);runtime_close();root=dump()
        assert all(find(desc=d,root=root) is None for d in ['Back','Home','最近任务'])
        runtime_option('showNavigation',True);runtime_close();root=dump()
        nav=[bounds(find(desc=d,root=root)) for d in ['Back','Home','最近任务']]
        assert abs((nav[0][0]+nav[-1][2])/2-540)<3
        status=next(n for n in root.iter('node') if '已连接' in n.get('text',''))
        screenshot('50-runtime-navigation-restored')
        assert bounds(status)[2]<=nav[0][0], (bounds(status),nav)
        runtime_option('keyboard',True);runtime_close();assert find(desc='完整虚拟键盘') is not None
        runtime_option('keyboard',False);runtime_close();assert find(desc='完整虚拟键盘') is None
        assert core_pid()==pid
        record('navigation and full keyboard can be changed live; restored navigation remains physically centered')
        runtime_open();screenshot('51-runtime-portrait')
        click(find(rid='runtime_disconnect'));assert find(rid='disconnect_confirm') is not None
        click(find(rid='disconnect_cancel'));assert core_pid()==pid
        runtime_close();tap('断开');target_settings(1,0,0)
        bottom();assert find(text='同步远端屏幕方向').get('checked')=='true'
        assert find(text='充电时保持远端唤醒').get('checked')=='false'
        raw=shell(a.client,'run-as',PKG,'cat','shared_prefs/connections.xml')
        saved=json.loads(next(n.text for n in ET.fromstring(raw) if n.get('name')=='profiles'))
        current=next(p for p in saved if p['name']==test_name)
        assert current['syncRotation'] and not current['stayAwake'] and current['showNavigation']
        assert (current['maxSize'],current['bitrate'],current['fps'],current['codec'])==(1024,2,30,'h264')
        top();tap('切换配置  ▾');root=dump()
        click(next(n for n in root.iter('node') if n.get('content-desc','').startswith(test_name+'\n')))
        tap('删除');tap('删除配置');tap('取消')
        record('disconnect requires confirmation; cancellation retains session; runtime choices update the saved host without changing video settings')
    finally:
        try:shell(a.client,'am','force-stop',PKG)
        except (subprocess.SubprocessError,OSError) as e:print('Client cleanup pending:',e,flush=True)
        try:
            if old_awake=='null':shell(a.target,'settings','delete','global','stay_on_while_plugged_in')
            else:shell(a.target,'settings','put','global','stay_on_while_plugged_in',old_awake)
        except (subprocess.SubprocessError,OSError) as e:print('Keep-awake cleanup pending:',e,flush=True)


def verify_language():
    top()
    def language(option):
        top();click(find(rid='language_picker'));click(find(desc=option))
    language('跟随系统');root=dump()
    assert find(text='Remote control',root=root) is not None
    title=bounds(find(text='Remote control',root=root));brand=bounds(find(text='SCRCPY / ANDROID',root=root))
    assert brand[0]>=title[2] and abs((title[1]+title[3])-(brand[1]+brand[3]))<20
    record('system language is used by default; product signature stays on the same header line')
    edit('Device IP / hostname','draft-phone.example');edit('Max edge · px','1')
    language('简体中文');assert find(desc='设备 IP / 主机名').get('text')=='draft-phone.example'
    assert find(desc='最长边 · px').get('text')=='1'
    language('English');assert find(desc='Device IP / hostname').get('text')=='draft-phone.example'
    assert find(desc='Max edge · px').get('text')=='1'
    shell(a.client,'am','force-stop',PKG);shell(a.client,'am','start','-n',PKG+'/.MainActivity')
    assert find(text='Remote control') is not None
    record('manual language selection preserves unsaved and invalid form values and persists after process restart')
    click(find(rid='quality_picker'));root=dump();assert find(desc='Economy',root=root) is not None
    click(find(desc='Economy'));assert find(desc='Max edge · px').get('text')=='1024'
    assert find(desc='Frame limit · FPS').get('text')=='30'
    screenshot('52-form-english')
    language('简体中文');edit('设备 IP / 主机名',a.host);edit('ADB 端口',str(a.port));bottom()
    for name,value in [('仅观看',False),('转发本机麦克风',False),('播放远端声音',False)]:
        if find(text=name).get('checked')!=str(value).lower():tap(name)
    shell(a.client,'wm','size','1080x1600');top();root=dump();assert find(rid='scroll_hint',root=root) is not None
    connect_before=bounds(find(text='连接设备   →',root=root))
    click(find(rid='scroll_hint'));bottom();root=dump()
    assert find(rid='scroll_hint',root=root) is None
    assert bounds(find(text='连接设备   →',root=root))==connect_before
    screenshot('53-scroll-bottom');shell(a.client,'wm','size','reset');time.sleep(.5)
    record('scroll continuation hint is visible when content remains, responds to tap, disappears at bottom and never moves the fixed connection button')
    verify_live_language()


def verify_live_language():
    connect();pid=core_pid();runtime_open()
    # Scroll the popup to its language row when the screen is short.
    for _ in range(4):
        root=dump();picker=find(rid='language_picker',root=root)
        if picker is not None:break
        l,t,r,b=bounds(find(rid='runtime_scroll',root=root));shell(a.client,'input','swipe',(l+r)//2,b-20,(l+r)//2,t+20,250)
    click(picker);click(find(desc='English'))
    assert find(text='Remote settings') is not None and core_pid()==pid
    click(find(rid='runtime_done'));click(find(desc='Virtual keyboard'))
    assert find(desc='Full virtual keyboard') is not None and find(desc='Paste') is not None
    click(find(desc='Hide keyboard'));click(find(rid='runtime_settings'))
    screenshot('54-runtime-english');click(find(rid='runtime_disconnect'))
    assert find(text='Disconnect?') is not None
    click(find(rid='disconnect_confirm'))
    assert find(text='Remote control') is not None
    record('live language switch translates settings, keyboard and confirmation without replacing the video session')
    top();click(find(rid='language_picker'));click(find(desc='简体中文'))


def verify_top_buffer():
    def bounds(node):
        assert node is not None
        return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
    connect();start_probe()
    def check_buffer(orientation):
        root=wait_connected();l,t,r,b=bounds(find(desc='远控触控区',root=root))
        state=shell(a.client,'dumpsys','window')
        bars=re.findall(r'type=statusBars frame=\[0,0\]\[\d+,(\d+)\] visible=(true|false)',state)
        assert bars and t>=int(bars[0][0])>0, ('Remote input overlaps status-bar area',t,bars[:3])
        assert bars[0][1]=='false', 'Native status bar should remain hidden in fullscreen'
        shell(a.target,'logcat','-c');shell(a.client,'input','tap',(l+r)//2,t//2)
        assert 'TOUCH' not in logs(), 'Top blank area forwarded a remote touch'
        for attempt in range(2):
            start_probe()
            root=dump();vl,vt,vr,vb=bounds(find(desc='远控屏幕',root=root))
            before=adb(a.client,'logcat','-d','-s','RemoteGesture:D','*:S')
            shell(a.client,'input','swipe',(vl+vr)//2,vt+2,(vl+vr)//2,vt+(vb-vt)//3,300)
            after=adb(a.client,'logcat','-d','-s','RemoteGesture:D','*:S')
            assert after.count('EDGE_ACTION_TOP')>before.count('EDGE_ACTION_TOP')
            assert after.count('CANCEL')==before.count('CANCEL')
            windows=shell(a.client,'dumpsys','window')
            assert re.search(r'mCurrentFocus=.*cn\.felix021\.scrcpy',windows), 'Local shade took focus'
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                target_windows=shell(a.target,'dumpsys','window')
                if re.search(r'mCurrentFocus=.*(NotificationShade|StatusBar)',target_windows,re.I):break
                time.sleep(.1)
            else:raise AssertionError('Remote shade did not open')
            shell(a.target,'cmd','statusbar','collapse')
        screenshot('20-top-buffer-'+orientation)
        record(orientation+' fullscreen hides local status bar, keeps its full height blank and sends top swipes only to target')
    check_buffer('portrait')
    shell(a.client,'settings','put','system','accelerometer_rotation',0);shell(a.client,'settings','put','system','user_rotation',1)
    time.sleep(1);check_buffer('landscape')
    tap('断开')
    shell(a.client,'settings','put','system','user_rotation',0);time.sleep(.5)


def verify_configs():
    def bounds(node):
        assert node is not None
        return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
    def snapshot():
        raw=shell(a.client,'run-as',PKG,'cat','shared_prefs/connections.xml')
        values=ET.fromstring(raw)
        return json.loads(next((n.text for n in values if n.get('name')=='profiles'),'[]'))
    def switches(navigation, watching, awake, audio=True, mic=False, rotation=True):
        bottom()
        if find(text='仅观看').get('checked')=='true':tap('仅观看')
        for text,value in [('展示虚拟按键',navigation),('播放远端声音',audio),('转发本机麦克风',mic),('仅观看',watching),('同步远端屏幕方向',rotation),('充电时保持远端唤醒',awake)]:
            if find(text=text).get('checked')!=str(value).lower():tap(text)
    def save(name=None, suggested=None):
        top();tap('保存配置')
        node=find(desc='配置名称')
        if suggested is not None:assert node.get('text')==suggested, node.attrib
        if name is not None:
            click(node)
            shell(a.client,'input','keyevent','KEYCODE_MOVE_END')
            shell(a.client,'input','keyevent',*(['KEYCODE_DEL']*100))
            shell(a.client,'input','text',name)
        tap('保存')
        assert find(text='保存连接配置') is None
    def picker():
        top();tap('切换配置  ▾');return dump()
    def choose(name):
        root=picker()
        click(next(n for n in root.iter('node') if n.get('content-desc','').startswith(name+'\n')))
        tap('使用配置')
        assert find(text='使用配置') is None
    def expect(profile):
        roots=[top(),bottom()]
        nodes=[n for root in roots for n in root.iter('node')]
        for desc,key in [('设备 IP / 主机名','host'),('ADB 端口','port'),('最长边 · px','maxSize'),('码率 · Mbps','bitrate'),('帧率上限 · FPS','fps')]:
            node=next((n for n in nodes if n.get('content-desc')==desc),None)
            assert node is not None and node.get('text')==str(profile[key]), (desc,node.attrib if node is not None else None)
        assert any(n.get('text')==('H.265' if profile['codec']=='h265' else 'H.264') for n in nodes)
        for text,key in [('展示虚拟按键','showNavigation'),('仅观看','viewOnly'),('播放远端声音','forwardAudio'),('转发本机麦克风','forwardMic'),('同步远端屏幕方向','syncRotation'),('充电时保持远端唤醒','stayAwake')]:
            node=next(n for n in nodes if n.get('text')==text)
            assert node.get('checked')==str(profile.get(key,key not in ['viewOnly','forwardMic'])).lower(), key
    def remove(name, confirm=True):
        root=picker()
        click(next(n for n in root.iter('node') if n.get('content-desc','').startswith(name+'\n')))
        tap('删除');tap('删除配置' if confirm else '取消');tap('取消')
    name_a=test_name+'-A';name_b=test_name+'-B'
    # Existing stored objects retain exactly the same schema and values on upgrade.
    old=snapshot()
    (a.output/'profiles-before.json').write_text(json.dumps(old,ensure_ascii=False))
    if old:
        choose(old[0]['name']);expect(old[0])
        record('existing saved connection restores after upgrading from previous APK')
    top();edit('设备 IP / 主机名',a.host);edit('ADB 端口',str(a.port));tap('省流')
    edit('最长边 · px','1280');switches(True,False,True);save(name_a)
    config_a=dict(name=name_a,host=a.host,port=a.port,maxSize=1280,bitrate=2,fps=30,codec='h264',showNavigation=True,viewOnly=False,stayAwake=True,forwardAudio=True,forwardMic=False,syncRotation=True)
    top();edit('设备 IP / 主机名','second-device.example');edit('ADB 端口','5566');tap('高清')
    edit('最长边 · px','1920');edit('帧率上限 · FPS','45');click(find(desc='视频编码'));tap('H.265')
    switches(False,True,False,audio=False,mic=True,rotation=False);save(name_b,'second-device.example:5566')
    config_b=dict(name=name_b,host='second-device.example',port=5566,maxSize=1920,bitrate=12,fps=45,codec='h265',showNavigation=False,viewOnly=True,stayAwake=False,forwardAudio=False,forwardMic=True,syncRotation=False)
    root=picker()
    assert find(desc=name_a+'\n'+a.host+':'+str(a.port),root=root) is not None
    assert find(desc=name_b+'\nsecond-device.example:5566',root=root) is not None
    screenshot('18-connection-picker');tap('取消')
    choose(name_a);expect(config_a);choose(name_b);expect(config_b)
    record('two independent hosts restore address, port, codec, all video parameters and all switches with one selection')
    choose(name_a);tap('高清');top();tap('保存配置')
    assert find(desc='配置名称').get('text')==name_a
    tap('取消');tap('省流');edit('最长边 · px','1280');edit('码率 · Mbps','4');save(suggested=name_a)
    config_a['bitrate']=4
    profiles=snapshot()
    assert sum(p['name']==name_a for p in profiles)==1
    assert next(p for p in profiles if p['name']==name_b)==config_b
    assert all(p in profiles for p in old)
    choose(name_b);expect(config_b);choose(name_a);expect(config_a)
    shell(a.client,'am','force-stop',PKG);shell(a.client,'am','start','-n',PKG+'/.MainActivity')
    expect(config_a);screenshot('19-selected-connection')
    record('quality shortcuts preserve connection name; same-name save replaces only that config; restart retains selected settings and old configs')
    remove(name_b,False)
    assert any(p['name']==name_b for p in snapshot())
    remove(name_b)
    assert not any(p['name']==name_b for p in snapshot())
    assert next(p for p in snapshot() if p['name']==name_a)==config_a
    record('delete cancellation preserves config and confirmed deletion leaves other hosts intact')
    verify_top_buffer()
    remove(name_a)


def verify_appearance():
    def bounds(node):
        assert node is not None
        return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
    def state():
        raw=shell(a.client,'run-as',PKG,'cat','shared_prefs/connections.xml')
        values={n.get('name'):n.text for n in ET.fromstring(raw)}
        return json.loads(values.get('profiles','[]')),json.loads(values.get('last','{}'))
    def picker():
        top();tap('切换配置  ▾');return dump()
    def row(name, root=None):
        if root is None:root=dump()
        return next(n for n in root.iter('node') if n.get('content-desc','').startswith(name+'\n'))
    def save(name):
        top();tap('保存配置');click(find(desc='配置名称'))
        shell(a.client,'input','keyevent','KEYCODE_MOVE_END')
        shell(a.client,'input','keyevent',*(['KEYCODE_DEL']*100))
        shell(a.client,'input','text',name);tap('保存')
    before,_=state();name_a=test_name+'-A';name_b=test_name+'-B'
    tap('省流');save(name_a);top();edit('设备 IP / 主机名','spare-phone.example');edit('ADB 端口','5566');save(name_b)
    root=picker();assert row(name_b,root).get('selected')=='true'
    click(row(name_a,root));root=dump()
    assert row(name_a,root).get('selected')=='true' and row(name_b,root).get('selected')=='false'
    assert state()[1]['name']==name_b, 'Highlighting a row applied it without confirmation'
    screenshot('21-config-highlight');tap('取消')
    assert state()[1]['name']==name_b
    root=picker();assert row(name_b,root).get('selected')=='true'
    click(row(name_a,root));tap('使用配置')
    assert state()[1]['name']==name_a
    record('custom picker highlights exactly one row; cancel preserves current settings; explicit confirmation switches host')
    root=picker();click(row(name_b,root));tap('删除')
    assert find(text='删除「'+name_b+'」？') is not None
    screenshot('22-delete-confirmation')
    assert any(p['name']==name_b for p in state()[0]), 'Delete happened before second confirmation'
    tap('取消');root=dump()
    assert row(name_b,root).get('selected')=='true'
    assert any(p['name']==name_b for p in state()[0])
    tap('使用配置');assert state()[1]['name']==name_b
    root=picker();assert row(name_b,root).get('selected')=='true'
    tap('删除');tap('删除配置');profiles,last=state()
    assert not any(p['name']==name_b for p in profiles)
    assert last['name']=='' and last['host']=='spare-phone.example' and last['port']==5566
    root=dump();assert find(text='使用配置',root=root).get('enabled')=='false' and find(text='删除',root=root).get('enabled')=='false'
    assert all(p in profiles for p in before)
    click(row(name_a,root));tap('使用配置')
    record('delete requires a second confirmation; cancel keeps selection; deleting the active config clears its name and preserves other hosts')
    picker();shell(a.client,'settings','put','system','accelerometer_rotation',0);shell(a.client,'settings','put','system','user_rotation',1)
    time.sleep(.8);root=dump()
    assert root.get('rotation')=='1' and row(name_a,root).get('selected')=='true'
    for title in ['取消','删除','使用配置']:
        l,t,r,b=bounds(find(text=title,root=root));assert 0<=l<r<=2340 and 0<=t<b<=1080
    screenshot('23-config-landscape')
    shell(a.client,'settings','put','system','user_rotation',0);time.sleep(.8)
    tap('删除');tap('删除配置');tap('取消')
    assert state()[0]==before
    record('picker survives rotation with selection and footer visible; test configs removed without changing existing saved data')
    # Confirm that Android Launcher actually renders the new adaptive phone icon.
    shell(a.client,'input','keyevent','KEYCODE_HOME');time.sleep(.6)
    shell(a.client,'input','swipe',540,2140,540,650,350)
    for attempt in range(6):
        root=dump();icon=find(text='远控 · scrcpy',root=root)
        if icon is not None:break
        shell(a.client,'input','swipe',1030,2050,1030,650,350)
    assert icon is not None, 'Launcher app entry missing'
    screenshot('24-launcher-icon');click(icon);bottom()
    top();edit('设备 IP / 主机名',a.host);edit('ADB 端口',str(a.port));connect()
    root=wait_connected();exit=find(rid='runtime_settings',root=root);keyboard=find(desc='虚拟键盘',root=root)
    assert exit.get('class')==keyboard.get('class')=='android.widget.ImageButton'
    eb,kb=bounds(exit),bounds(keyboard)
    assert eb[1]==kb[1] and eb[3]==kb[3] and eb[2]-eb[0]==kb[2]-kb[0] and kb[2]<eb[0]
    assert find(text='断开',root=root) is None
    screenshot('25-icon-controls')
    shell(a.client,'wm','size','880x1900');time.sleep(.6)
    root=wait_connected();nav=bounds(find(desc='最近任务',root=root));kb=bounds(find(desc='虚拟键盘',root=root));eb=bounds(find(rid='runtime_settings',root=root))
    assert nav[2]<=kb[0] and kb[2]<eb[0] and eb[2]<=880, (nav,kb,eb)
    screenshot('26-compact-icon-controls');shell(a.client,'wm','size','reset');time.sleep(.6)
    begin_disconnect()
    assert find(text='断开连接？') is not None
    screenshot('27-disconnect-confirmation');tap('取消');wait_connected()
    assert re.search(r'(^|\s)com\.genymobile\.scrcpy\.Server(\s|$)',shell(a.target,'ps','-A','-o','ARGS'),re.M)
    record('disconnect icon requires confirmation; cancelling keeps the live remote session')
    begin_disconnect();tap('确认断开');bottom()
    end=time.monotonic()+8
    while time.monotonic()<end:
        if not re.search(r'(^|\s)com\.genymobile\.scrcpy\.Server(\s|$)',shell(a.target,'ps','-A','-o','ARGS'),re.M):break
        time.sleep(.2)
    else:raise AssertionError('Server survived the disconnect icon action')
    record('launcher phone icon renders; keyboard and disconnect share icon-button style, fit 320 dp width, and disconnect stops remote server')


def verify_dialogs(text_only=False):
    def bounds(node):
        assert node is not None, 'UI element missing'
        return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
    def stored():
        raw=shell(a.client,'run-as',PKG,'cat','shared_prefs/connections.xml')
        values={n.get('name'):n.text for n in ET.fromstring(raw)}
        return json.loads(values.get('profiles','[]')),json.loads(values.get('last','{}'))
    def rotate(value):
        shell(a.client,'settings','put','system','accelerometer_rotation',0)
        shell(a.client,'settings','put','system','user_rotation',value);time.sleep(.7)
    def set_text(desc,value):
        path=a.output/'editor-input.txt';path.write_text(value)
        adb(a.client,'push',path,'/data/local/tmp/scrcpy-editor-input.txt')
        cmd='CLASSPATH=/data/local/tmp/scrcpy-validation.apk app_process / '+PROBE+'.SetText '+shlex.quote(desc)+' --file /data/local/tmp/scrcpy-editor-input.txt'
        shell(a.client,cmd)
    def keyboard_buttons(*titles):
        root=dump();state=shell(a.client,'dumpsys','window')
        frames=[(int(t),int(b)) for t,b in re.findall(r'type=ime frame=\[\d+,(\d+)\]\[\d+,(\d+)\][^\n]*visible=true',state) if int(b)>int(t)]
        assert frames, 'Local IME did not open for the editor'
        top=min(t for t,b in frames)
        for title in titles:
            l,t,r,b=bounds(find(text=title,root=root));assert 0<=t<b<=top, (title,(l,t,r,b),top)
    def target_text():
        result=shell(a.target,'uiautomator','dump','/sdcard/scrcpy-validation.xml')
        assert 'dumped to:' in result
        root=ET.fromstring(shell(a.target,'cat','/sdcard/scrcpy-validation.xml'))
        return find(desc='验证输入框',root=root).get('text')
    def dock(name):
        root=wait_connected();window=bounds(root.find('node'))
        back=bounds(find(desc='Back',root=root));home=bounds(find(desc='Home',root=root));recents=bounds(find(desc='最近任务',root=root))
        status=next(n for n in root.iter('node') if '已连接' in n.get('text',''))
        sb=bounds(status);kb=bounds(find(desc='虚拟键盘',root=root));exit=bounds(find(rid='runtime_settings',root=root))
        mid=(window[0]+window[2])/2
        assert abs((home[0]+home[2])/2-mid)<=2, (name,home,window)
        assert abs((back[0]+recents[2])/2-mid)<=2, (name,back,recents,window)
        assert sb[2]<=back[0] and recents[2]<=kb[0] and kb[2]<exit[0]<=window[2], (sb,back,recents,kb,exit)
        assert '\n' in status.get('text','')
        screenshot('30-dock-'+name)
    before=stored();name=test_name+'-dialog'
    adb(a.client,'push',ROOT/'validation/probe/build/outputs/apk/debug/probe-debug.apk','/data/local/tmp/scrcpy-validation.apk')
    if not text_only:
        top();tap('保存配置');click(find(desc='配置名称'));set_text('配置名称','');tap('保存')
        assert find(text='请输入配置名称') is not None and stored()==before
        screenshot('28-save-validation');set_text('配置名称','cancelled-draft');tap('取消');assert stored()==before
        top();tap('保存配置');click(find(desc='配置名称'));set_text('配置名称',name)
        keyboard_buttons('取消','保存');screenshot('29-save-keyboard-portrait')
        rotate(1);keyboard_buttons('取消','保存');screenshot('29-save-keyboard-landscape')
        tap('保存');rotate(0)
        assert next(p for p in stored()[0] if p['name']==name)['host']==a.host
        record('save dialog uses themed editor, rejects empty names inline, cancels without writing, and saves with IME open in both orientations')
        bottom();click(find(desc='视频编码'))
        root=dump();assert find(text='H.264',root=root) is not None and find(text='H.265',root=root) is not None
        screenshot('29-codec-options');tap('H.265');assert find(text='H.265') is not None
        click(find(desc='视频编码'));tap('H.264');tap('省流');connect()
        dock('portrait')
        shell(a.client,'wm','size','880x1900');time.sleep(.7);dock('compact')
        shell(a.client,'wm','size','reset');time.sleep(.7);rotate(1);dock('landscape')
        click(find(desc='虚拟键盘'));dock('landscape-keyboard');click(find(desc='收起键盘'));rotate(0)
        record('codec options match theme; three navigation keys stay on window centerline with wrapping status in portrait, 320 dp width, landscape cutout, and keyboard layout')
    else:
        tap('省流');connect()
    shell(a.target,'cmd','statusbar','collapse');shell(a.target,'am','start','-W','-n',PROBE+'/.InputActivity')
    initial=target_text();click(find(desc='虚拟键盘'));click(find(desc='输入文字'))
    click(find(desc='发送文本'));set_text('发送文本','cancelled text');tap('取消')
    assert target_text()==initial
    click(find(desc='输入文字'));click(find(desc='发送文本'));set_text('发送文本','界'*22000);tap('发送')
    assert find(text='每次最多发送 64 KB 文本') is not None and target_text()==initial
    value='统一样式测试🙂 dialog text'
    set_text('发送文本',value);keyboard_buttons('取消','发送');screenshot('31-text-keyboard-portrait');tap('发送')
    assert target_text()==initial+value
    record('text dialog keeps cancel non-destructive, shows oversized-text error inline and sends Unicode through real clipboard protocol')
    rotate(1);click(find(desc='输入文字'));click(find(desc='发送文本'));set_text('发送文本','landscape text')
    keyboard_buttons('取消','发送');screenshot('31-text-keyboard-landscape');tap('取消');assert target_text()==initial+value
    dock('after-text-cancel')
    click(find(desc='输入文字'));set_text('发送文本','unfinished draft')
    shell(a.client,'input','keyevent','KEYCODE_HOME')
    end=time.monotonic()+10
    while time.monotonic()<end:
        activities=shell(a.client,'dumpsys','activity','activities')
        resumed=next((line for line in activities.splitlines() if 'topResumedActivity=' in line),'')
        server=re.search(r'(^|\s)com\.genymobile\.scrcpy\.Server(\s|$)',shell(a.target,'ps','-A','-o','ARGS'),re.M)
        if 'launcher' in resumed.lower() and not server:break
        time.sleep(.2)
    else:raise AssertionError('Client did not finish background disconnection')
    shell(a.client,'am','start','-n',PKG+'/.MainActivity')
    assert find(text='输入文字') is None
    rotate(0);bottom()
    if not text_only:
        top();tap('切换配置  ▾')
        root=dump();chosen=next(n for n in root.iter('node') if n.get('content-desc','').startswith(name+'\n'))
        click(chosen);tap('删除');tap('删除配置');tap('取消')
    assert stored()[0]==before[0]
    record('landscape text editor keeps footer above IME; cancel retains live session; backgrounding dismisses editor and cleanup preserves saved configs')


try:
    # All form helpers use portrait coordinates. Normalize after snapshotting,
    # including when a previous interrupted run left the client in landscape.
    shell(a.client,'am','force-stop',PKG)
    shell(a.client,'settings','put','system','accelerometer_rotation',0)
    shell(a.client,'settings','put','system','user_rotation',0)
    adb(a.client, 'install', '-r', ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
    adb(a.target, 'install', '-r', ROOT / 'validation/probe/build/outputs/apk/debug/probe-debug.apk')
    shell(a.target, 'input', 'keyevent', 224)
    shell(a.target, 'wm', 'dismiss-keyguard')
    shell(a.client, 'am', 'force-stop', PKG)
    shell(a.client, 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(.5)
    screenshot('01-form')
    click(find(rid='language_picker'));tap('简体中文')
    edit('设备 IP / 主机名', a.host)
    edit('ADB 端口', str(a.port))
    bottom()
    if find(text='仅观看').get('checked') == 'true':
        tap('仅观看')
    if a.phase!='audio':
        node=find(text='转发本机麦克风')
        if node is not None and node.get('checked')=='true':click(node)
    if a.phase == 'runtime':
        verify_runtime()
        sys.exit(0)
    if a.phase == 'language':
        verify_language()
        sys.exit(0)
    if a.phase == 'language-live':
        verify_live_language()
        sys.exit(0)
    if a.phase == 'ui':
        verify_ui()
        sys.exit(0)
    if find(text='展示虚拟按键').get('checked') == 'false':
        tap('展示虚拟按键')
    if a.phase in ['dialogs','text-dialogs']:
        verify_dialogs(a.phase=='text-dialogs')
        sys.exit(0)
    if a.phase == 'appearance':
        verify_appearance()
        sys.exit(0)
    if a.phase == 'configs':
        verify_configs()
        sys.exit(0)
    if a.phase == 'audio-runtime':
        verify_audio(True)
        sys.exit(0)
    if a.phase == 'audio':
        verify_audio()
        sys.exit(0)
    if a.phase == 'rotation':
        verify_rotation_restoration()
        sys.exit(0)
    if a.phase == 'landscape':
        verify_landscape()
        sys.exit(0)
    if a.phase == 'insets':
        tap('省流');verify_top_buffer()
        sys.exit(0)
    if a.phase in ['controls','keyboard']:
        verify_controls()
        sys.exit(0)
    if a.phase == 'all':
        tap('省流')
        edit('最长边 · px', '1280')
        # Save a custom profile through the actual dialog.
        top(); tap('保存配置')
        root = dump()
        click(next(n for n in root.iter('node') if n.get('class') == 'android.widget.EditText'))
        shell(a.client, 'input', 'keyevent', 'KEYCODE_MOVE_END')
        shell(a.client, 'input', 'keyevent', *(['KEYCODE_DEL'] * 32))
        shell(a.client, 'input', 'text', test_name)
        tap('保存')
        assert find(text='保存连接配置') is None, 'Save dialog did not close'
        record('custom profile saved using UI')
        shell(a.client, 'am', 'force-stop', PKG)
        shell(a.client, 'am', 'start', '-n', PKG + '/.MainActivity')
        time.sleep(.5)
        bottom()
        top(); tap('切换配置  ▾')
        root = dump()
        chosen = next(n for n in root.iter('node') if n.get('content-desc', '').startswith(test_name + '\n'))
        click(chosen)
        tap('使用配置')
        bottom()
        assert find(text='1280') is not None
        assert find(text='2') is not None
        assert find(text='30') is not None
        record('saved custom values restored after process restart')
        connect()
        screenshot('02-mirror')
        record('direct ADB connection and first decoded frame')
        start_probe()
        adb(a.target, 'logcat', '-c')
        shell(a.client, 'input', 'tap', 540, 1120)
        assert_log('TOUCH 1')
        record('remote touch injection')
        click(find(desc='虚拟键盘')); click(find(desc='Fn')); click(find(desc='菜单键')); click(find(desc='收起键盘'))
        assert_log('MENU 82')
        screenshot('03-menu')
        record('Menu virtual key reaches remote as KEYCODE_MENU 82')
        click(find(desc='Back'))
        assert_log('BACK 4')
        assert PROBE + '/.ProbeActivity' not in next((line for line in resumed().splitlines() if 'topResumedActivity=' in line), '')
        record('Back virtual key closes target activity')
        start_probe()
        click(find(desc='Home'))
        assert 'launcher' in next(line for line in resumed().splitlines() if 'topResumedActivity=' in line).lower()
        record('Home virtual key returns target to launcher')
        start_probe()
        click(find(desc='最近任务'))
        time.sleep(.5)
        screenshot('04-recents')
        # ColorOS recents may be part of launcher; a screenshot is retained for visual review.
        record('recent tasks virtual key exercised (screenshot)')
        click(find(desc='Home'))
        # Rotation of client must keep this ADB session alive.
        shell(a.client, 'settings', 'put', 'system', 'accelerometer_rotation', 0)
        shell(a.client, 'settings', 'put', 'system', 'user_rotation', 1)
        time.sleep(1)
        wait_connected()
        screenshot('05-client-landscape')
        record('client landscape keeps connection')
        shell(a.client, 'settings', 'put', 'system', 'user_rotation', 0)
        time.sleep(.5)
        tap('断开')
        bottom()
        click(find(desc='视频编码'))
        tap('H.265')
        tap('连接设备   →')
        wait_connected()
        start_probe()
        screenshot('06-h265')
        record('H.265 decode')
        # A target rotation emits a new 5.0.1 SESSION header and rebuilds the decoder.
        shell(a.target, 'settings', 'put', 'system', 'accelerometer_rotation', 0)
        shell(a.target, 'settings', 'put', 'system', 'user_rotation', 1)
        time.sleep(1)
        assert_log('ROTATION 2')
        screenshot('06b-target-landscape')
        adb(a.target, 'logcat', '-c')
        shell(a.client, 'input', 'tap', 540, 1180)
        assert_log('TOUCH ')
        record('target landscape rendered and touch remains reachable (screenshot)')
        shell(a.target, 'settings', 'put', 'system', 'user_rotation', 0)
        time.sleep(.5)
        tap('断开')
        bottom()
        tap('仅观看')
        tap('连接设备   →')
        wait_connected()
        start_probe()
        adb(a.target, 'logcat', '-c')
        shell(a.client, 'input', 'tap', 540, 1120)
        assert 'TOUCH ' not in logs()
        for title in ['Back', 'Home', '最近任务']:
            assert find(desc=title).get('enabled') == 'false'
        record('view-only blocks touch and disables virtual keys')
        tap('断开')
        # Restore editable mode, then reject an invalid parameter through the UI.
        bottom()
        tap('仅观看')
        edit('最长边 · px', '1')
        tap('连接设备   →')
        assert find(text='连接设备   →') is not None
        record('invalid resolution does not start a session')
        edit('最长边 · px', '1280')
        click(find(desc='视频编码'))
        tap('H.264')
    else:
        tap('省流')
        edit('最长边 · px', '1280')
    tap('连接设备   →')
    wait_connected()
    if a.phase == 'lifecycle':
        start_probe();adb(a.target,'logcat','-c')
        node=find(desc='远控屏幕')
        l,t,r,b=map(int,re.findall(r'\d+',node.get('bounds')))
        shell(a.client,'input','motionevent','DOWN',(l+r)//2,(t+b)//2)
        assert_log('TOUCH_DOWN')
    shell(a.client, 'input', 'keyevent', 'KEYCODE_HOME')
    if a.phase == 'lifecycle':
        assert_log('TOUCH 1')
        record('backgrounding releases the active remote touch before closing ADB')
    # HOME animation must finish before bringing the same task forward again.
    time.sleep(1.5)
    assert 'launcher' in next(line for line in shell(a.client, 'dumpsys', 'activity', 'activities').splitlines() if 'topResumedActivity=' in line).lower()
    shell(a.client, 'am', 'start', '-n', PKG + '/.MainActivity')
    bottom()
    assert find(text='连接设备   →') is not None
    record('backgrounding disconnects cleanly; app can be opened again')
    screenshot('07-final-form')
    # Cancel a genuinely pending socket connect, then reconnect to the real device.
    top()
    edit('设备 IP / 主机名', '192.0.2.1')
    bottom()
    tap('连接设备   →')
    stored=ET.fromstring(shell(a.client,'run-as',PKG,'cat','shared_prefs/connections.xml'))
    last=json.loads(next(n.text for n in stored if n.get('name')=='last'))
    assert last['name']=='', 'Editing the endpoint carried another saved host name into the new connection'
    record('editing host or port clears the previous config name before persisting a connection')
    tap('断开')
    top()
    edit('设备 IP / 主机名', a.host)
    bottom()
    tap('连接设备   →')
    wait_connected()
    record('cancel pending connection and reconnect')
    server_pattern = r'(^|\s)com\.genymobile\.scrcpy\.Server(\s|$)'
    processes = shell(a.target, 'ps', '-A', '-o', 'ARGS')
    assert re.search(server_pattern, processes, re.MULTILINE), 'Server process must be observable while connected'
    tap('断开')
    end = time.monotonic() + 8
    while time.monotonic() < end:
        processes = shell(a.target, 'ps', '-A', '-o', 'ARGS')
        if not re.search(server_pattern, processes, re.MULTILINE):
            break
        time.sleep(.3)
    else:
        raise AssertionError('Remote server still running after disconnect')
    record('disconnect terminates the observed remote server')
except Exception:
    try:
        screenshot('failure')
        (a.output / 'client-log.txt').write_text(adb(a.client, 'logcat', '-d', '-s', 'ScrcpySession', 'ScrcpyServer'))
    except (subprocess.SubprocessError,OSError) as e:
        print('Failure capture unavailable:',e,flush=True)
    raise
finally:
    if a.phase in ['ui','appearance','dialogs','text-dialogs','language']:
        try:shell(a.client, 'wm', 'size', 'reset')
        except (subprocess.SubprocessError,OSError) as e:print('Display cleanup pending:',e,flush=True)
    for (serial,key), value in rotation_settings.items():
        try:
            if value == 'null': shell(serial, 'settings', 'delete', 'system', key)
            else: shell(serial, 'settings', 'put', 'system', key, value)
        except (subprocess.SubprocessError, OSError) as e:
            print('Cleanup pending (device unavailable):', serial, key, e, flush=True)
    try:
        shell(a.target,'cmd','statusbar','collapse')
        shell(a.client,'rm','-f','/data/local/tmp/scrcpy-validation.apk','/data/local/tmp/scrcpy-editor-input.txt')
        shell(a.target, 'am', 'force-stop', PROBE)
        adb(a.target, 'uninstall', PROBE)
    except (subprocess.SubprocessError, OSError) as e:
        print('Probe uninstall pending:', e, flush=True)
