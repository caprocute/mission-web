> Nguồn: baseline mục 4 (ADR-06, ADR-08, ADR-11, ADR-17) và mục 14 G3; TL-01 chương "Bảo mật" (Mô hình không tin cậy mặc định, Định danh và xác thực, Uỷ quyền tập trung bằng ABAC, Bảo vệ API và ứng dụng web, Khắc phục nhóm lỗ hổng nghiêm trọng S01 đến S05), mục "SVC-02 iam", chương "Chuyên đề G3: Hiệu năng của bảy lớp bảo vệ và mã hoá dữ liệu mật" (Mã hoá phong bì cho dữ liệu C2, Mã hoá đầu cuối cho dữ liệu C3, Ranh giới pháp lý với bí mật nhà nước), chương "Quan sát và nhật ký" (Log có cấu trúc, Che dữ liệu nhạy cảm, Tách nhật ký kiểm toán), chương "Độ tin cậy và khả năng chịu lỗi" mục "Timeout mặc định", Phụ lục A ADR-21, ADR-27; TL-03 chương "Mã hoá dữ liệu" (định dạng bản mã, E2EE), mục "Schema keyring và bảng dek"; TL-04 chương "An toàn thông tin" (Danh mục SEC và ánh xạ trục D, Quản lý bí mật và khoá: OpenBao, ESO, HSM); TL-02a BR-00-02; `contracts/openapi/common/common.yaml`; `docs/quy-dinh/BACKEND.md` mục 3. Mục "Nhật ký và che dữ liệu trong thư viện nền": TL-01 chương "Chịu lỗi và tự phục hồi" (mục "Cấu hình Resilience4j chuẩn", "Timeout mặc định", "Cô lập tài nguyên theo phụ thuộc"), chương "Quan sát" (mục "Log có cấu trúc", "Che dữ liệu nhạy cảm", "Tách nhật ký kiểm toán", "OpenTelemetry và lan truyền ngữ cảnh trace"); ADR-12, ADR-24; TL-03 (topic `sys.audit.v1`); task NEN-014, NEN-015, NEN-016. Mục "Nhật ký truy cập C2": TL-02b BR-12-10, BR-16-04; TL-01 "Tách nhật ký kiểm toán"; TL-03 "Sổ cái bất biến", L4470; task CORE-00-05.

# Bảo mật phía máy chủ

Trang này gom các biện pháp bảo mật mà người viết mã máy chủ phải hiện thực hoặc phải tôn trọng: xác thực và step-up, uỷ quyền OPA, mã hoá dữ liệu C2 và C3, che nhật ký, nhật ký kiểm toán, và bảng kiểm khi viết mã. Phía trình duyệt ở [trang 08](08-bao-mat-frontend.md), Keycloak và OIDC nhìn từ web ở [trang 07](07-xac-thuc-phan-quyen.md), mô hình vai trò, phạm vi dữ liệu và ma trận quyền ở [trang 15](15-phan-quyen.md) và [trang 15a](15a-ma-tran-phan-quyen.md); trang này không chép lại các trang đó. Mã SEC dẫn dưới đây lấy từ danh mục SEC-01 đến SEC-60 của TL-04.

## Không tin cậy mặc định và mTLS

Kiến trúc theo mô hình không tin cậy mặc định: không request nào được tin chỉ vì đến từ mạng nội bộ. JWT của người dùng được kiểm ở cổng APISIX và kiểm lại ở service; mọi kết nối giữa service ứng dụng được mã hoá và xác thực bằng mTLS với danh tính SPIFFE do Istio ambient (ztunnel) cấp, chế độ STRICT toàn cụm (ADR-11, SEC-21), cộng chính sách uỷ quyền tầng 4 chỉ cho phép các cặp service đã khai báo; mọi quyết định truy cập dữ liệu đi qua chính sách tập trung. Tầng dữ liệu nằm ngoài mesh nhưng dùng TLS hai chiều gốc có kiểm chứng thư (SEC-57). Chi tiết hạ tầng ở [trang 19](19-ha-tang-production-va-trien-khai.md).

## Keycloak, token và step-up

Keycloak 26 (SVC-02) là nhà cung cấp định danh duy nhất; ứng dụng chỉ là resource server, không tự cấp token và không bao giờ nhận mật khẩu (sửa S02, S04). Tenant pool là một Organization trong realm chung, tenant silo có realm riêng (ADR-08). Theo TL-01, access token sống 15 phút ký ES256, refresh token xoay vòng dùng một lần (phát hiện dùng lại thì thu hồi cả chuỗi), phiên SSO tối đa 10 giờ và nhàn rỗi 30 phút, khoá tạm 15 phút sau 5 lần sai. MFA bắt buộc cho các vai trò quản trị, lãnh đạo, người phê duyệt, cán bộ tổ chức; phương thức gồm TOTP, WebAuthn và đăng nhập VNeID mức 2. Thời hạn token lệch giữa các nguồn: TL-04 SEC-16 ghi access 5 phút, refresh 8 giờ; TL-01 mục "Định danh và xác thực" ghi refresh 8 giờ, mục "SVC-02 iam" ghi access 15 phút (trang 14).

