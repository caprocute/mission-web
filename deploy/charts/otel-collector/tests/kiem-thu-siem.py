#!/usr/bin/env python3
"""Kiểm thử luồng SIEM của otel-collector (INF-19, SEC-43).

1. Kiểm tĩnh bản render: đúng bốn topic sys.audit.v1, iam.auth.event.v1, sys.security.runtime.v1, sys.siem.v1;
   mọi kết nối Kafka có mTLS (ca, cert, key) và TLS ≥ 1.2; chặng ra SOC chỉ dựng khi có địa chỉ và chỉ ở namespace
   egress; otel-siem không có đường ra nào ngoài Kafka của cell.
2. Chạy thật bộ xử lý ECS của otel-siem trên bản ghi mẫu (nhật ký kiểm toán API, OPA từ chối, xuất hàng loạt,
   OpenBao Transit decrypt, dòng không phải JSON, sự kiện PAM syslog RFC 5424 với SD-ID vtpam@32473 gửi qua TCP tới
   receiver syslog thật) bằng otelcol-contrib: biến OTELCOL trỏ tới tệp chạy, hoặc ảnh
   docker OTEL_IMAGE. Không có cả hai thì bỏ qua phần 2 và in [BỎ QUA].

Dùng: kiem-thu-siem.py <render mặc định> <render có SOC>
In mỗi dòng [ĐẠT]/[HỎNG]/[BỎ QUA]; mã thoát khác 0 khi có [HỎNG].
"""
import json, os, shutil, socket, subprocess, sys, tempfile, time
import yaml

loi = 0


def ok(c, s):
    global loi
    print(("[ĐẠT] " if c else "[HỎNG] ") + s)
    loi += 0 if c else 1


def tai(p):
    return [d for d in yaml.safe_load_all(open(p)) if d]


def cau_hinh(docs, ten):
    for d in docs:
        if d["kind"] == "ConfigMap" and d["metadata"]["name"] == ten:
            return yaml.safe_load(d["data"]["config.yaml"]), d["metadata"]["namespace"]
    return None, None


mac_dinh, co_soc = tai(sys.argv[1]), tai(sys.argv[2])
c, ns = cau_hinh(mac_dinh, "otel-siem")
ok(c is not None and ns == "observability", "otel-siem dựng trong observability")
topics = {k: v["logs"]["topic"] for k, v in {**c["receivers"], **c["exporters"]}.items() if "logs" in v}
ok(topics.get("kafka/audit") == "sys.audit.v1" and topics.get("kafka/runtime") == "sys.security.runtime.v1"
   and topics.get("kafka/siem") == "sys.siem.v1", f"đúng tên topic SIEM: {sorted(set(topics.values()))}")
kafka = [v for k, v in {**c["receivers"], **c["exporters"]}.items() if k.startswith("kafka")]
ok(all({"ca_file", "cert_file", "key_file"} <= set(v["tls"]) and v["tls"]["min_version"] in ("1.2", "1.3") for v in kafka),
   f"{len(kafka)} kết nối Kafka đều mTLS, TLS ≥ 1.2")
ok(c["receivers"]["otlp/falco"]["protocols"]["http"]["tls"].get("client_ca_file") is not None,
   "OTLP từ Falcosidekick bắt buộc chứng thư máy khách (mTLS)")
ok(all(e in ("kafka/siem", "kafka/runtime") for p in c["service"]["pipelines"].values() for e in p["exporters"]),
   "otel-siem chỉ ghi Kafka của cell (không có đích ngoài)")
ok(not any(d["kind"] == "Deployment" and d["metadata"]["name"] == "siem-soc-forwarder" for d in mac_dinh),
   "không có địa chỉ SOC thì không dựng chặng ra SOC")
fw = [d for d in co_soc if d["kind"] == "Deployment" and d["metadata"]["name"] == "siem-soc-forwarder"]
ok(len(fw) == 1 and fw[0]["metadata"]["namespace"] == "egress", "chặng ra SOC chạy trong namespace egress (một chiều ra ngoài)")
sc, _ = cau_hinh(co_soc, "siem-soc-forwarder")
ok(sc is not None and sc["receivers"]["kafka/cell"]["logs"]["topic"] == "sys.siem.v1"
   and {"ca_file", "cert_file", "key_file"} <= set(sc["exporters"]["kafka/soc"]["tls"]), "chặng ra SOC đọc sys.siem.v1, ghi SOC bằng mTLS")
ok(not [r for r in sc["receivers"] if not r.startswith("kafka")], "chặng ra SOC không mở cổng nhận nào")

