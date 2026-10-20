#!/usr/bin/env python3
"""Quản lý và đồng bộ tệp theo dõi tiến độ tổng hợp duy nhất: TIEN-DO-HE-THONG.md.

Cách dùng cho mọi AI agent:
  1. Cập nhật trạng thái một hoặc nhiều task bằng lệnh:
       python3 scripts/cap-nhat-tien-do.py set NEN-011 --status "Xong, chờ gộp" --note "Nhánh feat/NEN-011, mvn verify xanh" --agent "Luồng B1"
       python3 scripts/cap-nhat-tien-do.py set NEN-011 NEN-012 --status "Đã gộp main" --note "Commit abc1234" --agent "Điều phối"
  2. Hoặc sau khi sửa trực tiếp bảng chi tiết trong TIEN-DO-HE-THONG.md, chạy lại:
       python3 scripts/cap-nhat-tien-do.py sync
     để tự động tính lại bảng tổng hợp toàn hệ thống, bảng theo Mốc M0-M7 và tiến độ đường găng.

Các giá trị trạng thái chuẩn:
  - Đã gộp main
  - Xong, chờ gộp
  - Đang làm
  - Làm một phần
  - Chưa bắt đầu
"""
import argparse
import collections
import csv
import datetime
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRACKING_FILE = os.path.join(ROOT, "TIEN-DO-HE-THONG.md")
TSV_FILE = os.path.join(ROOT, "docs/tasks/danh-muc-he-thong.tsv")
WEB_README = os.path.join(ROOT, "docs/tasks/README.md")

WEIGHT = {"S": 0.5, "M": 1.0, "L": 2.0}
VALID_STATUSES = {
    "Đã gộp main",
    "Xong, chờ gộp",
    "Đang làm",
    "Làm một phần",
    "Chưa bắt đầu",
}

GROUP_META = [
    ("WEB", "Phân hệ giao diện Web (`SVC-17`: `WEB-001..065`, `WEB-070..076`)"),
    ("NEN", "Nền móng hệ thống, thư viện nền và CSDL (`NEN-001..043`)"),
    ("CORE", "Ứng dụng lõi `mission-core` (`SVC-05`: `CORE-00..08`)"),
    ("DV", "Các dịch vụ vệ tinh (`SVC-01..04`, `SVC-06..16`: `DV-01..16`)"),
    ("DL", "Dữ liệu nghiệp vụ, Policy Pack, DMN và đối soát (`DL-01..15`)"),
    ("INF", "Hạ tầng Kubernetes, CI/CD, bảo mật và dự phòng (`INF-01..28`)"),
    ("QA", "Kiểm thử tích hợp, hiệu năng, ATTT và DR (`QA-01..16`)"),
    ("MOB", "Ứng dụng di động Flutter (`SVC-18`: `MOB-01..10`)"),
    ("VH", "Vận hành, tài liệu hướng dẫn và đưa vào sử dụng (`VH-01..09`)"),
    ("CNH", "Cá nhân hoá theo người dùng (`CNH-01..05`)"),
]

CRITICAL_PATH = [
    "NEN-001", "NEN-005", "NEN-030", "NEN-031", "NEN-033",
    "NEN-010", "NEN-019", "CORE-00-01", "CORE-00-02", "CORE-05-01",
    "CORE-05-02", "CORE-05-03", "CORE-08-13", "CORE-08-14", "CORE-04-04",
    "CORE-04-05", "CORE-04-07", "CORE-04-08", "CORE-04-10", "QA-06",
    "QA-07", "QA-16", "VH-08",
]


def get_prefix(tid: str) -> str:
    return tid.split("-")[0]


def get_milestone(tid: str) -> str:
    p = get_prefix(tid)
    if p == "NEN" or tid in ("INF-01", "INF-05") or tid.startswith("WEB-00") or tid in (
        "WEB-010", "WEB-011", "WEB-012", "WEB-013", "WEB-014", "WEB-015",
        "WEB-054", "WEB-055", "WEB-056", "WEB-057", "WEB-059", "WEB-060",
        "WEB-063", "WEB-064", "WEB-065",
    ):
        return "M0 – Nền móng phát triển & CSDL"
    if tid.startswith(("DV-01-", "DV-02-", "DV-03-", "DV-04-", "DV-13-01", "DV-13-02", "CORE-00-")) or tid in (
        "WEB-016", "WEB-017", "WEB-018", "WEB-019", "WEB-020", "WEB-058",
    ):
        return "M1 – Định danh và tổ chức (Đợt 1)"
    if tid.startswith(("CORE-08-", "CORE-05-")) or tid in (
        "DL-01", "DL-02", "DL-03", "DL-04", "DL-05", "DL-06", "DL-07",
        "WEB-045", "WEB-046", "WEB-050", "WEB-051", "WEB-061", "WEB-062",
    ) or p == "CNH":
        return "M2 – Cấu hình, Policy Pack & UI Pack"
    if tid.startswith(("CORE-01-", "CORE-02-", "CORE-03-", "DV-06-", "DV-07-", "DV-08-", "DV-09-", "DV-10-", "DV-11-", "DV-12-", "DV-13-")) or tid in (
        "WEB-021", "WEB-022", "WEB-023", "WEB-024", "WEB-025", "WEB-026", "WEB-027",
        "WEB-028", "WEB-029", "WEB-030", "WEB-031", "WEB-032", "WEB-033", "WEB-034",
        "WEB-070",
    ):
        return "M3 – Nghiệp vụ lõi OKR, Giao việc, Minh chứng (Đợt 2)"
    if tid.startswith(("CORE-04-", "CORE-06-", "CORE-07-", "DV-14-", "DV-15-", "DV-16-")) or tid in (
        "WEB-035", "WEB-036", "WEB-037", "WEB-038", "WEB-039", "WEB-040", "WEB-041",
        "WEB-042", "WEB-043", "WEB-044", "WEB-047", "WEB-048", "WEB-049",
        "WEB-071", "WEB-072", "WEB-073", "WEB-074", "WEB-075", "WEB-076",
    ):
        return "M4 – Đánh giá, Hồ sơ số, Giám sát & Phân tích"
    if p == "INF" or tid in (
        "DL-08", "QA-01", "QA-02", "QA-03", "QA-04", "QA-05", "QA-06", "QA-07",
        "QA-08", "QA-09", "QA-10", "QA-11", "QA-12", "QA-13", "WEB-052", "WEB-053",
    ):
        return "M5 – Hạ tầng Production, Tích hợp E2E, Hiệu năng & ATTT"
    if p == "MOB" or tid in (
        "QA-14", "QA-15", "DL-12", "DL-13", "DL-14", "DL-15",
        "VH-01", "VH-02", "VH-03", "VH-04", "VH-06", "VH-07", "VH-09",
    ):
        return "M6 – Dự phòng DR, Ứng dụng Di động & Vận hành"
    return "M7 – Thí điểm và Đưa vào sử dụng (Đợt 3)"


