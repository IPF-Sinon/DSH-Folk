#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# DSHA_ADB_SCRIPT_VERSION=11
"""
DSHA ADB 无线配对（绕过 Shizuku）—— 单次配对脚本。
协议：Android 11+ wireless debugging pairing（TLS1.3-PSK + SPAKE2，AOSP/BoringSSL）。
关键坑（已踩）：
  * SPAKE2 必须用 spake2-cffi（BoringSSL 兼容：32 字节消息、NUL 终止、非主子群盲化点）；
    绝不能 pip install warner 的 spake2（33 字节消息，必败并让设备弹窗报警）。
  * 配对服务监听地址因 ROM 而异：多数绑定 0.0.0.0（127.0.0.1 可连），
    部分 ROM 只绑定 WiFi 接口 IP（127.0.0.1 连不上！）→ 自动多地址尝试：
    优先 App 传入的真实 host（mDNS 解析），再 127.0.0.1，再本机接口 IP。
  * 配对务必单次执行：失败握手会让设备弹"配对失败"并关闭配对，禁止循环重试。
  * 成功标志 PAIR_OK；随后直连 adbd（传统 5555 或无线调试常规端口）自检。
用法：
  python3 adb-pair.py --code 123456 [--host <ip>] [--port <配对端口>] [--connect-port 5555]
  python3 adb-pair.py --genkey                        # 仅生成/确保密钥
输出（供 App/脚本解析）：
  KEY_GEN_OK / DEPS_MISSING / NO_PAIR_PORT / PORT_UNREACHABLE /
  TLS_ERROR / SPAKE2_ERROR / WRONG_CODE / PAIR_OK / CONNECT_OK / CONNECT_WARN
"""
import argparse
import os
import socket
import sys
import time

KEYDIR = '/root/.dsh/adbkeys'
KEY = KEYDIR + '/adbkey'
KEYPUB = KEY + '.pub'
DEFAULT_CONNECT_PORT = 5555


def check_deps():
    try:
        import adb_shell_wifi  # noqa
        # 新版库(0.5.0+)从 spake2.spake2 导入（spake2-cffi 的模块名就是 spake2）
        from spake2.spake2 import Spake2_Alice, Spake2_Bob  # noqa
    except Exception as e:
        print('DEPS_MISSING: %s' % e)
        print('RUN: python3 -m pip install --break-system-packages '
              'adb_shell_wifi pyopenssl spake2-cffi aiofiles async_timeout zeroconf')
        return False
    return True


def ensure_key():
    os.makedirs(KEYDIR, exist_ok=True)
    os.chmod(KEYDIR, 0o700)
    if not (os.path.exists(KEY) and os.path.exists(KEYPUB)):
        from adb_shell_wifi.auth.keygen import keygen
        keygen(KEY)
        print('KEY_GEN_OK')
    return True


def local_ips():
    """本机所有 IPv4 接口地址（proot 与 Android 共享网络栈，能拿到 WiFi IP）。"""
    ips = []
    try:
        import subprocess
        out = subprocess.check_output(
            "ip -4 addr show | grep -oP 'inet \\K[0-9.]+' | grep -v '^127\\.'",
            shell=True, timeout=5).decode().split()
        ips.extend(out)
    except Exception:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    # 去重保序
    seen = set()
    return [ip for ip in ips if not (ip in seen or seen.add(ip))]


def probe(port, host='127.0.0.1', timeout=2.5):
    try:
        s = socket.create_connection((host, port), timeout=timeout)
        s.close()
        return True
    except Exception:
        return False


