> Nguồn: Baseline mục 4 (ADR-08, ADR-21 tham chiếu), mục 10 (AC); TL-01 mục bảo mật (token, MFA, step-up RFC 9470, VNeID, OPA, CORS, CSRF), Phụ lục A (ADR-08, ADR-21); TL-02 QT-00 (BR-00-01 đến BR-00-08, SCR-0001, trạng thái `acr`); TL-04 mục 9.2 (SEC-04, SEC-07, SEC-08, SEC-13 đến SEC-18, SEC-54); mã `@vt/auth`; `infra/dev/README.md` (realm dev).

# Xác thực và phân quyền

Trang này giải thích cách người dùng đăng nhập vào phân hệ web, cách token được giữ và làm mới, khi nào cần xác thực bước lên, và vai trò của frontend trong phân quyền. Nguyên tắc xuyên suốt là máy chủ quyết định quyền: frontend chỉ ẩn những gì người dùng chắc chắn không dùng được để giao diện gọn hơn, còn mọi kiểm soát thật nằm ở APISIX, OPA và mission-core.

## Mô hình định danh (ADR-08)

ADR-08 chọn Keycloak 26 đặt theo từng cell, dùng tính năng Organizations để biểu diễn tenant, thay vì mỗi tenant một realm, vì realm-per-tenant không co giãn tới hàng nghìn tenant. Keycloak liên kết VNeID và IdP của khách hàng qua môi giới danh tính (identity brokering). Mọi client OIDC dùng luồng Authorization Code với PKCE, kèm `state` và `nonce` (SEC-17). Ứng dụng web là client công khai (public client), không giữ bí mật client.

Với VNeID, TL-01 quy định `state` 128 bit, `nonce`, `code_verifier` 256 bit với phương thức S256 và dùng Pushed Authorization Requests (PAR) nếu VNeID hỗ trợ. BR-00-03 chỉ chấp nhận tài khoản VNeID mức 2; MSG-00-03 hướng dẫn người dùng nâng mức hoặc dùng tài khoản cơ quan.

## Luồng đăng nhập

Luồng đăng nhập ứng với QT-00 và SCR-0001. Người dùng mở tên miền của tenant; hệ thống phân giải tenant và cell; shell chuyển người dùng tới Keycloak của cell để xác thực bằng tài khoản cơ quan, VNeID hoặc chứng thư số (chỉ phương thức được bật trong `registry.tenant_idp` mới hiển thị). Keycloak đánh giá có cần MFA không, xác thực MFA nếu cần, rồi trả mã ủy quyền; shell đổi mã lấy token. Nếu người dùng có từ hai phân công còn hiệu lực, shell cho chọn đơn vị và vai trò, ghi ngữ cảnh bằng `PUT /api/v1/org/me/context` và nhận token mới có claim `org`. Cuối cùng shell nạp UI Pack, quyền và đưa người dùng tới SCR-0002.

Phần lớn giao diện SCR-0001 (ô mật khẩu, mã OTP, chọn phương thức MFA) chạy trong Keycloak, nên tuỳ biến giao diện đăng nhập là việc của theme Keycloak (suy luận từ việc bảng chức năng SCR-0001 gọi trực tiếp các đường dẫn `/realms/{cell}/...`). Phần chọn phân công và báo cáo truy cập bất thường thuộc shell.

## Mức bảo đảm xác thực và MFA

TL-02 định nghĩa trạng thái mức bảo đảm của phiên (`acr`): `CHUA_XAC_THUC`, `AAL1`, `AAL2`, `HET_HAN`, `BI_THU_HOI`. Bảng dưới gom các quy tắc của QT-00 và TL-01 liên quan tới MFA.

| Quy tắc | Nội dung |
|---|---|
| BR-00-01 | Vai trò `appraiser`, `decider`, `second_approver`, `tenant_admin`, `platform_admin` chỉ dùng trong phiên AAL2 |
| BR-00-02 | Duyệt có ký số, xác nhận nghiệm thu, khởi chạy khoá kỳ, mở lại kỳ yêu cầu MFA trong vòng 15 phút trước thao tác |
| BR-00-05 | Sai 5 lần trong 15 phút khoá tài khoản 15 phút (SEC-14) |
| BR-00-06 | Đăng nhập từ thiết bị hoặc vị trí mới phải MFA và gửi thông báo (SEC-54) |
| SEC-13 | MFA bắt buộc bằng TOTP, WebAuthn hoặc VNeID mức 2 |
| TL-02 QT-00 | Phiên AAL2 hạ xuống AAL1 sau 15 phút; phiên hết hạn sau 30 phút không hoạt động |

