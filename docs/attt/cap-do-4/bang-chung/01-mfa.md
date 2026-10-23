> Nguồn: TL-04 mục 9.3 (SEC-13), mục 9.7 bảng tám yêu cầu cấp độ 4; task DV-02-01, DV-02-03, WEB-056. Ngày đánh giá: 23/10/2026.

# Bằng chứng 01: MFA bắt buộc (SEC-13)

## Yêu cầu

Mọi người dùng phải xác thực đa yếu tố, không có nhánh bỏ qua. Người vận hành hạ tầng dùng khoá WebAuthn (FIDO2). TL-04 yêu cầu hai loại bằng chứng: cấu hình Keycloak lưu trong Git và báo cáo cho thấy 100% người dùng đã có yếu tố thứ hai.

## Biện pháp đã có

Mẫu realm production theo cell (`deploy/charts/iam/files/realm/realm-cell.yaml`) thay luồng đăng nhập dựng sẵn của Keycloak bằng luồng `mission-forms`. Luồng dựng sẵn có nhánh "chỉ hỏi OTP khi người dùng đã cài", nghĩa là người chưa cài vẫn đăng nhập được bằng mật khẩu; luồng `mission-forms` bỏ nhánh này. Người đã cài yếu tố thứ hai chọn SmartOTP (TOTP 6 chữ số, chu kỳ 30 giây, không dùng lại mã) hoặc WebAuthn. Người chưa cài phải cài SmartOTP ngay qua required action `CONFIGURE_TOTP` và xác minh bằng một mã đúng trước khi được phát token. Bậc xác thực được ánh xạ sang claim `acr` để các thao tác nhạy cảm đòi step-up (DV-02-03). Chính sách MFA theo vai trò, thiết bị và rủi ro nằm trong `backend/services/iam/keycloak-events/src/main/java/vn/viettel/okrkpi/iam/events/MfaPolicy.java`. Mẫu realm được kiểm bằng `backend/services/iam/realm-template/src/test/java/vn/viettel/okrkpi/iam/realm/RealmTemplateStaticTest.java` (kiểm tĩnh) và `backend/services/iam/realm-template/src/test/java/vn/viettel/okrkpi/iam/realm/RealmTemplateKeycloakIT.java` (dựng Keycloak thật bằng Testcontainers).

## Cách thu bằng chứng

Script `docs/attt/cap-do-4/thu-bang-chung.sh` gọi API quản trị Keycloak của môi trường thử để đếm người dùng đang bật và người dùng có ít nhất một thông tin xác thực OTP hoặc WebAuthn, rồi ghi tỷ lệ vào `mfa.txt` trong thư mục bằng chứng có ngày. Script đọc thông tin truy cập từ biến môi trường, không ghi vào tệp.

## Trạng thái và điểm còn thiếu

Trạng thái: **Một phần**. Cấu hình và kiểm thử đã có trong Git. Báo cáo tỷ lệ 100% chưa có, vì chưa có môi trường staging hoặc production với người dùng thật (INF-21). Realm dev có tài khoản thử dùng cho kiểm thử đầu cuối; các tài khoản này chỉ tồn tại trong môi trường dev và không có trong mẫu realm production. Yêu cầu WebAuthn bắt buộc cho người vận hành hạ tầng sẽ được thực thi qua PAM (INF-27), xem [04-pam.md](04-pam.md).
