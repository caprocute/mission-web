# WEB-054 · Bảng quyền phía giao diện sinh từ ma trận phân quyền

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-054` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Phân quyền (xuyên giai đoạn) |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-001, WEB-006 |
| Truy vết | Baseline mục 10 (AC) → wiki/15a → `docs/phan-quyen/ma-tran.json` → `@vt/auth` → SEC-18 (OPA vẫn là nơi quyết định) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Thay việc rải danh sách vai trò trong mã (`roleGuard('leader', 'manager', …)`, `roles: [...]` trong `nav.ts`) bằng một bảng quyền duy nhất sinh từ `docs/phan-quyen/ma-tran.json`, để mọi chỗ ẩn hiện menu, tuyến và nút trong shell và remote đọc cùng một nguồn với wiki/15a và tệp Excel. Kết quả người dùng thấy không đổi ở task này; kết quả cho người phát triển là hằng số quyền có kiểu, hàm `can()`, directive `*vtCan` và guard `permissionGuard()` trong `@vt/auth`.

## 2. Đầu vào bắt buộc đọc

- wiki/15-phan-quyen.md (toàn bộ, đặc biệt mục "Hiện thực ở từng tầng" và "Hiện trạng mã")
- wiki/15a-ma-tran-phan-quyen.md (mục "Cách đọc và cách sửa", ký hiệu, phạm vi)
- wiki/07-xac-thuc-phan-quyen.md
- docs/phan-quyen/ma-tran.json và docs/phan-quyen/gen_matrix.py
- frontend/projects/auth/src/lib/session.service.ts, auth.guard.ts

## 3. Phạm vi công việc

- Mở rộng `docs/phan-quyen/gen_matrix.py` thêm tuỳ chọn `--ts` sinh `frontend/projects/auth/src/lib/permissions.generated.ts` từ cùng `ma-tran.json`: kiểu `PermissionKey` là hợp các khoá `SCR-qqnn.<khoá>` (chỉ dòng `web: true`), và bảng `PERMISSIONS` ánh xạ mỗi khoá sang danh sách `VtRole` có ô khác `-` (bỏ AC-11, AC-12). Ô suy luận (`*`) vẫn cho phép nhưng mang cờ `inferred` để có thể tắt bằng cấu hình khi cần kiểm thử chặt. Không đưa tên chức năng, ghi chú hay phạm vi vào bundle, chỉ giữ dữ liệu cần để quyết định, nhằm giữ ngân sách shell ≤ 250 KB gzip.
- Viết `PermissionService` (hoặc mở rộng `SessionService`) với `can(key: PermissionKey): boolean` và `canAny(...keys)`, dạng `computed` theo vai trò của phân công đang chọn; viết structural directive `*vtCan="'SCR-0203.duyet'"` (standalone) và `permissionGuard(...keys)` cho router. Giữ `roleGuard` để không vỡ mã cũ nhưng đánh dấu `@deprecated` trỏ sang `permissionGuard`.
- Chú thích rõ trong mã và trong wiki/15 rằng `can()` chỉ trả lời "vai trò có thể có quyền"; ràng buộc theo đối tượng (người khởi tạo, người được giao, trạng thái, tách vai trò, step-up) vẫn do máy chủ quyết định và giao diện xử lý 403 hoặc 401 theo MSG.
- Thêm `gen_matrix.py --check` và kiểm tra tệp `.generated.ts` không lệch vào script npm (ví dụ `npm run check:permissions`) để pipeline của WEB-002 gọi được.
- Kiểm thử Vitest: mỗi vai trò thử với vài khoá tiêu biểu (ví dụ `SCR-0203.duyet` chỉ `leader`, `SCR-0801.duyet-huy` chỉ `second_approver`), người dùng nhiều vai trò, người dùng chưa đăng nhập.

## 4. Ngoài phạm vi

- Áp bảng quyền vào menu, tuyến, nút (WEB-055).
- Đồng bộ realm Keycloak (WEB-056) và kiểm thử đầu cuối (WEB-057).
- Mọi logic ABAC theo đơn vị, trạng thái, lịch sử tham gia hồ sơ: thuộc OPA phía máy chủ.

## 5. Tệp được phép sửa

- docs/phan-quyen/gen_matrix.py
- frontend/projects/auth/** (thêm `permissions.generated.ts`, service, directive, guard, test; cập nhật `public-api.ts`)
- frontend/package.json (chỉ thêm script kiểm tra, không thêm phụ thuộc)
- wiki/15-phan-quyen.md (cập nhật mục hiện trạng mã)

## 6. Tiêu chí hoàn thành

- `python3 docs/phan-quyen/gen_matrix.py --check` và kiểm tra tệp sinh TypeScript đều xanh; sửa một ô trong `ma-tran.json` mà không sinh lại thì kiểm tra báo lỗi.
- Mọi khoá chức năng web của ma trận có trong `PermissionKey`; không có khoá thừa.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-054 trong `docs/tasks/README.md`.
