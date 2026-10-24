# DV-09-01 · SVC-09 Khung service `ledger` và migration CSDL chỉ ghi thêm

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-09-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | NEN-017; cần NEN-030, NEN-031 (quy ước Flyway, vai trò `svc_/mig_/ro_/cdc_`) đã gộp (bổ sung, hiển nhiên vì task tạo migration và tài khoản CSDL) |
| Truy vết | QT-13 → SVC-09 ledger-service → bảng `ledger.ledger_entry`, `ledger.chain_head`, `ledger.merkle_seal`, `ledger.cell_seal`, `ledger.anchor_receipt`, `ledger.verify_run`, `ledger.archive_segment`, `ledger.outbox_event` → ADR-09, ADR-26 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng khung service Go `ledger` (SVC-09) dùng thư viện Go dùng chung của NEN-017 và tạo bằng migration toàn bộ CSDL `ledger` đúng từ điển TL-03: bảng `ledger_entry` phân vùng hai mức (tenant rồi tháng) chỉ ghi thêm theo nghĩa chặt, các bảng niêm phong, bản neo, kiểm tra, lưu trữ. Khi xong, mọi task DV-09 sau có nền dữ liệu mà chính CSDL tự bảo vệ tính bất biến: UPDATE, DELETE, TRUNCATE trên `ledger_entry` đều bị từ chối với mọi vai trò, kể cả vai trò migration.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Schema ledger (SVC-09)" (L3473–3692): "Thiết kế", "DDL mẫu: ledger.ledger_entry", "DDL mẫu: ledger.merkle_seal", "Từ điển dữ liệu" (bảy bảng).
- TL-03 chương "Sổ cái bất biến" (L4088–4140) và mục "Phân vùng" (L4560), "Flyway và quy trình thay đổi lược đồ" (L4592).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-09 ledger-service" (L1108–1116) và chương "Toàn vẹn dữ liệu và bằng chứng pháp lý" mục "Sổ cái chuỗi băm phân vùng" (L2562).
- `00-co-so-thiet-ke-chung.md` mục 5 (SVC-09), mục 9 (schema `ledger`), ADR-09.
- `contracts/openapi/modules/oversight.yaml` phần `/ledger/*` (để biết cột nào API cần đọc); bản trích `extract/tl03-phan2.md` mục A7, B2.

## 3. Phạm vi công việc

- Tạo khung `services-go/ledger/` theo mẫu Go của NEN-017: `cmd/ledger-service`, gói `internal/{store,ingest,seal,verify,api}`, cấu hình, probe `/healthz` và `/readyz`, metric OpenTelemetry, Dockerfile không root.
- Viết migration tạo `ledger.ledger_entry` đúng DDL TL-03: PK (`tenant_id`, `recorded_month`, `chain_partition`, `seq_no`), `PARTITION BY LIST (tenant_id)` rồi `RANGE (recorded_month)`, CHECK `chain_partition` 0..63, CHECK độ dài 32 byte cho bốn cột băm, CHECK `actor_kind` IN (`NGUOI`,`DICH_VU`,`HE_THONG`), UQ (`tenant_id`,`recorded_month`,`event_id`), hai chỉ mục `ix_ledger_entry__aggregate`, `ix_ledger_entry__chain_seq`.
- Tạo hàm `ledger.fn_ledger_immutable()` cùng hai trigger `tg_ledger_entry__no_update` (BEFORE UPDATE OR DELETE) và `tg_ledger_entry__no_truncate`; REVOKE ALL FROM PUBLIC; GRANT chỉ SELECT, INSERT cho `svc_ledger`; SELECT cho `ro_ledger`, `cdc_ledger`; không vai trò nào có UPDATE/DELETE, kể cả `mig_ledger`.
- Tạo `chain_head` (`fillfactor=50`, được UPDATE), `merkle_seal`, `cell_seal`, `anchor_receipt` (CHECK `anchor_target` IN (`CELL_KHAC`,`KHO_VPTW`,`TSA_DOC_LAP`,`OBJECT_LOCK`)), `verify_run` (CHECK `verify_kind` IN (`DAU_CHUOI`,`NIEM_PHONG`,`TOAN_BO`,`LUU_TRU`)), `archive_segment`, cùng `outbox_event` cho EVT-19 và EVT-20.
- Viết lệnh tạo trước phân vùng tháng cho mỗi tenant (`ensure-partitions`, chạy theo lịch) và thủ tục tạo phân vùng tenant khi tenant mới được cấp (suy luận theo DB-LED-02).
- Ghi `COMMENT ON COLUMN` phân loại C0–C3 cho mọi cột (cột `metadata` là C1, không chứa C2 dạng rõ).
- Điểm lệch cần lưu ý: TL-01 (L2562–2595) đặt tên cột `chain_no`, `seq` và công thức `entry_hash = SHA-256(0x01 || canonical(header) || payload_hash || prev_hash)`, trong khi TL-03 (L3489–3545) dùng `chain_partition`, `seq_no`, `metadata_hash` và `entry_hash = SHA-256(prev_hash || payload_hash || metadata_hash)`; task theo TL-03 (tài liệu CSDL chi tiết hơn và viết sau) và ghi lựa chọn vào `wiki/14`.

## 4. Ngoài phạm vi

- Thư viện băm và luồng ghi chuỗi (DV-09-02), niêm phong (DV-09-03), kiểm tra (DV-09-04), API tra cứu (DV-09-05).
- Cụm PostgreSQL riêng `pg-ledger` trên Kubernetes (INF-11) và job lưu trữ Parquet, DETACH có hai người duyệt (DL-13).

## 5. Tệp được phép sửa

- `services-go/ledger/**`
- `infra/dev/` (chỉ thêm CSDL `ledger` và vai trò cho profile backend nếu NEN-020 chưa có)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/ledger && go vet ./... && go test ./...` chạy xanh, gồm kiểm thử tích hợp Testcontainers PostgreSQL 17 áp toàn bộ migration từ CSDL trống.
- Kiểm thử chứng minh UPDATE, DELETE, TRUNCATE trên `ledger_entry` bị từ chối với `svc_ledger` và với `mig_ledger`; chèn trùng `event_id` trong cùng tenant và tháng bị UQ chặn; chèn `chain_partition = 64` bị CHECK chặn.
- `govulncheck ./...` không có lỗ hổng mức cao; lint phân loại cột của NEN-030 không báo lỗi trên schema `ledger`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki trang 14 ghi điểm lệch tên cột và công thức băm đã chọn.

## 7. Báo cáo

Theo AGENTS.md mục 6.
