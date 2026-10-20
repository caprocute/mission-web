# WEB-076 · MOD-08 Đọc cờ tính năng phía web

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-076` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | W |
| Cỡ việc | S |
| Phụ thuộc | CORE-08-07; task sửa hợp đồng cho phép mọi người dùng đọc cờ hiệu lực (bổ sung, xem mục 3) |
| Truy vết | QT-00, QT-18 → SCR-0002, SCR-1801 → `GET /api/v1/config/feature-flags` (`listFeatureFlags`, hiện chỉ `tenant_admin`) → MOD-08 → `config.feature_flag`; OPS-04 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Shell và các remote biết tính năng nào đang bật cho tenant và đơn vị của người dùng, để ẩn menu, tuyến, nút và widget của tính năng chưa bật, đúng nguyên tắc tách triển khai mã khỏi phát hành tính năng (OPS-04). Khi xong, quản trị tenant bật một cờ ở máy chủ thì người dùng thấy chức năng ở lần tải trang kế tiếp, tắt cờ thì chức năng biến mất, còn máy chủ vẫn là nơi kiểm cuối cùng.

## 2. Đầu vào bắt buộc đọc

- `TL-04-trien-khai-van-hanh.md` mục "GitOps và phát hành theo đợt cell" (dòng 3616, OPS-04 bật tính năng theo tenant bằng cờ).
- `TL-03-thiet-ke-csdl.md` "Bảng config.feature_flag" (dòng 1586).
- `contracts/openapi/modules/config.yaml` `/config/feature-flags`, schema `FeatureFlagPage`.
- Thẻ CORE-08-07 (định dạng `rollout`, đề xuất sửa hợp đồng); `wiki/02-kien-truc-frontend.md`, `wiki/15a-ma-tran-phan-quyen.md`.

## 3. Phạm vi công việc

- Điều kiện bắt đầu và điểm lệch cần lưu ý: hợp đồng hiện chỉ cho `tenant_admin` đọc danh sách cờ, nên người dùng thường chưa có nguồn đọc. Chỉ làm phần nối API thật khi hợp đồng đã có cách đọc cờ hiệu lực cho mọi người dùng (theo đề xuất ở CORE-08-07); trước đó làm toàn bộ phần web với giả lập và ghi rõ trong báo cáo.
- Viết `FeatureFlagService` trong thư viện dùng chung: tải cờ hiệu lực một lần khi khởi động và khi đổi ngữ cảnh phân công, giữ trong signal, có `isEnabled(key)` dạng `computed`; lỗi tải thì coi mọi cờ là tắt (an toàn) và ghi telemetry.
- Cung cấp cách ẩn hiện dùng chung: guard tuyến theo cờ, chỉ thị hoặc thành phần bọc cho menu và nút, điều kiện trong bố cục SDUI; kết hợp với `can()` của ma trận phân quyền chứ không thay thế.
- Dùng ở shell ít nhất một lần (tránh NG0203 với thư viện dùng chung mà remote cần) và gắn cho một tính năng mẫu được thống nhất với người phụ trách.
- Thêm fixture giả lập cờ và tham số trang để bật tắt cờ khi kiểm thử.

## 4. Ngoài phạm vi

- Màn hình quản trị cờ cho `tenant_admin` (nằm trong WEB-072).
- Đánh giá `rollout` theo tỷ lệ phía trình duyệt (máy chủ trả cờ đã đánh giá cho người dùng).

## 5. Tệp được phép sửa

- `frontend/projects/api-client/src/lib/**`, `frontend/projects/api-client/src/mock/**`
- `frontend/projects/auth/src/lib/**` (chỉ guard và chỉ thị theo cờ, nếu đặt cạnh `can()`)
- `frontend/projects/fe-shell/src/app/**`
- `wiki/02-kien-truc-frontend.md`

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh: cờ tắt thì guard chặn tuyến, lỗi tải cờ thì coi như tắt, đổi phân công thì tải lại.
- Màn hình mở trên trình duyệt không có lỗi console khi bật và tắt cờ mẫu.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
