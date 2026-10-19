# WEB-058 · Quản trị phân công vai trò và uỷ quyền (cần bổ sung thiết kế)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-058` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Phân quyền (xuyên giai đoạn) |
| Cỡ việc | L (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-018, WEB-054; quyết định thiết kế của người phụ trách |
| Truy vết | QT-01 (suy luận) → chưa có SCR → chưa có API trong TL-01 Phụ lục C → `config.role_binding` (TL-03, MOD-08) → EVT-15 → SEC-18, SEC-22 |
| Trạng thái | Chờ thiết kế |

## 1. Mục tiêu

Bộ TL có dữ liệu cho việc cấp vai trò (`config.role_binding` với đơn vị phạm vi, phạm vi `DON_VI` hoặc `DON_VI_VA_CAP_DUOI`, luồng, thời hạn, người cấp, người duyệt thứ hai cho vai trò nhạy cảm) và có quy định uỷ quyền (duyệt thay, nghiệm thu thay theo `flowable.wf_binding`, giao quyền check-in thay ở QT-03), nhưng không có màn hình SCR, không có API trong TL-01 Phụ lục C và không có BR, MSG riêng cho việc quản trị các quyền này. Task này có hai pha: pha 1 lập đề xuất bổ sung thiết kế để người phụ trách cấp mã SCR, API, BR, MSG; pha 2 chỉ bắt đầu khi đã có mã, hiện thực màn hình trong `fe-admin`.

## 2. Đầu vào bắt buộc đọc

- wiki/15-phan-quyen.md mục "Nguyên tắc" (quyền tạm thời, uỷ quyền, kiêm nhiệm) và "Cấp, thu hồi quyền và nhật ký kiểm toán"
- wiki/15a-ma-tran-phan-quyen.md (dòng `SCR-0301.giao-check-in-thay` và các dòng "Bốn mắt", "Uỷ quyền" của bảng ràng buộc)
- wiki/14-quyet-dinh-va-cau-hoi-mo.md
- TL-03 bảng `config.role_binding`, `org.assignment`; TL-02 chương "Tác nhân và phân quyền", ngoại lệ của QT-02, QT-03, QT-06, QT-07; TL-01 mục "Định danh và xác thực" (thay đổi quyền cần step-up)

## 3. Phạm vi công việc

- Pha 1: viết đề xuất (trong wiki/14 hoặc `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md`) gồm các chức năng cần có, mỗi chức năng dẫn nguồn: xem vai trò của một đơn vị và của một người; cấp vai trò có thời hạn và phạm vi; duyệt thứ hai khi cấp `decider`, `second_approver`, `policy_author`; thu hồi; giao quyền check-in thay; xem lịch sử cấp và thu hồi; cấu hình người duyệt thay. Nêu vai trò được làm từng chức năng (đề xuất `tenant_admin` cấp, người duyệt thứ hai khác người cấp), yêu cầu step-up, sự kiện và nhật ký kiểm toán. Không tự đặt mã SCR, BR, MSG; ghi "chờ cấp mã".
- Pha 2 (sau khi có mã): thêm dòng vào `ma-tran.json`, chạy `gen_matrix.py`, rồi hiện thực màn hình trong `fe-admin` trên dữ liệu giả lập qua `@vt/api-client`, theo khung của các task màn hình (WEB-016).

## 4. Ngoài phạm vi

- Quản trị tài khoản, mật khẩu, MFA của người dùng (thuộc Keycloak).
- Quyền đặc quyền hạ tầng qua PAM (SEC-42).

## 5. Tệp được phép sửa

- Pha 1: wiki/14-quyet-dinh-va-cau-hoi-mo.md, wiki/15-phan-quyen.md, docs/phan-quyen/**
- Pha 2: frontend/projects/fe-admin/**, frontend/projects/api-client/src/lib/**, docs/phan-quyen/ma-tran.json, wiki/15a-ma-tran-phan-quyen.md (chỉ qua `gen_matrix.py`)

## 6. Tiêu chí hoàn thành

- Pha 1: đề xuất được người phụ trách duyệt; mọi chức năng đề xuất có dẫn nguồn TL; điểm chờ quyết định ghi ở wiki/14.
- Pha 2: ẩn/hiện chức năng đúng ma trận phân quyền cho mọi vai trò liên quan; thao tác bị chặn ở máy chủ vẫn hiển thị lỗi 403 đúng MSG; cấp vai trò nhạy cảm không hoàn tất khi chưa có người duyệt thứ hai khác người cấp.
- `npx ng build` cho shell và remote liên quan thành công, không cảnh báo mới về ngân sách.
- Kiểm thử đơn vị (Vitest) cho logic mới chạy xanh; màn hình mở trên trình duyệt không có lỗi console.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-058 trong `docs/tasks/README.md`.

## 8. Kết quả (19/10/2026)

Pha 1 được người phụ trách duyệt ngày 19/10/2026 (mục 8 của `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md`). Pha 2 đã thêm hợp đồng API vào `contracts/openapi/modules/config.yaml`, chín dòng chức năng `SCR-0105.*`, `SCR-0106.*`, `SCR-0107.*` vào ma trận, client `RoleAdminApi` cùng dữ liệu giả lập, và ba màn hình trong `fe-admin`. Ngoài danh sách tệp ở mục 5, task phải sửa thêm `contracts/openapi/modules/config.yaml` (API-first, ADR-32), `frontend/projects/api-client/src/mock/**` (dữ liệu giả lập), `frontend/projects/auth/src/lib/permissions.generated.ts` và `docs/phan-quyen/ma-tran-phan-quyen.xlsx` (do `gen_matrix.py` sinh), `frontend/projects/fe-shell/src/app/layout/nav.ts` (đưa ba màn hình vào nhóm menu quản trị) cùng wiki/14, wiki/15.
