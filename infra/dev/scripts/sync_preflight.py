#!/usr/bin/env python3
"""Kiểm tra trước khi áp compose của repo lên máy chủ dev dùng chung (INF-22).

So cấu hình compose mới (``docker compose config --format json``, dựng trên máy trạm bằng các khoá
không bí mật của ``.env`` máy chủ) với trạng thái container thật trên máy chủ. Dừng (mã thoát 1) khi:

* một dịch vụ đang chạy sẽ được dựng lại trên chỗ lưu dữ liệu khác: volume có tên khác, hoặc thư
  mục gắn khác, hoặc mất hẳn điểm gắn dữ liệu (nguy cơ chạy Postgres, Kafka, MinIO trên volume trống);
* một cổng mà cấu hình mới mở đang bị container khác giữ (container ngoài project, hoặc container
  của project mà lần áp này không dựng lại);
* một dịch vụ được thay thế (biến ``SYNC_RETIRE``, dạng ``cũ=mới``) còn giữ dữ liệu có tên.

Volume ẩn danh mà ảnh khai ``VOLUME`` (ví dụ ``/etc/kafka/secrets`` của Kafka, ``/openbao/file`` của
OpenBao) không có trong compose; ``docker compose up`` khi dựng lại container của cùng dịch vụ kế thừa
chúng (không dùng ``-V``/``--renew-anon-volumes``), kể cả khi tên container đổi, nên chỉ báo lưu ý.
Container của dịch vụ được thay thế (``connect`` thay bằng ``debezium``) được coi là sẽ bị xoá trước
khi áp, nên cổng của nó không tính là tranh chấp.

Chỉ in tên dịch vụ, tên container, đường dẫn dữ liệu và số cổng; không in biến môi trường.

Cách dùng: sync_preflight.py <compose.json> <server-state.txt> <thư mục infra trên máy trạm> <thư mục infra trên máy chủ>
"""

import json
import os
import re
import sys

PROJECT = "mission-dev"


