# Theme Keycloak Mission (SCR-0001, YC-TK-13)

Thư mục `mission/` là theme Keycloak 26.4 của Mission, gắn vào `/opt/keycloak/themes/mission` (xem `infra/dev/docker-compose.yml`). Theme có đủ năm loại: `login` (giao diện đăng nhập SCR-0001 của WEB-020), `account` (kế thừa `keycloak.v3`), `admin` (kế thừa `keycloak.v2`), `email` (kế thừa `keycloak`) và `common`. Mỗi loại có `theme.properties` với `locales=vi,en` và tệp `messages/messages_vi.properties` (UTF-8). Realm `mission` trong `infra/dev/keycloak/realm-mission.json` đặt `loginTheme`, `accountTheme`, `adminTheme`, `emailTheme` là `mission` và `defaultLocale` là `vi`; realm `master` (trang quản trị) được đặt bằng `infra/dev/keycloak/cau-hinh-master.sh` vì không nạp từ tệp.

## Bản dịch tiếng Việt

Keycloak 26.4.7 không có sẵn tiếng Việt, nên toàn bộ khoảng 4.900 khoá của sáu bộ thông điệp gốc (`base` login/account/email/admin, `keycloak.v2` admin, `keycloak.v3` account) được dịch và quản lý trong `i18n-tools/` (nằm ngoài thư mục theme để Keycloak không quét nhầm):

- `kc-26.4.7-en/`: bản tiếng Anh gốc trích từ các tệp JAR của Keycloak 26.4.7, dùng làm chuẩn so khớp khoá.
- `vi/<bộ>.json`: bản dịch theo từng bộ (khoá → chuỗi tiếng Việt). Đây là nơi sửa bản dịch.
- `tuy-bien/<loại>.properties`: chuỗi riêng của Mission ghi đè bản dịch chuẩn, ví dụ nhãn trang đăng nhập SCR-0001.
- `kc_i18n.py`: `check` kiểm đủ khoá, giữ nguyên placeholder (`{{ten}}`, `{0}`, `{0,choice,…}`, thẻ HTML) và cảnh báo nhãn ngắn bị dài quá; `build` sinh lại `mission/*/messages/messages_vi.properties` (không sửa tay các tệp sinh ra); `split`/`merge` tách và gộp lô khi cần dịch thêm.

Quy tắc dịch: văn phong hành chính – công vụ; nút, menu, nhãn cột, huy hiệu 2–4 âm tiết (Lưu, Hủy, Xóa, Thêm, Cập nhật); thuật ngữ thống nhất (realm, client, phạm vi client, vai trò, nhóm, nhà cung cấp định danh, liên kết người dùng, thao tác bắt buộc, chứng thư, khóa bí mật); giữ nguyên tên giao thức và giá trị kỹ thuật (OpenID Connect, SAML, LDAP, OTP, mã mapper).

Khi nâng phiên bản Keycloak: trích lại `messages_en.properties` của phiên bản mới vào `kc-26.x-en/`, chạy `python3 i18n-tools/kc_i18n.py check` để thấy khoá mới, `split` các khoá thiếu, dịch, `merge`, rồi `build`.

## Kiểm thử

`kiem-thu/kiem-tra-tieng-viet.mjs` (Playwright) mở trang đăng nhập, OTP, trang tài khoản (cả khổ điện thoại) và trang quản trị trên một Keycloak đang chạy, báo cụm tiếng Anh còn sót, nhãn bị tràn khung, lỗi HTTP và lỗi console, và chụp ảnh từng màn hình. Thông tin đăng nhập truyền qua biến môi trường (xem đầu tệp), không ghi vào Git. Với server dev, chạy qua hầm SSH tới cổng 35080.