# Khớp với chart khác (INF-19): nhóm tiêu thụ nằm trong tiền tố ACL của KafkaUser siem-forwarder; thiết bị kiểm toán
# socket của OpenBao trỏ đúng cổng tcplog/openbao.
DEPLOY = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
nhom = [r["group_id"] for r in [*c["receivers"].values(), *sc["receivers"].values()] if "group_id" in r]
ok(nhom and all(g.startswith("siem-forwarder") for g in nhom), f"nhóm tiêu thụ trong tiền tố ACL siem-forwarder: {sorted(set(nhom))}")
dm = yaml.safe_load(open(os.path.join(DEPLOY, "charts/vt-platform/ha-tang/kafka/files/danh-muc.yaml")))
sf = dm["nguoiDung"]["siem-forwarder"]
ok({"sys.security.runtime.v1", "sys.siem.v1"} <= set(sf["ghi"]) and {"sys.audit.v1", "sys.security.runtime.v1", "sys.siem.v1"} <= set(sf["doc"]),
   "danh mục Kafka cấp cho siem-forwarder đủ quyền ghi, đọc của otel-siem và chặng ra SOC")
ob = yaml.safe_load(open(os.path.join(DEPLOY, "charts/vt-platform/ha-tang/openbao/values.yaml")))["audit"]["socket"]
cong = c["receivers"].get("tcplog/openbao", {}).get("listen_address", ":").rsplit(":", 1)[1]
ok(ob["enabled"] and ob["address"] == f"otel-siem.observability.svc:{cong}",
   f"OpenBao gửi kiểm toán socket tới otel-siem cổng {cong}")

# Phần 2: chạy thật bộ xử lý ECS.
MAU = [
    {"kind": "Event", "apiVersion": "audit.k8s.io/v1", "auditID": "a1", "verb": "create",
     "user": {"username": "oidc:nguoi.van.hanh", "groups": ["oidc:platform-ops-sre"]}, "sourceIPs": ["10.1.2.3"],
     "objectRef": {"resource": "pods", "namespace": "core", "name": "p", "subresource": "exec"},
     "responseStatus": {"code": 101}, "requestReceivedTimestamp": "2026-10-23T01:02:03Z"},
    {"kind": "Event", "apiVersion": "audit.k8s.io/v1", "auditID": "a2", "verb": "patch", "user": {"username": "oidc:x"},
     "sourceIPs": ["10.1.2.4"], "objectRef": {"resource": "roles", "apiGroup": "rbac.authorization.k8s.io"},
     "responseStatus": {"code": 200}, "requestReceivedTimestamp": "2026-10-23T01:02:04Z"},
    {"kind": "Event", "apiVersion": "audit.k8s.io/v1", "auditID": "a3", "verb": "create", "user": {"username": "oidc:pam"},
     "sourceIPs": ["10.1.2.4"], "objectRef": {"resource": "clusterrolebindings", "apiGroup": "rbac.authorization.k8s.io"},
     "requestObject": {"roleRef": {"name": "platform-admin"}}, "responseStatus": {"code": 201},
     "requestReceivedTimestamp": "2026-10-23T01:02:05Z"},
    {"kind": "Event", "apiVersion": "audit.k8s.io/v1", "auditID": "a4", "verb": "create", "user": {"username": "u"},
     "sourceIPs": ["10.1.2.5"], "objectRef": {"resource": "pods", "namespace": "core"},
     "responseStatus": {"code": 400, "message": "admission webhook denied: vt-require-signed-images failed"},
     "requestReceivedTimestamp": "2026-10-23T01:02:06Z"},
    {"kind": "Event", "apiVersion": "audit.k8s.io/v1", "auditID": "a5", "verb": "get", "user": {"username": "oidc:?"},
     "sourceIPs": ["10.1.2.6"], "objectRef": {"resource": "pods"}, "responseStatus": {"code": 401},
     "requestReceivedTimestamp": "2026-10-23T01:02:07Z"},
    {"@timestamp": "2026-10-23T01:02:08Z", "message": "từ chối", "event": {"dataset": "security", "action": "authz.deny",
     "outcome": "failure"}, "service": {"name": "mission-core"}, "user": {"id": "u-1"}, "tenant": {"id": "t-1"}},
    {"@timestamp": "2026-10-23T01:02:09Z", "event": {"dataset": "audit", "action": "XUAT_HANG_LOAT", "outcome": "success"},
     "service": {"name": "mission-core"}, "user": {"id": "u-2"}},
    {"@timestamp": "2026-10-23T01:02:10Z", "event": {"dataset": "security", "action": "waf.block"}, "service": {"name": "apisix"}},
]
OPENBAO = [{"time": "2026-10-23T01:02:11Z", "type": "request", "auth": {"display_name": "kubernetes-core-mission"},
            "request": {"operation": "update", "path": "transit/decrypt/kek-tenant-t1", "remote_address": "10.1.3.4"}}]