def load_state(path):
    """Dòng ``C|tên|dịch vụ|đang chạy|VOLUME của ảnh json|mounts json`` cho container, ``P|project|tên|cổng`` cho cổng."""
    containers, ports = [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("C|"):
                _, name, service, running, image_vols, mounts = line.split("|", 5)
                containers.append(
                    {
                        "name": name.strip('"').lstrip("/"),
                        "service": service,
                        "running": running == "true",
                        "image_volumes": set((json.loads(image_vols or "null") or {}).keys()),
                        "mounts": json.loads(mounts or "[]") or [],
                    }
                )
            elif line.startswith("P|"):
                _, project, name, spec = line.split("|", 3)
                for host, port in re.findall(r"(?:([\d.:\[\]a-f]*):)?(\d+)->", spec):
                    ports.append({"project": project, "name": name, "host": host, "port": int(port)})
    return containers, ports


ANON = re.compile(r"^[0-9a-f]{64}$")


def retire_map():
    """``SYNC_RETIRE="connect=debezium other=new"``: dịch vụ cũ trên máy chủ được thay bằng dịch vụ mới."""
    out = {}
    for item in os.environ.get("SYNC_RETIRE", "").split():
        old, _, new = item.partition("=")
        if old and new:
            out[old] = new
    return out


def new_mounts(service, config, local_dir, server_dir):
    # Thư mục cha của infra/dev (infra/) được chép cùng chỗ trên máy chủ (keycloak-theme), nên ánh xạ theo cha.
    local_root, server_root = os.path.dirname(local_dir), os.path.dirname(server_dir)
    out = {}
    for v in service.get("volumes", []):
        target = v.get("target")
        if v.get("type") == "volume":
            vol = config.get("volumes", {}).get(v.get("source"), {})
            out[target] = ("volume", vol.get("name") or f"{PROJECT}_{v.get('source')}")
        elif v.get("type") == "bind":
            src = v.get("source", "")
            if src == local_root or src.startswith(local_root + "/"):
                src = server_root + src[len(local_root):]
            out[target] = ("bind", src.rstrip("/"))
    return out


def main(argv):
    if len(argv) != 5:
        print(__doc__, file=sys.stderr)
        return 2
    config_path, state_path, local_dir, server_dir = argv[1:]
    local_dir, server_dir = local_dir.rstrip("/"), server_dir.rstrip("/")
    with open(config_path, encoding="utf-8") as f:
        config = json.load(f)
    services = config.get("services", {})
    containers, ports = load_state(state_path)
    errors, notes = [], []

    retired = retire_map()
    replaced = set()
    for c in containers:
        svc = services.get(c["service"])
        if svc is None and c["service"] in retired and retired[c["service"]] in services:
            replaced.add(c["name"])
            named = [
                m for m in c["mounts"]
                if (m.get("Type") == "volume" and not ANON.match(m.get("Name") or ""))
                or (m.get("Type") == "bind" and (m.get("Source") or "").startswith(server_dir + "/data/"))
            ]
            for m in named:
                errors.append(
                    f"Dịch vụ {c['service']} (thay bằng {retired[c['service']]}): container {c['name']} còn dữ liệu "
                    f"ở {m.get('Destination')} ({m.get('Type')} {m.get('Name') or m.get('Source')}); không xoá tự động."
                )
            if not named:
                notes.append(
                    f"Container {c['name']} (dịch vụ {c['service']}) được thay bằng dịch vụ {retired[c['service']]}; "
                    "apply dừng và xoá nó trước khi dựng lại (không có dữ liệu có tên)."
                )
            continue
        if svc is None:
            if c["running"]:
                notes.append(
                    f"Container {c['name']} (dịch vụ {c['service']}) không thuộc các profile sẽ áp; giữ nguyên, vẫn giữ cổng."
                )
            continue
        replaced.add(c["name"])
        wanted = svc.get("container_name") or f"{PROJECT}-{c['service']}-1"
        if wanted != c["name"]:
            notes.append(f"Container {c['name']} sẽ được dựng lại với tên {wanted}.")
        mounts = new_mounts(svc, config, local_dir, server_dir)
        for m in c["mounts"]:
            dest = m.get("Destination")
            kind = m.get("Type")
            current = m.get("Name") if kind == "volume" else (m.get("Source") or "").rstrip("/")
            is_data = kind == "volume" or current.startswith(server_dir + "/data/")
            planned = mounts.get(dest)
            if planned is None and kind == "volume" and ANON.match(current or "") and dest in c["image_volumes"]:
                notes.append(
                    f"Dịch vụ {c['service']}: volume ẩn danh {dest} (VOLUME của ảnh) được compose kế thừa khi dựng lại."
                )
                continue
            if planned is None:
                msg = f"Dịch vụ {c['service']}: điểm gắn {dest} ({kind} {current}) không còn trong cấu hình mới."
                (errors if is_data else notes).append(msg)
            elif planned != (kind, current):
                msg = (
                    f"Dịch vụ {c['service']}: {dest} đang dùng {kind} {current}, cấu hình mới dùng "
                    f"{planned[0]} {planned[1]}."
                )
                (errors if is_data else notes).append(msg)

    held = [p for p in ports if p["project"] != PROJECT or p["name"] not in replaced]
    for name, svc in services.items():
        for p in svc.get("ports", []) or []:
            published = p.get("published")
            if not published:
                continue
            for port in range(int(str(published).split("-")[0]), int(str(published).split("-")[-1]) + 1):
                for h in held:
                    if h["port"] == port:
                        errors.append(
                            f"Dịch vụ {name}: cổng {port} đang bị container {h['name']} giữ "
                            f"(project {h['project'] or 'ngoài compose'})."
                        )

    for n in notes:
        print(f"  [lưu ý] {n}")
    for e in errors:
        print(f"  [DỪNG]  {e}")
    if errors:
        print(
            "  Không áp. Đặt *_DATA trong .env máy chủ trỏ đúng chỗ lưu dữ liệu hiện có (infra/dev/README mục 2.1), "
            "hoặc di trú dữ liệu có kiểm soát, rồi chạy lại plan."
        )
        return 1
    print("  Không thấy lệch chỗ lưu dữ liệu hay tranh cổng.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
