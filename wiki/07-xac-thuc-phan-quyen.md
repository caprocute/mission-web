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

Xác thực bước lên (step-up) là cơ chế giao diện phải xử lý thường xuyên vì nhiều thao tác pháp lý cần nó (ký số, ký lô, mở lại kỳ, khoá kỳ, nghiệm thu). Theo TL-01, máy chủ kiểm `acr=aal2` và `auth_time` không quá 5 phút; nếu không đạt, máy chủ trả 401 với `WWW-Authenticate` mang lỗi `insufficient_user_authentication` theo RFC 9470. Shell khi đó mở hộp MFA bước lên, người dùng xác thực lại ở Keycloak với mức yêu cầu, rồi thao tác được gửi lại. Vì yêu cầu có `Idempotency-Key`, gửi lại không tạo bản ghi trùng (xem [Quy ước API](06-quy-uoc-api.md)). Từ WEB-006 luồng này đã có trong `@vt/auth`, mô tả ở mục "Hiện trạng mã". Realm dev đã cấu hình sẵn ánh xạ `acr` và luồng OTP để thử step-up (WEB-056, xem mục Keycloak ở môi trường dev).

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

Từ WEB-006, `provideVtAuth()` thay kho lưu mặc định của `angular-oauth2-oidc` (vốn là `sessionStorage`) bằng `VtTokenStorage`: access token, refresh token và ID token chỉ nằm trong bộ nhớ của ứng dụng. Riêng `nonce`, `PKCE_verifier`, `session_state` và đường dẫn cần quay về phải sống qua lần Keycloak chuyển hướng về trang, nên vẫn ở `sessionStorage` của tab và bị thư viện xoá ngay sau khi đổi mã lấy token. Hệ quả là tải lại trang thì mất token: shell chuyển sang Keycloak và SSO cho qua ngay, thường không phải nhập lại mật khẩu.

## Phân quyền: RBAC, ABAC và OPA

Baseline mục 10 quy định phân quyền gồm RBAC theo vai trò và ABAC theo cây tổ chức (đơn vị và cấp dưới), luồng (`DANG`, `NHA_NUOC`) và lớp dữ liệu (C0 đến C3), đánh giá bằng Open Policy Agent (SEC-18). ADR-21 đặt OPA chạy cùng pod với service, gọi qua localhost, bundle chính sách ký số; dùng đánh giá từng phần (partial evaluation) để sinh điều kiện SQL cho danh sách; lỗi thì từ chối (fail closed). Mỗi quyết định tốn khoảng 0,3 đến 1 ms.

Tách vai trò theo PL 4 V.1 là ràng buộc mà giao diện thấy rõ nhất: với cùng một hồ sơ, người khởi tạo, nghiệm thu, thẩm định, quyết định, khoá kỳ phải khác nhau. Giao diện không tự tính được ràng buộc này vì cần lịch sử tham gia hồ sơ; nó chỉ hiển thị lỗi OPA trả về bằng MSG tương ứng, ví dụ MSG-02-11 (người soạn không tự duyệt OKR), MSG-09-08 (người đã nghiệm thu không thẩm định), MSG-12-05, MSG-13-01. BR-14-03 còn yêu cầu OPA ẩn hẳn cảnh báo bất thường khỏi người là đối tượng của cảnh báo.

## Vai trò ở phía giao diện

`@vt/auth` khai báo kiểu `VtRole` gồm mười một vai trò của baseline: `platform_admin`, `tenant_admin`, `policy_author`, `leader`, `manager`, `member`, `appraiser`, `decider`, `second_approver`, `inspector`, `ai_readonly`. `SessionService.readClaims()` đọc vai trò từ `realm_access.roles` của ID token, tenant từ claim `tenant` và đơn vị từ claim `unit_id`. `roleGuard(...roles)` chặn tuyến theo vai trò; menu trong `nav.ts` ẩn mục theo cùng danh sách. Chú thích trong mã ghi rõ "quyết định cuối cùng vẫn ở máy chủ (OPA, ABAC)".

