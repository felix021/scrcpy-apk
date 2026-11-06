#!/usr/bin/env python3
"""Exercise the actual AVD app against an ADB-connected target (no mock transport)."""
import argparse
import sys
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

P = argparse.ArgumentParser(description=__doc__)
P.add_argument('--client', required=True, help='AVD adb serial')
P.add_argument('--target', required=True, help='target adb serial, already authorized on this host')
P.add_argument('--host', required=True, help='target address reachable from AVD')
P.add_argument('--port', type=int, default=5555)
P.add_argument('--phase', choices=['all', 'lifecycle', 'ui'], default='all',
               help='Run core, lifecycle, or compact-UI/Back/four-edge-gesture checks')
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


def find(text=None, desc=None, root=None):
    if root is None:
        root = dump()
    for n in root.iter('node'):
        if text is not None and n.get('text') == text:
            return n
        if desc is not None and n.get('content-desc') == desc:
            return n
    return None


def click(node):
    assert node is not None, 'UI element missing'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    shell(a.client, 'input', 'tap', (x1 + x2) // 2, (y1 + y2) // 2)
    time.sleep(.5)


def tap(text):
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
        if find(text='我的预设', root=root) is not None:
            return
        if any(n.get('package') == PKG for n in root.iter('node')):
            shell(a.client, 'input', 'swipe', 1030, 2100, 1030, 600, 400)
        time.sleep(.3)
    raise AssertionError('Form did not scroll to connection button')


def top():
    end = time.monotonic() + 25
    while time.monotonic() < end:
        root = dump()
        if find(text='远程控制', root=root) is not None:
            return
        shell(a.client, 'input', 'swipe', 1030, 600, 1030, 2100, 400)
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
        if any('连接结束：' in n.get('text','') for n in root.iter('node')) or find(text='连接设备   →', root=root):
            raise AssertionError('Connection returned to form: ' + str([n.get('text') for n in root.iter('node')]))
        time.sleep(.5)
    raise AssertionError('No rendered frame within 60 seconds')


def connect():
    bottom()
    tap('连接设备   →')
    wait_connected()


def start_probe():
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
        assert find(text='断开') is not None, 'Local Back or gesture exited remote control'
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
        assert current.count(' '+edge) > previous.count(' '+edge), f'{edge} gesture did not reach the video surface'
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
    assert all(find(desc=name,root=root) is None for name in ['Back','Home','Menu','最近任务'])
    x1,y1,x2,y2 = bounds(find(desc='远控屏幕',root=root))
    assert y2-y1 > 2340*.88, 'Video does not use available height'
    screenshot('09-gesture-only')
    record('virtual keys hidden and video fills height above bottom safe area')
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
    record('video top-down swipe opens remote notification shade')
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
    assert all(find(desc=name,root=root) is not None for name in ['Back','Home','Menu','最近任务'])
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


try:
    adb(a.client, 'install', '-r', ROOT / 'app/build/outputs/apk/debug/app-debug.apk')
    adb(a.target, 'install', '-r', ROOT / 'validation/probe/build/outputs/apk/debug/probe-debug.apk')
    shell(a.target, 'input', 'keyevent', 224)
    shell(a.target, 'wm', 'dismiss-keyguard')
    shell(a.client, 'am', 'force-stop', PKG)
    shell(a.client, 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(.5)
    screenshot('01-form')
    edit('设备 IP / 主机名', a.host)
    edit('ADB 端口', str(a.port))
    bottom()
    if find(text='仅观看').get('checked') == 'true':
        tap('仅观看')
    if a.phase == 'ui':
        verify_ui()
        sys.exit(0)
    if find(text='展示虚拟按键').get('checked') == 'false':
        tap('展示虚拟按键')
    if a.phase == 'all':
        tap('省流')
        edit('最长边 · px', '1280')
        # Save a custom profile through the actual dialog.
        tap('保存为预设')
        root = dump()
        click(next(n for n in root.iter('node') if n.get('class') == 'android.widget.EditText'))
        shell(a.client, 'input', 'keyevent', 'KEYCODE_MOVE_END')
        shell(a.client, 'input', 'keyevent', *(['KEYCODE_DEL'] * 32))
        shell(a.client, 'input', 'text', test_name)
        tap('保存')
        assert find(text='保存连接预设') is None, 'Save dialog did not close'
        record('custom profile saved using UI')
        shell(a.client, 'am', 'force-stop', PKG)
        shell(a.client, 'am', 'start', '-n', PKG + '/.MainActivity')
        time.sleep(.5)
        bottom()
        tap('我的预设')
        root = dump()
        chosen = next(n for n in root.iter('node') if n.get('text', '').startswith(test_name + ' '))
        click(chosen)
        tap('使用')
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
        click(find(desc='Menu'))
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
        for title in ['Back', 'Home', 'Menu', '最近任务']:
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
    shell(a.client, 'input', 'keyevent', 'KEYCODE_HOME')
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
    screenshot('failure')
    (a.output / 'client-log.txt').write_text(adb(a.client, 'logcat', '-d', '-s', 'ScrcpySession', 'ScrcpyServer'))
    raise
finally:
    if a.phase == 'ui':
        shell(a.client, 'wm', 'size', 'reset')
    for (serial,key), value in rotation_settings.items():
        try:
            if value == 'null': shell(serial, 'settings', 'delete', 'system', key)
            else: shell(serial, 'settings', 'put', 'system', key, value)
        except (subprocess.SubprocessError, OSError) as e:
            print('Cleanup pending (device unavailable):', serial, key, e, flush=True)
    try:
        shell(a.target, 'am', 'force-stop', PROBE)
        adb(a.target, 'uninstall', PROBE)
    except (subprocess.SubprocessError, OSError) as e:
        print('Probe uninstall pending:', e, flush=True)
