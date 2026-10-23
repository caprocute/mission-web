# Ghi chú TL-03: bảng, cột và quyết định bổ sung so với baseline

Tệp này liệt kê những gì TL-03 (`src/TL-03-thiet-ke-csdl.md`) thêm vào hoặc diễn giải khác `00-co-so-thiet-ke-chung.md`, và cách TL-03 đã tiếp nhận các đề xuất của TL-01, TL-02a, TL-02b. Tên bảng baseline được giữ nguyên.

## 1. Quy ước chung

- Vai trò CSDL theo TL-01: `svc_<schema>` (chạy, `NOINHERIT NOBYPASSRLS`), `mig_<schema>` (Flyway); TL-03 thêm `ro_<schema>` (chỉ đọc) và `cdc_<schema>` (Debezium). Vai trò đặc biệt: `svc_ledger_archiver`, `svc_keyring`.
- Cột `period_bucket int` = năm×10 + quý (năm×10 cho dữ liệu năm), là khoá phân vùng LIST; PK của bảng theo kỳ là (`period_bucket`, `id`).
- Phân loại cột ghi bằng JSON trong `COMMENT ON COLUMN`; CI kiểm tra; cấu hình CDC sinh tự động từ chú thích. Điểm số và mức xếp loại là C1; nhận xét, khiếu nại, góp ý kiểm điểm, số định danh, ngày sinh là C2 (thống nhất TL-01). Ngoại lệ có lý do: `appraisal.prospect_group.group_code` là C2 không mã hoá, bảo vệ bằng quyền cột.
- Ngữ cảnh tenant chỉ bằng `set_config('app.tenant_id', ?, true)` trong giao dịch, RLS `FORCE`.
- Đặt CSDL: mỗi tenant một "CSDL dữ liệu tenant" `td_<mã>` (9 schema mission-core cùng `org`, `scoring`, `evidence`, `docgen`, `modulith`, `keyring`); `cell_shared` cho `notify`, `integ`; `td_pool` cho cell pool (16 phân vùng con HASH theo tenant). Năm cụm mỗi cell: `pg-biz`, `pg-wf`, `pg-ledger`, `pg-iam`, `pg-sec`.

## 2. Điểm khác với TL-01 cần người tổng hợp quyết định

- TL-01 mục 6 nêu "mỗi schema có connector Debezium và slot riêng". TL-03 dùng một connector outbox mỗi CSDL (cùng một connector CDC phân tích mỗi CSDL) vì mỗi slot giải mã toàn bộ WAL của CSDL; lý do ở mục 10.2. Module tách thành microservice thì có connector riêng.
- TL-01 dùng tên `org.unit`, `org.position_assignment`, `task.assignment`; TL-03 giữ tên baseline `org.org_unit`, `org.assignment`, `task.task_assignee` theo chỉ đạo của người điều phối, và ghi rõ ánh xạ ở chương 7.

## 3. Schema và bảng bổ sung