Thao tác nhạy cảm (ký, quyết định xếp loại, mở lại kỳ, thay đổi quyền, xuất dữ liệu C2, nghiệm thu) yêu cầu nâng bậc xác thực. Service kiểm claim `acr` bằng `aal2` và `auth_time` trong cửa sổ cho phép; nếu không đạt, trả `401` với header `WWW-Authenticate` chứa `insufficient_user_authentication` theo RFC 9470, và giao diện mở luồng xác thực lại. Độ dài cửa sổ chưa thống nhất: TL-01 và hợp đồng `contracts/openapi/common/common.yaml` ghi `auth_time` không quá 5 phút, còn BR-00-02 của TL-02a và tham số Policy Pack `mfa_stepup_window_minutes` ghi 15 phút. Đề xuất đọc cửa sổ từ tham số Policy Pack với mặc định theo hợp đồng (trang 14, chờ chốt). Tên claim tenant trong token cũng chưa thống nhất (`tenant_id`, `tid`, `tenant`), xem trang 14.

## Uỷ quyền bằng OPA cùng pod

Quyết định quyền tách khỏi mã nghiệp vụ sang Open Policy Agent (ADR-21, SEC-18). Chính sách viết bằng Rego, quản lý trong Git, kiểm bằng `opa test`, phân phối thành bundle có ký tới container `opa` chạy cùng pod với service; service gọi qua localhost. Đầu vào gồm chủ thể lấy từ JWT (người dùng, tenant, vai trò, đơn vị, mức xác thực), hành động, và tài nguyên (loại, đơn vị chủ quản, người được giao, kỳ, lớp dữ liệu). Dữ liệu cây tổ chức được đẩy vào OPA dạng dữ liệu bundle theo tenant và cập nhật khi có EVT-14 hoặc EVT-15. Service chỉ cho phép khi `allow` đúng và tập `deny` rỗng; các luật `deny` mẫu của TL-01 chặn dữ liệu C3 với người không phải người nhận và chặn nghiệm thu khi chưa nâng bậc.

Với danh sách, kiểm từng dòng quá đắt, nên thư viện nền dùng đánh giá từng phần (partial evaluation): service gửi truy vấn với tài nguyên chưa biết, OPA trả điều kiện còn lại, thư viện dịch thành mệnh đề SQL bổ sung. Kết quả được đệm theo (người dùng, hành động, phiên bản bundle) trong 60 giây. Chi phí 0,3 đến 1 ms mỗi quyết định; thời gian chờ gọi OPA là 50 ms kết nối và 100 ms tổng, và OPA lỗi thì service từ chối (fail closed). Ở mã Java, quyền khai báo bằng `@RequiresPermission` ở use case lớp `application`; mọi endpoint mặc định từ chối (`anyRequest().authenticated()`, danh sách công khai khai báo tường minh một chỗ, sửa S03). Rego mẫu của TL-01 dùng vai trò `LANH_DAO`; danh sách vai trò thật lấy từ ma trận ở trang 15a, không tự đặt trong mã hay trong chính sách.

## Bảy lớp bảo vệ G3 và chi phí

Baseline mục 14 G3 đặt nguyên tắc: lớp bảo vệ nào tốn kém thì đưa ra khỏi đường găng của request hoặc chỉ chạy ở mốc pháp lý tần suất thấp. Bảng sau chép bảy lớp và chi phí ước tính.

| Lớp | Đặt ở đâu | Chi phí ước tính |
|---|---|---|
| 1. Nhật ký chuỗi băm | Bất đồng bộ qua outbox tới ledger-service, 64 phân vùng chuỗi mỗi tenant, niêm phong Merkle mỗi giờ | +0,2 đến 0,5 ms cho một dòng outbox trong giao dịch |
| 2. Ký số mốc pháp lý | Chỉ ở 4 mốc, bất đồng bộ, ký lô | 1 đến 3 s mỗi lần ký nhưng ngoài đường găng |
| 3. Minh chứng chỉ ghi | SHA-256 tính dạng luồng khi tải lên, Object Lock | khoảng 15 đến 25 ms cho tệp 10 MB |
| 4. Hai trục thời gian | Chỉ bảng tổ chức và phân công | ghi gấp khoảng 2 lần trên tập nhỏ |
| 5. Khoá kỳ, hai người duyệt | Kiểm trạng thái kỳ trong bộ đệm khi ghi | dưới 0,1 ms |
| 6. Chống làm thay | Step-up chỉ ở thao tác quan trọng, phát hiện đăng nhập bất thường bất đồng bộ | không tác động request thường |
| 7. Tách quyền kỹ thuật | Mã hoá trường C2 AES-256-GCM với DEK đệm 15 phút | 1 đến 3 µs mỗi trường; gọi HSM khoảng 5 ms, dưới 1 lần mỗi 15 phút mỗi tenant mỗi pod |

Ngân sách độ trễ phía máy chủ cho "cập nhật tiến độ" (p95) theo baseline: cổng và WAF 3 ms, xác thực JWT 0,3 ms, OPA 0,5 ms, mTLS 0,5 ms, logic 10 ms, giao dịch CSDL và outbox 8 ms, mã hoá trường 0,05 ms, tổng khoảng 25 ms so với ngưỡng 800 ms của NFR-03.

## Mã hoá phong bì cho dữ liệu C2

