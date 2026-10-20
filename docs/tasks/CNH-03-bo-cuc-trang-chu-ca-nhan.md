# CNH-03 · SCR-0002/SCR-1602 Web: lưu và khôi phục bố cục trang chủ và dashboard cá nhân

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CNH-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | W |
| Cỡ việc | M |
| Phụ thuộc | CNH-02, WEB-048 |
| Truy vết | QT-00 → SCR-0002 → `GET /api/v1/ui/layouts/home` (`getHomeLayout`) và API tuỳ chọn cá nhân (CNH-02); QT-16 → SCR-1602 → `/api/v1/analytics/dashboards` (`listDashboards`, `createDashboard`, `updateDashboard`) → MOD-08, SVC-15 → `config.user_preference` (đề xuất), `config.dashboard_def` |
| Trạng thái | Chờ người dùng chốt |

## 1. Mục tiêu

Cán bộ sắp xếp lại, ẩn hoặc hiện các khối trên trang chủ "Việc của tôi hôm nay" trong phạm vi tenant cho phép, chọn một dashboard cá nhân làm mặc định, và thấy đúng bố cục đó khi đăng nhập ở máy khác. Khi xong, mọi thay đổi lưu ở máy chủ, có thể đưa về mặc định của đơn vị bằng một thao tác.

## 2. Đầu vào bắt buộc đọc

- Đề xuất đã duyệt của CNH-01 và hợp đồng `config.yaml` đã bổ sung.
- `TL-02a-quy-trinh.md` "SCR-0002. Trang chủ" (dòng 426); `TL-02b-quy-trinh.md` "SCR-1602. Trình dựng dashboard" (dòng 1728).
- `wiki/03-giao-dien-theo-tenant.md`, `wiki/09-hieu-nang-va-trai-nghiem.md` (chế độ cao tuổi, ≤ 3 chạm); thẻ WEB-048, WEB-009.
- `frontend/projects/sdui-renderer/src/lib/`, `frontend/projects/fe-shell/src/app/pages/home.page.ts`.

## 3. Phạm vi công việc

- Phần cần bổ sung thiết kế trước khi làm: chưa có đặc tả màn hình cho chế độ "Tuỳ chỉnh trang chủ" (thao tác, thông báo, mã SCR nếu tách màn hình) và chưa có API; chỉ làm khi CNH-01 được duyệt và CNH-02 đã có trên máy chủ hoặc giả lập theo hợp đồng mới.
- Đề xuất mặc định (suy luận): nút "Tuỳ chỉnh" trên trang chủ mở chế độ sắp xếp; mỗi khối có nút lên, xuống, ẩn (thao tác được bằng bàn phím, không bắt buộc kéo thả); nút "Khôi phục mặc định"; lưu một lần khi bấm "Lưu" với `If-Match`, xung đột hiện `<vt-conflict-notice>`.
- Trang chủ dựng từ `getHomeLayout` đã áp lớp phủ của máy chủ; trình duyệt không tự ghép lớp phủ ngoài để phản hồi nhanh khi đang sửa.
- Trình dựng dashboard (SCR-1602): thêm thao tác "Đặt làm dashboard mặc định" lưu khoá `dashboard.default`; bảng điều hành mở dashboard mặc định nếu còn quyền xem.
- Thêm giả lập theo hợp đồng mới cho API tuỳ chọn để kiểm thử.

## 4. Ngoài phạm vi

- Cấu hình bố cục trang chủ cấp tenant (SCR-1801, WEB-050); backend (CNH-02).

## 5. Tệp được phép sửa

- `frontend/projects/fe-shell/src/app/pages/home.page.ts` và tệp đi kèm
- `frontend/projects/sdui-renderer/src/lib/**` (chỉ hỗ trợ chế độ sắp xếp)
- `frontend/projects/fe-dashboard/**`
- `frontend/projects/api-client/src/**` (model, client, giả lập tuỳ chọn cá nhân)

## 6. Tiêu chí hoàn thành

- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; thao tác ẩn một khối ≤ 3 chạm; axe-core không có lỗi mức serious.
- Đăng nhập ở hai trình duyệt khác nhau thấy cùng bố cục sau khi lưu.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
