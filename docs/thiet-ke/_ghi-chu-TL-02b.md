# Ghi chú của TL-02b cho người tổng hợp

Tệp này liệt kê các tên bảng, cột, trạng thái và tham số mà TL-02b (QT-09 đến QT-18 và chương Ma trận truy vết) dùng nhưng chưa có trong baseline `00-co-so-thiet-ke-chung.md`, để TL-03 xác nhận hoặc đổi tên. Không có mã nào của baseline bị đổi nghĩa.

## Đánh số hình

Phần B đánh số hình theo chương tạm là B1 (Quy trình đánh giá, xếp loại và hồ sơ số), B2 (Quy trình giám sát và hậu kiểm), B3 (Quy trình cấu hình, phân tích và trợ lý AI), B4 (Ma trận truy vết), vì chưa biết số chương thực của phần A. Khi ghép TL-02, thay "Hình B1." … "Hình B4." bằng số chương thực, ví dụ: `sed -i 's/Hình B1\./Hình 10./; ...'`. Chương "Quy trình giám sát, cấu hình và phân tích" trong lời giao việc được chia thành hai chương B2 và B3 cho cân đối.

## Bảng mới

| Bảng | Mục đích | Dùng ở |
|---|---|---|
| `appraisal.batch_decision` | Quyết định xếp loại hàng loạt: số, ngày quyết định, người ký, danh sách hồ sơ, trạng thái ký lô | QT-09, SCR-0904 |
| `dossier.remediation_item` | Hạn chế cần khắc phục từ biên bản kiểm điểm, trạng thái theo dõi qua các kỳ | QT-11, SCR-1101 |
| `config.component_registry` | Danh mục component được phép dùng trong bố cục SDUI, có phiên bản (ví dụ registry v2026.3) | QT-18, SCR-1801 |
| `config.dashboard_def` | Định nghĩa dashboard tự dựng (chỉ số, chiều, bố cục, chia sẻ); có thể đặt ở schema `config` hoặc một schema của SVC-15 | QT-16, SCR-1602 |

## Cột mới trên bảng đã có

| Bảng | Cột | Ghi chú |
|---|---|---|
| `appraisal.appraisal_profile` | `flow` (`DANG`, `NHA_NUOC`), `submitted_at` | Một người có thể có hai hồ sơ cùng kỳ theo hai luồng |
| `appraisal.rating_proposal` | `level_kind` (`TU_DE_XUAT`, `THAM_DINH`, `DE_XUAT`, `QUYET_DINH`), `level`, `reason_enc`, `comment_enc` | Lưu riêng 4 mức (QĐ 16.3) |
| `appraisal.score_snapshot` | `policy_version`, `input_hash`, `total_score`, `group_scores_json`, `detail_json`, `over_target_ratio`, `objective_ratio` | Tiêu chí I6 |
| `appraisal.rating_explanation` | `system_level`, `conditions_json`, `text` | Câu giải thích hạ mức |
| `appraisal.general_criteria_result` | `met`, `level` | |
| `appraisal.self_assessment` | `content_enc`, `breakthrough_enc`, `claims_json` | |
| `appraisal.annual_rating` | `score`, `system_level`, `block_reason`, `reassign_flag` | QĐ 15.5, 15.6 |
| `appraisal.prospect_group` | `group_code`, `rank_in_group`, `suggested_group`, `reason_enc`, `training_plan_ref` | |
| `dossier.review_meeting` | `draft_sent_at`, `meeting_at`, `subject_person_id`, `participants_json`, `vote_json` | Kiểm tra ≥ 03 ngày làm việc |
| `dossier.review_comment` | `content_enc`, `kind` (ưu điểm, hạn chế, kiến nghị), `phase` (`TRUOC_HOI_NGHI`, `HOI_NGHI`) | |
| `dossier.form_instance` | `template_version` | |
| `dossier.dossier_package` | `code`, `due_date`, `manifest_hash`, `status` | |
| `oversight.complaint` | `kind` (`KHIEU_NAI`, `GIAI_TRINH`, `BAO_LUU`), `profile_id`, `targets_json`, `content_enc`, `request_text`, `attachments_json`, `reserve_opinion` | |
| `oversight.complaint_response` | `outcome`, `adjustment_json`, `document_ref`; loại bản ghi `YEU_CAU`, `GIAI_TRINH`, `KET_LUAN`, `BAO_LUU` | |
| `oversight.reopen_request` | `code`, `period_id`, `audit_kind`, `scope_json`, `open_until`, `reason`, `basis_ref`, `status` | Phạm vi mở lại |
| `oversight.reopen_approval` | `comment` (cùng người duyệt, thời điểm, mức MFA, chữ ký) | |
| `oversight.anomaly_alert` | `evidence_chain_json`, `status` | |
| `oversight.alert_action` | `note`, loại hành động | |
| `config.ui_pack_version` | `policy_pack_ref`, `tokens_json`, `assets_json`, `layout_json`, `elder_mode`, `few_steps` | |
| `config.form_schema` | `form_key`, `version`, `schema_json` (`x-cel`, `x-classification`), `ui_json` | |
| `okr.objective`, `okr.key_result` | `ai_assisted` | Nội dung chèn từ gợi ý AI |
| `ai.suggestion_log` | `accepted_by`, `prompt_redacted`, `output`, `sources_json`, `kind` (ví dụ `TOM_TAT_TUAN`) | |
| `policy.decision_table` | `hit_policy`, `rules_json`, `explanation_templates_json` | |
| `policy.policy_rule` | `params_json`, `overridable` | |
| `policy.golden_test_case` | `approved_by` | |
| `policy.test_run` | `engine_match`, `property_result_json`, `cases_json` | |
| `flowable.wf_binding` | `scope_json`, `params_json` | |