MILESTONE_ORDER = [
    "M0 – Nền móng phát triển & CSDL",
    "M1 – Định danh và tổ chức (Đợt 1)",
    "M2 – Cấu hình, Policy Pack & UI Pack",
    "M3 – Nghiệp vụ lõi OKR, Giao việc, Minh chứng (Đợt 2)",
    "M4 – Đánh giá, Hồ sơ số, Giám sát & Phân tích",
    "M5 – Hạ tầng Production, Tích hợp E2E, Hiệu năng & ATTT",
    "M6 – Dự phòng DR, Ứng dụng Di động & Vận hành",
    "M7 – Thí điểm và Đưa vào sử dụng (Đợt 3)",
]


def discover_cards():
    cards = {}
    for p in glob.glob(os.path.join(ROOT, "docs/tasks/*.md")):
        m = re.match(r"([A-Z]+(?:-\d+)+)-[a-z]", os.path.basename(p))
        if m:
            cards[m.group(1)] = f"docs/tasks/{os.path.basename(p)}"
    return cards


def parse_existing_tracking():
    """Đọc trạng thái, ghi chú và nhật ký từ TIEN-DO-HE-THONG.md nếu đã tồn tại."""
    if not os.path.exists(TRACKING_FILE):
        return {}, [], []
    with open(TRACKING_FILE, encoding="utf-8") as f:
        content = f.read()

    task_state = {}
    # Match rows in section 3: | [ID](...) or ID | Name | Size | Stream | Level | Deps | **Status** | Note |
    row_re = re.compile(
        r"^\|\s*(?:\[([A-Z]+(?:-\d+)+)\]\([^)]+\)|([A-Z]+(?:-\d+)+))\s*\|"
        r"\s*([^|]+)\|\s*([SML])\s*\|\s*([^|]+)\|\s*([^|]+)\|\s*([^|]+)\|\s*(?:\*\*([^*|]+)\*\*|([^|]+))\s*\|\s*([^|]*)\|",
        re.M,
    )
    for m in row_re.finditer(content):
        tid = (m.group(1) or m.group(2)).strip()
        status = (m.group(7) or m.group(8)).strip()
        note = m.group(9).strip()
        task_state[tid] = (status, note)

    # Extract active streams section if customized
    streams_lines = []
    m_stream = re.search(
        r"<!-- ACTIVE_STREAMS_START -->\n(.*?)\n<!-- ACTIVE_STREAMS_END -->",
        content,
        re.S,
    )
    if m_stream:
        streams_lines = m_stream.group(1).strip().splitlines()

    # Extract changelog lines
    log_lines = []
    m_log = re.search(
        r"<!-- PROGRESS_LOG_START -->\n(.*?)\n<!-- PROGRESS_LOG_END -->",
        content,
        re.S,
    )
    if m_log:
        for line in m_log.group(1).strip().splitlines():
            if line.startswith("|") and not line.startswith("| Thời gian") and not line.startswith("|---"):
                log_lines.append(line)

    return task_state, streams_lines, log_lines