Xác thực bước lên (step-up) là cơ chế giao diện phải xử lý thường xuyên vì nhiều thao tác pháp lý cần nó (ký số, ký lô, mở lại kỳ, khoá kỳ, nghiệm thu). Theo TL-01, máy chủ kiểm `acr=aal2` và `auth_time` không quá 5 phút; nếu không đạt, máy chủ trả 401 với `WWW-Authenticate` mang lỗi `insufficient_user_authentication` theo RFC 9470. Shell khi đó chuyển người dùng sang Keycloak để xác thực lại với mức yêu cầu, rồi quay về và gửi lại thao tác. Vì yêu cầu có `Idempotency-Key`, gửi lại không tạo bản ghi trùng (xem [Quy ước API](06-quy-uoc-api.md)). Mã hiện tại chưa xử lý luồng này; đây là việc cần làm trước khi hiện thực các màn hình ký số (suy luận về thứ tự ưu tiên).

TL-01 có thêm thông số về mật khẩu và lưu trữ phía Keycloak (băm Argon2id hoặc PBKDF2 theo SEC-15); frontend không xử lý mật khẩu và không lưu mật khẩu ở trình duyệt (SEC-04).

## Thời hạn token và phiên

Thời hạn token là điểm chưa thống nhất giữa các tài liệu. Bảng dưới chép nguyên giá trị của từng nguồn.

| Nguồn | Access token | Refresh token | Phiên SSO |
|---|---|---|---|
| TL-01 mục bảo mật | 15 phút, ký ES256 | Xoay vòng, hạn 8 giờ | Tối đa 10 giờ |
| TL-02 QT-00 | 5 phút | 30 phút trượt | Hết hạn sau 30 phút không hoạt động |
| TL-04 SEC-16 | 5 phút | 8 giờ, thu hồi ngay | Không nêu |

Giao diện không nên phụ thuộc vào con số cụ thể: thư viện làm mới token trước khi hết hạn theo trường `expires_in` mà Keycloak trả về, và mọi request phải chịu được việc nhận 401 do token hết hạn giữa chừng (làm mới rồi gửi lại). Việc chốt con số thuộc cấu hình Keycloak (xem trang 14).

## Lưu giữ token

TL-01 quy định token chỉ giữ trong bộ nhớ của ứng dụng, không dùng cookie cho API; vì vậy tấn công giả mạo yêu cầu liên trang (CSRF) không áp dụng cho API. SEC-07 cấm ghi token vào log; SEC-08 cấm đặt JWT trong URL (lỗ hổng S11 của hệ cũ). `authInterceptor` chỉ gắn `Authorization` cho request tới `/api/` hoặc chứa `/api/v1/`, không gắn cho tài nguyên tĩnh hay CDN, để token không rò ra nguồn khác.

Cần kiểm tra một điểm trong mã: `angular-oauth2-oidc` mặc định lưu token vào `sessionStorage` của trình duyệt nếu ứng dụng không cung cấp `OAuthStorage` riêng (suy luận từ hành vi mặc định của thư viện; `provideVtAuth()` hiện chỉ gọi `provideOAuthClient()` không đối số). Nếu đúng, cách lưu này lệch với yêu cầu "chỉ trong bộ nhớ" của TL-01 và cần một `OAuthStorage` lưu trong bộ nhớ, chấp nhận việc người dùng phải đăng nhập lại (qua SSO của Keycloak, thường không phải nhập lại mật khẩu) khi tải lại trang.

## Phân quyền: RBAC, ABAC và OPA

Baseline mục 10 quy định phân quyền gồm RBAC theo vai trò và ABAC theo cây tổ chức (đơn vị và cấp dưới), luồng (`DANG`, `NHA_NUOC`) và lớp dữ liệu (C0 đến C3), đánh giá bằng Open Policy Agent (SEC-18). ADR-21 đặt OPA chạy cùng pod với service, gọi qua localhost, bundle chính sách ký số; dùng đánh giá từng phần (partial evaluation) để sinh điều kiện SQL cho danh sách; lỗi thì từ chối (fail closed). Mỗi quyết định tốn khoảng 0,3 đến 1 ms.

