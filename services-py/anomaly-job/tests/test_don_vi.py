"""Kiểm thử không cần Docker của job bất thường (DV-15-07): event_id, bộ ngưỡng, mã hoá EVT-20, cấu hình."""

from __future__ import annotations

import io
import json

import fastavro
import pytest

from app.config import REPO_SCHEMA, ConfigError, Settings
from app.producer import Evt20Codec, PublishError, event_id
from app.rules import RULES
from app.thresholds import ThresholdError, ThresholdSet

ROW = {"tenant_id": "0b5c1a9e-1111-4222-8333-944455556666", "alert_key": "abc123", "signal_code": "CAO_BANG",
       "severity": "VUA", "subject_type": "DON_VI", "subject_id": "11111111-2222-4333-8444-555555555555",
       "evidence_chain": '["dieu_kien:CUNG_MOT_MUC","appraisal_profile:11111111-2222-4333-8444-666666666666"]',
       "model_version": "luat-2026.10.1;nguong-v3"}


def test_event_id_matches_vtplatform_and_is_stable() -> None:
    # Giá trị tính bằng vtplatform.kafka.deterministic_event_id_from_alert_key cho cùng đầu vào.
    assert event_id("0B5C1A9E-1111-4222-8333-944455556666", " abc123 ") == "66ef2863-bc54-5602-bed5-b218f88db08c"
    assert event_id(ROW["tenant_id"], "abc123") == event_id(ROW["tenant_id"], "abc123")
    assert event_id(ROW["tenant_id"], "abc124") != event_id(ROW["tenant_id"], "abc123")


def test_threshold_defaults_and_errors() -> None:
    ts = ThresholdSet.parse({"version": 3, "rowVersion": 7, "signals": [
        {"signalCode": "TU_CHAM_CAO", "enabled": True, "params": {"scoreGap": 8}},
        {"signalCode": "CAO_BANG", "enabled": False}]})
    s = ts.signals["TU_CHAM_CAO"]
    assert (s.param("scoreGap"), s.param("consecutiveQuarters"), s.param("scoreQuarters")) == (8.0, 3.0, 2.0)
    assert ts.version == 3 and not ts.signals["CAO_BANG"].enabled
    params = {r.signal_code: r.params(ts.signals[r.signal_code]) for r in RULES}
    assert params["TU_CHAM_CAO"] == {"consecutive": 3, "gap": 8.0, "score_quarters": 2}
    assert params["CAO_BANG"]["min_people"] == 5
    with pytest.raises(ThresholdError):
        ThresholdSet.parse({"signals": []})
    with pytest.raises(ThresholdError):
        ThresholdSet.parse({"version": 1, "signals": [{"signalCode": "X", "enabled": True, "params": {"a": "b"}}]})


def test_codec_round_trip_and_rejects_off_contract_rows() -> None:
    codec = Evt20Codec(REPO_SCHEMA)
    raw = codec.encode(ROW)
    rec = fastavro.schemaless_reader(io.BytesIO(raw), codec.schema, None)
    assert rec["alertKey"] == "abc123" and str(rec["subjectId"]) == ROW["subject_id"]
    assert json.loads(rec["evidenceChain"])[0] == "dieu_kien:CUNG_MOT_MUC"
    with pytest.raises(PublishError):
        codec.encode({**ROW, "severity": "MEDIUM"})
    with pytest.raises(PublishError):
        codec.encode({**ROW, "evidence_chain": '{"ten": "Nguyễn Văn A"}'})


def test_settings_require_secrets_from_env() -> None:
    env = {"ANALYTICS_CELL": "dev", "CLICKHOUSE_URL": "http://ch:8123", "CLICKHOUSE_PASSWORD": "x",
           "KAFKA_BOOTSTRAP_SERVERS": "k:9092", "REGISTRY_URL": "http://r/apis/registry/v3/",
           "CONFIG_API_URL": "http://core", "OIDC_TOKEN_URL": "http://kc/token", "OIDC_CLIENT_ID": "anomaly-job",
           "OIDC_CLIENT_SECRET": "y", "KAFKA_SECURITY_PROTOCOL": "SSL"}
    s = Settings.from_env(env)
    assert s.clickhouse_user == "analytics_dev_anomaly" and s.db == "analytics_dev"
    assert s.registry_url == "http://r/apis/registry/v3" and s.kafka_extra == {"security.protocol": "SSL"}
    with pytest.raises(ConfigError, match="OIDC_CLIENT_SECRET"):
        Settings.from_env({k: v for k, v in env.items() if k != "OIDC_CLIENT_SECRET"})