def build_initial_state():
    """Khởi tạo trạng thái chuẩn xác của toàn bộ 401 task từ lịch sử git main và các nhánh hiện tại."""
    state = {}

    # 1. WEB-001..065
    web_merged = {
        "WEB-001": "Đã gộp `main` (ESLint, Prettier, Vitest toàn workspace)",
        "WEB-002": "Đã gộp `main` (`Jenkinsfile` đủ cổng chất lượng, gitleaks, Semgrep, Trivy, SBOM)",
        "WEB-003": "Đã gộp `main` (cấu hình ngân sách bundle và Lighthouse CI)",
        "WEB-006": "Đã gộp `main` (OIDC Keycloak, chọn đơn vị/vai trò, step-up MFA)",
        "WEB-007": "Đã gộp `main` (`@vt/api-client`, xử lý lỗi RFC 9457, 409, JobTracker 202)",
        "WEB-008": "Đã gộp `main` (interceptor và fixture dữ liệu giả lập theo module)",
        "WEB-015": "Đã gộp `main` (khung 8 remote micro-frontend, cổng 4202–4209)",
        "WEB-016": "Đã gộp `main` (SCR-0101 Danh sách tenant)",
        "WEB-017": "Đã gộp `main` (SCR-0102 Wizard khởi tạo tenant)",
        "WEB-018": "Đã gộp `main` (SCR-0103 Cây tổ chức có lịch sử hai trục thời gian)",
        "WEB-019": "Đã gộp `main` (SCR-0104 Đồng bộ và đối soát nhân sự)",
        "WEB-021": "Đã gộp `main` (SCR-0201 Cây mục tiêu OKR)",
        "WEB-022": "Đã gộp `main` (SCR-0202 Tạo/sửa O và KR)",
        "WEB-023": "Đã gộp `main` (SCR-0203 Phê duyệt OKR)",
        "WEB-024": "Đã gộp `main` (SCR-0301 Check-in KR)",
        "WEB-025": "Đã gộp `main` (SCR-0401 Thư viện danh mục công việc)",
        "WEB-026": "Đã gộp `main` (SCR-0402 Lập kế hoạch công tác quý)",
        "WEB-027": "Đã gộp `main` (SCR-0403 Phê duyệt kế hoạch, so sánh phiên bản)",
        "WEB-028": "Đã gộp `main` (SCR-0501 Giao việc)",
        "WEB-029": "Đã gộp `main` (SCR-0502 Tạo nhiệm vụ từ văn bản chỉ đạo)",
        "WEB-030": "Đã gộp `main` (SCR-0503 Việc của tôi – hoàn thiện)",
        "WEB-031": "Đã gộp `main` (SCR-0601 Chi tiết nhiệm vụ & SCR-0602 Đề nghị gia hạn)",
        "WEB-032": "Đã gộp `main` (SCR-0701 Nộp minh chứng có băm SHA-256)",
        "WEB-033": "Đã gộp `main` (SCR-0703 Nghiệm thu chất lượng 5 mức)",
        "WEB-034": "Đã gộp `main` (SCR-0801 Quản lý kỳ và tiến trình khoá kỳ)",
        "WEB-035": "Đã gộp `main` (SCR-0901 Phiếu tự đánh giá)",
        "WEB-036": "Đã gộp `main` (SCR-0902 Thẩm định hồ sơ đánh giá)",
        "WEB-037": "Đã gộp `main` (SCR-0903 Phiếu xếp loại có giải thích)",
        "WEB-038": "Đã gộp `main` (SCR-0904 Quyết định xếp loại hàng loạt và ký số)",
        "WEB-039": "Đã gộp `main` (commit `454cbc3` – SCR-1001 Xếp loại năm & SCR-1002 Phân nhóm triển vọng)",
        "WEB-042": "Đã gộp `main` (SCR-1201 Gửi khiếu nại & SCR-1202 Xử lý khiếu nại)",
        "WEB-043": "Đã gộp `main` (SCR-1301 Yêu cầu mở lại kỳ & SCR-1302 Tra cứu sổ cái)",
        "WEB-044": "Đã gộp `main` (SCR-1401 Trung tâm cảnh báo bất thường)",
        "WEB-045": "Đã gộp `main` (commit `aca3442` – SCR-1501 Công thức CEL & SCR-1502 Bảng quyết định DMN)",
        "WEB-046": "Đã gộp `main` (commit `328ef37` – SCR-1503 Chạy test case vàng & SCR-1504 Cấu hình luồng duyệt)",
        "WEB-047": "Đã gộp `main` (SCR-1601 Bảng điều hành lãnh đạo & SCR-1603 Đi xuống tới minh chứng)",
        "WEB-048": "Đã gộp `main` (SCR-1602 Trình dựng dashboard)",
        "WEB-054": "Đã gộp `main` (bảng quyền sinh từ `ma-tran.json`: `can()`, `*vtCan`, `permissionGuard`)",
        "WEB-055": "Đã gộp `main` (áp ma trận phân quyền vào menu, tuyến và nút của shell/remote)",
        "WEB-056": "Đã gộp `main` (đồng bộ realm Keycloak dev với ma trận phân quyền)",
        "WEB-057": "Đã gộp `main` (kiểm thử Playwright đối chiếu ma trận vai trò × tuyến)",
        "WEB-058": "Đã gộp `main` (SCR-0105..0107 Quản trị phân công vai trò và uỷ quyền trên mock)",
        "WEB-059": "Đã gộp `main` (sửa lỗi khả năng truy cập nền, axe 0 lỗi critical/serious, Lighthouse A11y 1.00)",
        "WEB-063": "Đã gộp `main` (bộ hợp đồng OpenAPI 3.1 toàn dự án: 11 tệp, 351 thao tác)",
        "WEB-064": "Đã gộp `main` (sinh kiểu frontend từ `contracts/openapi/`, `npm run api:check`)",
        "WEB-065": "Đã gộp `main` (đối chiếu client kế hoạch SCR-0402, SCR-0403 với hợp đồng OpenAPI)",
    }
    for tid, note in web_merged.items():
        state[tid] = ("Đã gộp main", note)

    state["WEB-010"] = ("Làm một phần", "Đã gộp phần khung trên `main`; ngân sách dựng 50 trường ≤ 50 ms đang ở mức ~95 ms")
    state["WEB-040"] = ("Đang làm", "Nhánh `feat/WEB-040-hoi-nghi-kiem-diem`: đã có model & `DossierApi`, đang làm mock và `fe-dossier`")
    state["WEB-052"] = ("Làm một phần", "Đã gộp pha 1 trên `main`; pha 2 đang ở nhánh `feat/WEB-052-pha-2`")

    # 2. NEN-001..043
    nen_merged = {
        "NEN-001": "Đã gộp `main` (cấu trúc monorepo toàn hệ thống, `AGENTS.md`, `docs/quy-dinh/`, `scripts/agent-task.sh`)",
        "NEN-002": "Đã gộp `main` (commit `c00d4f0` – Wiki backend trang 16 kiến trúc, trang 17 CSDL đa khách thuê)",
        "NEN-003": "Đã gộp `main` (Wiki backend trang 18 sự kiện, trang 19 hạ tầng K8s, trang 20 bảo mật máy chủ)",
        "NEN-004": "Đã gộp `main` (tổng hợp điểm lệch thiết kế vào `docs/DIEM-LECH-KHI-LAP-KE-HOACH.md` và wiki trang 14)",
        "NEN-005": "Đã gộp `main` (khung Maven đa module `backend/`, `platform-bom`, Java 21, Spotless, JaCoCo, ArchUnit)",
        "NEN-006": "Đã gộp `main` (commit `4ab2545` – sinh giao diện máy chủ từ OpenAPI 3.1 và khung kiểm thử hợp đồng)",
        "NEN-007": "Đã gộp `main` (commit `98a320a` – lược đồ Avro cho EVT-01..EVT-26, topic kỹ thuật và kiểm tương thích)",
        "NEN-008": "Đã gộp `main` (commit `0359745`, gộp tại `275bb0f` – đặc tả AsyncAPI 3.0 và kiểm tra chéo Avro)",
        "NEN-009": "Đã gộp `main` (commit `4d80c31` – `vt-platform-starter` nhân `kernel` và lỗi RFC 9457 chuẩn)",
        "NEN-010": "Đã gộp `main` (commit `3af0823` – ngữ cảnh tenant từ JWT, `TenantFilter`, `SET LOCAL app.tenant_id`)",
        "NEN-016": "Đã gộp `main` (`vt-platform-starter`: Resilience4j, OpenTelemetry, log ECS JSON che dữ liệu nhạy cảm)",
        "NEN-017": "Đã gộp `main` (commit `fe40716` – thư viện Go dùng chung `vtplatform`: tenant, RLS, outbox/inbox, RFC 9457, OTel)",
        "NEN-018": "Đã gộp `main` (commit `3b4e808` – thư viện Python dùng chung `vtplatform`: tenant, RLS, RFC 9457, OTel, Kafka)",
        "NEN-021": "Đã gộp `main` (commit `f32a1d7`, gộp tại `275bb0f` – lược đồ Avro cho các topic sự kiện kỹ thuật)",
        "NEN-022": "Đã gộp `main` (commit `3b0f87e` – công cụ phát lại outbox và cấu hình connector Debezium theo schema)",
        "NEN-030": "Đã gộp `main` (quy ước Flyway theo schema, tài khoản `mig_<schema>`, linter kiểm tra migration)",
        "NEN-031": "Đã gộp `main` (vai trò CSDL và quyền mặc định theo schema `svc_`, `ro_`, `mig_`, `cdc_`)",
        "NEN-032": "Đã gộp `main` (bảng dùng chung mẫu: `outbox_event`, `inbox_processed`, `idempotency_key`, `keyring.dek`)",
        "NEN-033": "Đã gộp `main` (mẫu RLS chuẩn, hàm `fn_create_period_partitions`, cấu hình PgBouncer transaction pooling)",
        "NEN-034": "Đã gộp `main` (commit `9e4b6fc`, gộp tại `275bb0f` – kiểm thử chống rò rỉ tenant lớp CSDL, RLS, PgBouncer, Semgrep)",
        "NEN-040": "Đã gộp `main` (commit `653ce97` – gói chính sách OPA Rego sinh từ `ma-tran.json` và bộ kiểm thử `opa test`)",
        "NEN-041": "Đã gộp `main` (commit `e22cb71` – thư viện CEL dùng chung `vt-cel` và bộ ca kiểm thử đối chiếu `cel-js`)",
    }
    for tid, note in nen_merged.items():
        state[tid] = ("Đã gộp main", note)

    state["NEN-011"] = ("Đang làm", "Luồng 1 (nhánh `feat/NEN-011-NEN-012-outbox-inbox`): đang hoàn thiện `OutboxWriter`, `EventEnvelope`, Apicurio")
    state["NEN-012"] = ("Đang làm", "Luồng 1 (nhánh `feat/NEN-011-NEN-012-outbox-inbox`): đang hoàn thiện consumer Kafka chuẩn với inbox idempotent, DLQ")
    state["NEN-013"] = ("Xong, chờ gộp", "Luồng 5 (commit `d49dfd3` trên nhánh `feat/NEN-013-014-015-platform-starter`): kho Idempotency-Key 48h, ETag/If-Match, con trỏ HMAC, job 202")
    state["NEN-014"] = ("Đang làm", "Luồng 5 (nhánh `feat/NEN-013-014-015-platform-starter`): đang hiện thực `OpaClient`, `@RequiresPermission`, step-up `acr`/`auth_time`")
    state["NEN-015"] = ("Đang làm", "Luồng 5 (nhánh `feat/NEN-013-014-015-platform-starter`): sẽ làm ngay sau `NEN-014` trên cùng nhánh")
    state["NEN-019"] = ("Đang làm", "Luồng 2 (nhánh `feat/NEN-019-NEN-020-template-testkit`): đang dựng mẫu service Java lục giác và `scripts/new-service.sh`")
    state["NEN-020"] = ("Đang làm", "Luồng 2 (nhánh `feat/NEN-019-NEN-020-template-testkit`): đang bổ sung cấu hình môi trường dev backend và Testcontainers")

    # 3. DV
    state["DV-02-01"] = ("Xong, chờ gộp", "Luồng 4 (nhánh `feat/B5-dinh-danh-to-chuc`): mẫu realm Keycloak 26.4, đạt 16/16 kiểm thử")
    state["DV-03-01"] = ("Xong, chờ gộp", "Luồng 4 (nhánh `feat/B5-dinh-danh-to-chuc`): khung dịch vụ Go `tenant-registry` và migration schema `registry`")
    state["DV-03-02"] = ("Xong, chờ gộp", "Luồng 4 (nhánh `feat/B5-dinh-danh-to-chuc`): API phân giải tenant theo tên miền, danh sách cell, bộ đệm")
    state["DV-03-03"] = ("Xong, chờ gộp", "Luồng 4 (nhánh `feat/B5-dinh-danh-to-chuc`): máy trạng thái 7 trạng thái vòng đời tenant, API nháp wizard, gợi ý mô hình")
    state["DV-04-01"] = ("Xong, chờ gộp", "Luồng 4 (nhánh `feat/B5-dinh-danh-to-chuc`): khung `org-service` và migration schema `org` hai trục thời gian")
    state["DV-04-02"] = ("Đang làm", "Luồng 4 (nhánh `feat/B5-dinh-danh-to-chuc`): đang hoàn thiện kiểm thử truy vấn `asOf`, `knownAt` của `org-service`")
    state["DV-04-03"] = ("Đang làm", "Luồng 4 (nhánh `feat/B5-dinh-danh-to-chuc`): đã có lớp nghiệp vụ thay đổi tổ chức, đang hoàn thiện phát sự kiện EVT-14..16")

    # 4. DL
    state["DL-01"] = ("Đã gộp main", "Đã gộp `main` (commit `cf3eafd` – Policy Pack giả lập cấu hình động theo QĐ 366 và NĐ 335/2025)")
    state["DL-02"] = ("Đã gộp main", "Đã gộp `main` (commit `6aaab2d` – bảng DMN xếp loại quý, năm và 13 trường hợp đặc thù giả lập)")
    state["DL-03"] = ("Đã gộp main", "Đã gộp `main` (commit `f00b178` – bộ ca kiểm thử vàng giả lập cho hai gói quy tắc và trình chạy tham chiếu)")
    state["DL-04"] = ("Làm một phần", "Đã có bảng chuyển trạng thái `task_lifecycle` trong gói Policy Pack nháp trên `main` (commit `7cb036b`)")
    state["DL-05"] = ("Đã gộp main", "Đã gộp `main` (commit `cf3eafd` – mẫu biểu DOCX 1A–1D, 2, 3 và bảng chấm điểm bản giả lập)")
    state["DL-07"] = ("Làm một phần", "Đã có lịch nghỉ lễ quốc gia 2026–2027 và khung danh mục khối, chức danh trên `main` (commit `c939a88`)")

    # 5. INF
    state["INF-01"] = ("Đã gộp main", "Đã gộp `main` (chart thư viện `vt-service`, kịch bản SSH tunnel `scripts/tunnel.sh` và cấu hình `.env` động BE/FE tại `6b5b83f`, `2b58231`)")
    state["INF-05"] = ("Xong, chờ gộp", "Nhánh `origin/claude/b8-ha-tang-g7g48k` (commit `7f05e51` – thư viện pipeline Jenkins `vt-pipeline` cho Java, Semgrep, gitleaks)")

    # 6. CNH
    state["CNH-01"] = ("Đã gộp main", "Đã gộp `main` (commit `b0fcedd` – đề xuất thiết kế cá nhân hoá trình bày và lược đồ JSON `config.user_preference`)")

    return state