Mã hoá phong bì dùng hai tầng khoá (ADR-06, SEC-11). KEK theo tenant nằm trong OpenBao Transit, được bảo vệ bởi khoá gốc trong HSM; CELL-DTW dùng HSM riêng qua PKCS#11. DEK là khoá AES-256 theo tổ hợp tenant × tháng × mục đích, lưu đã bọc trong `keyring.dek` của CSDL (mục đích theo TL-03 là `NHAN_XET_DANH_GIA`, `KHIEU_NAI`, `GOP_Y_KIEM_DIEM`, `DINH_DANH_CA_NHAN`, `TEP_MINH_CHUNG`, `BLIND_INDEX`; TL-01 dùng ví dụ `pii`, `appraisal-comment`, `complaint`). Chia DEK theo mục đích cho phép xoá bằng mật mã có chọn lọc. Tên KEK lệch ba nguồn: TL-01 `kek-<tenant>`, TL-04 `kek-tenant-<tenantId>` (kiểu `aes256-gcm96`), ví dụ `kek_ref` của TL-03 `transit/keys/t-hcm-c2@v3` (trang 14).

Luồng ghi một trường C2: `CryptoProvider` tìm DEK của tenant cho tháng hiện tại trong bộ đệm; nếu chưa có thì đọc DEK đã bọc từ `keyring.dek`, gửi OpenBao Transit mở bọc, lưu đệm; mã hoá bằng AES-256-GCM với nonce ngẫu nhiên 96 bit và AAD; ghi bản mã vào cột `_enc` cùng `dek_id`. Bản mã tự mô tả: 1 byte phiên bản thuật toán, 16 byte `dek_id`, 12 byte nonce, phần bản mã, 16 byte thẻ xác thực. AAD là chuỗi `tenant_id | schema.bảng.cột | id dòng`, nên bản mã chép sang dòng khác, cột khác hay tenant khác sẽ giải mã thất bại. Byte phiên bản cho phép đổi sang thuật toán của Ban Cơ yếu ở bản cách ly mà vẫn đọc dữ liệu cũ.

Bộ đệm DEK nằm trong heap của tiến trình, sống 15 phút, tối đa 10.000 mục, khoá theo `(tenant, purpose, month)`. DEK không bao giờ được ghi vào Valkey, log hay heap dump (heap dump tắt ở môi trường vận hành, pod cấm `ptrace`). Xoay vòng: DEK mới mỗi tháng tự nhiên, dữ liệu cũ giải mã bằng DEK cũ nhờ `dek_id`; KEK xoay hằng năm hoặc khi nghi lộ bằng `transit/rewrap` bọc lại DEK mà không giải mã dữ liệu (SEC-12); mã hoá lại dữ liệu chỉ khi nghi DEK lộ. Huỷ KEK của tenant khi hết hạn lưu trữ làm mọi dữ liệu C2 của tenant, kể cả trong bản sao lưu, không còn đọc được (xoá bằng mật mã).

Dữ liệu C2 không truy vấn theo giá trị được. Với trường cần tra cứu chính xác như số định danh, bảng có cột chỉ mục mù `_bidx` lưu HMAC-SHA-256 của giá trị đã chuẩn hoá với khoá HMAC riêng của tenant, cắt 16 byte; tra cứu tính HMAC của giá trị tìm rồi so khớp. Chỉ mục mù chỉ lộ "hai dòng có cùng giá trị", không hỗ trợ tìm theo khoảng hay tiền tố. DBA không đọc được C2: chính sách OpenBao chỉ cấp `decrypt` cho tài khoản dịch vụ của ứng dụng, DBA dùng vai trò `dba_ops` qua PAM không có `SELECT` trên bảng nghiệp vụ, và đọc dữ liệu khi xử lý sự cố phải qua quy trình phá kính hai người duyệt, có thời hạn 2 giờ, ghi hình phiên và ghi sổ cái (SEC-58).

## Mã hoá trường trong mã (NEN-015)

Gói `vn.viettel.okrkpi.shared.crypto` trong `vt-platform-starter` cung cấp hiện thực chuẩn cho mã hoá phong bì C2 và chỉ mục mù:

- **Đánh dấu cột C2 và tích hợp JPA/jOOQ:**
  - Mọi cột lưu bản mã C2 đặt tên hậu tố `_enc` (`BYTEA`), đi kèm cột `dek_id` (`UUID` tham chiếu `keyring.dek(id)`). Trong thực thể JPA, trường ánh xạ cột `*_enc` bắt buộc gắn `@Convert(converter = ...)` kế thừa `AbstractC2AttributeConverter` (hoặc dùng `C2StringAttributeConverter`) — hoặc giữ kiểu `byte[]` và gọi trực tiếp `FieldEncryptor` ở adapter lưu trữ (jOOQ/JDBC).
  - Luật ArchUnit `PlatformArchitectureRules.C2_FIELDS_MUST_HAVE_CONVERTER` chặn mọi trường JPA ánh xạ cột `*_enc` có kiểu khác `byte[]` mà thiếu `@Convert`, và `PlatformArchitectureRules.C2_TYPES_MUST_MASK_TO_STRING` bắt buộc mọi lớp chứa trường C2 phải ghi đè `toString()` để che giá trị nhạy cảm (`[REDACTED:C2]`).
