# NEN-015 · ADR-06/ADR-17/ADR-30 vt-platform-starter: mã hoá trường phong bì, DekCache, chỉ mục mù, OpenBao Transit

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-015` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | L (nên tách: a) định dạng bản mã + `FieldEncryptor` + `DekCache`; b) OpenBao Transit + sinh DEK + chỉ mục mù) |
| Phụ thuộc | NEN-009, NEN-032 |
| Truy vết | ADR-06, ADR-17, ADR-30 → TL-01 "Mã hoá phong bì cho dữ liệu C2" → TL-03 "Mã hoá phong bì cho cột C2", "Chỉ mục mù" → bảng `keyring.dek`, cột `<cột>_enc` + `dek_id`, `<cột>_bidx` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cung cấp cho mọi service Java khả năng mã hoá trường dữ liệu C2 trước khi rời ứng dụng, đúng mô hình khoá phong bì: DEK AES-256 theo tenant × tháng × mục đích, được bọc bằng KEK của tenant trong OpenBao Transit, lưu đã bọc trong `keyring.dek`, mở bọc và giữ trong bộ nhớ tối đa 15 phút. Cùng lúc cung cấp chỉ mục mù để so khớp bằng trên cột mã hoá. Khi xong, task nghiệp vụ chỉ cần đánh dấu thuộc tính `@Classified(C2)` là cột `_enc` được ghi và đọc đúng định dạng, không ai đặt khoá trong mã hay cấu hình.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Chuyên đề G3": "Phân loại dữ liệu C0 đến C3" (L2235–2248), "Mã hoá phong bì cho dữ liệu C2" (L2250–2307: mô hình khoá, định dạng bản mã, bộ đệm DEK, xoay vòng, chỉ mục mù); ADR-06 (L3442), ADR-17 (L3552).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Mã hoá dữ liệu" (L4142–4183) và bảng `keyring.dek` (L910–927).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Quản lý bí mật và khoá: OpenBao, ESO, HSM" (L2866–2972).

## 3. Phạm vi công việc

- Gói `crypto` trong `vt-platform-starter`: giao diện `CryptoProvider` (mặc định AES-256-GCM của JCA; thay được bằng nhà cung cấp mật mã Ban Cơ yếu ở bản cách ly) và `FieldEncryptor.encrypt/decrypt(tenant, purpose, aadParts, plaintext)`.
- Định dạng bản mã đúng TL-01/TL-03: `version(1 byte, 0x01) || dek_ref(16 byte) || nonce(12 byte ngẫu nhiên) || ciphertext || tag(16 byte)`; AAD = `tenant_id|schema.bảng.cột|id dòng`; giải mã sai AAD (chép bản mã sang dòng khác) phải thất bại.
- `DekCache`: trong heap, khoá `(tenant, purpose, month)`, thời hạn 15 phút, tối đa 10.000 mục, ghi đè 0 lên mảng khoá khi đẩy ra; không bao giờ ghi DEK rõ ra Valkey, nhật ký hay đĩa.
- `DekService`: lấy DEK `ACTIVE` của tháng hiện hành từ `keyring.dek`, nếu chưa có thì sinh DEK mới, bọc qua OpenBao Transit `kek-<tenant>` (`transit/encrypt`), chèn dòng (UQ `(tenant_id, purpose, valid_month)` chống sinh trùng giữa pod); giải mã dùng `dek_id` trong bản mã kể cả DEK `DECRYPT_ONLY`; DEK `DESTROYED` thì trả lỗi rõ (xoá bằng mật mã).
- Client OpenBao tối giản qua HTTP (timeout 500 ms/2 s theo TL-01 "Timeout mặc định"), xác thực bằng Kubernetes auth hoặc token từ tệp do CSI/ESO gắn; không đọc khoá từ biến cấu hình dạng rõ.
- Chỉ mục mù: `BlindIndexer.compute(tenant, column, value)` = HMAC-SHA256 với khoá HMAC theo tenant + cột lấy từ OpenBao, giá trị chuẩn hoá (NFC, bỏ khoảng trắng thừa, chữ thường khi phù hợp), cắt 16 byte.
- Tích hợp JPA: `@Classified(C2)` (từ `vt-kernel`) trên thuộc tính bắt buộc đi kèm `AttributeConverter` mã hoá; luật ArchUnit chặn thuộc tính `@Classified(C2)` thiếu converter và chặn `toString()` in trường C2 (TL-01 "Che dữ liệu nhạy cảm").
- Kiểm thử tích hợp Testcontainers: PostgreSQL với `keyring.dek` (NEN-032) và OpenBao chế độ dev có Transit; vòng mã hoá/giải mã; hai pod giả lập sinh DEK cùng lúc chỉ có một dòng; đổi tháng sinh DEK mới và DEK cũ vẫn giải mã được.
- Điểm lệch cần lưu ý: TL-01 nêu mục đích DEK `pii`, `appraisal-comment`, `complaint`, còn TL-03 CHECK `keyring.dek.purpose` dùng `NHAN_XET_DANH_GIA`, `KHIEU_NAI`, `GOP_Y_KIEM_DIEM`, `DINH_DANH_CA_NHAN`, `TEP_MINH_CHUNG`, `BLIND_INDEX`; task theo TL-03.

## 4. Ngoài phạm vi

- Job xoay DEK đầu tháng, xoay KEK, mã hoá lại khi lộ khoá, xoá bằng mật mã khi chấm dứt hợp đồng: chưa có hàng riêng trong danh mục, ghi vào báo cáo để thread điều phối bổ sung.
- E2EE C3 (`@vt/e2ee`, `e2ee_key_wrap`): task web và task nghiệp vụ liên quan.
- Ghi sổ cái mỗi lần giải mã: CORE-00-05; cụm OpenBao production: INF-13.

## 5. Tệp được phép sửa

- `backend/libs/vt-kernel/**` (chỉ annotation `@Classified` nếu NEN-009 chưa có)
- `backend/libs/vt-platform-starter/**` (gói `crypto`, kiểm thử)
- `backend/platform-bom/pom.xml` (chỉ thêm Testcontainers Vault/OpenBao nếu cần)
- `wiki/20-bao-mat-phia-may-chu.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter -am verify` chạy xanh với các ca ở mục 3.
- Vector kiểm thử cố định (khoá, nonce, AAD, bản rõ → bản mã) được lưu trong tài nguyên kiểm thử để thư viện Go/Python (NEN-017, NEN-018) đối chiếu nếu cần.
- Đo: mã hoá + giải mã một trường khi DEK đã đệm dưới 50 µs trung bình (tham khảo TL-01 "Ngân sách độ trễ"); báo cáo số đo thật.
- Quét nhật ký kiểm thử không có bản rõ của trường C2 hay khoá.
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật wiki trang 20 (nếu đã có) mục "Mã hoá trường trong mã".

## 7. Báo cáo

Theo AGENTS.md mục 6.
