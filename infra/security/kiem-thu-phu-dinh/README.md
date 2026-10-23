# Bộ kiểm thử phủ định ATTT (QA-13)

> Nguồn: TL-04 mục 9.8 (NFR-08, OWASP ASVS 4.0.3 mức 2, mức 3 cho V2, V3, V6); baseline mục 13 (quy ước API); ADR-08 (OIDC Code + PKCE); wiki 06, 07, 15; `docs/tasks/QA-13-asvs-zap.md`.

Bộ kiểm thử này gọi một môi trường thật (dev, staging, hoặc service dựng riêng) để xác nhận máy chủ từ chối đúng các yêu cầu sai: thiếu hoặc giả mạo token, token hết hạn, truy cập chéo tenant, thiếu `If-Match`, dùng lại `Idempotency-Key` với thân khác, tệp sai loại, `limit` vượt 200, cùng các tiêu đề và thông báo lỗi. Mỗi tệp kiểm thử ghi mục ASVS mà nó làm bằng chứng; bảng đối chiếu đầy đủ ở `docs/attt/asvs-4.0.3-tu-danh-gia.md` (mã PD1 đến PD6).

Bộ chỉ dùng thư viện chuẩn của Node 22 (`node:test`, `fetch`, `node:crypto`), không cần `npm install`. Không có địa chỉ, tài khoản hay mật khẩu nào trong repo: mọi giá trị đọc từ biến môi trường.

## Chạy

```bash
cd infra/security/kiem-thu-phu-dinh
export KT_API_URL=https://<cổng API>/api/v1            # hoặc http://localhost:<cổng org-service>/api/v1
export KT_OIDC_ISSUER=https://<keycloak>/realms/mission
export KC_TEST_USER_PASSWORD=...  KC_TEST_OTP_SECRET=...  # cùng biến với infra/dev/.env
export KT_QUAN_TRI_KHAC=<tài khoản quản trị của tenant thứ hai>
npm test                                                # kết quả JUnit ở ket-qua-junit.xml
```

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `KT_API_URL` | (bắt buộc) | Gốc API gồm `/api/v1` |
| `KT_OIDC_ISSUER` | (bắt buộc) | Issuer realm `mission` |
| `KT_CLIENT_ID`, `KT_REDIRECT_URI` | `mission-web`, `http://localhost:4200/` | Client công khai và một redirect URI đã đăng ký; bộ kiểm thử chỉ đọc `code` từ Location |
| `KT_MAT_KHAU` hoặc `KC_TEST_USER_PASSWORD` | – | Mật khẩu tài khoản thử |
| `KT_OTP_SECRET` hoặc `KC_TEST_OTP_SECRET` | – | Bí mật OTP dạng rõ như trong realm (Keycloak dùng byte UTF-8 của chuỗi làm khoá HMAC) |
| `KT_NGUOI_DUNG` | `canbo` | Cán bộ AC-06, phải có ít nhất một phân công còn hiệu lực |
| `KT_QUAN_TRI` | `quantri` | Quản trị tenant A (AC-02) |
| `KT_QUAN_TRI_KHAC` | – | Tài khoản thuộc tenant khác; trống thì bỏ qua nhóm chéo tenant |
| `KT_TOKEN_NGUOI_DUNG`, `KT_TOKEN_QUAN_TRI`, `KT_TOKEN_QUAN_TRI_KHAC` | – | Token cấp sẵn, dùng khi không đăng nhập được bằng biểu mẫu |
| `KT_TOKEN_HET_HAN` | – | Một token thật đã hết hạn; nếu không có, đặt `KT_CHO_HET_HAN_GIAY` (ví dụ 420) để bộ tự chờ token hết hạn |
| `KT_DON_VI_A` | phân công đầu tiên của cán bộ | Mã đơn vị của tenant A |

Tài khoản realm dev hiện có thuộc tính `tenant = "default"`, không phải UUID, nên org-service trả 403 cho mọi lời gọi (ActorFilter chỉ nhận UUID). Muốn chạy bộ này trên dev, realm dev cần gán `tenant` bằng UUID của tenant đã nạp dữ liệu, và một tài khoản quản trị thuộc tenant thứ hai cho nhóm chéo tenant.

Luồng `PUT /org/me/context` ghi ngữ cảnh vào thuộc tính người dùng Keycloak; service account `org-service` cần vai trò `realm-management` `view-users`, `manage-users` và token phải mang `resource_access` (client scope `roles` hoặc mapper client role), nếu không org-service trả 503.

## Ca kiểm thử không làm đổi dữ liệu

Mọi lệnh sửa trong bộ đều bị từ chối trước khi ghi (thiếu hoặc sai phiên bản, sai quyền). Ngoại lệ duy nhất là `PUT /org/me/context` của tài khoản cán bộ thử, ghi lại đúng phân công đầu tiên của chính tài khoản đó. Bộ không dò mật khẩu sai nhiều lần để không khoá tài khoản thử dùng chung; chống dò đã được `RealmTemplateKeycloakIT` kiểm trên realm dựng từ mẫu.

## Ca ở trạng thái todo

`node:test` chạy ca `todo` nhưng không làm đỏ bộ. Hiện có một ca: thân multipart hỏng ở `POST /org/imports` làm org-service trả 500 thay vì 400. Khi sửa xong, bỏ tuỳ chọn `todo` và gỡ mục 30001 trong `infra/security/zap/ngoai-le.yaml`.

## Lấy token cho công cụ khác

`node lay-token.mjs [access|refresh] [tên đăng nhập]` in đúng một token ra stdout (để gán biến, không ghi tệp). `infra/security/zap/chay-zap.sh` dùng lệnh này để lấy refresh token cho ZAP khi không có client secret.

## Liên quan

- [Bảng tự đánh giá ASVS](../../../docs/attt/asvs-4.0.3-tu-danh-gia.md)
- [Kế hoạch ZAP](../zap/README.md)
- [Quy ước API](../../../wiki/06-quy-uoc-api.md)