| Bảng | Mục đích | Nguồn đề xuất |
|---|---|---|
| `<schema>.inbox_aggregate_version` | Phiên bản cao nhất đã áp dụng, xử lý sự kiện sai thứ tự | TL-03 |
| `<schema>.idempotency_key` | Khoá chống lặp API (giữ 48 giờ) | TL-01 |
| `modulith.event_publication` | Event Publication Registry của Spring Modulith, thêm `tenant_id` | TL-01 |
| `keyring.dek` | DEK đã bọc theo tenant × tháng × mục đích (thay `dek_store` của bản nháp) | TL-01 |
| `registry.tenant_status_log`, `registry.tenant_quota` | Lịch sử trạng thái tenant; hạn mức tài nguyên | TL-03; TL-01 |
| `org.person_contact`, `org.person_public_key`, `org.org_change_order` | Liên hệ C2 có chỉ mục mù; khoá công khai E2EE; quyết định tổ chức làm căn cứ phiên bản | TL-03 |
| `org.reporting_line` | Quan hệ báo cáo, hai trục thời gian | TL-01 |
| `org.sync_discrepancy` | Lệch đồng bộ nhân sự | TL-02a |
| `org_replica.unit_closure_v`, `replica_checkpoint`, `reconcile_run` | Bảng closure; con trỏ; đối soát bucket | TL-03 |
| `config.period_status_log` | Nhật ký chuyển trạng thái kỳ | TL-03 |
| `config.period_lock_order`, `config.period_lock_step` | Lệnh khoá kỳ, chiếu bước saga | TL-02a |
| `config.component_registry`, `config.dashboard_def` | Danh mục component SDUI; dashboard tự dựng (đặt ở `config`) | TL-02b |
| `policy.test_case_result` | Kết quả từng ca kiểm thử vàng | TL-03 |
| `okr.objective_basis`, `okr.objective_review` | Nối mục tiêu nhiều căn cứ; ý kiến duyệt | TL-02a |
| `okr.period_state`, `task.period_state`, `product.period_state` | Bản sao trạng thái kỳ cho trigger chặn ghi | TL-03 |
| `task.program`, `task.program_milestone` | Chương trình tầng 2 | TL-03 |
| `task.task_secret_content`, `task.e2ee_key_wrap`, `product.e2ee_key_wrap` | Nội dung mật C3 và khoá bọc | TL-03 |
| `appraisal.batch_decision` | Quyết định xếp loại hàng loạt, ký lô ≤ 1.000 | TL-02b |
| `dossier.remediation_item` | Hạn chế cần khắc phục theo kỳ | TL-02b |
| `flowable.saga_instance`, `flowable.saga_step` | Trạng thái saga | TL-03 |
| `scoring.score_input_v` | Tập đầu vào chuẩn hoá để tái lập | TL-03 |
| `evidence.upload_session` | Tải lên nhiều phần qua URL ký trước | TL-03 |
| `ledger.chain_head`, `ledger.cell_seal`, `ledger.archive_segment` | Đầu chuỗi; niêm phong mức cell (một chữ ký, một token TSA mỗi cell mỗi cửa sổ); lưu trữ Parquet | TL-03, TL-01 |
| `integ.outbound_message`, `integ.transfer_package` | Thông điệp ra; gói chuyển liên cell | TL-03 |

Bỏ khỏi bản nháp: `appraisal.special_case_flag` (trùng `dossier.special_case`).

## 4. Cột bổ sung chính