- **Định dạng bản mã và cách tính AAD:**
  - `FieldEncryptor.encryptField(tenantId, purpose, aadParts, plaintext)` trả `EncryptedField(ciphertext, dekId)`. Mảng byte `ciphertext` tuân thủ định dạng nhị phân của TL-03: `version (1B = 0x01) || dek_ref (16B UUID big-endian) || nonce (12B CSPRNG) || ciphertext || tag (16B GCM)`.
  - Dữ liệu xác thực bổ sung (AAD) ghép chuỗi UTF-8 theo mẫu `tenant_id|schema.bảng.cột|id dòng` (tương ứng `tenantId` nối với các phần tử `aadParts` bằng ký tự `|`). Giải mã kiểm tra khớp `dek_ref` với `tenantId` và `purpose` kỳ vọng; sai lệch AAD, đổi tenant, đổi cột, đổi dòng hoặc sửa 1 bit bất kỳ trong mảng bản mã đều ném `CryptoException` (không bao giờ trả `null` hay bản rõ sai). Khi dùng `AbstractC2AttributeConverter`, lớp adapter đặt ngữ cảnh cột và khoá chính dòng qua `EncryptionContextHolder.withContext(...)`.
- **Quản lý DEK và bộ đệm trong heap:**
  - `DekPurpose` bám đúng Ràng buộc `CHECK` của bảng `keyring.dek` (TL-03): `NHAN_XET_DANH_GIA`, `KHIEU_NAI`, `GOP_Y_KIEM_DIEM`, `DINH_DANH_CA_NHAN`, `TEP_MINH_CHUNG`, `BLIND_INDEX`.
  - `DekService` tra cứu `DekCache` (LRU tối đa 10.000 mục, TTL 15 phút trong heap, tự ghi đè mảng byte khoá về `0` khi hết hạn hoặc trục xuất). Khi trượt bộ đệm, `DekService` đọc `keyring.dek` dưới ngữ cảnh RLS `app.tenant_id`, gọi `OpenBaoTransitClient` (`/v1/transit/decrypt/kek-<tenant>`) để mở bọc; nếu tháng hiện tại chưa có DEK `ACTIVE` thì sinh khoá 256 bit mới, bọc qua `/v1/transit/encrypt/kek-<tenant>` và chèn `ON CONFLICT (tenant_id, purpose, valid_month) DO NOTHING` để hai pod đua nhau hội tụ về duy nhất một dòng.
  - DEK ở trạng thái `DECRYPT_ONLY` hoặc `RETIRED` vẫn cho phép giải mã dữ liệu lịch sử nhưng không dùng để mã hoá mới; DEK ở trạng thái `DESTROYED` (xoá bằng mật mã) ném `DekDestroyedException`.
- **Tra cứu bằng chỉ mục mù (`_bidx`):**
  - `BlindIndexer.compute(tenantId, columnName, rawValue)` chuẩn hoá `rawValue` theo thứ tự: chuẩn hoá Unicode `NFC` → cắt khoảng trắng đầu/cuối và gom khoảng trắng liên tiếp thành một dấu cách → đổi chữ thường `Locale.ROOT` → lấy khoá gốc mục đích `BLIND_INDEX` của tenant → dẫn xuất khoá con riêng cho từng `columnName` bằng HMAC-SHA256 → tính HMAC-SHA256 và cắt 16 byte đầu để lưu vào cột `*_bidx` (`BYTEA`) và so khớp bằng toán tử `=`.
- **Những điều tuyệt đối không được làm với trường C2:**
  1. Không lưu bản rõ C2 hoặc khoá DEK vào Valkey/Redis hoặc bộ đệm phân tán ngoài heap của pod.
  2. Không đưa bản rõ C2 vào payload sự kiện Kafka/outbox (chỉ truyền ID bản ghi hoặc tham chiếu).
  3. Không lập chỉ mục toàn văn (OpenSearch) hay đồng bộ sang kho phân tích (ClickHouse) đối với nội dung C2.
  4. Không ghi bản rõ C2, bản mã thô chưa che hoặc khoá DEK vào nhật ký vận hành, nhật ký kiểm toán hay thông điệp ngoại lệ.

## Xoay vòng khoá dữ liệu (DL-14)

Ba loại xoay vòng của TL-01 (ADR-17, ADR-30, SEC-12) là ba lệnh của lớp `KeyRotationMain` trong `backend/libs/key-rotation`, chạy cho mọi CSDL có schema `keyring` bằng mẫu `deploy/charts/mission-core/templates/key-rotation.yaml`. Lệnh `thang` chạy 00:05 ngày 1 hằng tháng: sinh trước DEK tháng mới qua `DekService` cho các mục đích tenant đang dùng (trừ `BLIND_INDEX`) và chuyển DEK `ACTIVE` của tháng trước sang `DECRYPT_ONLY`; chạy lại không sinh thêm. Lệnh `boc-lai` chạy hằng ngày: nhóm DEK theo `kek_ref`, so phiên bản trong tiền tố `vault:v<n>:` với `latest_version` của KEK, gọi `transit/rewrap` theo lô cho DEK cũ rồi cập nhật `wrapped_dek` và `kek_ref` (`...@v<n>`) với điều kiện khớp bản bọc cũ, nên hai lần chạy chồng nhau không ghi đè nhau và dữ liệu không bị giải mã. Lệnh `ma-hoa-lai` chạy theo phiếu khi nghi lộ DEK, cần người đề nghị và người duyệt khác nhau: tìm mọi cột `BYTEA` tên `*_enc` hoặc có chú thích `cls` C2 trong bảng có `dek_id` và `tenant_id`, xử lý theo lô `FOR UPDATE SKIP LOCKED`, mã hoá lại bằng DEK `ACTIVE` với cùng AAD, tăng `row_version`, xong thì DEK cũ sang `RETIRED`; dừng giữa chừng vẫn đọc được mọi dòng và chạy lại tiếp từ chỗ dừng.

