#!/usr/bin/env python3
"""Sinh manifest Strimzi cho luồng MirrorMaker 2 phân tích quốc gia của từng cell (DV-15-11).

Mỗi cell một tệp generated/<cell>.yaml gồm:
- KafkaUser `mm2-agg-quoc-gia` trên cụm của cell: mTLS, chỉ Read/Describe/DescribeConfigs trên tiền tố topic `agg.`,
  không quyền ghi, không nhóm tiêu thụ. Dù topicsPattern bị sửa sai, tài khoản này cũng không đọc được topic `cdc.*` hay
  topic nghiệp vụ.
- KafkaMirrorMaker2 `mm2-agg-<cell>-quoc-gia`: nguồn là cụm cell, đích là Kafka kho quốc gia, topicsPattern `agg\\..*`,
  DefaultReplicationPolicy (topic đích mang tiền tố alias cell để kho quốc gia phân biệt cell và không đè nhau), không
  checkpointConnector, không groupsPattern (không sao chép offset nhóm tiêu thụ), emit.offset-syncs.enabled = false nên
  không ghi gì vào cụm cell (khi tắt thì Kafka từ chối mọi khoá offset-syncs.topic.*, kể cả location). Khác luồng DR
  của TL-04 (IdentityReplicationPolicy, đồng bộ offset nhóm).

Dùng: sinh_mm2.py [--values values.yaml] [--out generated] [--check]; --check báo lỗi nếu tệp sinh đã lệch."""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
from typing import Any

import yaml

ROOT = pathlib.Path(__file__).resolve().parent
USER = "mm2-agg-quoc-gia"
HEADER = ("# Sinh bởi analytics/mirrormaker/sinh_mm2.py (DV-15-11). Không sửa tay; sửa values.yaml rồi chạy lại.\n"
          "# Luồng MirrorMaker 2 phân tích quốc gia của {cell}: chỉ họ topic agg.* rời cell,"
          " tách khỏi luồng DR (TL-04).\n")
CELL_RE = re.compile(r"^[a-z][a-z0-9-]{1,30}$")


def labels(cell: str) -> dict[str, str]:
    return {"app.kubernetes.io/part-of": "mission-analytics", "app.kubernetes.io/component": "mm2-phan-tich-quoc-gia",
            "mission.vn/cell": cell}


def kafka_user(v: dict[str, Any], cell: str) -> dict[str, Any]:
    topic = {"type": "topic", "name": "agg.", "patternType": "prefix"}
    return {
        "apiVersion": "kafka.strimzi.io/v1",
        "kind": "KafkaUser",
        "metadata": {"name": USER, "namespace": v["namespace"],
                     "labels": {"strimzi.io/cluster": v["source"]["cluster"], **labels(cell)}},
        "spec": {
            "authentication": {"type": "tls"},
            "authorization": {"type": "simple", "acls": [
                {"resource": topic, "operations": ["Read", "Describe", "DescribeConfigs"]},
            ]},
        },
    }


