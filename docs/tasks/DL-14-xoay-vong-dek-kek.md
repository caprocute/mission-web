# DL-14 · ADR-17/ADR-30 Xoay vòng DEK định kỳ và bọc lại khoá theo KEK mới

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-14` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 (cần trước lần xoay KEK hằng năm đầu tiên ở sản xuất) |
| Luồng đề xuất | B11 |
| Cỡ việc | M (gợi ý tách: (a) chuyển trạng thái DEK đầu tháng và metric; (b) bọc lại DEK theo KEK mới; (c) mã hoá lại dữ liệu khi lộ DEK) |
| Phụ thuộc | NEN-015 (`DekService`, `keyring.dek`, client OpenBao Transit), INF-13 (OpenBao, Transit, lịch xoay vòng OPS-20); DV-09-02 (sổ cái) (bổ sung) |
| Truy vết | ADR-17, ADR-30 → TL-01 "Phân cấp khoá", ba loại xoay vòng (L2256, L2297) → TL-03 "Schema keyring và bảng dek" (L910–931), mục mã hoá (L4150–4166) → TL-04 "Quản lý bí mật và khoá" (L2866), OPS-20 (`vt_key_age_days`) → bảng `keyring.dek` (`status` `ACTIVE`, `DECRYPT_ONLY`, `RETIRED`, `DESTROYED`), mọi cột `dek_id` → `sys.audit.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

NEN-015 sinh DEK theo tenant, tháng, mục đích khi cần, nhưng chưa có ai làm ba loại xoay vòng mà TL-01 mô tả: chuyển DEK tháng trước sang chỉ giải mã đầu mỗi tháng, bọc lại toàn bộ DEK bằng phiên bản KEK mới khi KEK xoay hằng năm hay nghi lộ (không đụng dữ liệu), và mã hoá lại dữ liệu khi nghi một DEK bị lộ. Task này viết các job đó cho mọi CSDL có schema `keyring`, có sổ cái và chỉ số tuổi khoá. Khi xong, OPS-20 chạy được bằng lịch tự động, xoay KEK của một tenant vài trăm DEK xong trong vài giây, và dữ liệu cũ vẫn đọc được sau mọi lần xoay.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Phân cấp khoá" (L2256), "Có ba loại xoay vòng" (L2297–2299: DEK tháng, KEK năm bằng `transit/rewrap`, mã hoá lại khi lộ), ADR-17 (L3552), ADR-30 (L3682).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema keyring và bảng dek" (L910–931: `wrapped_dek` dạng `vault:v<n>:...`, `kek_ref`, `alg_version`, vòng đời), "Mã hoá" (L4150, L4158: đầu tháng sinh DEK mới, tháng trước `DECRYPT_ONLY`; mã hoá lại theo lô nhỏ ngoài giờ cao điểm), xoá bằng mật mã (L4166).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Quản lý bí mật và khoá: OpenBao, ESO, HSM" (L2866), OPS-20 (L3861).
- Thẻ `docs/tasks/NEN-015-starter-ma-hoa-truong.md` (định dạng bản mã, `DekCache`, ngoài phạm vi ghi việc xoay), `INF-13-openbao-pki-hsm.md` (CronJob lịch xoay, `vt_key_age_days`), `DL-13-luu-giu-xoa-mat-ma.md` (huỷ DEK).

## 3. Phạm vi công việc

- Job đầu tháng (mỗi CSDL có `keyring`): sinh trước DEK `ACTIVE` của tháng mới cho mọi (tenant, mục đích) đang dùng, chuyển DEK tháng trước sang `DECRYPT_ONLY`; chạy lại được nhiều lần không sinh trùng (UQ `tenant_id`, `purpose`, `valid_month`); `DekCache` 15 phút của NEN-015 tự hết hạn nên không cần khởi động lại ứng dụng.
- Job bọc lại theo KEK mới: khi KEK của tenant có phiên bản mới, gọi `transit/rewrap` theo lô cho mọi `wrapped_dek` còn ở `ACTIVE`, `DECRYPT_ONLY`, `RETIRED`, cập nhật `wrapped_dek` và `kek_ref` trong cùng giao dịch theo lô, không bao giờ có DEK rõ trong ứng dụng; sau khi xong mới cho phép đặt `min_decryption_version` của KEK (bước vận hành có duyệt, ghi trong runbook).
- Job mã hoá lại khi nghi lộ một DEK: đánh dấu DEK nghi lộ, đọc theo lô nhỏ các dòng có `dek_id` đó ở mọi bảng có cột `_enc` (danh sách sinh từ chú thích phân loại cột), giải mã, mã hoá lại bằng DEK `ACTIVE` mới qua `FieldEncryptor`, cập nhật kèm `row_version`; chạy ngoài giờ cao điểm, dừng và tiếp tục được, xong thì DEK cũ sang `RETIRED`. Chỉ chạy khi có phê duyệt hai người (suy luận theo nguyên tắc bốn mắt của thao tác nhạy cảm).
- Ghi sự kiện `sys.audit.v1` cho mỗi lần chạy (loại xoay, tenant, số DEK, số dòng), metric `vt_key_age_days` theo loại khoá và metric tiến độ job; cảnh báo theo OPS-20 khi quá hạn 7 ngày.
- Điểm lệch cần lưu ý: tên KEK khác nhau giữa các nguồn: TL-01 `kek-<tenant>`, ví dụ `kek_ref` TL-03 `transit/keys/t-hcm-c2@v3`, INF-13 `kek-tenant-<tenantId>`; dùng đúng tên INF-13 đã cấu hình và chỉ đọc từ `kek_ref`, không suy tên từ mã; ghi vào `wiki/14`.
- Bản cách ly dùng nhà cung cấp mật mã Ban Cơ yếu (`alg_version` khác 1): job chỉ gọi qua `CryptoProvider`, không gắn cứng AES-GCM.
- Viết `ops/runbooks/xoay-vong-khoa-du-lieu.md` (xoay KEK hằng năm, xử lý nghi lộ DEK, kiểm chứng đọc lại mẫu, quay lui khi job dừng giữa chừng).

## 4. Ngoài phạm vi

- Cụm OpenBao, HSM, cấu hình Transit và CronJob lịch chung (INF-13); huỷ DEK để xoá bằng mật mã (DL-13); khoá E2EE C3 (CORE-07-06).

## 5. Tệp được phép sửa

- `backend/libs/key-rotation/**` (mới, vị trí suy luận), khai báo mô-đun trong `backend/pom.xml`
- `deploy/charts/*/templates/*key-rotation*` (CronJob, Job theo yêu cầu)
- `ops/runbooks/xoay-vong-khoa-du-lieu.md`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, `wiki/20-bao-mat-phia-may-chu.md` (chỉ mục xoay vòng)

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl libs/key-rotation -am verify` xanh gồm Testcontainers PostgreSQL 17 và OpenBao chế độ dev có Transit: đổi tháng sinh DEK mới, DEK cũ `DECRYPT_ONLY` vẫn giải mã; xoay KEK rồi rewrap 300 DEK, đọc lại mọi bản mã mẫu thành công và `kek_ref` trỏ phiên bản mới; mã hoá lại một DEK nghi lộ xong thì không còn dòng nào tham chiếu nó.
- Kiểm thử chứng minh không có DEK rõ trong nhật ký, metric, sự kiện; job chạy hai lần liên tiếp không đổi kết quả.
- `helm lint` và `helm template` không lỗi; runbook có bước kiểm chứng và quay lui; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