Mỗi lần chạy ghi sự kiện `sys.audit.v1` và đẩy `vt_key_age_days{loai="dek"}`, `vt_key_rotation_items_total`, `vt_key_rotation_remaining`; cảnh báo `VtDekQuaHan` theo OPS-20 khi DEK `ACTIVE` quá hạn tháng 7 ngày. Chỉ sau khi `boc-lai` không còn DEK cũ và đọc lại mẫu đạt mới đặt `min_decryption_version` của KEK. Các bước vận hành và quay lui ở `ops/runbooks/xoay-vong-khoa-du-lieu.md`; giới hạn đã chốt (không thay DEK trong cùng tháng, không mã hoá lại `BLIND_INDEX`) ở trang 14.

## Mã hoá đầu cuối C3: máy chủ chỉ giữ bản mã

Với C3, máy chủ không được đọc nội dung kể cả khi bị xâm nhập hoàn toàn (ADR-27, SEC-25). Trình duyệt người gửi sinh khoá nội dung AES-256-GCM, mã hoá nội dung, rồi bọc khoá nội dung cho từng người nhận theo ECDH-ES (khoá P-256 theo thiết bị, HKDF-SHA256, AES-KW) và cho khoá phục hồi của tổ chức. Phía máy chủ chỉ làm ba việc: kiểm quyền theo ABAC, ghi siêu dữ liệu và sổ cái, lưu bản mã cùng các khoá đã bọc (`task.task_secret_content`, `task.e2ee_key_wrap`, `product.e2ee_key_wrap`). Máy chủ chỉ lưu khoá công khai của thiết bị trong danh bạ khoá có ký và ghi sổ cái; khoá riêng không bao giờ rời thiết bị. Khoá phục hồi của tổ chức chia Shamir 3 trên 5 cho năm cán bộ, các mảnh giao trực tiếp theo biên bản, không đi qua máy chủ.

Hệ quả cho mã máy chủ: không tìm toàn văn, không đưa vào OpenSearch, ClickHouse hay ai-gateway, không OCR và không quét mã độc được trên nội dung C3; tra cứu chỉ qua chỉ mục mù trên vài trường mô tả do người gửi tính. APISIX và WAF không kiểm nội dung tuyến C3 (vì là bản mã), nên các tuyến này giới hạn chặt kích thước và tốc độ. Cell SaaS và đám mây riêng không xử lý bí mật nhà nước; thông tin đó chỉ xử lý ở bản cài cách ly, nơi `CryptoProvider` được thay bằng bộ mật mã chuyên dùng của Ban Cơ yếu (SEC-46).

## Bí mật, khoá và mật mã được phép

Mọi bí mật nằm trong OpenBao (ADR-17, SEC-10), không bao giờ trong Git, mã, cấu hình hay wiki; gitleaks chặn ở pre-commit, pipeline và quét định kỳ (SEC-09). Mỗi cell có một cụm OpenBao 5 node tự mở khoá bằng HSM, xác thực pod bằng phương thức Kubernetes. Bí mật tĩnh đến ứng dụng qua External Secrets Operator dưới dạng Secret Kubernetes; Transit và secret động gọi trực tiếp qua thư viện nền. Secret động cho PostgreSQL: mỗi pod nhận một người dùng CSDL riêng `NOBYPASSRLS` sống 24 giờ, được gia hạn bởi ứng dụng và thu hồi khi pod chết; PgBouncer dùng `auth_query` và gom pool theo CSDL để không nhân số kết nối theo số người dùng động. Mẫu lệnh của TL-04 cấp người dùng động vào vai trò nhóm `svc_mission`, khác bộ vai trò `svc_<schema>` mỗi module của TL-01 và TL-03 (trang 14). Chu kỳ xoay vòng theo TL-04: mật khẩu CSDL động 24 giờ, chứng thư ztunnel 24 giờ, chứng thư nội bộ cert-manager 90 ngày, khoá ký JWT của Keycloak 90 ngày, khoá cosign hằng năm.

Danh sách thuật toán được phép (SEC-20): AES-256-GCM, ChaCha20-Poly1305; ECDSA P-256, P-384, RSA từ 2048 bit (khoá mới từ năm 2027 dùng từ 3072), Ed25519; SHA-256, SHA-384, SHA-3; Argon2id, PBKDF2-HMAC-SHA256 từ 600.000 vòng cho mật khẩu; TLS 1.3, TLS 1.2 chỉ với ECDHE-AEAD. Cấm DES, 3DES, RC4, MD5, SHA-1 cho chữ ký, `PBEWithMD5AndDES` và mọi `TrustManager` chấp nhận tất cả chứng thư (sửa S09); Semgrep có quy tắc chặn các mẫu này.

## Nhật ký: che dữ liệu và tách kiểm toán

