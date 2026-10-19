# WEB-055 · Áp ma trận phân quyền vào menu, tuyến và nút của shell và các remote hiện có

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-055` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Phân quyền (xuyên giai đoạn) |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-054, WEB-015 |
| Truy vết | wiki/15a → `PERMISSIONS` (`@vt/auth`) → `fe-shell` (`nav.ts`, `app.routes.ts`), `fe-task` (SCR-0002, SCR-0503, SCR-0601) và khung remote của WEB-015 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Menu, tuyến và nút trong shell và các remote đang có ẩn hiện đúng ma trận phân quyền cho mười một vai trò người dùng, và ba chỗ đang chặn quá chặt so với TL-02 được sửa: tuyến `/admin` (SCR-0103 cho AC-07, AC-10 xem; SCR-0801 cho AC-04, AC-09, AC-10), tuyến `/config-studio` (SCR-1503 cho AC-08), tuyến `/dashboard` (SCR-1603 cho AC-07). Mục menu nghiệp vụ không còn hiện với vai trò không có chức năng nào trong nhóm đó (ví dụ `platform_admin`).

## 2. Đầu vào bắt buộc đọc

- wiki/15-phan-quyen.md mục "Hiện trạng mã và khoảng trống"
- wiki/15a-ma-tran-phan-quyen.md (toàn bộ ma trận chính và bảng ràng buộc)
- wiki/02-kien-truc-frontend.md (ánh xạ remote với QT)
- frontend/projects/fe-shell/src/app/layout/nav.ts, app.routes.ts, remotes.ts

## 3. Phạm vi công việc

- Đổi `NavItem.roles` thành danh sách `PermissionKey`; một mục menu hiện khi người dùng `canAny` một chức năng xem của các SCR trong nhóm (ví dụ "Quản trị" hiện khi có `SCR-0103.xem`, `SCR-0801.xem` hoặc chức năng của SCR-0101..0104).
- Thay `roleGuard` ở `app.routes.ts` bằng `permissionGuard` gắn ở từng tuyến con của remote thay vì chỉ tuyến cha; với tuyến cha chứa nhiều SCR, guard cho qua khi có ít nhất một chức năng, còn tuyến con của từng SCR chặn riêng.
- Trong `fe-task`, ẩn hiện các nút của SCR-0002, SCR-0503, SCR-0601 bằng `*vtCan` theo ma trận, kết hợp điều kiện hiển thị theo trạng thái của bảng chức năng TL-02 (ví dụ "Nhận việc" chỉ khi `DA_GIAO` và là người chủ trì).
- Thống nhất xử lý 403 và 401 step-up cùng interceptor của WEB-006: lỗi 403 có `code` thì hiện MSG tương ứng (MSG-02-11, MSG-09-08, MSG-12-05, MSG-13-01, MSG-16-06…), không có `code` thì hiện thông báo 403 chung; không ẩn lỗi.
- Ghi vào wiki/10 hoặc wiki/15 quy ước: mọi nút thao tác mới phải gắn khoá quyền của ma trận; nếu chức năng chưa có khoá thì bổ sung `ma-tran.json` trước.

## 4. Ngoài phạm vi

- Màn hình mới chưa có remote (các task màn hình tự áp `*vtCan` khi làm).
- Thay đổi chính sách phía máy chủ.

## 5. Tệp được phép sửa

- frontend/projects/fe-shell/src/app/** (layout, routes, pages khung)
- frontend/projects/fe-task/**
- frontend/projects/fe-*/src/app/*.routes.ts của các remote đã dựng khung ở WEB-015
- frontend/projects/auth/** (chỉ khi cần sửa directive, guard)
- wiki/10-quy-uoc-ma-nguon.md, wiki/15-phan-quyen.md

## 6. Tiêu chí hoàn thành

- Với người dùng giả lập lần lượt mang từng vai trò, menu và tuyến khớp ma trận (kiểm bằng Vitest cho `nav.ts` và guard; kiểm thử đầu cuối đầy đủ ở WEB-057).
- Ẩn/hiện chức năng đúng ma trận phân quyền cho mọi vai trò liên quan; thao tác bị chặn ở máy chủ vẫn hiển thị lỗi 403 đúng MSG.
- Chế độ Đảng viên cao tuổi và bàn phím (Tab/Enter/Esc) dùng được; axe-core không có lỗi mức serious.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-055 trong `docs/tasks/README.md`.