# Sự kiện PAM theo hợp đồng ops/runbooks/pam-cap-quyen.md (SD-ID vtpam@32473), một dòng mỗi sự kiện; dòng cuối không
# có SD của PAM, chỉ có MSGID và thông điệp nhắc phá kính.
SD = "vtpam@32473"
PAM = [
    f'<86>1 2026-10-23T01:00:00Z pam-1.vt.local warpgate - pam.yeu-cau [{SD} event_action="pam.yeu-cau" user="nguoi.van.hanh" request_id="YC-1" role="debug-app" namespace="core"] Yêu cầu quyền',
    f'<86>1 2026-10-23T01:01:00Z pam-1.vt.local warpgate - pam.duyet [{SD} event_action="pam.duyet" user="nguoi.van.hanh" request_id="YC-1" approver="duyet.a,duyet.b"] Đã duyệt',
    f'<86>1 2026-10-23T01:01:30Z pam-1.vt.local warpgate - pam.tu-choi [{SD} event_action="pam.tu-choi" user="nguoi.khac" request_id="YC-2" approver="duyet.a"] Từ chối',
    f'<86>1 2026-10-23T01:02:00Z pam-1.vt.local warpgate - pam.cap-quyen [{SD} event_action="pam.cap-quyen" user="nguoi.van.hanh" request_id="YC-1" role="debug-app" namespace="core" expires="2026-10-23T03:02:00Z"] Cấp quyền',
    f'<86>1 2026-10-23T01:03:00Z pam-1.vt.local warpgate - pam.phien-bat-dau [{SD} event_action="pam.phien-bat-dau" user="nguoi.van.hanh" session_id="ph-7" target="pg-core-rw.data-pg.svc:5432" recording_uri="https://warpgate.vt.local/@warpgate/admin/recordings/r-7"] Mở phiên',
    f'<84>1 2026-10-23T01:04:00Z pam-1.vt.local warpgate - pam.lenh-nhay-cam [{SD} event_action="pam.lenh-nhay-cam" user="nguoi.van.hanh" session_id="ph-7" command="DROP TABLE task.giao_viec"] Lệnh nhạy cảm',
    f'<86>1 2026-10-23T01:05:00Z pam-1.vt.local warpgate - pam.thu-hoi [{SD} event_action="pam.thu-hoi" user="nguoi.van.hanh" request_id="YC-1" role="debug-app"] Thu hồi',
    f'<82>1 2026-10-23T01:06:00Z pam-1.vt.local warpgate - pam.pha-kinh [{SD} event_action="pam.pha-kinh" user="truc.ca" approver="duyet.a,duyet.b" request_id="PK-1"] Mở phiên phá kính',
    '<86>1 2026-10-23T01:07:00Z pam-1.vt.local warpgate - - - Break-glass session closed',
]

otelcol = os.environ.get("OTELCOL")
anh = os.environ.get("OTEL_IMAGE")
if not otelcol and not (anh and shutil.which("docker")):
    print("[BỎ QUA] chạy thật bộ xử lý ECS: đặt OTELCOL=<otelcol-contrib> hoặc OTEL_IMAGE=<ảnh>")
    sys.exit(1 if loi else 0)