Hiện shell chặn tuyến như sau: `/dashboard` cho `leader`, `manager`, `inspector`; `/config-studio` cho `policy_author`, `tenant_admin`; `/admin` cho `platform_admin`, `tenant_admin`. Các tuyến khác chỉ cần đăng nhập. Khi bổ sung remote, danh sách vai trò của tuyến phải lấy từ cột "Quyền" và "Tác nhân" của SCR trong TL-02, không tự suy ra; các cột này đã được tổng hợp thành [ma trận phân quyền](15a-ma-tran-phan-quyen.md), còn nguyên tắc, phạm vi dữ liệu và cách hiện thực từng tầng nằm ở [trang Phân quyền](15-phan-quyen.md). Đối chiếu với ma trận cho thấy chặn theo tuyến cha hiện nay quá chặt ở `/admin`, `/config-studio`, `/dashboard`; task WEB-054 và WEB-055 thay `roleGuard` rải rác bằng bảng quyền sinh từ ma trận.

## Hiện trạng mã

`SessionService` (WEB-006) dùng `angular-oauth2-oidc` với `responseType: 'code'` (PKCE S256, `state`, `nonce` do thư viện sinh và kiểm, SEC-17). Khởi tạo gồm ba bước: nạp discovery document, xử lý callback bằng `tryLoginCodeFlow()`, rồi nếu chưa có token thì gọi `initCodeFlow()` với đường dẫn đang mở làm `state` để quay lại đúng chỗ sau đăng nhập. Keycloak không phản hồi thì shell hiện thông báo lỗi chứ không chuyển hướng lặp. Token được làm mới bằng refresh token qua `setupAutomaticSilentRefresh()`; làm mới lỗi thì phiên sang `HET_HAN`, màn hình giữ nguyên và hiện nút "Đăng nhập lại". Đăng xuất gọi `logOut()`, tức chuyển tới `end_session_endpoint` của Keycloak.

`authInterceptor` xử lý lỗi xác thực theo bốn nhánh:
- 401 có `insufficient_user_authentication`: đọc `acr_values` và `max_age` từ `WWW-Authenticate`, gọi `requireStepUp`, xong thì gửi lại.
- 401 khác: làm mới token một lần (các request song song dùng chung một lần làm mới) rồi gửi lại; không được thì báo hết phiên.
- 403: hiện thông báo theo mã lỗi, MSG-00-06 khi không có phân công, MSG-00-07 khi tenant tạm ngừng, còn lại là câu chung. Màn hình có MSG riêng (ví dụ MSG-09-08) đặt `SKIP_FORBIDDEN_NOTICE` trong `HttpContext` để không bị hiện hai lần.
- Lệnh ghi được gắn `Idempotency-Key` ngay trước khi gửi, nên lần gửi lại sau bước lên dùng cùng khoá.

Xác thực bước lên dùng `await session.requireStepUp('aal2', maxAgeGiay, lyDo)` trước thao tác quan trọng (ký, quyết định, nghiệm thu, khoá kỳ). Nếu phiên đã đạt mức và MFA còn trong `max_age` thì Promise xong ngay; nếu chưa, `<vt-step-up-dialog>` trong shell mở hộp MFA. Nút "Xác thực" mở cửa sổ Keycloak (`initLoginFlowInPopup` với `acr_values`, `max_age`, trang nhận kết quả `/silent-refresh.html`), nên màn hình bên dưới và dữ liệu đang nhập không mất như TL-02 QT-00 yêu cầu. Huỷ thì Promise bị từ chối. Cửa sổ phải mở từ thao tác bấm của người dùng, nếu không trình duyệt chặn popup; vì thế hộp MFA luôn có nút bấm chứ không tự mở cửa sổ. `SessionService` theo dõi `acr` và `auth_time` trong token và tự hạ `AAL2` về `AAL1` sau 15 phút.

Chọn phân công (QT-00 bước 7) chạy sau đăng nhập. `loadContext()` gọi `GET /api/v1/org/me` và xử lý bốn trường hợp:
- Không có phân công: hiện MSG-00-06 và nút đăng xuất.
- Một phân công: dùng ngay.
- Đã chọn trong phiên này (khoá `vt.assignment` của `sessionStorage`): dùng lại.
- Còn lại: `<vt-context-picker>` mở hộp chọn, chọn sẵn phân công dùng lần trước, nếu không có thì phân công chính.