Log ứng dụng ghi ra đầu ra chuẩn dạng JSON theo ECS (Log4j2 `JsonTemplateLayout`, ghi bất đồng bộ), kèm `trace.id`, `span.id`, `tenant.id`; không ghi tệp trong container. Ba lớp bảo đảm log không chứa dữ liệu nhạy cảm (SEC-07, SEC-53). Lớp một ở mã: kiểu dữ liệu có trường C2, C3 ghi đè `toString()` để che, cấm ghi log đối tượng request, header `Authorization` và cookie. Lớp hai ở Log4j2: một `RewritePolicy` che các mẫu nhận diện được (chuỗi JWT, số định danh 12 chữ số, số điện thoại, email). Lớp ba ở OpenTelemetry Collector: bộ xử lý `redaction` và `transform` áp lại cùng các mẫu cho mọi nguồn, kể cả thành phần bên thứ ba. Một job hằng tuần quét mẫu log đã lưu, phát hiện là lỗi phải sửa ở nguồn.

Nhật ký kiểm toán tách hẳn khỏi log vận hành. Sự kiện kiểm toán gồm đăng nhập, đăng xuất, đổi quyền, truy cập C2 và C3, xuất dữ liệu, thao tác quản trị và mọi thao tác ở mốc pháp lý; chúng được phát qua outbox lên `sys.audit.v1`, ghi vào chỉ mục kiểm toán OpenSearch chỉ đọc với mọi vai trò trừ consumer ghi, và sự kiện quan trọng được nối vào sổ cái. Keycloak gửi sự kiện xác thực tới `iam.auth.event.v1`, cũng đưa vào kiểm toán. Thời hạn lưu lệch: TL-01 ghi 5 năm (chuyển lưu trữ lạnh sau 1 năm), TL-04 ghi 10 năm qua consumer `audit-archiver` ghi Parquet có ký vào bucket Object Lock, khớp NFR-09 (lưu nhật ký kiểm toán và sổ cái ít nhất 10 năm); đề xuất theo NFR-09 (trang 14).

## Nhật ký và che dữ liệu trong thư viện nền (NEN-016)

### Định dạng và nơi ghi

- Log4j2 với `JsonTemplateLayout` theo ECS (`EcsLayout.json`), ghi ra đầu ra chuẩn, không ghi tệp trong container. Cấu hình nằm trong `log4j2-spring.xml` của starter, Spring Boot tự nạp.
- Mỗi dòng có `@timestamp`, `log.level`, `log.logger`, `message`, `service.name` (lấy từ biến môi trường `OTEL_SERVICE_NAME`), `event.dataset` (`mission.app` hoặc `mission.audit`), và khi có ngữ cảnh: `trace.id`, `span.id`, `tenant.id`.
- `trace.id` của nhật ký, `traceId` của phản hồi lỗi RFC 9457 và trace trong Tempo là một giá trị: lấy từ span OpenTelemetry hiện hành, không có span thì lấy từ header `traceparent`, không có nữa thì sinh mới.
- `tenant.id` chỉ lấy từ nguồn đã kiểm (bean `TenantIdProvider`; NEN-010 cung cấp hiện thực đọc từ token). Starter mặc định không ghi tenant, để không bao giờ ghi một giá trị do client tự khai (suy luận).
- Ghi bất đồng bộ (AsyncLogger, LMAX Disruptor). Hàng đợi đầy thì bỏ dòng từ INFO trở xuống, giữ WARN và ERROR, và đếm số dòng bị bỏ ở metric `log4j2.events.discarded`; cần cảnh báo khi metric này tăng.
- Service phải loại `spring-boot-starter-logging` (Logback) khỏi các starter khác của Spring Boot; starter đã kéo `spring-boot-starter-log4j2`. Có cả hai thì Spring Boot báo xung đột SLF4J lúc khởi động.

### Che dữ liệu (hai lớp)

1. **Lớp một, không ghi ngay từ đầu.** Không ghi thân yêu cầu, header `Authorization`, cookie, dữ liệu C2/C3. Starter tắt các logger ghi chi tiết HTTP (`CommonsRequestLoggingFilter`, wire log của Apache HttpClient, `jdk.internal.httpclient.debug`) và đặt `spring.mvc.log-request-details=false`. Luật ArchUnit `PlatformArchitectureRules.NO_REQUEST_LOGGING_FILTERS` cấm dùng bộ lọc ghi yêu cầu của Spring trong mã.
2. **Lớp hai, che khi lỡ ghi.** Mọi dòng đi qua RewritePolicy `<MissionRedaction/>` (lớp `RedactingRewritePolicy`) trước khi ra appender. Policy che thông điệp, giá trị MDC và thông điệp của ngoại lệ đính kèm (cả chuỗi nguyên nhân, giữ tên lớp và dấu vết ngăn xếp gốc). Các mẫu bị che:

| Mẫu | Thay bằng |
|---|---|
| Chuỗi JWT (`eyJ….….…`) | `[REDACTED:JWT]` |
| Token sau `Bearer` / `Basic` | `[REDACTED:TOKEN]` |
| Giá trị header `Cookie` / `Set-Cookie` | `[REDACTED:TOKEN]` |
| Số định danh 12 chữ số đứng riêng (CCCD) | `[REDACTED:ID]` |
| Số điện thoại Việt Nam (`0`, `84`, `+84` rồi 9 chữ số) | `[REDACTED:PHONE]` |
| Địa chỉ email | `[REDACTED:EMAIL]` |

