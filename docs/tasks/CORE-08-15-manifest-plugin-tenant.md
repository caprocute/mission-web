# CORE-08-15 · ADR-05 lớp 4 Manifest plugin theo tenant và kiểm chữ ký bản dựng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-15` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-08-11 |
| Truy vết | QT-18 → SCR-1801 → phần `plugins` của `UiPackRuntime` trong `GET /api/v1/ui/packs/current`, `GET /api/v1/ui/components` (widget của plugin) → MOD-08 → `config.ui_pack_version` → BR-18-12; ADR-05 lớp 4, ADR-16 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho phép tenant bật plugin micro-frontend chuyên ngành (y tế, giáo dục, LLVT) một cách an toàn: plugin chỉ được nạp khi có trong manifest của UI Pack tenant, bản dựng đã ký và đã quét bảo mật, có mã toàn vẹn để shell kiểm. Khi xong, máy chủ từ chối phát hành UI Pack có plugin chưa ký hoặc sai nguồn, và shell nhận manifest có `integrity` để nạp bằng Native Federation.

## 2. Đầu vào bắt buộc đọc

- `TL-01-thiet-ke-kien-truc.md` G1 "Lớp 4: Micro-frontend Plugins" (dòng 1512), "Cấu trúc UI Pack" (dòng 1534, phần `plugins`), "Bảo mật: chống XSS và CSP" (dòng 1759).
- `TL-02b-quy-trinh.md` QT-18 "Quy tắc nghiệp vụ" (BR-18-12) và "Thông tin chung" (dòng 1945, tích hợp kiểm tra chữ ký plugin theo ADR-16).
- `contracts/openapi/modules/config.yaml` schema `UiPackRuntime` (mục `plugins`: `name`, `remoteEntry`, `integrity`) và `listUiComponents`.
- `docs/tasks/WEB-051-plugin-chuyen-nganh.md` (phía web).
- Bản trích `extract/tl01-xuyen-suot.md` X-G1-13, `extract/tl02b-may-chu.md` QT-18 (BE-Q18-09).

## 3. Phạm vi công việc

- Điểm lệch cần lưu ý: TL-03 không có bảng riêng cho plugin; manifest nằm trong phần `plugins` của `ui_pack_version` theo TL-01 và hợp đồng. Danh mục plugin đã duyệt của nền tảng được khai báo bằng tệp seed có phiên bản (tên, nguồn `remoteEntry` được phép, khoá công khai cosign, danh sách widget), không tạo bảng mới trong task này (suy luận); ghi vào `wiki/14`.
- Kiểm khi gửi duyệt và khi phát hành: plugin trong manifest phải có trong danh mục đã duyệt; `remoteEntry` thuộc nguồn được phép và khớp CSP; `integrity` (SHA-384 dạng SRI) khớp bản dựng; chữ ký cosign của bản dựng kiểm được bằng khoá công khai khai báo; không đạt thì lỗi kiểm tra trong job `TEST_RUN` (CORE-08-10).
- Gộp widget của plugin được bật vào kết quả `listUiComponents` của tenant (BR-18-12) để trình biên tập bố cục chọn được.
- Phát hành gói có plugin đi qua đúng chuỗi ký và EVT-23 của CORE-08-11; không có đường phát hành riêng cho plugin.
- Kiểm thử: plugin không có trong danh mục bị chặn; `integrity` sai bị chặn; chữ ký sai bị chặn; plugin hợp lệ xuất hiện trong `UiPackRuntime.plugins` và trong danh mục component.

## 4. Ngoài phạm vi

- Ký bản dựng plugin trong CI và quét bảo mật (pipeline của đội ngành, nhóm INF chuỗi cung ứng).
- Nạp plugin phía shell (WEB-051).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `data/seed/plugins/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh với bốn kịch bản ở mục 3.
- `getCurrentUiPack` của tenant có plugin trả `plugins[]` đủ `name`, `remoteEntry`, `integrity` đúng hợp đồng.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
