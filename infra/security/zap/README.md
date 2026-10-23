# OWASP ZAP cho Mission (SEC-40, QA-13)

> Nguồn: TL-04 mục 9.8 "Kiểm thử bảo mật: ASVS mức 2 và OWASP ZAP" (mẫu Automation Framework, CSP SEC-19, tiêu chí đạt); TL-04 bảng SEC (SEC-19, SEC-24, SEC-40); `docs/tasks/QA-13-asvs-zap.md`.

Thư mục chứa hai kế hoạch ZAP Automation Framework, kịch bản xác thực, danh sách ngoại lệ và bước đối chiếu. Mọi địa chỉ và bí mật nạp từ biến môi trường (ZAP thay `${TÊN}` trong kế hoạch), nên cùng tệp chạy được với dev, staging hay một service dựng riêng.

| Tệp | Vai trò |
|---|---|
| `okr-api.yaml` | Quét chủ động API theo hợp đồng OpenAPI đã gộp của một module, có đăng nhập; chính sách `API-Minimal` (chèn mã, duyệt đường dẫn, XXE, lỗi máy chủ), tối đa 60 phút, 4 luồng |
| `web-baseline.yaml` | Quét thụ động trang web; quy tắc 10038 (thiếu CSP) và 10020 (thiếu chống nhúng khung) nâng lên High để chặn bản dựng làm mất CSP |
| `auth/keycloak-client-credentials.js` | Kịch bản Graal.js lấy token: `client_credentials` với `ZAP_CLIENT_SECRET` (staging), hoặc `refresh_token` với `ZAP_REFRESH_TOKEN` (dev, client công khai); giữ refresh token mới nhất khi Keycloak xoay vòng |
| `ngoai-le.yaml` | Cảnh báo đã xem xét: giải trình, người duyệt, trạng thái, hạn xem lại |
| `kiem-ngoai-le.mjs` | Đọc SARIF và `ngoai-le.yaml`: chặn khi còn High, Medium chưa giải trình hoặc ngoại lệ quá hạn |
| `chay-zap.sh` | Chạy ảnh `ghcr.io/zaproxy/zaproxy` với kế hoạch và đối chiếu ngoại lệ |

## Chạy

```bash
# API (mặc định module org; danh sách module ở contracts/openapi/modules)
export ZAP_API_URL=https://<cổng API>/api/   KT_OIDC_ISSUER=https://<keycloak>/realms/mission
export ZAP_CLIENT_ID=mission-zap ZAP_CLIENT_SECRET=...      # staging; hoặc bỏ trống để tự đăng nhập người dùng thử
infra/security/zap/chay-zap.sh api org

# Trang web
ZAP_WEB_URL=https://<miền web>/ infra/security/zap/chay-zap.sh web
```

Khi không có `ZAP_CLIENT_SECRET` hay `ZAP_REFRESH_TOKEN`, `chay-zap.sh` lấy refresh token của `KT_ZAP_NGUOI_DUNG` (mặc định `quantri`) bằng `kiem-thu-phu-dinh/lay-token.mjs`, nên cần thêm `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET`. Đích chỉ tới được từ máy chạy (ví dụ `localhost`) thì đặt `ZAP_DOCKER_NETWORK=host`. Báo cáo ghi vào `ZAP_BAO_CAO` (mặc định `infra/security/zap/ket-qua/`, đã bỏ qua trong git): `zap.sarif.json`, `zap-api.json` cho API; `zap-web.sarif.json`, `zap-web.json` cho web (mẫu `sarif-json` của ZAP thêm đuôi `.json` vào `reportFile`).

Chạy trực tiếp `zap.sh -cmd -autorun infra/security/zap/okr-api.yaml` cũng được nếu đã đặt đủ biến `ZAP_*` (xem đầu tệp kế hoạch); khi đó `exitStatus` của kế hoạch trả 1 khi có High, 2 khi có Medium, và cần chạy thêm `node infra/security/zap/kiem-ngoai-le.mjs <tệp sarif>` để xét ngoại lệ.

Trong pipeline, bước `vtZapScan` của `ci/vt-pipeline` gọi `chay-zap.sh`, lấy client secret từ Jenkins credentials và lưu báo cáo làm artifact. Client `mission-zap` cho grant `client_credentials` trên staging cần được tạo trong realm (thuộc task IAM), với vai trò người dùng thử và claim `tenant` của tenant kiểm thử.

## Tiêu chí và ngoại lệ

Đạt khi 0 cảnh báo High và mọi cảnh báo Medium khớp một mục còn hạn trong `ngoai-le.yaml`. Cảnh báo High chỉ được bỏ qua khi mục ngoại lệ có `trangThai: da-duyet` (dương tính giả đã xác minh). Mục mới phải có giải trình kèm mã thiết kế làm căn cứ, vai trò người duyệt, ngày xem lại tối đa ba tháng; mục tạm cho lỗi đang chờ sửa có hạn ngắn và ghi rõ khi nào gỡ.

## Kết quả lần chạy đầu (23/10/2026, cục bộ)

Quét API module org với org-service và Keycloak 26.4 (realm dev) chạy cục bộ, đăng nhập bằng tài khoản quản trị: 39 URL từ hợp đồng, 0 High, 1 Medium (30001 trên `POST /org/imports`: thân multipart hỏng làm service trả 500, đã ghi nhận và có ngoại lệ hạn 06/11/2026). Quét web với nginx của SVC-17: bản đúng cấu hình đạt (một Medium 10098 về CORS `*` trên tệp tĩnh công khai, đã giải trình); bản cố ý bỏ CSP bị chặn với 3 High (10038, 10020). Chưa chạy trên server dev dùng chung vì phiên này không tới được server đó.

Quét thụ động lưu lượng của bộ hành trình đầu cuối (TL-04 mục 9.8) cần chạy Playwright qua proxy ZAP ở chế độ daemon; việc này làm khi khung chạy hành trình QA-03 có trên môi trường thật.

## Liên quan

- [Bảng tự đánh giá ASVS](../../../docs/attt/asvs-4.0.3-tu-danh-gia.md)
- [Bộ kiểm thử phủ định](../kiem-thu-phu-dinh/README.md)
- [Bảo mật frontend](../../../wiki/08-bao-mat-frontend.md)
