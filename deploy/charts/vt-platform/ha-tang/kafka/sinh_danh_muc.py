#!/usr/bin/env python3
"""Sinh danh mục topic, người dùng Kafka và nguồn outbox cho chart kafka (INF-10) từ hợp đồng sự kiện.

Đầu vào: contracts/events/catalog.yaml (EVT-01..EVT-26, topic kỹ thuật), contracts/events/outbox-sources.yaml
(bảng outbox theo schema), contracts/events/avro/**. Đầu ra trong thư mục chart:
  files/danh-muc.yaml  topic theo nhóm TL-04 mục 4.6, cặp <topic>.retry/.dlq.<consumer>, ACL theo người dùng,
                       schema có outbox; Helm đọc bằng .Files.Get.
  files/avro/**        bản sao lược đồ Avro để Job đăng ký vào Apicurio (chart không đọc được ngoài thư mục).
Chạy lại sau mỗi lần sửa contracts/events; tests/chay-kiem-thu.sh báo lỗi khi đầu ra lệch hợp đồng (--kiem).

Dùng: sinh_danh_muc.py [--kiem]
"""
import argparse
import filecmp
import json
import os
import shutil
import sys

import yaml

CHART = os.path.dirname(os.path.abspath(__file__))
GOC = os.path.abspath(os.path.join(CHART, "..", "..", "..", "..", ".."))
EVENTS = os.path.join(GOC, "contracts", "events")

# Nhóm topic TL-04 mục 4.6. Ví dụ TL-04 nêu tên được xếp trước; topic EVT còn lại có scoring-service (SVC-07) tiêu
# thụ vào nhóm đầu vào tính điểm, còn lại vào nhóm nghiệp vụ cao. Nhóm "thấp" thêm hai topic hiếm (suy luận).
NGHIEP_VU_CAO = {"task.task.progressed.v1", "task.task.statuschanged.v1", "okr.keyresult.checkedin.v1"}
NGHIEP_VU_THAP = {"policy.pack.published.v1", "oversight.complaint.filed.v1",
                  "config.uipack.published.v1", "ledger.seal.created.v1"}
SO_CAI = {"sys.ledger.append.v1"}
HE_THONG = {"sys.audit.v1", "iam.auth.event.v1", "sys.evidence.object.stored.v1", "sys.tenant.provisioned.v1",
            "evidence.object.scanned.v1"} | {"sys.security.runtime.v1", "sys.siem.v1"}

# Topic hạ tầng an ninh không nằm trong hợp đồng sự kiện (mang JSON/OTLP, không có lược đồ Avro, không đăng ký
# Apicurio): TL-04 mục 10.3 (SEC-50, cảnh báo Falco qua Falcosidekick vào sys.security.runtime.v1) và SEC-43 (luồng
# nhật ký an ninh chuẩn hoá ECS tới SIEM qua sys.siem.v1). TL-04 không nêu số phân vùng và thời gian giữ; xếp vào nhóm
# hệ thống cùng sys.audit.v1 (suy luận). Bên tiêu thụ: otel-siem đọc sys.security.runtime.v1, chặng ra SOC
# (siem-soc-forwarder, cùng tài khoản siem-forwarder) đọc sys.siem.v1 (INF-19).
TOPIC_AN_NINH = [
    {"topic": "sys.security.runtime.v1", "consumers": ["SIEM"]},
    {"topic": "sys.siem.v1", "consumers": ["SIEM"]},
]

# Mã bên tiêu thụ trong catalog → tài khoản Kafka (cũng là nhóm tiêu thụ và hậu tố <consumer>). Module của
# mission-core dùng chung một tài khoản; bên ngoài cell (analytics-national qua MirrorMaker, INF-18) bỏ qua.
TAI_KHOAN = {
    "SVC-01": "api-gateway", "SVC-02": "iam", "SVC-04": "org-service", "SVC-05": "mission-core",
    "SVC-06": "workflow-service", "SVC-07": "scoring-service", "SVC-08": "evidence-service",
    "SVC-09": "ledger-service", "SVC-10": "esign-service", "SVC-11": "document-service",
    "SVC-12": "notification-service", "SVC-13": "integration-gateway", "SVC-14": "ai-gateway",
    "SVC-15": "analytics", "SVC-16": "search", "SVC-17": "web-frontend",
    "org_replica": "mission-core", "audit-archiver": "audit-archiver", "SIEM": "siem-forwarder",
}
NGOAI_CELL = {"analytics-national", "SVC-03"}
# Bên tiêu thụ không có tài khoản Kafka riêng: OPA chạy cùng pod với service (TL-04, SEC-57) và nhận gói chính sách
# qua bundle, không đọc topic (suy luận; EVT-27 ghi OPA là bên tiêu thụ, CORE-08-16).
KHONG_TAI_KHOAN = {"OPA"}

