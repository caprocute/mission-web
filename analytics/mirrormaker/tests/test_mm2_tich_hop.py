"""Kiểm thử tích hợp DV-15-11 trên hai cụm Kafka 3.9 thật và MirrorMaker 2 chế độ dedicated, cấu hình dựng từ đúng
manifest đã sinh: bản ghi agg.* sang cụm đích (mang tiền tố alias cell) trong vài phút; topic cdc.*, topic nghiệp vụ,
hàng đợi dlq của agg.* và offset nhóm tiêu thụ không sang."""

from __future__ import annotations

import os
import pathlib
import socket
import time
from collections.abc import Iterator

import pytest
import yaml

from tests.test_mm2 import CELLS, ROOT, sinh_mm2

pytestmark = pytest.mark.integration
os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")
KAFKA_IMAGE = os.environ.get("KAFKA_IMAGE", "mirror.gcr.io/apache/kafka:3.9.1")
AGG = "agg.province.metric.v1"
KHONG_SANG = ["cdc.tenant_data.task.task", "task.task.assigned.v1", f"{AGG}.dlq.analytics-national"]


def _docker() -> bool:
    try:
        import docker

        docker.from_env().ping()
        return True
    except Exception:
        return False


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def _kafka(net, alias: str, port: int):
    from testcontainers.core.container import DockerContainer

    return (DockerContainer(KAFKA_IMAGE).with_network(net).with_network_aliases(alias).with_bind_ports(9094, port)
            .with_env("KAFKA_NODE_ID", "1").with_env("KAFKA_PROCESS_ROLES", "broker,controller")
            .with_env("KAFKA_LISTENERS", "INTERNAL://:19092,EXTERNAL://:9094,CONTROLLER://:9093")
            .with_env("KAFKA_ADVERTISED_LISTENERS", f"INTERNAL://{alias}:19092,EXTERNAL://127.0.0.1:{port}")
            .with_env("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                      "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT")
            .with_env("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL")
            .with_env("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
            .with_env("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
            .with_env("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .with_env("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .with_env("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .with_env("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0"))


def _wait_topics(admin, timeout: float = 60) -> None:
    deadline = time.monotonic() + timeout
    while True:
        try:
            admin.list_topics(timeout=5)
            return
        except Exception:
            if time.monotonic() > deadline:
                raise
            time.sleep(1)


@pytest.fixture(scope="module")
def hai_cum(tmp_path_factory) -> Iterator[tuple[str, str, object]]:
    if not _docker():
        pytest.skip("kiểm thử tích hợp cần Docker")
    from testcontainers.core.container import DockerContainer
    from testcontainers.core.network import Network

    net = Network()
    net.create()
    p1, p2 = _free_port(), _free_port()
    nguon, dich = _kafka(net, "kafka-nguon", p1), _kafka(net, "kafka-dich", p2)
    nguon.start()
    dich.start()
    mm2 = None
    try:
        manifest = next(d for d in yaml.safe_load_all((ROOT / "generated" / f"{CELLS[0]}.yaml").read_text())
                        if d["kind"] == "KafkaMirrorMaker2")
        props = tmp_path_factory.mktemp("mm2") / "mm2.properties"
        props.write_text(sinh_mm2.dedicated_properties(manifest, "kafka-nguon:19092", "kafka-dich:19092")
                         + f"{CELLS[0]}->quoc-gia.refresh.topics.interval.seconds = 5\n")
        props.chmod(0o644)
        from confluent_kafka.admin import AdminClient, NewTopic

        src = AdminClient({"bootstrap.servers": f"127.0.0.1:{p1}"})
        _wait_topics(src)
        _wait_topics(AdminClient({"bootstrap.servers": f"127.0.0.1:{p2}"}))
        for f in src.create_topics([NewTopic(t, num_partitions=3, replication_factor=1)
                                    for t in [AGG, *KHONG_SANG]]).values():
            f.result(30)
        mm2 = (DockerContainer(KAFKA_IMAGE).with_network(net)
               .with_volume_mapping(str(props), "/tmp/mm2.properties")  # noqa: S108 - đường dẫn trong container
               .with_command("/opt/kafka/bin/connect-mirror-maker.sh /tmp/mm2.properties"))
        mm2.start()
        yield f"127.0.0.1:{p1}", f"127.0.0.1:{p2}", mm2
    finally:
        if mm2 is not None:
            mm2.stop()
        dich.stop()
        nguon.stop()
        net.remove()


def test_only_agg_reaches_national_cluster(hai_cum: tuple[str, str, object]) -> None:
    from confluent_kafka import Consumer, Producer
    from confluent_kafka.admin import AdminClient

    src, dst, mm2 = hai_cum
    p = Producer({"bootstrap.servers": src})
    for t in [AGG, *KHONG_SANG]:
        p.produce(t, key=b"k", value=f"ban-ghi-{t}".encode())
    assert p.flush(30) == 0
    # Một nhóm nghiệp vụ đọc và commit offset ở cụm nguồn; offset này không được sao sang đích.
    g = Consumer({"bootstrap.servers": src, "group.id": "nhom-nghiep-vu", "auto.offset.reset": "earliest",
                  "enable.auto.commit": False})
    g.subscribe([AGG])
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        m = g.poll(1)
        if m is not None and not m.error():
            g.commit(m, asynchronous=False)
            break
    g.close()

    target = f"{CELLS[0]}.{AGG}"
    c = Consumer({"bootstrap.servers": dst, "group.id": "kho-quoc-gia", "auto.offset.reset": "earliest"})
    t0 = time.monotonic()
    got = None
    while time.monotonic() - t0 < 180 and got is None:
        if target in AdminClient({"bootstrap.servers": dst}).list_topics(timeout=5).topics:
            c.subscribe([target])
            m = c.poll(2)
            if m is not None and not m.error():
                got = m.value()
        else:
            time.sleep(2)
    c.close()
    if got != f"ban-ghi-{AGG}".encode():
        out, err = mm2.get_logs()  # type: ignore[attr-defined]
        pathlib.Path(os.environ.get("MM2_LOG", "/dev/null")).write_bytes(out + err)
        pytest.fail("quá 3 phút chưa thấy bản ghi agg ở cụm đích\n" + (out + err).decode(errors="replace")[-3000:])
    time.sleep(10)  # thêm hai chu kỳ làm mới danh sách topic của MirrorMaker 2
    topics = set(AdminClient({"bootstrap.servers": dst}).list_topics(timeout=5).topics)
    leaked = {t for t in topics if any(t.endswith(x) for x in KHONG_SANG)}
    assert not leaked, leaked
    assert not [t for t in topics if "checkpoints" in t], topics
    groups = AdminClient({"bootstrap.servers": dst}).list_consumer_groups().result(timeout=10)
    assert "nhom-nghiep-vu" not in {x.group_id for x in groups.valid}