- Mọi bảng theo kỳ: `period_bucket`.
- `appraisal.appraisal_profile`: `org_asof_recorded_at`, `org_asof_valid_date`, `submitted_at`, `appraiser_id`, `decider_id`.
- `appraisal.score_snapshot`: `snapshot_kind`, `input_hash`, `policy_content_hash`, `policy_version`, `snapshot_hash`, `group_scores_json`, `detail_json`, `superseded_by_id`, `rule_checks`, `downgrade_reasons` (`exceed_ratio` = `over_target_ratio` của TL-02b).
- `appraisal.rating_proposal`: cột bước đổi tên thành `level_kind` (theo TL-02b); `rating_code` (TL-02b gọi `level`); `reason_enc`, `comment_enc`, `dek_id`, `batch_decision_id`, `is_current`, `explanation_required`.
- `appraisal.annual_rating`: `block_reason`, `reassign_flag`, `system_level`, `has_khtnv_quarter`, `consecutive_khtnv`.
- `task.task_assignee`: hai trục thời gian (`valid_from/to`, `recorded_from/to`).
- `task.task`: `source_type`, `kr_id`, `acceptor_id`, `milestones`, `score_due_date`, `delay_risk`; `task.plan_version`: `is_current`, `content_hash`, `review_comment`; `task.plan_item`: `origin_item_id`, `change_reason`, `co_assignees`, `recurrence`.
- `product.evidence_version`: `excluded_period_id`, `reuse_justification`, `attested_at`, `doc_*`; `product.acceptance`: `criteria_results`, `revision_cause`, `comment_enc`.
- `okr.objective`/`key_result`: `ai_assisted`, `risk_flag`, `content_source`…; `okr.kr_checkin`: `status`, `source_value`, `override_reason`.
- `config.period`: `milestones`, `plan_deadline`; `config.ui_pack_version`: `policy_pack_ref`, `tokens_json`, `assets_json`, `layout_json`, `elder_mode`, `few_steps`.
- `registry.tenant`: `onboarding_code`, `contract_ref`, `flows`, `expected_accounts`, `account_count`, `kek_ref`; `registry.cell.capacity_accounts`; `registry.cell.active_dc` (TTDL đang phục vụ cell, cờ `active_site` của TL-04 mà `dns-sync` đọc; NULL nghĩa là `primary_dc`, CHECK thuộc `primary_dc` hoặc `standby_dc`; migration V4 của SVC-03, INF-20); `registry.tenant_cell.tenancy_model`, `data_region`.
- `dossier.review_meeting`: `draft_sent_at`, `meeting_at`, `participants_json`, `vote_json`; `oversight.complaint`: `kind`, `targets_json`; `oversight.reopen_request`: `scope_json`, `open_until`, `code`; `flowable.wf_binding`: `scope_json`, `params_json`; `ai.suggestion_log`: `accepted_by`, `edited_fields`, `decision`, `sources_json`.
- Cột chỉ mục mù `<cột>_bidx` (HMAC-SHA256 cắt 16 byte) cho C2 cần tra cứu bằng.

## 5. Trạng thái

Đã áp dụng bộ trạng thái của TL-02a, TL-02b cho: `registry.tenant` (thêm `DANG_CHUYEN_CELL`), `task.task_change_request`, `okr.kr_checkin`, `okr.key_result.risk_flag`, `config.period_lock_order`, `config.period_lock_step`, `config.ui_pack_version`, `dossier.dossier_package`, `oversight.complaint` (thêm `RUT`), `oversight.reopen_request` (TL-03 thêm `DA_THUC_HIEN`, `DA_DONG`), `oversight.anomaly_alert`. Trạng thái mới của TL-03: `appraisal.batch_decision`, `dossier.remediation_item`, `flowable.saga_instance`, `flowable.saga_step`, `keyring.dek`.

## 6. Sự kiện và topic

- EVT-26 KyDaHuyKhoa `config.period.unlocked.v1` đưa vào danh mục chương 11 và bước bù trừ saga.
- Topic hệ thống theo TL-01: `sys.ledger.append.v1`, `sys.audit.v1`, `sys.evidence.object.stored.v1`, `sys.tenant.provisioned.v1`, `iam.auth.event.v1`; DLQ `<topic>.dlq.<consumer>`, thử lại `<topic>.retry.<consumer>`.
- Topic kỹ thuật TL-03 thêm: `period.drain.marker`, `org.bootstrap.v1`, `registry.tenant.changed.v1`, `cdc.<db>.<schema>.<table>`, `agg.*`.
- EVT-20 không đi qua outbox PostgreSQL (producer đọc bảng `anomaly_out` của ClickHouse, `event_id` tất định).

## 7. Sổ cái (thống nhất TL-01)

`ledger.ledger_entry` không có cột nào được cập nhật (đã bỏ `seal_id` của bản nháp); phân vùng mức 1 theo `tenant_id` (LIST, HASH 16 ở pool), mức 2 theo tháng; trigger chặn UPDATE, DELETE, TRUNCATE. Liên kết niêm phong qua `merkle_seal.chain_ranges`. Cây Merkle hai mức (lá 0x01, nút 0x02), RFC 8785 với NFC, một chữ ký và một token TSA mỗi cell mỗi giờ, 5 phút trong 3 ngày cuối kỳ; neo hằng ngày tới ít nhất hai bên độc lập; chuỗi nối tiếp khi chuyển cell.