# Topic bên phát ghi thẳng (không qua outbox, catalog "nonOutboxTopics" và TL-04 mục 10.3 cho sys.audit.v1).
GHI_TRUC_TIEP = {
    "iam": ["iam.auth.event.v1"],
    "ledger-service": ["sys.ledger.append.v1"],
    "org-service": ["org.bootstrap.v1"],
    "analytics": ["analytics.anomaly.detected.v1", "agg.province.metric.v1"],
    # SEC-57, TL-04 mục 10.3: chỉ Fluent Bit và tài khoản audit-writer được ghi sys.audit.v1.
    "fluent-bit": ["sys.audit.v1"],
    "audit-writer": ["sys.audit.v1"],
    # INF-19: otel-siem ghi cảnh báo Falco đã chuẩn hoá và luồng ECS cho SIEM.
    "siem-forwarder": ["sys.security.runtime.v1", "sys.siem.v1"],
}
CHI_GHI_KIEM_TOAN = {"fluent-bit", "audit-writer"}


def doc(p):
    with open(p, encoding="utf-8") as f:
        return yaml.safe_load(f)


def tai_khoan(ma):
    if ma.startswith("MOD-"):
        return "mission-core"
    if ma in NGOAI_CELL or ma in KHONG_TAI_KHOAN:
        return None
    if ma not in TAI_KHOAN:
        raise SystemExit(f"bên tiêu thụ chưa ánh xạ tài khoản Kafka: {ma}")
    return TAI_KHOAN[ma]


def nhom(topic, ben_tieu_thu, ky_thuat):
    if topic in SO_CAI:
        return "soCai"
    if topic in HE_THONG:
        return "heThong"
    if ky_thuat:
        return "theoDanhMuc"
    if topic in NGHIEP_VU_CAO:
        return "nghiepVuCao"
    if topic in NGHIEP_VU_THAP:
        return "nghiepVuThap"
    if "SVC-07" in ben_tieu_thu:
        return "dauVaoTinhDiem"
    return "nghiepVuCao"


def sinh():
    cat = doc(os.path.join(EVENTS, "catalog.yaml"))
    src = doc(os.path.join(EVENTS, "outbox-sources.yaml"))
    topics, cap_loi, doc_quyen = [], [], {}
    for e in cat["events"] + cat["technicalTopics"] + TOPIC_AN_NINH:
        ky_thuat = "code" not in e
        t = {"ten": e["topic"], "nhom": nhom(e["topic"], e["consumers"], ky_thuat)}
        if "code" in e:
            t["ma"] = e["code"]
        if ky_thuat and "partitions" in e:
            t["phanVung"] = e["partitions"]
            t["luuGiu"] = e["retention"]
        if e["topic"].startswith("org.") and not ky_thuat:
            # Topic trạng thái tổ chức (ECST): nén theo khoá và giữ 30 ngày (TL-01, TL-03 "Cấu hình topic").
            t["nenTheoKhoa"] = True
        topics.append(t)
        for tk in sorted({tai_khoan(m) for m in e["consumers"]} - {None}):
            doc_quyen.setdefault(tk, set()).add(e["topic"])
            cap_loi.append({"topic": e["topic"], "consumer": tk})

    # Connect ghi mọi topic mà bảng outbox được phép phát, trừ sys.audit.v1 (SEC-57).
    ghi_connect = set(src.get("wildcardTopics", []))
    schema = []
    for s in src["sources"]:
        ghi_connect |= set(s["topics"])
        schema.append({"schema": s["schema"], "bang": s["outboxTable"]})
    ghi_connect -= {"sys.audit.v1"}

    nguoi_dung = {"kafka-connect": {"ghi": sorted(ghi_connect)}}
    for tk, ts in GHI_TRUC_TIEP.items():
        nguoi_dung.setdefault(tk, {})["ghi"] = sorted(ts)
    for tk, ts in doc_quyen.items():
        nguoi_dung.setdefault(tk, {})["doc"] = sorted(ts)
    for tk in nguoi_dung.values():
        tk.setdefault("doc", [])
        tk.setdefault("ghi", [])
    nguoi_dung["dlq-replayer"] = {"doc": [], "ghi": [], "hoLoi": True}
    return {
        "nguon": "contracts/events/catalog.yaml, contracts/events/outbox-sources.yaml (sinh bằng sinh_danh_muc.py)",
        "tuongThich": cat["compatibility"],
        "topics": topics,
        "capLoi": sorted(cap_loi, key=lambda x: (x["topic"], x["consumer"])),
        "nguoiDung": dict(sorted(nguoi_dung.items())),
        "schemaOutbox": schema,
        "avro": danh_sach_avro(cat["events"] + cat["technicalTopics"]),
    }