def load_all_tasks():
    """Đọc danh sách 65 task WEB-001..065 và 336 task hệ thống thành cấu trúc thống nhất."""
    cards = discover_cards()
    web_tasks = []
    with open(WEB_README, encoding="utf-8") as f:
        text = f.read()

    # Map stream from README description
    stream_map = {}
    for tid_num in range(1, 66):
        tid = f"WEB-{tid_num:03d}"
        if tid_num in list(range(1, 16)) + [20, 51, 52, 53, 59, 60, 63, 64]:
            stream_map[tid] = "W-Nền tảng"
        elif tid_num in range(54, 59):
            stream_map[tid] = "W-Phân quyền"
        elif tid_num in [16, 17, 18, 19, 45, 46, 50, 61, 62]:
            stream_map[tid] = "W-Giám sát/CH"
        elif tid_num in [21, 22, 23, 24, 35, 36, 37, 38, 39, 40, 41, 49]:
            stream_map[tid] = "W-OKR/Đánh giá"
        else:
            stream_map[tid] = "W-Nghiệp vụ"

    rows_web = re.findall(
        r"^\| \[(WEB-\d+)\]\(([^)]+)\) \| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \|",
        text,
        re.M,
    )
    for tid, _link, name, size, dep, _status in rows_web:
        tid = tid.strip()
        web_tasks.append({
            "id": tid,
            "name": name.strip(),
            "size": size.strip(),
            "deps": dep.strip(),
            "stream": stream_map.get(tid, "W"),
            "level": "–",
            "group": "WEB-001..065 · Phân hệ giao diện Web (Kế hoạch Web GĐ0–GĐ4)",
            "card": cards.get(tid, f"docs/tasks/{_link.strip()}"),
            "default_note": "",
        })

    # Load 336 tasks from danh-muc-he-thong.tsv
    tsv_groups = []
    tsv_rows = []
    with open(TSV_FILE, encoding="utf-8") as f:
        for r in csv.reader(f, delimiter="\t"):
            if not r or r[0].startswith("#ID"):
                continue
            if r[0].startswith("## "):
                tsv_groups.append((f"{r[0][3:].strip()} · {r[1].strip()}", []))
                continue
            r = (r + [""] * 8)[:8]
            tsv_rows.append(r)
            tsv_groups[-1][1].append(r)

    ids = {r[0] for r in tsv_rows}
    deps_map = {}
    for r in tsv_rows:
        ds = [d.strip() for d in re.split(r"[,;]", r[3]) if d.strip() and d.strip() not in ("–", "-")]
        deps_map[r[0]] = [d for d in ds if d in ids]

    level = {}

    def walk(x, stack=()):
        if x in level:
            return
        for d in deps_map[x]:
            if d not in stack:
                walk(d, stack + (x,))
        level[x] = 1 + max((level[d] for d in deps_map[x]), default=0)

    for x in ids:
        walk(x)

    all_groups = [("WEB-001..065 · Phân hệ giao diện Web (Kế hoạch Web GĐ0–GĐ4)", web_tasks)]
    for gtitle, rs in tsv_groups:
        gtasks = []
        for r in rs:
            tid = r[0].strip()
            gtasks.append({
                "id": tid,
                "name": r[1].strip(),
                "size": r[2].strip(),
                "deps": r[3].strip(),
                "stream": r[5].strip(),
                "level": str(level.get(tid, 1)),
                "group": gtitle,
                "card": cards.get(tid, ""),
                "default_note": r[7].strip(),
            })
        all_groups.append((gtitle, gtasks))

    return all_groups


