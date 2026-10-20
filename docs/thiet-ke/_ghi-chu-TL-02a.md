# Ghi chú TL-02a: đề xuất ngoài baseline để TL-03 và người tổng hợp đối chiếu

Tệp này liệt kê mọi bảng, cột, trạng thái, sự kiện và tham số mà TL-02a (QT-00 đến QT-08) đặt tên ngoài `00-co-so-thiet-ke-chung.md`. Không đổi tên hay nghĩa của mã đã có. Mọi cột chung (`id`, `tenant_id`, `created_*`, `updated_*`, `row_version`, `period_id`) mặc định có theo baseline mục 9.

## 1. Trạng thái mới

| Đối tượng | Cột | Giá trị | Dùng ở |
|---|---|---|---|
| Tenant | `registry.tenant.status` | `KHOI_TAO`, `DANG_CAP_PHAT`, `DANG_CAU_HINH`, `SAN_SANG`, `DANG_VAN_HANH`, `TAM_NGUNG`, `DA_CHAM_DUT` | QT-01, Hình 6.6 |
| Đề nghị gia hạn, điều chỉnh | `task.task_change_request.status` | `NHAP`, `CHO_DUYET`, `DA_DUYET`, `TU_CHOI`, `DA_RUT` | QT-06, Hình 8.8 |
| Lần check-in | `okr.kr_checkin.status` | `NHAP`, `DA_GUI` (bất biến sau khi gửi) | QT-03, Hình 7.7 |
| Cờ rủi ro KR | `okr.key_result.risk_flag` | `BINH_THUONG`, `THEO_DOI`, `RUI_RO` | QT-03, Hình 7.7 |
| Phiên đăng nhập (logic, không lưu bảng nghiệp vụ) | Keycloak `acr` | `CHUA_XAC_THUC`, `AAL1`, `AAL2`, `HET_HAN`, `BI_THU_HOI` | QT-00, Hình 6.2 |
| Lệch đồng bộ | `org.sync_discrepancy.severity` | `CAO`, `VUA`, `THAP` | QT-01 |
| Lệnh khoá kỳ | `config.period_lock_order.status` | `NHAP`, `CHO_DUYET`, `DA_DUYET`, `DANG_CHAY`, `HOAN_TAT`, `DA_HUY` | QT-08 |
| Bước saga khoá kỳ | `config.period_lock_step.status` | `CHO`, `DANG_CHAY`, `DAT`, `DAT_CO_CANH_BAO`, `LOI`, `DA_BU_TRU` | QT-08 |

Trạng thái chuẩn baseline được giữ nguyên: `config.period.status`, `task.plan_version.status`, `task.task.status` (+ `qua_han`), `product.evidence_version.status`, `okr.objective.status`.

## 2. Bảng mới đề xuất

| Bảng | Mục đích | Cột chính |
|---|---|---|
| `org.sync_discrepancy` | Lệch dữ liệu nhân sự giữa nguồn ngoài và nền tảng (SCR-0104) | `person_id`, `field`, `source_connector`, `source_value`, `current_value`, `severity`, `suggested_action`, `resolution`, `resolved_by`, `resolved_at`, `sync_run_id` |
| `okr.objective_basis` | Bảng nối mục tiêu với nhiều căn cứ | `objective_id`, `basis_id` |
| `okr.objective_review` | Ý kiến của người duyệt OKR mỗi lần duyệt/trả lại | `objective_id`, `version_no`, `reviewer_id`, `decision`, `comment` |
| `config.period_lock_order` | Lệnh khoá kỳ (lập, duyệt, khởi chạy) | `period_id`, `requested_by`, `approved_by`, `approved_at`, `started_by`, `started_at`, `basis_doc`, `saga_id`, `idempotency_key`, `status`, `cancel_requested_by`, `cancel_approved_by` |
| `config.period_lock_step` | Chiếu tiến trình từng bước saga để hiển thị (SCR-0801) | `lock_order_id`, `step_no`, `name`, `status`, `started_at`, `finished_at`, `result_summary`, `compensation_action`, `compensated_at` |

## 3. Cột mới đề xuất trên bảng baseline