Phân công có vai trò AAL2 ghi chú MSG-00-05 và cần bước lên trước khi dùng (BR-00-01). Chọn xong, shell ghi `PUT /api/v1/org/me/context`, làm mới token để nhận claim `org`, rồi đặt vai trò và đơn vị của phiên theo phân công đó. Bảng quyền `can()` (WEB-054) đọc vai trò này nên menu đổi theo phân công. Nút "Đổi phân công" trên thanh trên hiện khi có từ hai phân công. Lỗi khi tải phân công (trừ tenant tạm ngừng) không chặn người dùng: phiên dùng vai trò trong token.

Khi `auth.enabled = false` trong `/config.json` (mặc định ở dev), `SessionService` tạo người dùng giả lập, phân công lấy từ API giả lập (`?mock-org=kiem-nhiem` cho ba phân công, `?mock-org=khong-phan-cong` cho MSG-00-06), và bước lên được giả lập ngay trên trình duyệt. Chế độ này chỉ dành cho dev và không được bật ở môi trường có dữ liệu thật. `?mock-error=401` trên URL trang làm API giả lập trả 401 step-up cho lệnh ghi đầu tiên của mỗi `Idempotency-Key`, dùng để thử luồng bước lên.

Đường API `/api/v1/org/me` và `/api/v1/org/me/context` lấy từ SCR-0001 của TL-02; dạng dữ liệu (`Assignment`, `MyOrgProfile`) sinh từ `contracts/openapi/modules/org.yaml` (WEB-064) và cần đối chiếu khi org-service phát hành springdoc thật.

## Keycloak ở môi trường dev

Theo `infra/dev/README.md`, Keycloak 26.4 ở dev nạp realm `mission` với 11 vai trò AC đúng theo `docs/phan-quyen/ma-tran.json` (kiểm bằng `python3 docs/phan-quyen/check_realm.py`) và client công khai `mission-web` dùng Authorization Code + PKCE S256. Từ WEB-056, mọi claim ngữ cảnh do một client scope dùng chung `mission-context` cấp: vai trò realm ở `realm_access.roles` (cả ID token và access token), `tenant`, `unit_id` và `acr`; hai thuộc tính `tenant`, `unit_id` được khai báo trong hồ sơ người dùng của realm với quyền sửa chỉ cho quản trị, nên người dùng không tự đặt được như TL-01 yêu cầu. Mười tài khoản thử phủ đủ vai trò AC-01..AC-10, danh sách nằm trong README hạ tầng dev; mật khẩu và khoá OTP thử chỉ nằm trong `infra/dev/.env` không commit.

Luồng đăng nhập `mission-browser` của realm dev hiện thực hai quy tắc xác thực. Thứ nhất, BR-00-01: tài khoản có vai trò `appraiser`, `decider`, `second_approver`, `tenant_admin` hoặc `platform_admin` luôn phải nhập OTP sau mật khẩu, và token nhận được mang `acr=aal2`. Thứ hai, step-up theo RFC 9470: realm ánh xạ `acr` với mức xác thực (`aal1` là 1, `aal2` là 2); khi ứng dụng gọi lại trang đăng nhập với `acr_values=aal2`, Keycloak hỏi OTP và trả token `acr=aal2`, còn nếu phiên đã ở mức `aal2` trong 15 phút gần nhất (BR-00-02) thì SSO cho qua ngay. Hai giới hạn của cấu hình dev cần biết: khi step-up, Keycloak hỏi lại mật khẩu trước OTP (đánh đổi để giữ đúng thứ tự mức xác thực, xem trang 14), và mức `aal2` hết hiệu lực sau 15 phút nên token làm mới sau đó có thể trở về `acr=aal1`, giao diện phải xử lý 401 `insufficient_user_authentication` như mọi trường hợp step-up khác. Ở dev, realm là một realm cố định, khác với production nơi realm đặt theo cell và tenant là Organization (suy luận từ ADR-08 và đường dẫn `/realms/{cell}/...` của SCR-0001).

## Liên quan

- [Phân quyền](15-phan-quyen.md) và [Ma trận phân quyền](15a-ma-tran-phan-quyen.md)
- [Quy trình và màn hình QT-00 đến QT-08](05a-quy-trinh-va-man-hinh-qt00-08.md) (QT-00)
- [Quy ước API](06-quy-uoc-api.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md) (tác nhân và vai trò)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
