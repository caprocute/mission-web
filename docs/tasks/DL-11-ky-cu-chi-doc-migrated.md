# DL-11 · ADR-09/ADR-20 Chuyển dữ liệu kỳ cũ dạng chỉ đọc và niêm phong sổ cái loại `MIGRATED`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | DL-10, DV-09-03 (bổ sung hiển nhiên: CORE-08-03 chặn ghi kỳ đã khoá) |
| Truy vết | ADR-09, ADR-20; TL-03 "Di trú từ hệ hiện tại…" (dữ liệu chuyển ghi sổ cái loại `MIGRATED`) → API đọc `GET /api/v1/ledger/entries` (`listLedgerEntries`), `GET /api/v1/oversight/ledger/proofs` (`getLedgerInclusionProof`), `POST /api/v1/ledger/verify-runs` (`createLedgerVerifyRun`) → MOD-08, MOD-04, SVC-09 → bảng `config.period`, `appraisal.score_snapshot`, `ledger.ledger_entry`, `ledger.merkle_seal` → EVT-19 |
| Trạng thái | Chưa bắt đầu (làm được khi DL-10 xong, tức là khi đã có quyền đọc Oracle hệ hiện hành) |

## 1. Mục tiêu

Dữ liệu các kỳ đánh giá đã hoàn tất ở hệ cũ có mặt trên nền tảng mới ở dạng chỉ đọc: kỳ ở trạng thái `DA_KHOA`, mọi lệnh ghi vào kỳ đó trả 409 `period-locked`, và mỗi bản ghi chuyển sang có một mục sổ cái loại `MIGRATED` mang băm của dòng nguồn, được niêm phong Merkle và kiểm chứng bằng `ledger-verify`. Đây là điều kiện chuyển bước của đợt 2 ("dữ liệu kỳ cũ chuyển chỉ đọc + niêm phong sổ cái bản chuyển đổi") và giúp chứng minh sau này dữ liệu không bị sửa khi chuyển.

## 2. Đầu vào bắt buộc đọc

- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Di trú từ hệ hiện tại theo mẫu Strangler Fig" (dòng 4600–4618), chương "Sổ cái bất biến" (mục "Ghi một bản ghi", "Niêm phong Merkle và dấu thời gian").
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: chương "Toàn vẹn dữ liệu và bằng chứng pháp lý" (mục "Định dạng băm chuẩn hoá", "Công cụ kiểm chứng độc lập"); "Lộ trình chuyển đổi theo Strangler Fig", mục "Đợt 2".
- Hợp đồng `contracts/openapi/modules/oversight.yaml` (nhóm `/ledger/*`), `contracts/openapi/modules/config.yaml` (`getPeriod`).
- Kết quả DL-09, DL-10; `wiki/04-ma-dinh-danh-va-truy-vet.md`.

## 3. Phạm vi công việc

- Mở rộng công cụ của DL-10 thêm bước "chuyển kỳ cũ": tạo `config.period` cho từng kỳ cũ, nạp dữ liệu, rồi đặt kỳ sang `DA_KHOA` theo đúng máy trạng thái kỳ (không ghi thẳng cột trạng thái nếu MOD-08 có API nội bộ).
- Hồ sơ đánh giá cũ ghi `snapshot_kind = 'CHOT_KY'` và gắn Policy Pack "di trú" (gói đánh dấu không dùng để tính lại; định danh do agent đặt, ghi "(suy luận)"); `POST /appraisals/profiles/{id}/recompute` trên hồ sơ di trú phải bị từ chối có lý do rõ.
- Với mỗi dòng nguồn đã chuyển, phát yêu cầu ghi sổ cái qua `sys.ledger.append.v1` với loại sự kiện `MIGRATED`, `payload_hash` là SHA-256 của dòng nguồn sau chuẩn hoá RFC 8785, `event_id` tất định để phát lại không trùng.
- Yêu cầu niêm phong ngay sau khi nạp xong một kỳ (giống saga gọi niêm phong ở mốc pháp lý) và chạy `ledger-verify` trên gói xuất của kỳ đó.
- Kiểm tra rằng lệnh ghi vào kỳ di trú (tạo nhiệm vụ, nộp minh chứng, sửa tự đánh giá) trả 409 `period-locked` theo RFC 9457.
- Ghi runbook chạy theo từng kỳ, có bước kiểm chứng (R8 = 0, `ledger-verify` hợp lệ) và quay lui (xoá kỳ chưa niêm phong; kỳ đã niêm phong thì không xoá, chỉ đánh dấu thay thế).

## 4. Ngoài phạm vi

- Thuật toán sổ cái, niêm phong, bản neo (DV-09-xx); khoá kỳ thường qua saga (CORE-08-05).
- Chuyển tenant đang hoạt động sang hệ mới (LEG-11).

## 5. Tệp được phép sửa

- `backend/tools/legacy-migration/**`
- `data/policy-pack/di-tru/**` (gói đánh dấu, suy luận)
- `data/migration/oracle/**`
- `ops/runbooks/chuyen-du-lieu-ky-cu.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl tools/legacy-migration -am verify` xanh, kiểm thử tích hợp chạy cùng ảnh ledger-service (Go) và PostgreSQL 17 trong Testcontainers.
- Trên một kỳ giả lập: số mục `MIGRATED` bằng số dòng nguồn; `ledger-verify` báo hợp lệ cả năm bước; R8 = 0.
- Mọi lệnh ghi thử vào kỳ di trú trả 409 `period-locked`; `recompute` trên hồ sơ di trú bị từ chối.
- Chạy lại toàn bộ bước không tạo thêm mục sổ cái.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