| Bảng | Cột | Kiểu gợi ý | Ghi chú |
|---|---|---|---|
| `registry.tenant` | `status`, `onboarding_code`, `contract_ref`, `flows`, `expected_accounts`, `account_count`, `kek_ref` | enum, text, text, text[], int, int, text | QT-01 |
| `registry.cell` | `capacity_accounts` | int | Tính % tải, ngưỡng 85% |
| `registry.tenant_cell` | `tenancy_model`, `data_region` | enum(`silo`,`bridge`,`pool`), text | Nếu baseline chưa có |
| `org.org_unit` | `flows`, `level`, `unit_type`, `subject_group_id`, `code` | text[], enum, enum, uuid, text | `flows` là đề xuất mới; các cột còn lại có thể đã nằm trong TL-03 |
| `org.org_unit_relation` | `relation_type` (`PARENT`, `PREDECESSOR`) | enum | Lưu đơn vị tiền thân khi sáp nhập |
| `config.period` | `milestones` (JSON), `plan_deadline` | jsonb, date | Hạn duyệt kế hoạch, hạn nộp minh chứng, hạn nghiệm thu tồn đọng, ngày khoá dự kiến |
| `okr.objective` | `progress_pct` (tính), `over_limit_reason`, `version_no` | numeric, text, int | |
| `okr.key_result` | `data_source`, `formula_text`, `content_source` (`HUMAN`,`AI_ACCEPTED`,`AI_EDITED`), `risk_flag`, `verifier_id`, `checkin_frequency`, `baseline_value` | | |
| `okr.kr_checkin` | `status`, `source_value`, `source_fetched_at`, `override_reason`, `confidence`, `highlights`, `blockers` | | Chỉ thêm |
| `task.work_catalog` | `parent_catalog_id`, `version_no` | uuid, int | Kế thừa danh mục cấp trên |
| `task.catalog_item` | `position_id`, `default_group`, `standard_days`, `status` | uuid, enum, int, enum | Cùng `difficulty` (1–5), `base_score` (0–100), `quality_criteria`, `evidence_required` |
| `task.plan_version` | `content_hash`, `review_comment` | text(sha256), text | |
| `task.plan_item` | `co_assignees` (JSON), `flows`, `origin_item_id`, `change_reason`, `recurrence` | | `origin_item_id` dùng để so sánh phiên bản |
| `task.task` | `source_type`, `kr_id`, `acceptor_id`, `milestones` (JSON), `score_due_date`, `delay_risk` | | `score_due_date` là hạn tính hệ số C, khác `due_date` (hạn thực hiện) |
| `task.task_progress` | `channel` (`WEB`,`MOBILE`) | enum | |
| `task.task_source_doc` | `paragraph_no` | int | Khoá chống tạo trùng theo (văn bản, đoạn) |
| `task.task_change_request` | `status`, `cause_claimed`, `cause_decided`, `blocker_id`, `approver_id`, `attachment_ids`, `new_values` (JSON) | | |
| `product.evidence` | `source_type`, `external_doc_ref` | enum, text | |
| `product.evidence_version` | `reuse_justification`, `attested_at`, `doc_number`, `doc_date`, `doc_summary`, `excluded_period_id` | | `excluded_period_id` là cờ "không tính trong kỳ" khi khoá kỳ còn `DA_NOP` |
| `product.acceptance` | `criteria_results` (JSON), `revision_cause`, `comment_enc` (C2) | | Cùng `quality_level` (1/0,75/0,5/0,25/0) |
| `ai.suggestion_log` | `confidence`, `decision`, `edited_fields` | numeric, enum, text[] | Ghi ô AI người dùng giữ/sửa |

## 4. Sự kiện đề xuất (chưa có mã EVT)

- **Huỷ khoá kỳ** (tên gợi ý `KyDaHuyKhoa`, topic `config.period.unlocked.v1`), phát bởi MOD-08 khi saga khoá kỳ bù trừ toàn bộ (kỳ `DANG_KHOA` → `MO`). Tiêu thụ: MOD-01/02/03 (mở ghi lại), SVC-07 (đánh dấu lô điểm `superseded`), SVC-09. Baseline chỉ có EVT-22 cho mở lại hậu kiểm từ `DA_KHOA`, không áp dụng được cho trường hợp này. Đề nghị người tổng hợp cấp mã EVT-26.

## 5. Tham số Policy Pack đề xuất

| Tham số | Giá trị mặc định đã dùng | Ghi chú |
|---|---|---|
| `plan_approval_deadline_rule` | Ngày làm việc cuối của tuần lịch thứ 2 (tuần 1 = tuần chứa ngày 1 của tháng đầu quý). Q4/2026 → 09/10/2026; Q1/2027 → 08/01/2027 | Cần đối chiếu điều khoản PL 1 |
| `plan_max_adjustments_per_quarter` | 2 | |
| `group_weights` | Ví dụ Khối Đảng v2.0: thường xuyên 30, then chốt 70 (thang 100) | Luồng `NHA_NUOC`: 30 tiêu chí chung + 70 kết quả nhiệm vụ (NĐ 335) |
| `timeliness_penalty_per_day` | 0,05; C = max(0, 1 − 0,05 × ngày làm việc chậm chủ quan) | |
| `task_kpi_formula` | (A + B + C)/3 | |
| `difficulty_coefficients` | Mức 4 = 1,2 (chỉ dùng gợi ý điểm chuẩn khi xây danh mục) | Giả định, cần cơ quan tổ chức xác nhận |
| `extension_max_per_task_quarter`, `extension_escalation_days` | 2 lần; > 10 ngày làm việc thì cấp trên duyệt | |
| `acceptance_sla_days` | 5 ngày làm việc | |
| `kr_risk_low_streak` | 2 lần tự tin Thấp liên tiếp | |
| `similarity_threshold` | 0,7 | Phát hiện nhiệm vụ trùng |
| `mfa_stepup_window_minutes` | 15 | |

## 6. Mẫu luồng BPMN được nhắc tới (cho `flowable.wf_template`)

`duyet-okr` (v2), `duyet-ke-hoach`, `duyet-de-nghi` (chỉ khi vượt ngưỡng), `chot-ky` (saga, v3), saga cấp phát tenant (do SVC-03 khởi tạo, chạy trên SVC-06).

## 7. Ghi chú ghép với TL-02b

- TL-02a dùng chương `#` sau: Thông tin tài liệu {-}; 1 Giới thiệu; 2 Thuật ngữ và chữ viết tắt; 3 Tác nhân và phân quyền; 4 Tổng quan nghiệp vụ; 5 Quy ước đặc tả và giao diện chung; 6 Định danh, tenant và cơ cấu tổ chức (QT-00, QT-01); 7 Mục tiêu và kế hoạch công tác (QT-02 đến QT-04); 8 Thực hiện nhiệm vụ và nghiệm thu (QT-05 đến QT-07); 9 Khoá kỳ đánh giá (QT-08).
- Mỗi QT là `##`, các mục khung là `###`, mỗi màn hình là `####`. TL-02b nên bắt đầu bằng chương `#` mới (chương 10 trở đi), đánh số hình theo chương từ Hình 10.1, và không lặp lại YAML hay "Thông tin tài liệu".