def mirror_maker(v: dict[str, Any], cell: str) -> dict[str, Any]:
    t, s = v["target"], v["source"]
    group = f"mm2-agg-{cell}"
    rf = str(t["replicationFactor"])
    policy = "org.apache.kafka.connect.mirror.DefaultReplicationPolicy"
    return {
        "apiVersion": "kafka.strimzi.io/v1",
        "kind": "KafkaMirrorMaker2",
        "metadata": {"name": f"mm2-agg-{cell}-quoc-gia", "namespace": v["namespace"], "labels": labels(cell)},
        "spec": {
            "version": v["kafkaVersion"],
            "replicas": v["replicas"],
            "target": {
                "alias": t["alias"],
                "bootstrapServers": t["bootstrapServers"],
                "groupId": group,
                "configStorageTopic": f"{group}.configs",
                "offsetStorageTopic": f"{group}.offsets",
                "statusStorageTopic": f"{group}.status",
                "tls": {"trustedCertificates": [{"secretName": t["caSecret"], "pattern": "*.crt"}]},
                "authentication": {"type": "tls", "certificateAndKey": {
                    "secretName": t["userSecret"], "certificate": "user.crt", "key": "user.key"}},
                "config": {"config.storage.replication.factor": rf, "offset.storage.replication.factor": rf,
                           "status.storage.replication.factor": rf},
            },
            "mirrors": [{
                "source": {
                    "alias": cell,
                    "bootstrapServers": s["bootstrapServers"],
                    "tls": {"trustedCertificates": [{"secretName": s["caSecret"], "pattern": "*.crt"}]},
                    "authentication": {"type": "tls", "certificateAndKey": {
                        "secretName": USER, "certificate": "user.crt", "key": "user.key"}},
                },
                "topicsPattern": v["topicsPattern"],
                "topicsExcludePattern": v["topicsExcludePattern"],
                "sourceConnector": {
                    "tasksMax": v["tasksMax"],
                    "config": {
                        "replication.policy.class": policy,
                        "replication.policy.separator": ".",
                        "replication.factor": rf,
                        "emit.offset-syncs.enabled": "false",
                        "sync.topic.acls.enabled": "false",
                        "sync.topic.configs.enabled": "false",
                        "refresh.topics.interval.seconds": "60",
                    },
                },
            }],
            "resources": v["resources"],
        },
    }


def render(v: dict[str, Any], cell: str) -> str:
    if not CELL_RE.match(cell):
        raise SystemExit(f"tên cell không hợp lệ: {cell!r}")
    docs = [kafka_user(v, cell), mirror_maker(v, cell)]
    return HEADER.format(cell=cell) + yaml.safe_dump_all(docs, sort_keys=False, allow_unicode=True, width=120)


def dedicated_properties(manifest: dict[str, Any], source_bootstrap: str, target_bootstrap: str) -> str:
    """Cấu hình MirrorMaker 2 chế độ dedicated (connect-mirror-maker.sh) dựng từ đúng manifest đã sinh, thay mTLS bằng
    PLAINTEXT, để kiểm thử tích hợp trên hai cụm Kafka cục bộ dùng chung pattern và cấu hình connector."""
    spec = manifest["spec"]
    m = {**spec["mirrors"][0]}
    # Tệp .properties của Java bỏ dấu '\\' đứng trước ký tự thường: nhân đôi để regex giữ nguyên.
    for k in ("topicsPattern", "topicsExcludePattern"):
        m[k] = m[k].replace("\\", "\\\\")
    src, dst = m["source"]["alias"], spec["target"]["alias"]
    lines = [f"clusters = {src}, {dst}", f"{src}.bootstrap.servers = {source_bootstrap}",
             f"{dst}.bootstrap.servers = {target_bootstrap}", f"{src}->{dst}.enabled = true",
             f"{dst}->{src}.enabled = false", f"{src}->{dst}.topics = {m['topicsPattern']}",
             f"{src}->{dst}.topics.exclude = {m['topicsExcludePattern']}",
             # Không có checkpointConnector trong manifest: không phát checkpoint, không đồng bộ offset nhóm.
             f"{src}->{dst}.emit.checkpoints.enabled = false", f"{src}->{dst}.sync.group.offsets.enabled = false",
             f"{src}->{dst}.emit.heartbeats.enabled = false",
             "replication.factor = 1", "config.storage.replication.factor = 1", "offset.storage.replication.factor = 1",
             "status.storage.replication.factor = 1"]
    for k, val in m["sourceConnector"]["config"].items():
        if not k.endswith("replication.factor"):
            lines.append(f"{src}->{dst}.{k} = {val}")
    return "\n".join(lines) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--values", type=pathlib.Path, default=ROOT / "values.yaml")
    ap.add_argument("--out", type=pathlib.Path, default=ROOT / "generated")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    v = yaml.safe_load(args.values.read_text(encoding="utf-8"))
    stale = []
    for cell in v["cells"]:
        text, path = render(v, cell), args.out / f"{cell}.yaml"
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(path.name)
            continue
        args.out.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        print(path)
    if stale:
        sys.exit(f"tệp sinh đã lệch values.yaml: {', '.join(stale)}; chạy lại sinh_mm2.py")


if __name__ == "__main__":
    main()