## Trạng thái mới (baseline mục 9.1 chưa có)

- Gói hồ sơ `dossier.dossier_package.status`: `DANG_LAP` → `DANG_SINH` → `CHO_KY` → `DA_KY` → `DA_NOP` → `DA_LUU_TRU`.
- Khiếu nại `oversight.complaint.status`: `MOI`, `DA_TIEP_NHAN`, `DANG_GIAI_TRINH`, `CHO_KET_LUAN`, `DA_TRA_LOI`, `DANG_THI_HANH`, `KHONG_THU_LY`, `DONG`.
- Yêu cầu mở lại kỳ `oversight.reopen_request.status`: `CHO_DUYET_1`, `CHO_DUYET_2`, `DA_DUYET`, `TU_CHOI`.
- Cảnh báo `oversight.anomaly_alert.status`: `MOI`, `DANG_XEM`, `HOP_LE`, `DA_GIAO_KIEM_TRA`, `CO_VI_PHAM`, `KHONG_VI_PHAM`, `TU_DONG_DONG`, `DONG`.
- UI Pack `config.ui_pack_version.status`: `NHAP`, `DANG_KIEM_TRA`, `CHO_DUYET`, `DA_PHAT_HANH`, `THAY_THE`.

## Tham số Policy Pack mới

- `nam.cach_tinh` (mặc định `TRUNG_BINH_QUY`), `phan_nhom.mo_o_ky_quy` (mặc định `false`), `kiem_diem.cach_dem_ngay`, `khieu_nai.thoi_han` (mặc định 10 ngày làm việc), `phan_bo.toi_da_htxsnv` (mặc định 20%), tham số công thức `tru_tien_do`, `loai_ngay`, `cach_tinh_chat_luong`, `lam_tron`.

## Mẫu luồng (flowable.wf_template) được nhắc tên

`duyet-ho-so-danh-gia` v3, `duyet-ke-hoach-quy` v2, `duyet-kep-chi-huy-cap-uy` v1, `xu-ly-khieu-nai` v2, `mo-lai-ky-hai-nguoi` v1; tổng 27 mẫu nhân phiên bản.

## API được đặt tên (đề xuất cho OpenAPI của TL-01)

Theo quy ước `/api/v1/<module>`: `appraisals/profiles/*`, `appraisals/batch-decisions`, `appraisals/annual/*`, `appraisals/prospect-groups/*`, `dossiers/meetings/*`, `dossiers/forms/*`, `dossiers/packages/*`, `oversight/complaints/*`, `oversight/reopen-requests/*`, `oversight/alerts/*`, `ledger/entries`, `ledger/verify-runs`, `ledger/exports`, `policies/packs/*`, `policies/test-cases`, `policies/workflow-templates/*`, `policies/workflow-bindings/simulate`, `analytics/query`, `analytics/dashboards/*`, `analytics/trace/*`, `ai/assist/okr`, `ai/suggestions/*`, `ai/speech-to-text`, `ui/packs/*`, `ui/forms/*`, `ui/components`, `config/anomaly-thresholds`.
