# Lớp ngữ nghĩa Cube của kho phân tích (DV-15-03)

Thư mục này chứa mô hình Cube cho chỉ số chuẩn của SVC-15 (ADR-14) theo TL-02b QT-16 (SCR-1601 bảng điều hành, SCR-1602 trình dựng dashboard) và TL-03 "Phân quyền phía đọc". Cube đọc kho ClickHouse của cell (`analytics/clickhouse`, DV-15-01) qua các view `cube_*` của migration `V010`, áp bộ lọc tenant và nhánh cây tổ chức từ JWT, và dựng pre-aggregation theo (tenant, đơn vị, kỳ) làm mới mỗi 5 phút ngay trong ClickHouse.

## Thành phần

| Tệp | Nội dung |
|---|---|
| `cube.js` | `checkAuth` (JWT HS256 có `tenant`, `org_path`), `queryRewrite` (lọc tenant và nhánh), driver ClickHouse theo tenant (`SQL_tenant_id`), CSDL pre-aggregation theo tenant, danh sách tenant cần làm mới |
| `model/cubes/nhiem_vu.yml` | `task`: `ty_le_dung_han`, `so_viec_cham`, `so_ngay_cham_binh_quan`, số nhiệm vụ |
| `model/cubes/okr.yml` | `okr`: `tien_do_o`; `okr_checkin`: mức tự tin KR |
| `model/cubes/danh_gia.yml` | `appraisal`: `cho_tham_dinh`, phân bố mức xếp loại; `score`: điểm KPI trung bình; `rating`: đề xuất xếp loại theo cấp |
| `model/cubes/minh_chung.yml` | `evidence`: số minh chứng chờ nghiệm thu |
| `model/cubes/don_vi.yml`, `ky.yml` | Chiều đơn vị (đường dẫn trên hai cây), khối đối tượng, kỳ, luồng (`flow.code`); tên theo ví dụ `platform.yaml` (`org_unit.name`, `period.label`, `subject_group.code`) |
| `model/cubes/do_moi.yml` | `data_freshness`: thời điểm dữ liệu mới nhất và độ trễ (BR-16-08) |
| `tests/` | Kiểm thử tích hợp ClickHouse + Cube với bộ ca vàng `data/golden/analytics-measures`; `test_mo_hinh.py` kiểm tĩnh (không cần Docker) tên thành viên mà frontend dùng |

Mỗi cube có `meta.phien_ban` (phiên bản lớp ngữ nghĩa, hiện `2026.4`) và tiêu đề, mô tả tiếng Việt cho `getSemanticModel` (DV-15-04) đọc từ `/cubejs-api/v1/meta`. Truy vấn chỉ dùng được measure, dimension đã công bố; không có SQL tự do (BR-16-02).

## Chạy ở dev

Áp migration kho trước (`analytics/clickhouse/README.md`), rồi trong `infra/dev` đặt `ANALYTICS_CUBE_PASSWORD` (trùng mật khẩu đã dùng khi áp migration), `CUBEJS_API_SECRET`, `CUBE_REFRESH_TENANTS` trong `.env` và chạy `docker compose --profile analytics up -d cube`. Cube nghe ở cổng `35400`; mọi lời gọi cần JWT HS256 ký bằng `CUBEJS_API_SECRET` với claim `tenant` (UUID) và `org_path` (ví dụ `/tw/vptw`). Người dùng cuối không gọi Cube trực tiếp mà qua analytics-api (DV-15-04).

## Kiểm thử

```bash
cd analytics/cube
uv venv .venv && uv pip install --python .venv/bin/python pytest 'testcontainers>=4.9' ruff
.venv/bin/python -m pytest -q -s tests     # cần Docker; CUBE_LOGS=1 in nhật ký Cube khi dừng
```

Kiểm thử dựng ClickHouse 25.8 bằng cấu hình `infra/dev/clickhouse`, áp toàn bộ migration, nạp `du-lieu.json`, chạy Cube v1.3.60 với thư mục này, rồi kiểm: từng ca trong `ca-kiem-thu.json` trả đúng giá trị tính tay; người xem nhánh A không thấy số ngoài nhánh, tenant khác không lẫn số; thiếu hoặc sai ngữ cảnh bị từ chối 403; truy vấn bảng điều hành đọc bảng pre-aggregation (nhật ký truy vấn ClickHouse); p95 dưới 1 giây ở tải thử cục bộ. Quyết định và điểm lệch: `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, mục DV-15-03.