Tách vai trò theo PL 4 V.1 là ràng buộc mà giao diện thấy rõ nhất: với cùng một hồ sơ, người khởi tạo, nghiệm thu, thẩm định, quyết định, khoá kỳ phải khác nhau. Giao diện không tự tính được ràng buộc này vì cần lịch sử tham gia hồ sơ; nó chỉ hiển thị lỗi OPA trả về bằng MSG tương ứng, ví dụ MSG-02-11 (người soạn không tự duyệt OKR), MSG-09-08 (người đã nghiệm thu không thẩm định), MSG-12-05, MSG-13-01. BR-14-03 còn yêu cầu OPA ẩn hẳn cảnh báo bất thường khỏi người là đối tượng của cảnh báo.

## Vai trò ở phía giao diện

`@vt/auth` khai báo kiểu `VtRole` gồm mười một vai trò của baseline: `platform_admin`, `tenant_admin`, `policy_author`, `leader`, `manager`, `member`, `appraiser`, `decider`, `second_approver`, `inspector`, `ai_readonly`. `SessionService.readClaims()` đọc vai trò từ `realm_access.roles` của ID token, tenant từ claim `tenant` và đơn vị từ claim `unit_id`. `roleGuard(...roles)` chặn tuyến theo vai trò; menu trong `nav.ts` ẩn mục theo cùng danh sách. Chú thích trong mã ghi rõ "quyết định cuối cùng vẫn ở máy chủ (OPA, ABAC)".

Hiện shell chặn tuyến như sau: `/dashboard` cho `leader`, `manager`, `inspector`; `/config-studio` cho `policy_author`, `tenant_admin`; `/admin` cho `platform_admin`, `tenant_admin`. Các tuyến khác chỉ cần đăng nhập. Khi bổ sung remote, danh sách vai trò của tuyến phải lấy từ cột "Quyền" và "Tác nhân" của SCR trong TL-02, không tự suy ra.

## Hiện trạng mã

`SessionService` dùng `angular-oauth2-oidc` với `responseType: 'code'` (PKCE do thư viện tự sinh), `redirectUri` và `postLogoutRedirectUri` là gốc của trang, tắt silent refresh bằng iframe (`useSilentRefresh: false`) nên việc làm mới dựa vào refresh token qua `setupAutomaticSilentRefresh()`, tắt kiểm tra phiên bằng iframe. Khởi tạo bằng `loadDiscoveryDocumentAndLogin()`. Đăng xuất gọi `logOut()`, tức chuyển tới `end_session_endpoint` của Keycloak như TL-01 yêu cầu.

Khi `auth.enabled = false` trong `/config.json` (mặc định ở dev), `SessionService` tạo một người dùng giả lập có vai trò `member` và `manager` để phát triển giao diện không cần Keycloak. Chế độ này chỉ dành cho dev và không được bật ở môi trường có dữ liệu thật.

Ba điểm cần sửa hoặc bổ sung trong mã: chú thích trong `session.service.ts` dẫn chiếu SEC-15 và SEC-54 cho `state` và `nonce`, trong khi theo TL-04 biện pháp đúng là SEC-17 (SEC-15 là băm mật khẩu, SEC-54 là phát hiện đăng nhập bất thường); chưa có luồng chọn phân công và `PUT /api/v1/org/me/context`; chưa xử lý 401 step-up.

## Keycloak ở môi trường dev

Theo `infra/dev/README.md`, Keycloak 26.4 ở dev nạp realm `mission` với 11 vai trò AC và client công khai `mission-web` dùng Authorization Code + PKCE S256, cùng một số tài khoản thử buộc đổi mật khẩu ở lần đăng nhập đầu. Tên tài khoản, mật khẩu và địa chỉ máy chủ không ghi trong wiki; người cần dùng đọc tệp `.env` cục bộ không commit theo `AGENTS.md`. Ở dev, realm là một realm cố định, khác với production nơi realm đặt theo cell và tenant là Organization (suy luận từ ADR-08 và đường dẫn `/realms/{cell}/...` của SCR-0001).

## Liên quan

- [Quy trình và màn hình QT-00 đến QT-08](05a-quy-trinh-va-man-hinh-qt00-08.md) (QT-00)
- [Quy ước API](06-quy-uoc-api.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md) (tác nhân và vai trò)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
