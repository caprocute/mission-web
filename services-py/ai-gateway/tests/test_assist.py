"""Kiểm thử không cần Docker của DV-14-04: luật soát KR, trọng số, mô hình giả theo lược đồ, đồng bộ lược đồ hợp
đồng và đầu ra mẫu của hợp đồng khớp lược đồ đã sinh."""

from __future__ import annotations

import json
from typing import Any

import jsonschema
import pytest

from app.assist import review as rv
from app.assist.extract import SCHEMA as EXTRACT_SCHEMA
from app.assist.sync_schemas import OUT, build
from app.llm.fake_json import fake_reply, find_schema, schema_instruction


def test_schemas_json_in_sync_with_contract() -> None:
    assert json.loads(OUT.read_text(encoding="utf-8")) == json.loads(json.dumps(build())), \
        "chạy lại: uv run python -m app.assist.sync_schemas"


@pytest.mark.parametrize(("kr", "rules"), [
    ({"code": "KR 1.3", "title": "Tăng cường công tác kiểm tra, giám sát", "unit": None, "targetValue": None},
     ["BR-02-05"]),
    ({"code": "KR 1.4", "title": "Tổ chức hội nghị sơ kết", "unit": "lần", "baselineValue": 0, "targetValue": 1},
     ["BR-02-06"]),
    ({"code": "KR 1.5", "title": "Kiểm tra 100% chi bộ trực thuộc trước 31/3/2027", "unit": "%", "targetValue": 100},
     []),
    ({"code": "KR 1.6", "title": "Đẩy mạnh chuyển đổi số", "unit": "%", "targetValue": 80}, []),
    ({"code": "KR 1.7", "title": "Triển khai phần mềm", "unit": None, "targetValue": None}, ["BR-02-05", "BR-02-06"]),
])
def test_review_rules(kr: dict[str, Any], rules: list[str]) -> None:
    got = rv.review(kr["code"], kr, rv.Policy())
    assert [f.rule for f in got] == rules
    if rules[:1] == ["BR-02-05"] and "Tăng" in kr["title"]:
        assert got[0].message == 'KPI mơ hồ: "tăng cường" không đo được. Vui lòng bổ sung chỉ tiêu và đơn vị đo.'
        assert got[0].severity == "LOI" and got[0].msg == "MSG-02-05"


def test_policy_from_compose_context() -> None:
    pol = rv.Policy.from_context({"policy": {"vagueWords": ["chăm lo"], "taskVerbs": ["soạn"]}})
    assert pol.vague == ("chăm lo",) and pol.task_verbs == ("soạn",)
    assert [f.rule for f in rv.review("KR 1", {"title": "Chăm lo đời sống"}, pol)] == ["BR-02-05"]
    assert rv.Policy.from_context(None) == rv.Policy()


def test_rewrite_filter_and_similarity() -> None:
    pol = rv.Policy()
    assert rv.acceptable_rewrite("Kiểm tra 18 chi bộ trong quý I/2027", pol)
    assert not rv.acceptable_rewrite("Tăng cường kiểm tra 18 chi bộ", pol)
    assert not rv.acceptable_rewrite("Kiểm tra các chi bộ", pol)
    assert rv.similarity("Tỷ lệ hồ sơ giải quyết đúng hạn đạt 98%", "Tỷ lệ hồ sơ giải quyết đúng hạn 95%") > 0.5
    assert rv.similarity("Tỷ lệ hồ sơ đúng hạn", "Kết nạp đảng viên mới") < 0.2


@pytest.mark.parametrize("weights", [[None, None, None], [50, 30, 30], [1, 1, 1, 1, 1, 1, 1], [0.1, 0, 99]])
def test_weights_sum_to_100(weights: list[Any]) -> None:
    krs = [{"title": f"KR {i}", "weight": w} for i, w in enumerate(weights)]
    rv.normalize_weights(krs)
    assert sum(k["weight"] for k in krs) == 100 and all(isinstance(k["weight"], int) for k in krs)


def test_fake_llm_follows_schema_and_cites_only_with_context() -> None:
    prompt = "x\n" + schema_instruction(EXTRACT_SCHEMA) + "\n[#1] tài liệu"
    assert find_schema(prompt) == EXTRACT_SCHEMA
    out = json.loads(fake_reply(prompt, "abc"))
    jsonschema.validate(out, EXTRACT_SCHEMA, format_checker=jsonschema.FormatChecker())
    obj = {"type": "object", "properties": {"sources": {"type": "array", "items": {"type": "string"}}}}
    assert json.loads(fake_reply(schema_instruction(obj) + " [#1]", "d"))["sources"] == ["[#1]"]
    assert json.loads(fake_reply(schema_instruction(obj), "d"))["sources"] == ["mẫu d0"]
    assert json.loads(fake_reply("không có lược đồ", "d")) == {"answer": "Nội dung gợi ý mẫu d"}