def do_pair(host, port, code, priv_pem, pub_data):
    """单次配对；返回 (ok, detail)。detail 含协议阶段信息。"""
    from adb_shell_wifi.pairing import pair
    from adb_shell_wifi.pairing.connection import PairingException
    try:
        r = pair(host, port, code, priv_pem, pub_data, timeout_s=30)
        return True, 'PAIR_OK: %r' % (r,)
    except PairingException as e:
        m = str(e).lower()
        if 'decryption' in m or 'init_cipher' in m or 'spake' in m:
            return False, 'SPAKE2_ERROR: %s' % e
        if 'tls' in m or 'ssl' in m or 'handshake' in m:
            return False, 'TLS_ERROR: %s' % e
        return False, 'PAIR_FAIL: %s' % e
    except ImportError as e:
        return False, 'DEPS_MISSING: %s' % e
    except Exception as e:
        return False, 'PAIR_FAIL: %s (%s)' % (e, type(e).__name__)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--code', default=None, help='6 位配对码')
    ap.add_argument('--host', default='', help='配对服务地址（App mDNS 解析出的真实 IP，可空）')
    ap.add_argument('--port', type=int, default=0, help='配对端口（0=用户后补/不配）')
    ap.add_argument('--connect-port', type=int, default=DEFAULT_CONNECT_PORT)
    ap.add_argument('--genkey', action='store_true')
    a = ap.parse_args()

    if not check_deps():
        sys.exit(1)
    ensure_key()
    if a.genkey:
        sys.exit(0)

    if not a.code:
        print('NO_CODE')
        sys.exit(1)

    priv_pem = open(KEY, 'rb').read()
    pub_data = open(KEYPUB, 'rb').read().strip()

    port = a.port
    if not port:
        port = mdns_pair_port()
    if not port:
        print('NO_PAIR_PORT: 在 App 内输入手机「无线调试」界面显示的配对端口')
        sys.exit(1)

    # 候选地址：App 传入真实 host → 127.0.0.1 → 本机接口 IP
    candidates = []
    if a.host:
        candidates.append(a.host)
    candidates.append('127.0.0.1')
    for ip in local_ips():
        if ip not in candidates:
            candidates.append(ip)

    # 先探测可达性（任一地址通即可）
    reachable = None
    for h in candidates:
        if probe(port, h):
            reachable = h
            break
    if reachable is None:
        print('PORT_UNREACHABLE: 端口 %d 在所有地址(%s)都连不上。'
              '请确认手机「无线调试 → 使用配对码配对设备」弹窗刚打开（2 分钟内有效）'
              % (port, ','.join(candidates)))
        sys.exit(1)

    # 用可达地址配对（单次！）
    ok, detail = do_pair(reachable, port, a.code, priv_pem, pub_data)
    if not ok:
        print(detail)
        print('ADDR_USED=%s' % reachable)
        sys.exit(1)
    print(detail)
    print('ADDR_USED=%s' % reachable)

    # 配对成功后直连自检（等 adbd 更新授权列表）
    time.sleep(1.2)
    conn = a.connect_port
    last = None
    for candidate in (conn, 5555):
        try:
            out = adb_shell(candidate, ['id', 'getprop ro.product.model'])
            print('CONNECT_OK port=%d' % candidate)
            print(out.strip())
            save_connect_port(candidate)
            sys.exit(0)
        except Exception as e:
            last = e
    print('CONNECT_WARN: 配对成功但直连失败(%s)，请在 App 填写连接端口后重试' % last)
    sys.exit(0)


def adb_shell(port, cmds):
    from adb_shell_wifi.adb_device import AdbDeviceTls  # 无线调试 TLS 通道
    from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner
    signer = PythonRSASigner(open(KEYPUB, 'rb').read().strip(), open(KEY, 'rb').read())
    priv_pem = open(KEY, 'rb').read()
    dev = AdbDeviceTls('127.0.0.1', port)
    dev.connect(rsa_keys=[signer], auth_timeout_s=20, tls_priv_pem=priv_pem)
    try:
        return dev.shell(' && '.join(cmds))
    finally:
        dev.close()


def save_connect_port(port):
    try:
        with open(KEYDIR + '/connect_port', 'w') as f:
            f.write(str(port))
    except Exception:
        pass


def mdns_pair_port(timeout_s=6):
    # 尽力而为：容器回环收不到组播时返回 0，由 App NsdManager 兜底
    try:
        from zeroconf import Zeroconf, ServiceBrowser, ServiceListener
        found = {}
        class L(ServiceListener):
            def add_service(self, zc, type_, name):
                info = zc.get_service_info(type_, name)
                if info:
                    found[info.port] = name
            def update_service(self, zc, type_, name):
                pass
            def remove_service(self, zc, type_, name):
                pass
        zc = Zeroconf()
        ServiceBrowser(zc, '_adb-tls-pairing._tcp.local.', L())
        time.sleep(timeout_s)
        zc.close()
        if found:
            return sorted(found)[0]
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    main()
