"""Kiểm tra tĩnh manifest MirrorMaker 2 luồng phân tích quốc gia (DV-15-11), không cần Docker:

1. Tệp sinh khớp values.yaml; kubeconform -strict hợp lệ với lược đồ CRD Strimzi 1.2.0 (bỏ qua nếu máy không có).
2. topicsPattern chỉ nhận họ agg.* trong danh mục topic; không topic nghiệp vụ, cdc.*, retry, dlq, replay nào lọt.
3. Không sao chép offset nhóm tiêu thụ (không checkpointConnector, không groupsPattern), mTLS hai chiều, tách khỏi
   luồng DR của TL-04 (tên, tài khoản, chính sách nhân bản khác).
4. KafkaUser của luồng chỉ đọc tiền tố topic agg., không ghi, không nhóm tiêu thụ."""

from __future__ import annotations

import pathlib
import re
import shutil
import subprocess
import sys

import pytest
import yaml

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
sys.path.insert(0, str(ROOT))

import sinh_mm2  # noqa: E402

VALUES = yaml.safe_load((ROOT / "values.yaml").read_text(encoding="utf-8"))
CATALOG = yaml.safe_load((REPO / "contracts" / "events" / "catalog.yaml").read_text(encoding="utf-8"))
CELLS = VALUES["cells"]


def docs(cell: str) -> dict[str, dict]:
    out = {d["kind"]: d for d in yaml.safe_load_all((ROOT / "generated" / f"{cell}.yaml").read_text(encoding="utf-8"))}
    assert set(out) == {"KafkaUser", "KafkaMirrorMaker2"}
    return out


def mirrored(m: dict, topic: str) -> bool:
    """Cách MirrorSourceConnector chọn topic: khớp trọn topicsPattern và không khớp topicsExcludePattern."""
    return bool(re.fullmatch(m["topicsPattern"], topic)) and not re.fullmatch(m["topicsExcludePattern"], topic)


def test_generated_is_up_to_date() -> None:
    r = subprocess.run([sys.executable, str(ROOT / "sinh_mm2.py"), "--check"], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr


@pytest.mark.skipif(not (shutil.which("kubeconform") or (pathlib.Path.home() / "go/bin/kubeconform").exists()),
                    reason="máy không có kubeconform")
def test_kubeconform_strict() -> None:
    bin_ = shutil.which("kubeconform") or str(pathlib.Path.home() / "go/bin/kubeconform")
    files = [str(ROOT / "generated" / f"{c}.yaml") for c in CELLS]
    r = subprocess.run([bin_, "-strict", "-summary", "-kubernetes-version", "1.33.0", "-schema-location", "default",
                        "-schema-location",
                        str(ROOT / "tests/crd-schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"),
                        *files], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
    assert f"Valid: {2 * len(CELLS)}, Invalid: 0, Errors: 0, Skipped: 0" in r.stdout


@pytest.mark.parametrize("cell", CELLS)
def test_only_agg_topics_leave_the_cell(cell: str) -> None:
    m = docs(cell)["KafkaMirrorMaker2"]["spec"]["mirrors"][0]
    assert m["topicsPattern"] == "agg\\..*"
    topics = [t["topic"] for t in CATALOG["events"] + CATALOG["technicalTopics"]]
    assert [t for t in topics if mirrored(m, t)] == ["agg.province.metric.v1"]
    for t in ("cdc.tenant_data.task.task", "cdc.org.org.org_unit", "task.task.assigned.v1", "aggx.secret",
              "x.agg.province.metric.v1", "agg.province.metric.v1.retry.analytics-national",
              "agg.province.metric.v1.dlq.analytics-national", "agg.province.metric.v1.replay.analytics-national"):
        assert not mirrored(m, t), t


@pytest.mark.parametrize("cell", CELLS)
def test_no_consumer_offset_sync_and_mtls(cell: str) -> None:
    spec = docs(cell)["KafkaMirrorMaker2"]["spec"]
    m = spec["mirrors"][0]
    assert "checkpointConnector" not in m and "groupsPattern" not in m
    conf = m["sourceConnector"]["config"]
    assert conf["emit.offset-syncs.enabled"] == "false"
    assert not [k for k in conf if k.startswith("offset-syncs.")]  # Kafka từ chối connector nếu còn khoá này
    assert conf["sync.topic.acls.enabled"] == "false"
    for side in (spec["target"], m["source"]):
        assert side["bootstrapServers"].endswith(":9093")
        assert side["tls"]["trustedCertificates"]
        assert side["authentication"]["type"] == "tls"
        assert side["authentication"]["certificateAndKey"]["secretName"]


@pytest.mark.parametrize("cell", CELLS)
def test_separate_from_dr_flow(cell: str) -> None:
    mm2 = docs(cell)["KafkaMirrorMaker2"]
    conf = mm2["spec"]["mirrors"][0]["sourceConnector"]["config"]
    assert mm2["metadata"]["name"] == f"mm2-agg-{cell}-quoc-gia" != "mm2-hl-to-sn"
    assert conf["replication.policy.class"].endswith(".DefaultReplicationPolicy")
    assert mm2["spec"]["target"]["groupId"] == f"mm2-agg-{cell}"
    assert mm2["spec"]["mirrors"][0]["source"]["alias"] == cell
    secrets = {mm2["spec"]["target"]["authentication"]["certificateAndKey"]["secretName"],
               mm2["spec"]["mirrors"][0]["source"]["authentication"]["certificateAndKey"]["secretName"]}
    assert not secrets & {"mm2-sn-user", "mm2-hl-user"}


@pytest.mark.parametrize("cell", CELLS)
def test_source_user_reads_agg_prefix_only(cell: str) -> None:
    d = docs(cell)
    user = d["KafkaUser"]
    assert user["metadata"]["labels"]["strimzi.io/cluster"] == VALUES["source"]["cluster"]
    assert user["metadata"]["name"] == d["KafkaMirrorMaker2"]["spec"]["mirrors"][0]["source"]["authentication"][
        "certificateAndKey"]["secretName"]
    acls = user["spec"]["authorization"]["acls"]
    assert acls == [{"resource": {"type": "topic", "name": "agg.", "patternType": "prefix"},
                     "operations": ["Read", "Describe", "DescribeConfigs"]}]


def test_dedicated_properties_keep_regex() -> None:
    props = sinh_mm2.dedicated_properties(docs(CELLS[0])["KafkaMirrorMaker2"], "a:1", "b:2")
    assert f"{CELLS[0]}->quoc-gia.topics = agg\\\\..*" in props
    assert f"{CELLS[0]}->quoc-gia.sync.group.offsets.enabled = false" in props