Lớp hai chỉ là lưới an toàn: mẫu không nhận diện được (họ tên, địa chỉ, nội dung đánh giá) vẫn lọt nếu lớp một bị vi phạm. Số 12 chữ số chỉ bị che khi không liền chữ, số hay dấu gạch, để mã vết hex và UUID không bị che nhầm. `Redactor` dùng được trực tiếp cho `toString()` của kiểu mang dữ liệu C2.

### Bẫy kỹ thuật

- Plugin Log4j2 tự viết (`@Plugin`) chỉ được phát hiện nhờ bộ xử lý annotation của `log4j-core`, sinh ra tệp `META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat` trong jar. Thiếu tệp này thì `<MissionRedaction/>` bị bỏ qua, nhật ký không được che mà chỉ có cảnh báo lúc khởi động. Kiểm tra lại khi đổi cấu hình trình biên dịch.
- Kiểm thử bắt đầu ra bằng `OutputCaptureExtension` cần `follow="true"` trên appender Console. Vì ghi bất đồng bộ, kiểm thử phải chờ dòng xuất hiện (Awaitility), không đọc ngay sau lời gọi.
- MDC và ngữ cảnh OpenTelemetry không tự đi theo sang luồng khác. `ResilientClient` mang cả hai sang luồng ảo chạy lời gọi ra ngoài; mã tự dùng executor phải tự làm việc này.
- Giới hạn thời gian của câu lệnh SQL dùng timeout phía JDBC (`jakarta.persistence.query.timeout` 5 s) và timeout giao dịch 5 s, không dùng `SET statement_timeout` theo phiên, để chạy được qua PgBouncer chế độ transaction (suy luận).

### Nhật ký kiểm toán

- Kênh riêng, không đi qua logger vận hành: đăng nhập quản trị, đổi quyền, truy cập dữ liệu C2/C3, xuất dữ liệu, thao tác quản trị, thao tác ở mốc pháp lý. Mã nghiệp vụ gọi `AuditLogger.record(AuditEvent)`. Sự kiện truy cập dữ liệu nhạy cảm bắt buộc ghi mức C2 hoặc C3, và sự kiện chỉ mang định danh đối tượng, không mang dữ liệu dạng rõ.
- `DefaultAuditLogger` điền mã sự kiện UUIDv7, thời điểm UTC và mã vết, che thuộc tính khớp mẫu nhạy cảm, rồi chuyển sự kiện cho `AuditSink`.
- Đích đến chuẩn là outbox lên topic `sys.audit.v1` (NEN-011 khai báo bean `AuditSink`). Trước khi có NEN-011, starter dùng `LoggingAuditSink`: mỗi sự kiện là một dòng JSON trên logger `OKRKPI_AUDIT`, ra appender riêng với `event.dataset=mission.audit`. Kênh tạm này không bền như outbox và starter cảnh báo lúc khởi động. Tên các loại sự kiện (`AuditEventType`) là suy luận, chờ lược đồ Avro của NEN-007.

### Nhật ký truy cập C2 (CORE-00-05)

Mỗi lần dữ liệu C2 được giải mã cho người xem, hệ thống để lại vết không sửa được (BR-12-10, BR-16-04). `FieldEncryptor` có điểm móc `DecryptListener` gọi sau mỗi lần giải mã thành công; khi service có cả `FieldEncryptor` và `OutboxWriter`, `C2AccessAutoConfiguration` gắn `C2AccessAuditor` vào điểm móc đó. Bộ ghi vết nhận siêu dữ liệu (tenant, mục đích DEK, `schema.bảng.cột`, mã dòng), không bao giờ nhận bản rõ hay bản mã, và gom các lần giải mã của một giao dịch thành sự kiện `sys.audit.v1` với `action = XEM_C2`, danh sách `targets`, phân công và vai trò đang chọn của người xem (BR-00-08), cờ `system` cho tác vụ hệ thống. Sự kiện phát qua outbox ngay trước khi giao dịch commit, chia lô 100 đối tượng mỗi sự kiện (`okrkpi.audit.c2.max-targets-per-event`).

Ba trường hợp giao dịch được xử lý khác nhau. Giao dịch ghi thì dòng outbox nằm trong chính giao dịch đọc. Giao dịch chỉ đọc thì PostgreSQL từ chối INSERT, nên vết được ghi bằng giao dịch riêng (`REQUIRES_NEW`) ngay trước commit (suy luận). Không có giao dịch thì vết được ghi ngay bằng giao dịch riêng trước khi trả bản rõ. Ghi vết thất bại thì `decrypt` hoặc commit ném `C2AccessAuditException`, và dữ liệu C2 không đến tay người dùng. Use case trả tệp C2 không đi qua `FieldEncryptor` (mở minh chứng, tải biểu mẫu) gắn `@C2Access(objectType = "MINH_CHUNG")` để ghi cùng loại sự kiện.

Người xem lấy từ JWT: `sub` là mã người dùng, phân công và vai trò đang chọn đọc từ claim `active_assignment_id` và `active_role` (suy luận, TL chưa chốt tên claim); không có JWT người dùng thì coi là tác vụ hệ thống. Cờ "của chính mình" chỉ đặt khi mã đối tượng trùng mã người xem, còn lại để trống, vì điểm móc giải mã không biết chủ sở hữu dòng. Chi phí thêm đo trong kiểm thử tích hợp là khoảng 0,08 ms p95 mỗi lần giải mã. Để `sys.audit.v1` (ba đoạn) đi qua outbox, migration `V0_24` nới `ck_outbox_event__topic` thành 3 đến 5 đoạn kết thúc bằng `v<n>` (trang 14).