def ten_kieu(sch):
    """{tên đầy đủ: True} các record, enum, fixed định nghĩa trong một lược đồ, và tập tên kiểu được dùng."""
    dinh_nghia, dung = set(), set()
    nguyen_thuy = {"null", "boolean", "int", "long", "float", "double", "bytes", "string",
                   "record", "enum", "fixed", "array", "map"}

    def duyet(x, ns=None):
        if isinstance(x, dict):
            if x.get("type") in ("record", "enum", "fixed"):
                ns = x.get("namespace", ns)
                dinh_nghia.add(f"{ns}.{x['name']}" if ns and "." not in x["name"] else x["name"])
            for k, v in x.items():
                # Ký hiệu enum, bí danh, giá trị mặc định là dữ liệu, không phải tên kiểu.
                if k in ("symbols", "aliases", "default", "doc"):
                    continue
                if k in ("type", "items", "values") and isinstance(v, str) and v not in nguyen_thuy:
                    dung.add(v if "." in v or not ns else f"{ns}.{v}")
                duyet(v, ns)
        elif isinstance(x, list):
            for i in x:
                if isinstance(i, str) and i not in nguyen_thuy:
                    dung.add(i if "." in i or not ns else f"{ns}.{i}")
                duyet(i, ns)
    duyet(sch)
    return dinh_nghia, dung - dinh_nghia


def danh_sach_avro(evts):
    """Artifact Apicurio (nhóm default, TopicIdStrategy) theo thứ tự đăng ký: lược đồ được tham chiếu đứng trước
    lược đồ tham chiếu nó (OrgBootstrap dùng kiểu của ba sự kiện org.*)."""
    ds, chu = {}, {}
    for e in evts:
        with open(os.path.join(EVENTS, e["schema"]), encoding="utf-8") as f:
            dn, ngoai = ten_kieu(json.load(f))
        ds[e["artifactId"]] = {"artifactId": e["artifactId"], "tep": e["schema"], "ngoai": sorted(ngoai)}
        for t in dn:
            chu[t] = e["artifactId"]
    kq, xong = [], set()

    def them(a, dang=()):
        if a in xong:
            return
        if a in dang:
            raise SystemExit(f"tham chiếu vòng giữa lược đồ Avro: {a}")
        for t in ds[a]["ngoai"]:
            if t not in chu:
                raise SystemExit(f"{ds[a]['tep']}: kiểu {t} không có trong lược đồ nào của danh mục")
            them(chu[t], dang + (a,))
        x = {"artifactId": a, "tep": ds[a]["tep"]}
        if ds[a]["ngoai"]:
            x["thamChieu"] = [{"ten": t, "artifactId": chu[t]} for t in ds[a]["ngoai"]]
        kq.append(x)
        xong.add(a)
    for a in sorted(ds):
        them(a)
    return kq


def ghi(dich):
    os.makedirs(os.path.join(dich, "files"), exist_ok=True)
    d = sinh()
    with open(os.path.join(dich, "files", "danh-muc.yaml"), "w", encoding="utf-8") as f:
        f.write("# Sinh tự động bằng sinh_danh_muc.py từ contracts/events; không sửa tay.\n")
        yaml.safe_dump(d, f, allow_unicode=True, sort_keys=False, width=120)
    av = os.path.join(dich, "files", "avro")
    if os.path.isdir(av):
        shutil.rmtree(av)
    shutil.copytree(os.path.join(EVENTS, "avro"), av)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kiem", action="store_true", help="chỉ kiểm đầu ra trong chart khớp hợp đồng")
    a = ap.parse_args()
    if not a.kiem:
        ghi(CHART)
        return 0
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        ghi(tmp)
        lech = []
        if not filecmp.cmp(os.path.join(tmp, "files", "danh-muc.yaml"),
                           os.path.join(CHART, "files", "danh-muc.yaml"), shallow=False):
            lech.append("files/danh-muc.yaml")
        so = filecmp.dircmp(os.path.join(tmp, "files", "avro"), os.path.join(CHART, "files", "avro"))

        def duyet(c, p=""):
            lech.extend(p + x for x in c.left_only + c.right_only + c.diff_files)
            for k, v in c.subdirs.items():
                duyet(v, p + k + "/")
        duyet(so, "files/avro/")
    for x in lech:
        print("lệch hợp đồng:", x)
    return 1 if lech else 0


if __name__ == "__main__":
    sys.exit(main())
