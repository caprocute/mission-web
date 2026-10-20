# NEN-002 · SVC-05/ADR-01 Wiki backend phần 1: kiến trúc mission-core, microservice, CSDL và đa khách thuê

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-002` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | M |
| Phụ thuộc | NEN-001 |
| Truy vết | ADR-01, ADR-02, ADR-07, ADR-22, ADR-23 → SVC-01..SVC-18, MOD-01..MOD-08 → schema theo baseline mục 9 (`config`, `policy`, `okr`, `task`, `product`, `appraisal`, `dossier`, `oversight`, `org_replica`, `modulith`, `keyring`) |
| Trạng thái | Hoàn thành (20/10/2026, đã gộp vào `main`: `wiki/16-kien-truc-mission-core-va-microservice.md`, `wiki/17-thiet-ke-csdl-va-da-khach-thue.md`) |

## 1. Mục tiêu

Viết hai trang wiki mới cho người làm phía máy chủ: trang 16 "Kiến trúc mission-core và microservice" và trang 17 "Thiết kế CSDL và đa khách thuê". Wiki hiện chỉ phục vụ frontend (trang 01–15a), nên agent làm task NEN, CORE, DV phải tự đọc lại TL-01 và TL-03 rất dài cho mỗi task. Khi task xong, một agent mới chỉ cần đọc hai trang này (mỗi trang khoảng 150–250 dòng) để biết mission-core chia gói thế nào, module nào được gọi module nào, mỗi service dùng ngôn ngữ và CSDL gì, quy ước cột, vai trò CSDL, RLS và ba mô hình thuê bao, kèm dòng "Nguồn" trỏ đúng mục TL để tra sâu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 4 (ADR), 5 và 5.1 (SVC, MOD), 6 (cell), 8, 9, 9.1, 13.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Thiết kế chi tiết từng thành phần": mục "SVC-05 mission-core: lõi Modular Monolith" (L377–901, gồm "Cấu trúc gói Java theo module", "Khai báo module bằng Spring Modulith", "Quy tắc ArchUnit", "Giao tiếp giữa module", "Quản lý giao dịch", "Outbox", "Cấu hình mẫu") và các mục SVC-01..SVC-18 (L903–1226); chương "Đa khách thuê" (L2653–2740); Phụ lục A ADR-21..ADR-24 (L3592–3630).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Giới thiệu" (quy ước đặt tên L44–72, kiểu dữ liệu L74–95, cột chung L97–115, phân loại C0–C3 L117–137), "Nguyên tắc thiết kế dữ liệu" (L139–197), "Danh mục microservice, module và kho dữ liệu" (L288–617), "Mô hình thuê bao và phân bổ cơ sở dữ liệu theo cell" (L619–758).
- `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-01.md`, `_ghi-chu-TL-03.md` (bảng bổ sung, điểm mâu thuẫn).
- `docs/quy-dinh/BACKEND.md`; `wiki/README.md` (quy tắc cập nhật wiki); `wiki/01-tong-quan-he-thong.md`, `wiki/02-kien-truc-frontend.md` (để không lặp nội dung).

## 3. Phạm vi công việc

- Tạo `wiki/16-kien-truc-mission-core-va-microservice.md` gồm: vai trò SVC-05; cây gói `vn.viettel.okrkpi` với `shared` (kernel, tenant, outbox, crypto, authz, error) và tám module mỗi module bốn lớp `api`, `application`, `domain`, `infrastructure` cùng gói `internal`; ma trận `allowedDependencies` đúng TL-01 (config ← policy ← okr ← task ← product ← appraisal; dossier, oversight) kèm danh sách EVT mỗi module nhận.
- Trang 16 có bảng tóm tắt 17 service còn lại (mã SVC, tên triển khai, ngôn ngữ, CSDL hoặc schema sở hữu, sự kiện phát và nhận chính, kiểu co giãn) lấy từ baseline mục 5 và TL-01, và một mục "Quy tắc giao tiếp" nêu: gọi đồng bộ qua `*.api`, sự kiện ứng dụng `@ApplicationModuleListener` với Event Publication Registry (ADR-23), sự kiện ra ngoài luôn qua outbox, REST giữa service có Resilience4j (ADR-24).
- Trang 16 có mục "Quản lý giao dịch" tóm tắt TL-01 (giao dịch ở lớp `application`, `READ COMMITTED`, `row_version` và 409, không gọi mạng trong giao dịch, `IdempotencyStore`) và mục "Vị trí mã trong repo" trỏ tới `backend/`, `services-go/`, `services-py/`, `contracts/` theo `docs/quy-dinh/BACKEND.md`.
- Tạo `wiki/17-thiet-ke-csdl-va-da-khach-thue.md` gồm: quy ước đặt tên đối tượng (`pk_`, `fk_`, `uq_`, `ck_`, `ix_`, `rls_<bảng>__tenant`, `tg_`, `fn_`), kiểu chuẩn, bảy cột chung và `period_bucket`, cách chú thích phân loại cột `COMMENT ON COLUMN ... '{"cls":"C2",...}'`, quy tắc `_enc` + `dek_id` và `_bidx`.
- Trang 17 có mục vai trò CSDL bốn loại `mig_/svc_/ro_/cdc_<schema>` và các ngoại lệ (`svc_org_replica`, `modulith.event_publication`, `keyring.dek`), mục RLS (`ENABLE` + `FORCE`, `set_config('app.tenant_id', ?, true)`, cấm `SET` mức phiên qua PgBouncer chế độ giao dịch), mục ba mô hình silo, bridge, pool với cách bố trí CSDL `td_<mã>`, `td_pool`, `cell_shared` và định tuyến 421.
- Trang 17 có mục "Bảng bổ sung ngoài baseline" liệt kê bảng TL-03 đánh dấu bổ sung có ảnh hưởng tới mọi schema (`inbox_aggregate_version`, `idempotency_key`, `modulith.event_publication`, `keyring.dek`, `org_replica.unit_closure_v`…).
- Mỗi trang mở đầu bằng dòng "Nguồn" (tên tệp TL và tiêu đề mục) và kết thúc bằng mục "Liên quan"; nội dung suy luận đánh dấu "(suy luận)"; chỗ hai nguồn nói khác nhau ghi một câu và trỏ sang trang 14 (NEN-004 sẽ gom và đề xuất chốt).
- Điểm lệch cần lưu ý: TL-03 bố trí năm cụm PostgreSQL mỗi cell (`pg-biz`, `pg-wf`, `pg-ledger`, `pg-iam`, `pg-sec`) còn TL-04 dùng sáu cụm (`pg-core`, `pg-flowable`, `pg-platform`, `pg-iam`, `pg-ledger`, `pg-secure`); trang 17 trình bày cả hai, không tự chọn.
- Cập nhật `wiki/README.md`: thêm hai dòng mục lục cho trang 16, 17 và một câu trong phần "Cách dùng" chỉ người làm máy chủ đọc 16, 17 sau trang 01.

## 4. Ngoài phạm vi

- Sự kiện, đồng bộ, hạ tầng production và bảo mật phía máy chủ (trang 18, 19, 20): NEN-003.
- Gom và đề xuất chốt điểm lệch ở trang 14: NEN-004.
- Thiết kế chi tiết từng schema nghiệp vụ (từ điển dữ liệu từng bảng): các task migration CORE-0x-01, DV-xx-01 tự đọc TL-03.

## 5. Tệp được phép sửa

- `wiki/16-kien-truc-mission-core-va-microservice.md` (mới)
- `wiki/17-thiet-ke-csdl-va-da-khach-thue.md` (mới)
- `wiki/README.md` (chỉ mục lục và phần "Cách dùng")

## 6. Tiêu chí hoàn thành

- Mọi mã ADR, SVC, MOD, EVT, tên schema, tên bảng xuất hiện trong hai trang đều có trong baseline hoặc TL-01/TL-03; kiểm bằng một lệnh `grep -o` liệt kê mã rồi tra ngược từng mã trong `/mnt/project-files/kien-truc-okr-kpi/src/` (ghi lệnh và kết quả vào báo cáo).
- Ma trận phụ thuộc module trên trang 16 khớp từng dòng với TL-01 L450–480.
- Không có địa chỉ máy chủ, tài khoản, mật khẩu, token trong hai trang (`grep -nEi 'password|secret|token=|[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}'` không trả dòng nào ngoài giải thích khái niệm).
- Mỗi trang có dòng "Nguồn" ở đầu, mục "Liên quan" ở cuối; liên kết tương đối giữa các trang wiki mở được.
- Báo cáo cuối task theo AGENTS.md mục 6; mục "Cập nhật wiki" ghi rõ trang 16, 17 và README.

## 7. Báo cáo

Theo AGENTS.md mục 6.