def render_tracking_file(all_groups, task_state, streams_lines, log_lines):
    all_tasks = [t for _, ts in all_groups for t in ts]
    task_by_id = {t["id"]: t for t in all_tasks}

    # Ensure every task has a valid status and note
    for t in all_tasks:
        tid = t["id"]
        if tid not in task_state:
            task_state[tid] = ("Chưa bắt đầu", t["default_note"])
        else:
            st, nt = task_state[tid]
            if st not in VALID_STATUSES:
                st = "Chưa bắt đầu"
            if not nt and t["default_note"]:
                nt = t["default_note"]
            task_state[tid] = (st, nt)

    # Calculate group stats
    group_stats = []
    totals = {"count": 0, "weight": 0.0, "merged": 0, "ready": 0, "in_prog": 0, "partial": 0, "todo": 0, "merged_w": 0.0}

    for prefix, label in GROUP_META:
        ts = [t for t in all_tasks if get_prefix(t["id"]) == prefix]
        cnt = len(ts)
        w = sum(WEIGHT.get(t["size"], 1.0) for t in ts)
        merged = sum(1 for t in ts if task_state[t["id"]][0] == "Đã gộp main")
        ready = sum(1 for t in ts if task_state[t["id"]][0] == "Xong, chờ gộp")
        in_prog = sum(1 for t in ts if task_state[t["id"]][0] == "Đang làm")
        partial = sum(1 for t in ts if task_state[t["id"]][0] == "Làm một phần")
        todo = sum(1 for t in ts if task_state[t["id"]][0] == "Chưa bắt đầu")
        merged_w = sum(WEIGHT.get(t["size"], 1.0) for t in ts if task_state[t["id"]][0] == "Đã gộp main")
        pct = (merged / cnt * 100.0) if cnt else 0.0
        group_stats.append((prefix, label, cnt, w, merged, ready, in_prog + partial, todo, pct))

        totals["count"] += cnt
        totals["weight"] += w
        totals["merged"] += merged
        totals["ready"] += ready
        totals["in_prog"] += in_prog
        totals["partial"] += partial
        totals["todo"] += todo
        totals["merged_w"] += merged_w

    total_pct = (totals["merged"] / totals["count"] * 100.0) if totals["count"] else 0.0
    total_done_or_ready_pct = ((totals["merged"] + totals["ready"]) / totals["count"] * 100.0) if totals["count"] else 0.0

    # Calculate milestone stats
    ms_stats = []
    for ms in MILESTONE_ORDER:
        ts = [t for t in all_tasks if get_milestone(t["id"]) == ms]
        cnt = len(ts)
        merged = sum(1 for t in ts if task_state[t["id"]][0] == "Đã gộp main")
        ready = sum(1 for t in ts if task_state[t["id"]][0] == "Xong, chờ gộp")
        active = sum(1 for t in ts if task_state[t["id"]][0] in ("Đang làm", "Làm một phần"))
        todo = sum(1 for t in ts if task_state[t["id"]][0] == "Chưa bắt đầu")
        pct = (merged / cnt * 100.0) if cnt else 0.0
        ms_stats.append((ms, cnt, merged, ready, active, todo, pct))

    # Critical path stats
    cp_merged = sum(1 for tid in CRITICAL_PATH if task_state.get(tid, ("Chưa bắt đầu", ""))[0] == "Đã gộp main")
    cp_active = [tid for tid in CRITICAL_PATH if task_state.get(tid, ("Chưa bắt đầu", ""))[0] in ("Đang làm", "Xong, chờ gộp")]
    cp_next = next((tid for tid in CRITICAL_PATH if task_state.get(tid, ("Chưa bắt đầu", ""))[0] != "Đã gộp main"), "Hoàn thành")

    if not streams_lines:
        streams_lines = [
            "| Luồng | Worktree / Nhánh | Phạm vi đang thực hiện | Task đang xử lý | Trạng thái |",
            "|---|---|---|---|---|",
            "| **Điều phối (`DP`)** | `main` | Kiểm tra cổng chất lượng, gộp nhánh vào `main`, đồng bộ `TIEN-DO-HE-THONG.md`, quản lý hạ tầng `.env` & SSH tunnel | Toàn hệ thống | Đang hoạt động |",
            "| **Luồng 1 (`B1`)** | `feat/NEN-011-NEN-012-outbox-inbox` | Hoàn thiện Outbox/Inbox Kafka & Apicurio trong `vt-platform-starter` | `NEN-011`, `NEN-012` | Đang làm |",
            "| **Luồng 2 (`B1`)** | `feat/NEN-019-NEN-020-template-testkit` | Mẫu dịch vụ Java lục giác (`new-service.sh`) và môi trường dev backend / Testcontainers | `NEN-019`, `NEN-020` | Đang làm |",
            "| **Luồng 3 (`B1`)** | `feat/NEN-021-008-034-luong-b3` | Đã hoàn thành và gộp `NEN-021`, `NEN-008`, `NEN-034` vào `main`; sẵn sàng nhận task tiếp theo | Đã gộp `275bb0f` | Sẵn sàng nhận việc mới |",
            "| **Luồng 4 (`B5`)** | `feat/B5-dinh-danh-to-chuc` | Dịch vụ `tenant-registry` (Go), `org-service` (Java) và mẫu realm Keycloak | `DV-02-01`, `DV-03-01..03`, `DV-04-01..03` | 5 xong chờ gộp, 2 đang làm |",
            "| **Luồng 5 (`B1`)** | `feat/NEN-013-014-015-platform-starter` | Idempotency/ETag (`NEN-013` đã xong), OPA client & step-up (`NEN-014`), mã hoá trường C2 (`NEN-015`) | `NEN-013`, `NEN-014`, `NEN-015` | 1 xong chờ gộp, 2 đang làm |",
        ]

    if not log_lines:
        log_lines = [
            "| 20/10/2026 17:55 | Điều phối (`main`) | Toàn bộ 401 task | Khởi tạo tệp theo dõi tiến độ tổng hợp duy nhất `TIEN-DO-HE-THONG.md` theo yêu cầu `YC-PT-17`; đồng bộ trạng thái 74 task đã gộp `main` (bao gồm `NEN-006`, `008`, `017`, `018`, `021`, `022`, `034`, `041`, `CNH-01`, `INF-01` mới nhất). |",
        ]

    lines = [
        "# THEO DÕI TIẾN ĐỘ TỔNG HỢP TOÀN HỆ THỐNG MISSION",
        "",
        "> **TỆP THEO DÕI TIẾN ĐỘ DUY NHẤT CỦA DỰ ÁN (`YC-PT-11`, `YC-PT-17`)**",
        "> - Đây là **nguồn sự thật duy nhất** về tiến độ thực thi của toàn bộ **401 task** thuộc dự án Mission (gồm 65 task giao diện Web ban đầu và 336 task mở rộng toàn hệ thống).",
        "> - **Quy định bắt buộc cho mọi AI Agent**: Mọi agent (cả luồng điều phối giữ nhánh `main` lẫn các subagent làm việc trên nhánh/worktree riêng) **bắt buộc phải cập nhật tệp này** ngay khi nhận việc, khi hoàn thành task trên nhánh và khi gộp vào `main`:",
        ">   1. **Khi bắt đầu làm một task**: chạy `python3 scripts/cap-nhat-tien-do.py set <MÃ_TASK> --status \"Đang làm\" --note \"Nhánh <tên-nhánh>: đang thực hiện\" --agent \"<Tên luồng>\"`.",
        ">   2. **Khi hoàn thành và kiểm thử xanh trên nhánh**: chạy `python3 scripts/cap-nhat-tien-do.py set <MÃ_TASK> --status \"Xong, chờ gộp\" --note \"Nhánh <tên-nhánh>: đã qua build & test\" --agent \"<Tên luồng>\"`.",
        ">   3. **Khi luồng điều phối gộp vào `main`**: chạy `python3 scripts/cap-nhat-tien-do.py set <MÃ_TASK> --status \"Đã gộp main\" --note \"Đã gộp main (commit <hash>)\" --agent \"Điều phối\"`.",
        ">   4. Nếu sửa trực tiếp dòng trạng thái trong bảng chi tiết ở Mục 3, bắt buộc chạy `python3 scripts/cap-nhat-tien-do.py sync` để tự động tính lại bảng tổng hợp ở Mục 1.",
        "",
        "---",
        "",
        "## 1. Tổng quan tiến độ toàn hệ thống (tự động tính từ 401 task)",
        "",
        f"- **Tổng tiến độ đã gộp `main`**: **{totals['merged']} / {totals['count']} task ({total_pct:.1f}%)** — Khối lượng quy đổi đã gộp: **{totals['merged_w']:g} / {totals['weight']:g} đơn vị**.",
        f"- **Tổng tiến độ tính cả các task đã xong chờ gộp**: **{totals['merged'] + totals['ready']} / {totals['count']} task ({total_done_or_ready_pct:.1f}%)** (gồm **{totals['merged']}** đã gộp `main` + **{totals['ready']}** đã xong trên nhánh chờ gộp).",
        f"- **Đang thực hiện / Làm một phần**: **{totals['in_prog'] + totals['partial']} task** ({totals['in_prog']} đang làm, {totals['partial']} làm một phần).",
        f"- **Chưa bắt đầu**: **{totals['todo']} task**.",
        f"- **Tiến độ Đường găng (23 task)**: Đã gộp `main` **{cp_merged} / {len(CRITICAL_PATH)} task**; nút găng kế tiếp cần mở khoá: **`{cp_next}`** (đang xử lý trên nhánh: {', '.join(f'`{x}`' for x in cp_active) if cp_active else 'không'}).",
        "",
        "### 1.1. Bảng tổng hợp theo 10 nhóm phân hệ",
        "",
        "| Mã nhóm | Phân hệ / Phạm vi | Tổng task | Khối lượng | Đã gộp `main` | Xong, chờ gộp | Đang làm / 1 phần | Chưa bắt đầu | Tỷ lệ gộp `main` |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|",
    ]

    for prefix, label, cnt, w, merged, ready, active, todo, pct in group_stats:
        lines.append(
            f"| **{prefix}** | {label} | {cnt} | {w:g} | **{merged}** | {ready} | {active} | {todo} | **{pct:.1f}%** |"
        )
    lines.append(
        f"| **TỔNG** | **Toàn bộ hệ thống Mission** | **{totals['count']}** | **{totals['weight']:g}** | **{totals['merged']}** | **{totals['ready']}** | **{totals['in_prog'] + totals['partial']}** | **{totals['todo']}** | **{total_pct:.1f}%** |"
    )

    lines += [
        "",
        "### 1.2. Bảng tổng hợp theo 8 Mốc triển khai (M0 – M7)",
        "",
        "| Mốc triển khai | Tổng task | Đã gộp `main` | Xong, chờ gộp | Đang làm / 1 phần | Chưa bắt đầu | Tiến độ gộp `main` |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for ms, cnt, merged, ready, active, todo, pct in ms_stats:
        lines.append(f"| **{ms}** | {cnt} | **{merged}** | {ready} | {active} | {todo} | **{pct:.1f}%** |")

    lines += [
        "",
        "### 1.3. Trạng thái 23 task trên Đường găng",
        "",
        "Chuỗi đường găng quyết định thời gian hoàn thành toàn dự án:",
        "",
    ]
    cp_badges = []
    for tid in CRITICAL_PATH:
        st = task_state.get(tid, ("Chưa bắt đầu", ""))[0]
        if st == "Đã gộp main":
            cp_badges.append(f"✅ `{tid}`")
        elif st in ("Xong, chờ gộp", "Đang làm", "Làm một phần"):
            cp_badges.append(f"🔄 **`{tid}`** ({st})")
        else:
            cp_badges.append(f"⏳ `{tid}`")
    lines.append(" → ".join(cp_badges))

    lines += [
        "",
        "---",
        "",
        "## 2. Trạng thái các luồng phát triển đang chạy (giới hạn tối đa 5 luồng theo `YC-PT-12`)",
        "",
        "<!-- ACTIVE_STREAMS_START -->",
    ]
    lines.extend(streams_lines)
    lines += [
        "<!-- ACTIVE_STREAMS_END -->",
        "",
        "---",
        "",
        "## 3. Bảng theo dõi chi tiết toàn bộ 401 task",
        "",
        "Mọi thay đổi trạng thái task đều được cập nhật trực tiếp vào bảng dưới đây. Các giá trị cột **Trạng thái** hợp lệ: `Đã gộp main`, `Xong, chờ gộp`, `Đang làm`, `Làm một phần`, `Chưa bắt đầu`.",
    ]

    for gtitle, gtasks in all_groups:
        g_cnt = len(gtasks)
        g_merged = sum(1 for t in gtasks if task_state[t["id"]][0] == "Đã gộp main")
        g_ready = sum(1 for t in gtasks if task_state[t["id"]][0] == "Xong, chờ gộp")
        g_active = sum(1 for t in gtasks if task_state[t["id"]][0] in ("Đang làm", "Làm một phần"))
        lines += [
            "",
            f"### {gtitle} ({g_merged}/{g_cnt} đã gộp `main`, {g_ready} chờ gộp, {g_active} đang làm)",
            "",
            "| Mã | Tên task | Cỡ | Luồng | Tầng | Phụ thuộc | Trạng thái | Ghi chú / Nhánh / Kiểm chứng |",
            "|---|---|:---:|:---:|:---:|---|---|---|",
        ]
        for t in gtasks:
            tid = t["id"]
            st, nt = task_state[tid]
            id_cell = f"[{tid}]({t['card']})" if t["card"] else tid
            clean_name = t["name"].replace("|", "\\|")
            clean_deps = t["deps"].replace("|", "\\|") if t["deps"] else "–"
            clean_note = nt.replace("|", "\\|") if nt else ""
            lines.append(
                f"| {id_cell} | {clean_name} | {t['size']} | {t['stream']} | {t['level']} | {clean_deps} | **{st}** | {clean_note} |"
            )

    lines += [
        "",
        "---",
        "",
        "## 4. Nhật ký cập nhật tiến độ",
        "",
        "Mọi lần cập nhật trạng thái bằng `python3 scripts/cap-nhat-tien-do.py set ...` hoặc khi điều phối gộp nhánh vào `main` đều được ghi vết tại đây (mới nhất ở dưới).",
        "",
        "<!-- PROGRESS_LOG_START -->",
        "| Thời gian | Luồng / Agent | Task | Nội dung cập nhật |",
        "|---|---|---|---|",
    ]
    lines.extend(log_lines)
    lines += [
        "<!-- PROGRESS_LOG_END -->",
        "",
    ]

    with open(TRACKING_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    return totals, total_pct


def main():
    parser = argparse.ArgumentParser(description="Công cụ cập nhật tệp tiến độ tổng hợp TIEN-DO-HE-THONG.md")
    sub = parser.add_subparsers(dest="cmd")

    sub.add_parser("sync", help="Đọc lại bảng 401 task trong TIEN-DO-HE-THONG.md và tính lại các bảng thống kê tổng hợp")

    p_set = sub.add_parser("set", help="Cập nhật trạng thái một hoặc nhiều task và tự động tính lại thống kê")
    p_set.add_argument("tasks", nargs="+", help="Mã các task cần cập nhật (ví dụ: NEN-011 NEN-012)")
    p_set.add_argument("--status", required=True, choices=sorted(VALID_STATUSES), help="Trạng thái mới của task")
    p_set.add_argument("--note", default=None, help="Ghi chú nhánh, commit hoặc kết quả kiểm thử")
    p_set.add_argument("--agent", default="AI Agent", help="Tên luồng hoặc agent thực hiện cập nhật")

    args = parser.parse_args()

    all_groups = load_all_tasks()
    all_ids = {t["id"] for _, ts in all_groups for t in ts}

    existing_state, streams_lines, log_lines = parse_existing_tracking()
    if not existing_state:
        existing_state = build_initial_state()

    if args.cmd == "set":
        unknown = [tid for tid in args.tasks if tid not in all_ids]
        if unknown:
            sys.exit(f"Lỗi: Không tìm thấy mã task trong danh mục 401 task: {', '.join(unknown)}")
        now_str = datetime.datetime.now().strftime("%d/%m/%Y %H:%M")
        for tid in args.tasks:
            old_st, old_nt = existing_state.get(tid, ("Chưa bắt đầu", ""))
            new_nt = args.note if args.note is not None else old_nt
            existing_state[tid] = (args.status, new_nt)
        task_list_str = ", ".join(f"`{t}`" for t in args.tasks)
        note_summary = f" — {args.note}" if args.note else ""
        log_lines.append(
            f"| {now_str} | {args.agent} | {task_list_str} | Chuyển trạng thái sang **{args.status}**{note_summary} |"
        )

    totals, total_pct = render_tracking_file(all_groups, existing_state, streams_lines, log_lines)
    print(
        f"Đã đồng bộ {TRACKING_FILE}: "
        f"{totals['merged']}/{totals['count']} task đã gộp main ({total_pct:.1f}%), "
        f"{totals['ready']} xong chờ gộp, "
        f"{totals['in_prog'] + totals['partial']} đang làm/làm một phần, "
        f"{totals['todo']} chưa bắt đầu."
    )


if __name__ == "__main__":
    main()
