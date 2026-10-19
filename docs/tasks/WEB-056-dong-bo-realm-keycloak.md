# WEB-056 · Đồng bộ realm Keycloak dev với ma trận phân quyền

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (12 quy tắc không được vi phạm, quy trình làm task, mẫu báo cáo). Nhắc nhanh những điều hay bị quên:
> bám TL-01..TL-04 và baseline, giữ nguyên mã định danh; chỉ sửa tệp trong phạm vi; không commit `.env`; không `innerHTML`/`bypassSecurityTrust`/`eval`;
> máy chủ là nguồn sự thật; API qua `@vt/api-client`; Angular standalone + signals; WCAG 2.1 AA và chế độ Đảng viên cao tuổi; giữ ngân sách hiệu năng;
> build + test + mở màn hình thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh WEB-056` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Phân quyền (xuyên giai đoạn) |
| Cỡ việc | M (S ≈ ½ ngày agent, M ≈ 1 ngày, L ≈ 2 ngày; L nên tách nhỏ khi làm) |
| Phụ thuộc | WEB-006, WEB-054 |
| Truy vết | Baseline mục 10 → ADR-08 → SVC-02 Keycloak (realm `mission`, client `mission-web`) → BR-00-01, BR-00-02 → SEC-13, SEC-16, SEC-17 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Realm `mission` ở môi trường dev phản ánh đúng mô hình vai trò của wiki/15: đủ mười một realm role theo `ma-tran.json`, client scope mang claim ngữ cảnh (tenant, đơn vị, mức xác thực) theo tên đã chốt, MFA bắt buộc với các vai trò AAL2 của BR-00-01, cấu hình bậc xác thực để luồng step-up (401 `insufficient_user_authentication`) thử được ở dev, và mỗi vai trò có ít nhất một tài khoản thử để WEB-057 chạy đối chiếu ma trận.

## 2. Đầu vào bắt buộc đọc

- wiki/15-phan-quyen.md mục "Hiện thực ở từng tầng" và "Điểm lệch giữa các nguồn"
- wiki/07-xac-thuc-phan-quyen.md, wiki/12-ha-tang-dev-va-trien-khai.md
- wiki/14-quyet-dinh-va-cau-hoi-mo.md (tên claim tenant, MFA theo vai trò)
- infra/dev/README.md, infra/dev/keycloak/realm-mission.json
- TL-01 chương 16 "Định danh và xác thực"; TL-02 QT-00 BR-00-01, BR-00-02

## 3. Phạm vi công việc

- Viết kiểm tra tự động (mở rộng `gen_matrix.py` hoặc script riêng trong `docs/phan-quyen/`) so sánh realm role trong `realm-mission.json` với `vai_tro` của `ma-tran.json`: không thiếu, không thừa, mô tả mang đúng mã AC.
- Không tạo composite role nghiệp vụ vì nguồn không định nghĩa; nếu thấy cần (ví dụ vai trò mặc định), ghi đề xuất vào wiki/14 thay vì tự thêm.
- Gom các bộ ánh xạ claim của client `mission-web` vào một client scope dùng chung (ví dụ `mission-context`) gồm vai trò realm, tenant, đơn vị và `acr`; giữ tên `tenant`, `unit_id` mà `SessionService` đang đọc cho tới khi người phụ trách chốt tên theo TL-01 (`tenant_id`, `cell`) hoặc TL-02 (`org`, `roles`), và ghi rõ điểm chờ chốt trong báo cáo.
- Cấu hình ánh xạ bậc xác thực (`acr` ↔ mức) và luồng xác thực có điều kiện để vai trò `appraiser`, `decider`, `second_approver`, `tenant_admin`, `platform_admin` phải có OTP; thử yêu cầu `acr_values=aal2` từ ứng dụng nhận được token có `acr` đúng.
- Bổ sung tài khoản thử cho các vai trò còn thiếu (`policy_author`, `decider`, `second_approver`, `inspector`, `platform_admin`, `ai_readonly` nếu cần), mỗi tài khoản có `tenant`, `unit_id`; mật khẩu chỉ đặt qua biến trong `infra/dev/.env` (không commit), cập nhật `infra/dev/.env.example` với tên biến, không có giá trị thật.

## 4. Ngoài phạm vi

- Realm production theo cell và Organizations (thuộc hạ tầng TL-04, cấu hình bằng `keycloak-config-cli`).
- Tích hợp VNeID thật.

## 5. Tệp được phép sửa

- infra/dev/keycloak/**
- infra/dev/.env.example (chỉ tên biến), infra/dev/README.md
- docs/phan-quyen/** (script kiểm tra)
- wiki/07-xac-thuc-phan-quyen.md, wiki/12-ha-tang-dev-va-trien-khai.md, wiki/14-quyet-dinh-va-cau-hoi-mo.md, wiki/15-phan-quyen.md

## 6. Tiêu chí hoàn thành

- Kiểm tra realm so với `ma-tran.json` chạy xanh; thêm một vai trò lạ vào realm thì kiểm tra báo lỗi.
- Nạp lại realm trên Keycloak dev (chỉ container của project `mission-dev`) thành công; đăng nhập bằng mỗi tài khoản thử, ID token có đủ vai trò và claim ngữ cảnh; tài khoản có vai trò AAL2 bị yêu cầu OTP.
- Không có mật khẩu, địa chỉ máy chủ hay token trong tệp commit, wiki hay báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Kết thúc bằng báo cáo theo mẫu AGENTS.md mục 6, rồi cập nhật trạng thái dòng WEB-056 trong `docs/tasks/README.md`.