### Chịu lỗi và cổng quản trị (liên quan an toàn vận hành)

- Cấu hình Resilience4j chép nguyên văn TL-01, nạp với độ ưu tiên thấp nhất. Kiểm thử `Resilience4jDefaultsTest` đối chiếu từng khoá của registry với khối YAML trong TL-01. Lời gọi ra ngoài đi qua `ResilientClientFactory`/`ResilientClient`. Phụ thuộc không dùng được thì trả 503 kèm `Retry-After`, nội dung phản hồi không lộ tên phụ thuộc.
- Actuator chạy ở cổng quản trị riêng (`MANAGEMENT_PORT`, mặc định 8081) và chỉ mở `health`, `info`, `prometheus`. Cổng này không được đưa ra Ingress công khai (suy luận).
- Luật ArchUnit `PlatformArchitectureRules.NO_UNBOUNDED_EXECUTORS` cấm `Executors.newCachedThreadPool` và `Executors.newFixedThreadPool`. Service gắn luật này vào kiểm thử kiến trúc của mình.

## S01 đến S05: chống tái diễn bằng cơ chế

TL-01 vá năm lỗ hổng nghiêm trọng của bản đánh giá ở đợt 0a và chặn chúng tái xuất hiện bằng cơ chế chứ không bằng kỷ luật. S01 (bí mật trong mã) được chặn bằng OpenBao, ESO, tài khoản CSDL động, gitleaks và Trivy. S02 (tài khoản lấy token không cần mật khẩu) được chặn vì chỉ Keycloak cấp token, không có hồ sơ build bỏ qua xác thực, DAST thử đăng nhập không mật khẩu. S03 (đường dẫn chứa `/public` bỏ qua JWT) được chặn bằng mặc định từ chối, danh sách công khai khớp chính xác ở một chỗ, và kiểm thử hợp đồng khẳng định mọi route không công khai trả 401 khi không có token. S04 (mật khẩu rõ trong cookie) được chặn vì ứng dụng không bao giờ nhận mật khẩu và ArchUnit cấm tham chiếu `Cookie` ngoài thư viện nền. S05 (SQL injection qua `ORDER BY` ghép chuỗi) được chặn bằng `Sort` chỉ nhận từ ánh xạ API sang cột, ArchUnit và Semgrep cấm ghép chuỗi vào `createQuery`, `createNativeQuery`, `JdbcClient.sql`, cộng tài khoản CSDL tối thiểu quyền và RLS.

## Bảng kiểm khi viết mã máy chủ

Trước khi mở PR cho mã máy chủ, người viết tự kiểm các điểm sau (tổng hợp từ các mục trên và `docs/quy-dinh/BACKEND.md` mục 3; cách gom thành bảng kiểm là suy luận).

| Hạng mục | Điều phải đúng |
|---|---|
| Xác thực | Endpoint mới mặc định yêu cầu xác thực; tenant lấy từ claim của token đã xác thực, không từ header hay tham số do client gửi |
| Step-up | Thao tác nhạy cảm kiểm `acr` và `auth_time`, thiếu thì trả 401 kèm `insufficient_user_authentication` |
| Uỷ quyền | Use case có `@RequiresPermission`; danh sách áp điều kiện từ đánh giá từng phần của OPA; không tự đặt danh sách vai trò |
| Ngữ cảnh tenant | Mọi truy cập CSDL trong giao dịch tường minh; không `SET` mức phiên, không `set_config(..., false)` |
| SQL | Chỉ tham số ràng buộc; sắp xếp động chỉ qua danh sách trắng cột; không ghép chuỗi vào câu truy vấn |
| Dữ liệu C2 | Cột `_enc` kèm `dek_id`, mã hoá bằng `FieldEncryptor` trước khi rời ứng dụng; tra cứu bằng `_bidx`; không cache dạng rõ, không đưa vào sự kiện, OpenSearch hay ClickHouse |
| Dữ liệu C3 | Máy chủ chỉ nhận và trả bản mã cùng khoá đã bọc; không xử lý nội dung |
| Nhật ký | Không ghi token, request thô, C2, C3; kiểu có trường nhạy cảm che `toString()`; thao tác cần kiểm toán phát `sys.audit.v1` qua outbox |
| Bí mật | Không có bí mật trong mã, cấu hình, test hay tài liệu; lấy qua OpenBao hoặc ESO |
| Mật mã | Chỉ thuật toán trong danh sách SEC-20; không `TrustManager` chấp nhận tất cả |
| Lỗi | Trả RFC 9457, không lộ stack trace hay câu SQL |

## Liên quan

- [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
- [Phân quyền](15-phan-quyen.md)
- [Ma trận phân quyền](15a-ma-tran-phan-quyen.md)
- [Thiết kế CSDL và đa khách thuê](17-thiet-ke-csdl-va-da-khach-thue.md)
- [Sự kiện và đồng bộ dữ liệu](18-su-kien-va-dong-bo-du-lieu.md)
- [Hạ tầng production và triển khai](19-ha-tang-production-va-trien-khai.md)
- [Kiến trúc mission-core và microservice](21-kien-truc-mission-core-va-microservice.md)
- `docs/quy-dinh/BACKEND.md`
- [Quy ước API](06-quy-uoc-api.md)
