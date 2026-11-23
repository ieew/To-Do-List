#!/usr/bin/env python3
"""dsh-kit 对端 CLI（P3）：不写代码也能发通知 / 发剪贴板 / 收消息。

只用 Python 标准库，电脑和手机 proot 里都能跑。

首次：
    python3 tools/dsh_cli.py login --server http://127.0.0.1:8000 --user alice
    （密码可以交互输入，或加 --password；登录后令牌存 ~/.config/dsh-cli/session.json）

常用：
    tools/dsh_cli.py devices                    # 看设备（在线状态 + 设备号）
    tools/dsh_cli.py notify --to 手机 --title 喝水 --level 6
    tools/dsh_cli.py clip --text "要贴过去的文字"
    tools/dsh_cli.py pull                       # 用自己的 CLI 设备收（会打印并 ack）
    tools/dsh_cli.py watch                      # 每 2 秒拉一次，边收边打印
    tools/dsh_cli.py state                      # broker / 待投条数
    tools/dsh_cli.py demo --to 手机             # 各发一条（L3 / L7 / 剪贴板）

`--to` 可以写设备号、设备号前缀或设备名（默认 = 广播给该用户所有设备）。
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.request

DEFAULT_SESSION = os.path.expanduser("~/.config/dsh-cli/session.json")


# ---------------------------------------------------------------- HTTP


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def request(base: str, method: str, path: str, body=None, token=None, headers=None) -> dict:
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        code, message = "", raw[:200]
        try:
            err = json.loads(raw).get("error", {})
            code = err.get("code", "")
            message = err.get("message", message)
        except Exception:  # noqa: BLE001 - 错误体不是 JSON 就用原文
            pass
        raise ApiError(exc.code, code, message) from exc
    except urllib.error.URLError as exc:
        raise ApiError(0, "network", f"连不上 {base}: {exc.reason}") from exc


# ---------------------------------------------------------------- 会话


class Client:
    def __init__(self, session_path: str) -> None:
        self.path = session_path
        self.data = {}
        if os.path.exists(session_path):
            try:
                with open(session_path, encoding="utf-8") as f:
                    self.data = json.load(f)
            except Exception:  # noqa: BLE001
                self.data = {}

    @property
    def base(self) -> str:
        return (self.data.get("server") or "").rstrip("/")

    def save(self) -> None:
        os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, self.path)

    # ---- 令牌

    def login(self, password: str) -> None:
        if not self.base:
            raise SystemExit("没有 server：先 login --server http://host:port")
        body = {"username": self.data.get("username", ""), "password": password}
        if self.data.get("device_id"):
            body["device_id"] = self.data["device_id"]
        try:
            out = request(self.base, "POST", "/v1/auth/login", body)
        except ApiError as exc:
            if exc.status != 401:
                raise
            out = request(self.base, "POST", "/v1/auth/register", {
                "username": body["username"], "password": password})
            print(f"账号不存在，已注册：{body['username']}")
        self._store_tokens(out["tokens"])

    def refresh(self) -> None:
        token = self.data.get("refresh")
        if not token:
            raise ApiError(401, "no_refresh", "没有刷新令牌，请重新 login")
        out = request(self.base, "POST", "/v1/auth/refresh",
                      {"refresh_token": token, "device_id": self.data.get("device_id")})
        self._store_tokens(out["tokens"])

    def _store_tokens(self, tokens: dict) -> None:
        self.data["access"] = tokens["access_token"]
        self.data["access_exp"] = tokens.get("access_expires_at", 0)
        self.data["refresh"] = tokens.get("refresh_token", "")
        self.data["refresh_exp"] = tokens.get("refresh_expires_at", 0)
        self.save()

    # ---- 请求

    def call(self, method: str, path: str, body=None, retry: bool = True) -> dict:
        if not self.base:
            raise SystemExit("还没登录：先跑 login")
        try:
            return request(self.base, method, path, body, token=self.data.get("access"))
        except ApiError as exc:
            if exc.status == 401 and retry:
                # 先刷令牌；刷新也过期就要求重新登录
                try:
                    self.refresh()
                except ApiError:
                    raise SystemExit("登录过期，请重新 login") from exc
                return self.call(method, path, body, retry=False)
            raise

    # ---- 设备

    def ensure_device(self, name: str = "CLI") -> dict:
        device_id = self.data.get("device_id")
        try:
            out = self.call("POST", "/v1/dsh/devices/register", {
                "name": name, "platform": "cli",
                **({"device_id": device_id} if device_id else {}),
            })
        except ApiError as exc:
            if exc.status in (404, 409):
                self.data.pop("device_id", None)
                self.save()
                out = self.call("POST", "/v1/dsh/devices/register", {
                    "name": name, "platform": "cli"})
            else:
                raise
        self.data["device_id"] = out["device_id"]
        self.data["topic_in"] = out["mqtt"]["topic_in"]
        self.data["ticket"] = out["ticket"]
        self.save()
        return out

    def devices(self) -> list[dict]:
        return self.call("GET", "/v1/dsh/devices").get("devices", [])

    def resolve_device(self, target: str | None) -> str | None:
        if not target:
            return None
        try:
            for item in self.devices():
                if item["device_id"] == target or item["device_id"].startswith(target):
                    return item["device_id"]
                if item.get("name") == target:
                    return item["device_id"]
        except ApiError:
            pass
        if target.startswith("dsh-"):
            return target
        raise SystemExit(f"找不到设备：{target}（用 devices 看看有哪些）")


# ---------------------------------------------------------------- 命令


def cmd_login(args: argparse.Namespace) -> int:
    client = Client(args.session)
    client.data["server"] = args.server.rstrip("/") if args.server else client.data.get("server", "")
    client.data["username"] = args.user or client.data.get("username", "")
    if not client.base or not client.data["username"]:
        raise SystemExit("需要 --server 和 --user（第一次登录）")
    password = args.password or getpass.getpass("密码: ")
    client.login(password)
    client.ensure_device(args.device_name)
    print(f"已登录：{client.base}  user={client.data['username']}  "
          f"cli设备={client.data['device_id']}")
    return 0


def cmd_devices(args: argparse.Namespace) -> int:
    client = Client(args.session)
    items = client.devices()
    if args.json:
        print(json.dumps(items, ensure_ascii=False, indent=2))
        return 0
    if not items:
        print("还没有设备")
        return 0
    print(f"{'设备号':<16} {'名称':<10} {'平台':<8} 在线  吊销")
    for item in items:
        print(f"{item['device_id']:<16} {item.get('name',''):<10} "
              f"{item.get('platform',''):<8} {'是' if item.get('online') else '否':<4} "
              f"{'是' if item.get('revoked') else '否'}")
    return 0


def cmd_notify(args: argparse.Namespace) -> int:
    client = Client(args.session)
    device = client.resolve_device(args.to)
    payload = {"title": args.title, "body": args.body or ""}
    if args.url:
        payload["url"] = args.url
    body = {"kind": "notify", "payload": payload, "level": args.level}
    if device:
        body["device_id"] = device
    out = client.call("POST", "/v1/dsh/push", body)
    print(f"已投递 seq={out['seq']} level={out.get('level')} "
          f"目标={out.get('device_id') or '全部设备'} "
          f"nudge={'已发' if out.get('nudged') else '设备不在线，等它 pull'}")
    return 0


def cmd_clip(args: argparse.Namespace) -> int:
    client = Client(args.session)
    text = args.text
    if text == "-":
        text = sys.stdin.read()
    if not text:
        raise SystemExit("剪贴板内容为空")
    device = client.resolve_device(args.to)
    body = {"text": text, "fp": ""}
    if device:
        body["device_id"] = device
    out = client.call("POST", "/v1/dsh/clip", body)
    print(f"已发送：{'已落库' if out.get('stored') else '实时转发'}，"
          f"送达 {out.get('delivered', 0)} 台设备")
    return 0


def cmd_pull(args: argparse.Namespace) -> int:
    client = Client(args.session)
    client.ensure_device(args.device_name)
    since = int(args.since or 0)
    if not since:
        since = int(client.data.get("cursor", 0))
    total = 0
    while True:
        out = client.call("GET", f"/v1/dsh/pull?device_id={client.data['device_id']}"
                                 f"&since={since}&limit=100")
        messages = out.get("messages", [])
        for msg in messages:
            print_message(msg, args.json)
        seqs = [msg["seq"] for msg in messages]
        if seqs:
            client.call("POST", "/v1/dsh/ack",
                        {"device_id": client.data["device_id"], "seqs": seqs})
        total += len(messages)
        since = out.get("next_seq", since)
        client.data["cursor"] = since
        client.save()
        if not out.get("has_more") or not messages:
            break
    if not args.quiet:
        print(f"共 {total} 条，游标={since}")
    return 0


def print_message(msg: dict, as_json: bool) -> None:
    if as_json:
        print(json.dumps(msg, ensure_ascii=False))
        return
    stamp = time.strftime("%H:%M:%S", time.localtime(msg.get("created_at", 0) / 1000))
    kind = msg.get("kind", "?")
    payload = msg.get("payload")
    if kind == "notify" and isinstance(payload, dict):
        level = payload.get("level", "-")
        print(f"[{stamp}] L{level} 通知：{payload.get('title', '')}  {payload.get('body', '')}")
    elif kind == "clip":
        text = payload.get("text") if isinstance(payload, dict) else payload
        print(f"[{stamp}] 剪贴板：{text}")
    else:
        print(f"[{stamp}] {kind}：{json.dumps(payload, ensure_ascii=False)}")


def cmd_watch(args: argparse.Namespace) -> int:
    client = Client(args.session)
    client.ensure_device(args.device_name)
    print(f"监听中（设备 {client.data['device_id']}，每 {args.interval}s 拉一次，Ctrl+C 退出）")
    try:
        while True:
            args.since = client.data.get("cursor", 0)
            args.quiet = True
            cmd_pull(args)
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\n已退出")
    return 0


def cmd_state(args: argparse.Namespace) -> int:
    client = Client(args.session)
    out = client.call("GET", "/v1/dsh/state")
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


def cmd_whoami(args: argparse.Namespace) -> int:
    client = Client(args.session)
    print(f"server  {client.base or '-'}")
    print(f"user    {client.data.get('username', '-')}")
    print(f"设备    {client.data.get('device_id', '-')}")
    print(f"会话    {client.path}")
    return 0


def cmd_demo(args: argparse.Namespace) -> int:
    client = Client(args.session)
    device = client.resolve_device(args.to)
    target = {"device_id": device} if device else {}
    out = client.call("POST", "/v1/dsh/push", {
        "kind": "notify", "level": 3, **target,
        "payload": {"title": "dsh 对端自检 L3", "body": "悬浮、不出声"},
    })
    print(f"L3 通知 + {out['seq']}")
    out = client.call("POST", "/v1/dsh/push", {
        "kind": "notify", "level": 7, **target,
        "payload": {"title": "dsh 对端自检 L7", "body": "悬浮 + 震动 + 响声"},
    })
    print(f"L7 通知 + {out['seq']}")
    out = client.call("POST", "/v1/dsh/clip", {
        "text": f"dsh 对端剪贴板 {time.strftime('%H:%M:%S')}", **target,
    })
    print(f"剪贴板 + 送达 {out.get('delivered', 0)} 台")
    return 0


# ---------------------------------------------------------------- 入口


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="dsh-kit 对端 CLI")
    parser.add_argument("--session", default=DEFAULT_SESSION, help="会话文件")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("login", help="登录并注册一个 CLI 设备")
    p.add_argument("--server")
    p.add_argument("--user")
    p.add_argument("--password")
    p.add_argument("--device-name", default="CLI")
    p.set_defaults(func=cmd_login)

    p = sub.add_parser("devices", help="设备列表")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_devices)

    p = sub.add_parser("notify", help="发通知")
    p.add_argument("--to", help="设备号/前缀/名称；省略=全部设备")
    p.add_argument("--title", required=True)
    p.add_argument("--body", default="")
    p.add_argument("--url", default="")
    p.add_argument("--level", type=int, default=3, choices=range(0, 8))
    p.set_defaults(func=cmd_notify)

    p = sub.add_parser("clip", help="发剪贴板")
    p.add_argument("--to")
    p.add_argument("--text", required=True, help="文本；用 - 从 stdin 读")
    p.set_defaults(func=cmd_clip)

    p = sub.add_parser("pull", help="用 CLI 设备收一次并 ack")
    p.add_argument("--since", type=int, default=0)
    p.add_argument("--json", action="store_true")
    p.add_argument("--quiet", action="store_true")
    p.add_argument("--device-name", default="CLI")
    p.set_defaults(func=cmd_pull)

    p = sub.add_parser("watch", help="持续收（轮询）")
    p.add_argument("--interval", type=float, default=2.0)
    p.add_argument("--json", action="store_true")
    p.add_argument("--device-name", default="CLI")
    p.set_defaults(func=cmd_watch, since=0, quiet=True)

    p = sub.add_parser("state", help="服务端状态")
    p.set_defaults(func=cmd_state)

    p = sub.add_parser("whoami", help="当前会话")
    p.set_defaults(func=cmd_whoami)

    p = sub.add_parser("demo", help="各发一条（L3 / L7 / 剪贴板）")
    p.add_argument("--to")
    p.set_defaults(func=cmd_demo)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except ApiError as exc:
        print(f"失败：{exc.status} {exc.code} {exc.message}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
