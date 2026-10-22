"""Kiểm bộ sinh danh mục Kafka (INF-10) và bản render của chart với hợp đồng sự kiện."""
import os
import shutil
import subprocess
import sys

import pytest
import yaml

CHART = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, CHART)
import sinh_danh_muc as sdm  # noqa: E402

DM = sdm.sinh()
CAT = sdm.doc(os.path.join(sdm.EVENTS, "catalog.yaml"))


def render(*args):
    if not shutil.which("helm"):
        pytest.skip("không có helm")
    out = subprocess.run(["helm", "template", "t", CHART, *args], check=True, capture_output=True, text=True).stdout
    return [d for d in yaml.safe_load_all(out) if d]


def test_du_26_evt_va_topic_ky_thuat():
    ma = {t["ma"] for t in DM["topics"] if "ma" in t}
    assert ma == {f"EVT-{i:02d}" for i in range(1, 27)}
    ten = {t["ten"] for t in DM["topics"]}
    assert {e["topic"] for e in CAT["technicalTopics"]} <= ten


def test_nhom_theo_tl04():
    nhom = {t["ten"]: t["nhom"] for t in DM["topics"]}
    assert nhom["sys.ledger.append.v1"] == "soCai"
    assert nhom["sys.audit.v1"] == "heThong"
    assert nhom["task.task.progressed.v1"] == "nghiepVuCao"
    assert nhom["policy.pack.published.v1"] == "nghiepVuThap"
    # Sự kiện scoring-service tiêu thụ (không thuộc nhóm nêu tên) vào nhóm đầu vào tính điểm.
    dat_ten = sdm.NGHIEP_VU_CAO | sdm.NGHIEP_VU_THAP | sdm.SO_CAI | sdm.HE_THONG
    for e in CAT["events"]:
        if "SVC-07" in e["consumers"] and e["topic"] not in dat_ten:
            assert nhom[e["topic"]] == "dauVaoTinhDiem", e["topic"]
    assert any(n == "dauVaoTinhDiem" for n in nhom.values())


def test_sec57_chi_fluentbit_va_audit_writer_ghi_audit():
    ghi = {u for u, x in DM["nguoiDung"].items() if "sys.audit.v1" in x["ghi"]}
    assert ghi == {"fluent-bit", "audit-writer"}


def test_connect_ghi_moi_topic_outbox():
    ghi = set(DM["nguoiDung"]["kafka-connect"]["ghi"])
    for e in CAT["events"]:
        if e.get("outbox"):
            assert e["topic"] in ghi, e["topic"]


def test_moi_ben_tieu_thu_co_cap_retry_dlq():
    cap = {(c["topic"], c["consumer"]) for c in DM["capLoi"]}
    for u, x in DM["nguoiDung"].items():
        for t in x["doc"]:
            assert (t, u) in cap


def test_avro_tham_chieu_dang_ky_truoc():
    vi_tri = {a["artifactId"]: i for i, a in enumerate(DM["avro"])}
    for a in DM["avro"]:
        for t in a.get("thamChieu", []):
            assert vi_tri[t["artifactId"]] < vi_tri[a["artifactId"]]
    assert len(DM["avro"]) == len(CAT["events"]) + len(CAT["technicalTopics"])


def test_render_topic_va_acl():
    docs = render()
    topics = {d["spec"].get("topicName", d["metadata"]["name"]): d for d in docs if d["kind"] == "KafkaTopic"}
    for t in DM["topics"]:
        assert t["ten"] in topics
    assert topics["task.task.progressed.v1"]["spec"]["partitions"] == 24
    assert topics["scoring.score.computed.v1"]["spec"]["replicas"] == 3
    org = topics["org.unit.changed.v1"]["spec"]["config"]
    # Nén theo khoá và vẫn xoá sau 30 ngày (TL-03 "Cấu hình topic").
    assert org["cleanup.policy"] == "compact,delete" and int(org["retention.ms"]) == 30 * 86400000
    users = {d["metadata"]["name"]: d for d in docs if d["kind"] == "KafkaUser"}
    audit = [u for u, d in users.items() for a in d["spec"]["authorization"]["acls"]
             if a["resource"]["name"] == "sys.audit.v1" and "Write" in a["operations"]]
    assert sorted(audit) == ["audit-writer", "fluent-bit"]


def test_render_compact_gop_cum_va_ket_hop():
    docs = render("--set", "global.sizing=compact", "--set", "cheDo=ket-hop")
    con = {d["metadata"]["name"]: d for d in docs if d["kind"] == "KafkaConnector"}
    assert all(c["spec"]["config"]["database.hostname"].split("-rw")[0] in ("pg-core", "pg-ledger") for c in con.values())
    pools = [d for d in docs if d["kind"] == "KafkaNodePool"]
    assert len(pools) == 1 and sorted(pools[0]["spec"]["roles"]) == ["broker", "controller"]


def test_anh_khong_ghim_digest_bi_chan():
    if not shutil.which("helm"):
        pytest.skip("không có helm")
    r = subprocess.run(["helm", "template", "t", CHART, "--set", "connect.image.digest=latest"], capture_output=True, text=True)
    assert r.returncode != 0 and "SEC-55" in r.stderr


def test_schema_outbox_chua_khai_bao_bi_chan():
    if not shutil.which("helm"):
        pytest.skip("không có helm")
    r = subprocess.run(["helm", "template", "t", CHART, "--set", "connect.viTriSchema.task=null"], capture_output=True, text=True)
    assert r.returncode != 0 and "viTriSchema" in r.stderr