tmp = tempfile.mkdtemp()
try:
    def gia_tri(v):
        # AnyValue của OTLP JSON thành giá trị Python (map, mảng lồng nhau).
        if "kvlistValue" in v:
            return {x["key"]: gia_tri(x["value"]) for x in v["kvlistValue"].get("values", [])}
        if "arrayValue" in v:
            return [gia_tri(x) for x in v["arrayValue"].get("values", [])]
        return next(iter(v.values()), None)

    def cong_trong():
        with socket.socket() as so:
            so.bind(("127.0.0.1", 0))
            return so.getsockname()[1]

    def chay(ten, dong, cac_xu_ly, syslog=None):
        """syslog=<receiver gốc của chart>: nhận qua TCP bằng receiver syslog (bỏ TLS), dòng gửi qua socket."""
        vao = os.path.join(tmp, f"{ten}.jsonl")
        ra = os.path.join(tmp, f"{ten}-ra.json")
        cong = None
        if syslog is None:
            with open(vao, "w") as f:
                f.write("\n".join(dong) + "\n")
            nhan = {"filelog": {"include": [f"/d/{ten}.jsonl"], "start_at": "beginning"}}
        else:
            cong = cong_trong()
            r = {k: v for k, v in syslog.items() if k != "tcp"}
            r["tcp"] = {"listen_address": f"{'127.0.0.1' if otelcol else '0.0.0.0'}:{cong}"}
            nhan = {"syslog": r}
        cfg = {"receivers": nhan,
               "processors": {k: c["processors"][k] for k in cac_xu_ly},
               "exporters": {"file": {"path": f"/d/{ten}-ra.json"}},
               "service": {"telemetry": {"metrics": {"level": "none"}},
                           "pipelines": {"logs": {"receivers": list(nhan), "processors": cac_xu_ly, "exporters": ["file"]}}}}
        s = yaml.safe_dump(cfg, allow_unicode=True)
        if otelcol:
            s = s.replace("/d/", tmp + "/")
            open(os.path.join(tmp, f"{ten}.yaml"), "w").write(s)
            p = subprocess.Popen([otelcol, f"--config={tmp}/{ten}.yaml"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            open(os.path.join(tmp, f"{ten}.yaml"), "w").write(s)
            p = subprocess.Popen(["docker", "run", "--rm", "--name", f"inf19-siem-{ten}-{os.getpid()}", "-v", f"{tmp}:/d",
                                  *(["-p", f"127.0.0.1:{cong}:{cong}"] if cong else []), anh,
                                  f"--config=/d/{ten}.yaml"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if cong:
            han = time.time() + 20
            while True:
                try:
                    with socket.create_connection(("127.0.0.1", cong), timeout=2) as so:
                        so.sendall(("\n".join(dong) + "\n").encode())
                    break
                except OSError:
                    if time.time() > han:
                        raise
                    time.sleep(0.3)
        han = time.time() + 20
        while time.time() < han and not (os.path.exists(ra) and os.path.getsize(ra) > 0):
            time.sleep(0.5)
        time.sleep(1)
        if otelcol:
            p.terminate()
        else:
            subprocess.run(["docker", "stop", "-t", "2", f"inf19-siem-{ten}-{os.getpid()}"], capture_output=True)
        p.wait(timeout=30)
        kq = []
        for l in open(ra) if os.path.exists(ra) else []:
            for rl in json.loads(l)["resourceLogs"]:
                for sl in rl["scopeLogs"]:
                    for r in sl["logRecords"]:
                        kq.append(gia_tri(r["body"]))
        return kq

    goc = [json.dumps(m, ensure_ascii=False) for m in MAU] + ["\x01avro-nhi-phan-khong-phai-json"]
    kq = chay("kiem-toan", goc, ["transform/phan-tich", "filter/khong-phai-json", "transform/dau", "transform/ecs-kiem-toan", "transform/cuoi"])
    ok(len(kq) == len(MAU), f"{len(kq)}/{len(MAU)} bản ghi JSON qua chuẩn hoá, dòng không phải JSON bị bỏ")
    theo_id = {r.get("event.id"): r for r in kq if r.get("event.module") == "kubernetes"}
    ok(all(r.get("ecs.version") and r.get("@timestamp") and r.get("labels.cell") and "vt.goc" not in r for r in kq),
       "mọi bản ghi có ecs.version, @timestamp, labels.cell và không còn khoá tạm vt.goc")
    a1 = theo_id.get("a1", {})
    ok(a1.get("user.name") == "oidc:nguoi.van.hanh" and a1.get("source.ip") == "10.1.2.3" and a1.get("vt.phat_hien") == "shell-container"
       and a1.get("event.dataset") == "kubernetes.audit" and isinstance(a1.get("kubernetes.audit"), dict),
       "kiểm toán API: pods/exec → user.name, source.ip, thẻ shell-container, bản gốc ở kubernetes.audit")
    ok(theo_id.get("a2", {}).get("vt.phat_hien") == "doi-rbac", "đổi RBAC → thẻ doi-rbac")
    ok(theo_id.get("a3", {}).get("vt.phat_hien") == "pha-kinh", "binding tới platform-admin → thẻ pha-kinh")
    ok(theo_id.get("a4", {}).get("vt.phat_hien") == "anh-khong-ky" and theo_id["a4"].get("event.outcome") == "failure",
       "Kyverno từ chối ảnh không ký → thẻ anh-khong-ky, kết quả failure")
    ok(theo_id.get("a5", {}).get("vt.phat_hien") == "dang-nhap-that-bai", "401 ở API server → thẻ dang-nhap-that-bai")
    ung_dung = [r for r in kq if r.get("event.module") in ("opa", "mission", "waf")]
    mod = {r.get("event.action"): r for r in ung_dung}
    ok(mod.get("authz.deny", {}).get("event.module") == "opa" and mod["authz.deny"].get("organization.id") == "t-1",
       "quyết định từ chối của OPA → event.module opa, organization.id theo tenant")
    ok(mod.get("XUAT_HANG_LOAT", {}).get("vt.phat_hien") == "xuat-hang-loat", "xuất hàng loạt → thẻ xuat-hang-loat")
    ok(mod.get("waf.block", {}).get("event.module") == "waf", "nhật ký APISIX/Coraza → event.module waf")
    kq = chay("openbao", [json.dumps(m) for m in OPENBAO],
              ["transform/phan-tich", "filter/khong-phai-json", "transform/dau", "transform/ecs-openbao", "transform/cuoi"])
    ok(len(kq) == 1 and kq[0].get("vt.phat_hien") == "transit-decrypt" and kq[0].get("url.path", "").startswith("transit/decrypt/"),
       "OpenBao Transit decrypt → thẻ transit-decrypt, url.path")
    if "syslog/pam" in c["receivers"]:
        kq = chay("pam", PAM, ["transform/dau", "transform/ecs-pam", "transform/cuoi"], syslog=c["receivers"]["syslog/pam"])
        ok(len(kq) == len(PAM) and all(r.get("event.module") == "pam" and r.get("host.name") == "pam-1.vt.local" for r in kq),
           f"PAM: {len(kq)}/{len(PAM)} dòng syslog RFC 5424 qua receiver syslog/pam → event.module pam, host.name")
        hd = {r.get("event.action"): r for r in kq}
        yc = hd.get("pam.yeu-cau", {})
        ok(yc.get("user.name") == "nguoi.van.hanh" and yc.get("pam", {}).get("request_id") == "YC-1"
           and yc.get("event.category") == ["iam"] and yc.get("event.type") == ["creation"]
           and str(yc.get("@timestamp")).startswith("2026-10-23T01:00:00") and str(yc.get("@timestamp")).endswith("Z")
           and yc.get("message") == "Yêu cầu quyền",
           "PAM mở yêu cầu → user.name, pam.request_id, iam/creation, @timestamp theo thời điểm syslog, message là phần thông điệp")
        ok(hd.get("pam.duyet", {}).get("related.user") == ["duyet.a", "duyet.b"] and hd["pam.duyet"].get("event.type") == ["allowed"],
           "PAM duyệt → related.user là danh sách người duyệt, event.type allowed")
        ok(hd.get("pam.tu-choi", {}).get("event.type") == ["denied"] and hd["pam.tu-choi"].get("event.outcome") == "failure",
           "PAM từ chối → denied, event.outcome failure")
        cq = hd.get("pam.cap-quyen", {})
        ok(cq.get("user.target.name") == "debug-app" and cq.get("orchestrator.namespace") == "core"
           and cq.get("event.end") == "2026-10-23T03:02:00Z" and cq.get("event.type") == ["change"],
           "PAM cấp quyền → user.target.name, orchestrator.namespace, event.end, change")
        ph = hd.get("pam.phien-bat-dau", {})
        ok(ph.get("session.id") == "ph-7" and ph.get("destination.address") == "pg-core-rw.data-pg.svc:5432"
           and ph.get("url.full", "").endswith("/recordings/r-7") and ph.get("event.type") == ["start"] and ph.get("event.category") == ["session"],
           "PAM bắt đầu phiên → session.id, destination.address, url.full bản ghi hình, session/start")
        ln = hd.get("pam.lenh-nhay-cam", {})
        ok(ln.get("event.kind") == "alert" and ln.get("process.command_line") == "DROP TABLE task.giao_viec",
           "PAM lệnh nhạy cảm → event.kind alert, process.command_line")
        ok(hd.get("pam.thu-hoi", {}).get("event.type") == ["deletion"], "PAM thu hồi → event.type deletion")
        pk = hd.get("pam.pha-kinh", {})
        ok(pk.get("vt.phat_hien") == "pha-kinh" and pk.get("related.user") == ["duyet.a", "duyet.b"] and pk.get("user.name") == "truc.ca",
           "PAM phá kính → thẻ pha-kinh, người duyệt, người dùng")
        kh = [r for r in kq if r.get("message") == "Break-glass session closed"]
        ok(len(kh) == 1 and kh[0].get("vt.phat_hien") == "pha-kinh" and "event.action" not in kh[0] and "user.name" not in kh[0],
           "dòng PAM không có SD vtpam@32473 vẫn vào SIEM, thẻ pha-kinh theo thông điệp, không bịa event.action")
finally:
    shutil.rmtree(tmp, ignore_errors=True)
sys.exit(1 if loi else 0)
