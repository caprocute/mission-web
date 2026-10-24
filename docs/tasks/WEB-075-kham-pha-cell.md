# WEB-075 · ADR-07 Khám phá cell khi đăng nhập

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-075` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – Quy mô quốc gia, nhiều cell |
| Luồng đề xuất | W |
| Cỡ việc | S |
| Phụ thuộc | WEB-006, DV-01-04 |
| Truy vết | QT-00 → SCR-0001 → `GET /api/v1/registry/resolve` (`resolveTenantCell`; 421 `REGISTRY-421-TENANT-MOVED`, 503 `Retry-After: 30`) → SVC-01 lớp ngoài, SVC-03 → `registry.tenant`; ADR-07, ADR-29 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Người dùng vào bằng tên miền chung hay tên miền của tenant đều tới đúng cell phục vụ tenant của mình mà không phải biết cell là gì; khi tenant được chuyển sang cell khác, shell tự chuyển hướng và đăng nhập lại liền mạch. Khi xong, shell không còn phụ thuộc `apiBaseUrl` cố định trong `config.json` ở môi trường nhiều cell.

## 2. Đầu vào bắt buộc đọc

- `TL-01-thiet-ke-kien-truc.md` mục "Định tuyến tới cell" (dòng 2718); ADR-07 (dòng 3452), ADR-29 (dòng 3672).
- `TL-04-trien-khai-van-hanh.md` mục "GeoDNS và định tuyến tenant tới cell" (dòng 166: tên miền chung, chuyển hướng 307, mã lỗi "tenant đã chuyển cell").
- `contracts/openapi/modules/platform.yaml` `/registry/resolve` (phản hồi 200, 421, 503).
- `wiki/07-xac-thuc-phan-quyen.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`; `frontend/projects/api-client/src/lib/runtime-config.ts`; thẻ WEB-006, DV-01-04.

## 3. Phạm vi công việc

- Sau đăng nhập, shell gọi `resolveTenantCell` qua điểm vào toàn cục với tenant lấy từ token, nhận URL cổng API của cell và đặt làm gốc API cho phiên (signal trong dịch vụ cấu hình, không ghi đè tệp `config.json`); lưu URL cell trong `sessionStorage` (không phải dữ liệu C2) để lần tải sau dùng ngay.
- Bắt 421 từ bất kỳ lời gọi nào: đọc `Location` (URL cell mới), cập nhật gốc API, làm mới token nếu cell mới dùng realm khác theo hướng dẫn của DV-01-04, rồi điều hướng lại trang hiện tại; chỉ thử một lần để tránh vòng lặp, sau đó hiện thông báo nghiệp vụ.
- Bắt 503 của registry: tôn trọng `Retry-After` (dùng chung tiện ích của WEB-073 nếu đã có), tiếp tục dùng URL cell đã lưu nếu có.
- Luồng tên miền chung: trang nhập mã đơn vị hoặc thư điện tử công vụ chuyển hướng 307 do APISIX lớp ngoài trả; shell chỉ cần hiển thị trạng thái chờ và lỗi khi không tìm thấy tenant (suy luận, xác nhận lại với DV-01-04).
- Giả lập: thêm fixture cho `/registry/resolve` và tham số `mock-error=421` để kiểm thử.
- Kiểm CSP `connect-src` cho phép miền API của các cell (phối hợp cấu hình `deploy/web`, chỉ ghi đề xuất nếu ngoài phạm vi).

## 4. Ngoài phạm vi

- APISIX lớp ngoài, đệm 5 phút, cookie định tuyến HMAC (DV-01-04).
- Ứng dụng di động (MOB).

## 5. Tệp được phép sửa

- `frontend/projects/api-client/src/lib/**`, `frontend/projects/api-client/src/mock/**`
- `frontend/projects/auth/src/lib/**` (chỉ phần làm mới phiên khi đổi cell)
- `frontend/projects/fe-shell/src/app/**`
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/07-xac-thuc-phan-quyen.md`

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh: đặt gốc API từ kết quả phân giải, xử lý 421 một lần, không lặp vô hạn, 503 dùng URL đã lưu.
- Màn hình mở trên trình duyệt với giả lập 421 tự chuyển sang cell mới, không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
