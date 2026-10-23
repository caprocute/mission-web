> Nguồn: TL-04 mục 9.8 "Kiểm thử bảo mật: ASVS mức 2 và OWASP ZAP" (NFR-08, SEC-40, SEC-41); OWASP ASVS 4.0.3 bản JSON chính thức; task QA-13; các tệp kiểm thử và cấu hình nêu ở cột "Bằng chứng".

# Tự đánh giá OWASP ASVS 4.0.3

Tài liệu này là bảng tự đánh giá ASVS 4.0.3 của nền tảng Mission theo NFR-08: mức 2 cho toàn hệ thống và mức 3 cho ba chương V2 (xác thực), V3 (quản lý phiên) và V6 (mật mã lưu trữ). Mỗi yêu cầu có trạng thái, bằng chứng (mã ở bảng "Bằng chứng" bên dưới) và ghi chú. Bảng là đầu vào cho kiểm thử xâm nhập độc lập (SEC-41): kiểm thử viên đối chiếu từng mục, đặc biệt các mục "Có thiết kế, chờ kiểm", "Đạt một phần" và "Chưa đạt". Cột yêu cầu giữ nguyên văn tiếng Anh của ASVS (rút gọn) để đối chiếu đúng với danh mục kiểm tra của OWASP.

Ngày đánh giá: 23/10/2026. Phạm vi kiểm chứng tự động: org-service và Keycloak 26.4 với realm dev, chạy cục bộ; web shell qua nginx của SVC-17. Bản chạy trên server dev dùng chung và staging sẽ cập nhật cột trạng thái khi có kết quả.

## Thống kê

Trong 286 mục của ASVS 4.0.3, có 241 mục áp dụng cho phạm vi NFR-08 (sau khi bỏ 8 mục ASVS đã xoá, 10 mục mức 3 ngoài V2, V3, V6 và 27 mục không áp dụng). Số mục đạt (tự động hoặc cấu hình có kiểm) là 196/241, tức 81%. Tiêu chí của TL-04 là 100% mục áp dụng; các mục còn lại được liệt kê ở mục "Việc còn lại" và là phạm vi trọng tâm của SEC-41.

| Trạng thái | Số mục | Ý nghĩa |
|---|---:|---|
| Đạt (tự động) | 145 | Có kiểm thử tự động chạy trong pipeline hoặc bộ kiểm thử của QA-13 |
| Đạt (cấu hình có kiểm) | 51 | Đạt bằng cấu hình có kiểm tĩnh hoặc do nền tảng (Keycloak, nginx, Angular) bảo đảm theo cấu hình đã duyệt |
| Có thiết kế, chờ kiểm | 32 | Đã thiết kế hoặc đã làm, chưa có kiểm thử tự động; cần kiểm thủ công hoặc ở SEC-41 |
| Đạt một phần | 10 | Đạt một phần, ghi chú nêu phần thiếu |
| Chưa đạt | 3 | Chưa đạt, ghi chú nêu đề xuất |
| Không áp dụng | 27 | Không áp dụng cho kiến trúc của Mission |
| Ngoài phạm vi | 10 | Mục mức 3 ngoài V2, V3, V6 (NFR-08 không yêu cầu) |
| ASVS đã xoá | 8 | ASVS 4.0.3 đánh dấu DELETED |

Theo chương (chỉ tính mục áp dụng):

| Chương | Áp dụng | Đạt | Tỷ lệ | Mức yêu cầu |
|---|---:|---:|---:|---|
| V1 Architecture, Design and Threat Modeling | 37 | 31 | 83% | Mức 2 |
| V2 Authentication | 46 | 35 | 76% | Mức 3 |
| V3 Session Management | 20 | 17 | 85% | Mức 3 |
| V4 Access Control | 8 | 8 | 100% | Mức 2 |
| V5 Validation, Sanitization and Encoding | 25 | 22 | 88% | Mức 2 |
| V6 Stored Cryptography | 14 | 13 | 92% | Mức 3 |
| V7 Error Handling and Logging | 12 | 11 | 91% | Mức 2 |
| V8 Data Protection | 15 | 10 | 66% | Mức 2 |
| V9 Communication | 7 | 2 | 28% | Mức 2 |
| V10 Malicious Code | 4 | 3 | 75% | Mức 2 |
| V11 Business Logic | 8 | 7 | 87% | Mức 2 |
| V12 Files and Resources | 15 | 11 | 73% | Mức 2 |
| V13 API and Web Service | 8 | 7 | 87% | Mức 2 |
| V14 Configuration | 22 | 19 | 86% | Mức 2 |

## Việc còn lại

Các mục dưới đây chưa đạt hoặc chỉ đạt một phần; mỗi mục cần một task sửa hoặc một quyết định chấp nhận rủi ro của người phụ trách ATTT. Mục "Có thiết kế, chờ kiểm" không liệt kê lại ở đây mà nằm trong bảng chi tiết.

- **1.1.2** (Đạt một phần): Mô hình mối đe doạ có ở TL-04 (bảng SEC, D1–D12); chưa có bước bắt buộc mô hình hoá mối đe doạ cho từng thay đổi thiết kế.
- **1.1.3** (Đạt một phần): Task có tiêu chí nghiệm thu bảo mật ở mục "Kiểm thử"; chưa thành mẫu bắt buộc cho mọi câu chuyện người dùng.
- **2.1.7** (Chưa đạt): Chưa có kiểm mật khẩu lộ (danh sách mật khẩu yếu hoặc HIBP k-anonymity) trong passwordPolicy. Đề xuất: passwordBlacklist với danh sách 10.000 mật khẩu phổ biến.
- **2.1.8** (Chưa đạt): Trang đăng nhập theme mission chưa có thước đo độ mạnh mật khẩu khi đặt hoặc đổi.
- **2.1.10** (Đạt một phần): Không bắt xoay vòng định kỳ (không forceExpiredPasswordChange), nhưng có passwordHistory(5) mà ASVS 2.1.10 khuyên không dùng; giữ theo quy định nội bộ, cần người phụ trách ATTT xác nhận.
- **2.4.5** (Đạt một phần): Chưa có pepper (khoá bí mật bổ sung ngoài CSDL) cho băm mật khẩu Keycloak.
- **3.4.4** (Đạt một phần): Cookie Keycloak không dùng tiền tố __Host- (giới hạn của Keycloak 26); bù bằng miền đăng nhập riêng.
- **6.2.8** (Đạt một phần): So sánh HMAC dùng MessageDigest.isEqual; chưa rà toàn bộ phép so sánh bí mật.
- **8.3.6** (Đạt một phần): Khoá C3 xoá khỏi bộ nhớ sau dùng ở trình duyệt; JVM không bảo đảm ghi đè chuỗi.
- **12.3.4** (Đạt một phần): Tải xuống dùng attachment; chế độ xem trực tiếp (inline) vẫn cho phép với kiểu tệp đã kiểm, cần SEC-41 xác minh RFD.
- **13.1.5** (Đạt một phần): Content-Type sai: 415 (đạt). Thân multipart hỏng ở /org/imports trả 500 thay vì 400 (ca todo trong PD4).
- **14.2.3** (Đạt một phần): Tài nguyên cùng nguồn; UI Pack trên CDN kiểm chữ ký thay cho SRI.
- **14.4.2** (Chưa đạt): Phản hồi API chưa có Content-Disposition: attachment (org-service). Đề xuất thêm ở APISIX response-rewrite cho /api/.

## Bằng chứng

| Mã | Tệp | Nội dung |
|---|---|---|
| PD1 | `infra/security/kiem-thu-phu-dinh/test/01-xac-thuc.test.mjs` | Token: thiếu, rác, alg none, khoá lạ, nhầm HS256, sửa thân, exp giả, issuer lạ, token thật hết hạn |
| PD2 | `infra/security/kiem-thu-phu-dinh/test/02-da-khach-thue.test.mjs` | Chéo tenant (IDOR) 404, X-Tenant-Id tự đặt, sửa chéo, cán bộ không có quyền quản trị |
| PD3 | `infra/security/kiem-thu-phu-dinh/test/03-ghi-du-lieu.test.mjs` | Thiếu, cũ, sai If-Match; thiếu Idempotency-Key; dùng lại khoá với thân khác; khoá gắn người gọi |
| PD4 | `infra/security/kiem-thu-phu-dinh/test/04-dau-vao.test.mjs` | limit ngoài [1,200], mã không phải UUID, chèn SQL, con trỏ giả, ../, JSON hỏng, gán hàng loạt, 415, HPP, tệp tải lên |
| PD5 | `infra/security/kiem-thu-phu-dinh/test/05-tieu-de-va-loi.test.mjs` | no-store, nosniff, không lộ phiên bản, 404/405/406, TRACE, CORS, 401 đồng nhất |
| PD6 | `infra/security/kiem-thu-phu-dinh/test/06-luong-oidc.test.mjs` | Direct grant, implicit, thiếu PKCE, PKCE plain, redirect_uri lạ, code giả |
| ZAPA | `infra/security/zap/okr-api.yaml` | ZAP quét chủ động API theo hợp đồng, có đăng nhập |
| ZAPW | `infra/security/zap/web-baseline.yaml` | ZAP quét thụ động trang web; CSP và chống nhúng khung nâng High |
| RTS | `backend/services/iam/realm-template/src/test/java/vn/viettel/okrkpi/iam/realm/RealmTemplateStaticTest.java` | Kiểm tĩnh mẫu realm cell |
| RTI | `backend/services/iam/realm-template/src/test/java/vn/viettel/okrkpi/iam/realm/RealmTemplateKeycloakIT.java` | Keycloak 26 thật với mẫu realm: PKCE, ES256, OTP, AAL2, khoá dò, Argon2, refresh xoay vòng, mã khôi phục |
| VNE | `backend/services/iam/realm-template/src/test/java/vn/viettel/okrkpi/iam/realm/VneidBrokerKeycloakIT.java` | Môi giới VNeID: PKCE, nonce, chữ ký id_token, khớp người |
| KCE | `backend/services/iam/keycloak-events/src/test/java/vn/viettel/okrkpi/iam/events/ (MfaPolicyTest, RecoveryCodesTest)` | Chính sách MFA, mã khôi phục |
| MEV | `backend/services/iam/keycloak-events/src/test/java/vn/viettel/okrkpi/iam/events/MissionEventsKeycloakIT.java` | Sự kiện đăng nhập, đổi thông tin xác thực |
| REV | `backend/services/iam/revocation-consumer/src/test/java/vn/viettel/okrkpi/iam/revocation/RevocationListenerIT.java` | Thu hồi phiên |
| OPA | `backend/libs/vt-platform-starter/src/test/java/vn/viettel/okrkpi/shared/authz/ (OpaAuthzIT, StepUpAndPermissionTest); workflow-service DualApprovalTest, OpaAssigneeSeparationPolicyTest` | Phân quyền OPA, step-up, bốn mắt, tách vai trò |
| TEN | `backend/**/*TenantIsolationIT.java, mission-core ApiTenantLeakIT, libs/vt-tenant-test` | RLS và chống rò dữ liệu chéo tenant |
| API | `backend/libs/vt-platform-starter/src/test/java/vn/viettel/okrkpi/ (ApiConventionsIT, ProblemHandlingWebTest, ProblemErrorControllerTest, ETagsTest, IdempotencySupportTest, CursorCodecTest)` | Quy ước API baseline mục 13 |
| ENC | `backend/libs/vt-platform-starter/src/test/java/vn/viettel/okrkpi/shared/crypto/ (FieldEncryptionTest, FieldEncryptionIT, JcaCryptoProviderContractTest); libs/key-rotation KeyRotationIT` | Mã hoá cột C2, xoay khoá |
| E2E | `backend/mission-core/src/test/java/vn/viettel/okrkpi/ (KeyWrapTest, EvidenceKeyWrapServiceTest, SecretContentBoundaryTest); org-service E2eeDirectoryApiIT` | Mã hoá đầu cuối C3 |
| HSM | `backend/services/esign-service HsmPkcs11SignerTest; org-service OpenBaoDirectorySignerIT` | Khoá trong HSM, OpenBao Transit |
| LOG | `backend/libs/vt-platform-starter RedactionTest; integration-gateway RecordingMaskerTest, BanGhiKhongCoDuLieuCaNhanTest` | Che dữ liệu nhạy cảm trong nhật ký |
| AUD | `backend/libs/vt-platform-starter AuditLoggerTest; mission-core C2AccessAuditIT` | Kiểm toán |
| FIL | `backend/services/evidence-service (ContentInspectorTest, ClamdScannerTest, FileUploadFlowIT, FileAccessGuardAuthzTest, S3ObjectLockIT); org-service OrgImportApiIT` | Tệp tải lên |
| XML | `backend/services/integration-gateway EdxmlParserTest` | Bộ đọc XML an toàn |
| SIG | `backend/services/integration-gateway CloudEventSigningTest, CloudEventsMtlsSenderTest` | Chữ ký sự kiện, mTLS |
| SEM | `.semgrep/vt-rules.yml (vt-sql-string-concat, vt-log-request-body, vt-log-secret-token, vt-crypto-weak-cipher, vt-crypto-weak-hash, vt-tls-trust-all); libs/vt-tenant-test SemgrepTenantRuleTest` | SAST chặn merge (SEC-39) |
| GLK | `.gitleaks.toml; ci/vt-pipeline vtBuoc.quetBiMat` | Quét bí mật (SEC-09) |
| TRV | `ci/vt-pipeline vtBuoc.quetTrivyFs, SBOM` | SCA, cấu hình sai (SEC-23) |
| PIPE | `ci/vt-pipeline (vtJavaPipeline, vtBuoc, vtZapScan); backend/Jenkinsfile` | Cổng pipeline, ký ảnh, SLSA |
| ARC | `backend/**/ArchitectureRulesTest.java` | ArchUnit |
| K8S | `deploy/policies/vt-pod-hardening.yaml; deploy/charts/*/templates (NetworkPolicy); Istio ambient` | Cô lập và cứng hoá triển khai |
| GW | `deploy/charts/api-gateway (openid-connect, limit-count, limit-req, Coraza)` | Cổng API |
| BAO | `deploy/openbao; External Secrets` | Quản lý bí mật |
| NGX | `deploy/web/nginx.conf, security-headers.conf, csp-mac-dinh.conf` | Tiêu đề bảo mật trang web |
| FE | `frontend/projects/auth/src/lib/*.spec.ts; frontend/eslint.config.js` | Token trong bộ nhớ, đăng xuất, cấm innerHTML, bypassSecurityTrust |
| TL | `Bộ tài liệu thiết kế TL-01..TL-04, wiki 08, 15, 20` | Thiết kế, chưa có kiểm thử tự động riêng |

Bộ kiểm thử phủ định (PD1–PD6) và hai kế hoạch ZAP (ZAPA, ZAPW) chạy với bất kỳ môi trường nào qua biến môi trường; cách chạy ở `infra/security/kiem-thu-phu-dinh/README.md` và `infra/security/zap/README.md`.

## Gói bằng chứng cho kiểm thử xâm nhập độc lập (SEC-41)

Mục này là phần giao cho đơn vị kiểm thử xâm nhập độc lập theo TL-04 mục 9.8: kiểm thử trước khi vận hành chính thức, sau mỗi thay đổi lớn về xác thực hoặc phân quyền và ít nhất hằng năm, báo cáo theo từng mục ASVS. Tiêu chí đạt là 0 phát hiện Critical hoặc High chưa khắc phục tại thời điểm vận hành.

**Phạm vi trong.** Trang web (shell SVC-17 và các remote), cổng API APISIX (SVC-01) với mọi tuyến `/api/v1/*` theo hợp đồng `contracts/openapi/modules/*.yaml`, Keycloak realm `mission` (SVC-02) gồm luồng mission-browser, OTP, AAL2, mã khôi phục, môi giới VNeID giả lập, SCIM của org-service (`/scim/v2`). Cổng nội bộ `/internal/v1/*` chỉ kiểm từ trong mesh với token dịch vụ.

**Phạm vi ngoài.** Đo tải, tấn công từ chối dịch vụ và vét cạn mật khẩu quá ngưỡng khoá tài khoản (YC-PT-19: giai đoạn này chỉ phát triển); hệ thống bên ngoài (VNeID thật, LGSP, iOffice, CA) vì môi trường dùng giả lập (YC-PT-13); hạ tầng vật lý và Kubernetes control plane (thuộc kiểm thử hạ tầng riêng).

**Tài khoản thử theo vai trò.** Tài khoản có sẵn trong realm dev (`infra/dev/keycloak/realm-mission.json`); mật khẩu và bí mật OTP không ghi ở đây mà bàn giao qua kênh an toàn của dự án (biến KC_TEST_USER_PASSWORD, KC_TEST_OTP_SECRET). Kiểm thử chéo tenant cần thêm một tài khoản quản trị thuộc tenant thứ hai (bộ kiểm thử dùng biến KT_QUAN_TRI_KHAC).

| Tài khoản | Tác nhân | Vai trò realm | Cần OTP |
|---|---|---|---|
| nentang | AC-01 | platform_admin, wf_template_author | Có |
| quantri, quantri2 | AC-02 | tenant_admin, policy_author | Có |
| chinhsach | AC-03 | policy_author | Không |
| lanhdao | AC-04 | member, leader | Không |
| quanly | AC-05 | member, manager | Không |
| canbo | AC-06 | member | Không |
| thamdinh | AC-07 | member, appraiser | Có |
| quyetdinh | AC-08 | decider | Có |
| duyet2 | AC-09 | second_approver | Có |
| kiemtra | AC-10 | member, inspector | Không |

**Tài liệu và công cụ giao kèm.** Bảng tự đánh giá này; ma trận phân quyền `wiki/15a-ma-tran-phan-quyen.md`; hợp đồng OpenAPI đã gộp (`cd contracts && npm run bundle`, thư mục `contracts/dist`); bộ kiểm thử phủ định `infra/security/kiem-thu-phu-dinh` (kiểm thử viên chạy lại được với tài khoản của mình); kế hoạch ZAP `infra/security/zap` và báo cáo SARIF, JSON của lần quét gần nhất (artifact `zap-ket-qua/` của pipeline); danh sách ngoại lệ đang hiệu lực `infra/security/zap/ngoai-le.yaml`.

**Phát hiện đã biết khi bàn giao.** Thân multipart hỏng ở `POST /api/v1/org/imports` làm org-service trả 500 thay vì 400 (ZAP 30001, ca todo trong PD4); phản hồi API chưa có `Content-Disposition: attachment` (14.4.2); chưa kiểm mật khẩu lộ (2.1.7). Các mục "Có thiết kế, chờ kiểm" trong bảng là trọng tâm đề nghị kiểm thử viên xác minh thủ công.

**Quy tắc làm việc.** Chỉ kiểm trên môi trường dev hoặc staging được chỉ định; không dùng dữ liệu thật; báo ngay phát hiện Critical cho người phụ trách ATTT; mỗi phát hiện ghi mã mục ASVS và bằng chứng tái hiện; kiểm thử lại (retest) sau khi sửa theo TL-04.

## Bảng chi tiết

### V1 Architecture, Design and Threat Modeling

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 1.1.1 | 2 | Verify the use of a secure software development lifecycle that addresses security in all stages of development. | Đạt (cấu hình có kiểm) | PIPE, TL | Quy trình ở AGENTS.md, TL-04 mục 9.6 (MR hai người duyệt, cổng chất lượng). |
| 1.1.2 | 2 | Verify the use of threat modeling for every design change or sprint planning to identify threats, plan for countermeasures, facilitate appropriate ri… | Đạt một phần | TL | Mô hình mối đe doạ có ở TL-04 (bảng SEC, D1–D12); chưa có bước bắt buộc mô hình hoá mối đe doạ cho từng thay đổi thiết kế. |
| 1.1.3 | 2 | Verify that all user stories and features contain functional security constraints, such as "As a user, I should be able to view and edit my profile.… | Đạt một phần | TL | Task có tiêu chí nghiệm thu bảo mật ở mục "Kiểm thử"; chưa thành mẫu bắt buộc cho mọi câu chuyện người dùng. |
| 1.1.4 | 2 | Verify documentation and justification of all the application's trust boundaries, components, and significant data flows. | Đạt (cấu hình có kiểm) | TL | Ranh giới tin cậy: TL-01 (APISIX, mesh, cell), TL-04 mục 9 (vùng, NetworkPolicy). |
| 1.1.5 | 2 | Verify definition and security analysis of the application's high-level architecture and all connected remote services. | Đạt (cấu hình có kiểm) | TL | TL-01 kiến trúc, TL-04 mục 9 phân tích bảo mật từng tầng. |
| 1.1.6 | 2 | Verify implementation of centralized, simple (economy of design), vetted, secure, and reusable security controls to avoid duplicate, missing, ineffec… | Đạt (tự động) | ARC, API | Kiểm soát bảo mật tập trung trong vt-platform-starter (xác thực, OPA, lỗi, idempotency); ArchUnit chặn đi vòng. |
| 1.1.7 | 2 | Verify availability of a secure coding checklist, security requirements, guideline, or policy to all developers and testers. | Đạt (cấu hình có kiểm) | TL | wiki 08 bảng kiểm frontend, wiki 20 bảng kiểm máy chủ, AGENTS.md quy tắc 4–6. |
| 1.2.1 | 2 | Verify the use of unique or special low-privilege operating system accounts for all application components, services, and servers. | Đạt (tự động) | K8S | Kyverno vt-pod-hardening chặn chạy root; mỗi service một vai trò CSDL riêng (mig_*, svc_*, ro_*). |
| 1.2.2 | 2 | Verify that communications between application components, including APIs, middleware and data layers, are authenticated. Components should have the… | Có thiết kế, chờ kiểm | K8S, SIG | mTLS giữa thành phần qua Istio ambient (TL-04 mục 9.2); kiểm thử mTLS có ở CloudEventsMtlsSenderTest; chưa có kiểm thử đầu cuối trên cụm. |
| 1.2.3 | 2 | Verify that the application uses a single vetted authentication mechanism that is known to be secure, can be extended to include strong authenticatio… | Đạt (tự động) | RTI, PD1, PD6 | Một cơ chế: Keycloak OIDC (ADR-08); resource server kiểm JWT. |
| 1.2.4 | 2 | Verify that all authentication pathways and identity management APIs implement consistent authentication security control strength, such that there a… | Đạt (tự động) | RTI, VNE, PD1 | Mọi đường đăng nhập (mật khẩu, OTP, VNeID) qua cùng luồng Keycloak mission-browser. |
| 1.4.1 | 2 | Verify that trusted enforcement points, such as access control gateways, servers, and serverless functions, enforce access controls. Never enforce ac… | Đạt (tự động) | PD2, OPA, TEN | Điểm thực thi ở máy chủ: APISIX kiểm JWT, service kiểm OPA và RLS. |
| 1.4.2 | 3 | [DELETED, NOT ACTIONABLE] | ASVS đã xoá |  |  |
| 1.4.3 | 3 | [DELETED, DUPLICATE OF 4.1.3] | ASVS đã xoá |  |  |
| 1.4.4 | 2 | Verify the application uses a single and well-vetted access control mechanism for accessing protected data and resources. All requests must pass thro… | Đạt (tự động) | OPA | Một cơ chế OPA cùng pod (ADR, wiki 15). |
| 1.4.5 | 2 | Verify that attribute or feature-based access control is used whereby the code checks the user's authorization for a feature/data item rather than ju… | Đạt (tự động) | OPA, TEN | ABAC theo đơn vị, phạm vi, mức dữ liệu (wiki 15, ma trận phân quyền). |
| 1.5.1 | 2 | Verify that input and output requirements clearly define how to handle and process data based on type, content, and applicable laws, regulations, and… | Đạt (cấu hình có kiểm) | TL | Phân lớp C0–C3 và quy tắc xử lý theo lớp (TL-03, wiki 08, wiki 20). |
| 1.5.2 | 2 | Verify that serialization is not used when communicating with untrusted clients. If this is not possible, ensure that adequate integrity controls (an… | Đạt (tự động) | XML, SIG | Client chỉ trao đổi JSON; Avro chỉ giữa service có mTLS; không dùng tuần tự hoá Java. |
| 1.5.3 | 2 | Verify that input validation is enforced on a trusted service layer. | Đạt (tự động) | PD4, API | Kiểm đầu vào ở service theo hợp đồng OpenAPI (Bean Validation sinh từ hợp đồng). |
| 1.5.4 | 2 | Verify that output encoding occurs close to or by the interpreter for which it is intended. | Đạt (cấu hình có kiểm) | FE | Angular mã hoá đầu ra theo ngữ cảnh; ESLint cấm innerHTML và bypassSecurityTrust. |
| 1.6.1 | 2 | Verify that there is an explicit policy for management of cryptographic keys and that a cryptographic key lifecycle follows a key management standard… | Đạt (cấu hình có kiểm) | ENC, BAO | Chính sách khoá ở TL-04 (mã hoá phong bì C2, OpenBao Transit, xoay khoá). |
| 1.6.2 | 2 | Verify that consumers of cryptographic services protect key material and other secrets by using key vaults or API based alternatives. | Đạt (tự động) | ENC, HSM, BAO | Khoá trong OpenBao Transit hoặc HSM PKCS#11; ứng dụng chỉ giữ DEK đã bọc. |
| 1.6.3 | 2 | Verify that all keys and passwords are replaceable and are part of a well-defined process to re-encrypt sensitive data. | Đạt (tự động) | ENC | KeyRotationIT kiểm xoay khoá; bí mật dịch vụ qua External Secrets. |
| 1.6.4 | 2 | Verify that the architecture treats client-side secrets--such as symmetric keys, passwords, or API tokens--as insecure and never uses them to protect… | Đạt (tự động) | FE, GLK | Client không giữ bí mật: mission-web là client công khai PKCE; gitleaks chặn bí mật trong mã. |
| 1.7.1 | 2 | Verify that a common logging format and approach is used across the system. | Đạt (tự động) | LOG | Nhật ký ECS có cấu trúc thống nhất (logging.structured.format ecs). |
| 1.7.2 | 2 | Verify that logs are securely transmitted to a preferably remote system for analysis, detection, alerting, and escalation. | Có thiết kế, chờ kiểm | TL | OTel Collector chuyển về hệ thống quan sát tập trung (TL-04 mục 8); chưa kiểm trên dev. |
| 1.8.1 | 2 | Verify that all sensitive data is identified and classified into protection levels. | Đạt (tự động) | TL | Mọi cột có chú thích phân lớp {"cls": ...}; kiểm bằng *MigrationConventionsIT. |
| 1.8.2 | 2 | Verify that all protection levels have an associated set of protection requirements, such as encryption requirements, integrity requirements, retenti… | Đạt (tự động) | ENC, E2E | C2 mã hoá cột, C3 mã hoá đầu cuối (ADR-06), kiểm ở FieldEncryptionIT, SecretContentBoundaryTest. |
| 1.9.1 | 2 | Verify the application encrypts communications between components, particularly when these components are in different containers, systems, sites, or… | Có thiết kế, chờ kiểm | K8S | Istio ambient mTLS STRICT trong cell; kiểm thử trên cụm thuộc QA hạ tầng. |
| 1.9.2 | 2 | Verify that application components verify the authenticity of each side in a communication link to prevent person-in-the-middle attacks. For example,… | Có thiết kế, chờ kiểm | K8S, SIG | mTLS hai chiều; CloudEventsMtlsSenderTest kiểm phía gửi. |
| 1.10.1 | 2 | Verify that a source code control system is in use, with procedures to ensure that check-ins are accompanied by issues or change tickets. The source… | Đạt (cấu hình có kiểm) | PIPE | Git có MR, hai người duyệt, commit GitOps ký GPG. |
| 1.11.1 | 2 | Verify the definition and documentation of all application components in terms of the business or security functions they provide. | Đạt (cấu hình có kiểm) | TL | TL-02 (QT, SCR, BR) mô tả thành phần và luồng nghiệp vụ. |
| 1.11.2 | 2 | Verify that all high-value business logic flows, including authentication, session management and access control, do not share unsynchronized state. | Đạt (tự động) | API, PD3 | Luồng giá trị cao dùng khoá idempotency, If-Match, khoá kỳ; kiểm ở ApiConventionsIT, PeriodLockedWriteIT, PD3. |
| 1.11.3 | 3 | Verify that all high-value business logic flows, including authentication, session management and access control are thread safe and resistant to tim… | Ngoài phạm vi |  |  |
| 1.12.1 | 3 | [DELETED, DUPLICATE OF 12.4.1] | ASVS đã xoá |  |  |
| 1.12.2 | 2 | Verify that user-uploaded files - if required to be displayed or downloaded from the application - are served by either octet stream downloads, or fr… | Đạt (tự động) | FIL | Tải xuống qua URL ký trước thời hạn ngắn (SEC-08), kiểm quyền ở FileAccessGuardAuthzTest. |
| 1.14.1 | 2 | Verify the segregation of components of differing trust levels through well-defined security controls, firewall rules, API gateways, reverse proxies,… | Đạt (cấu hình có kiểm) | K8S | Namespace và NetworkPolicy theo mức tin cậy (TL-04 mục 9.2). |
| 1.14.2 | 2 | Verify that binary signatures, trusted connections, and verified endpoints are used to deploy binaries to remote devices. | Đạt (tự động) | PIPE, K8S | Ảnh ký cosign, Kyverno kiểm chữ ký khi tạo pod (SEC-37). |
| 1.14.3 | 2 | Verify that the build pipeline warns of out-of-date or insecure components and takes appropriate actions. | Đạt (tự động) | TRV | Trivy, Dependency-Track chặn HIGH và CRITICAL (SEC-23). |
| 1.14.4 | 2 | Verify that the build pipeline contains a build step to automatically build and verify the secure deployment of the application, particularly if the… | Đạt (tự động) | PIPE | Pipeline có gitleaks, Semgrep, Trivy, SpotBugs, ZAP (vtZapScan). |
| 1.14.5 | 2 | Verify that application deployments adequately sandbox, containerize and/or isolate at the network level to delay and deter attackers from attacking… | Đạt (tự động) | K8S | Pod không root, chỉ đọc hệ thống tệp, NetworkPolicy mặc định từ chối. |
| 1.14.6 | 2 | Verify the application does not use unsupported, insecure, or deprecated client-side technologies such as NSAPI plugins, Flash, Shockwave, ActiveX, S… | Không áp dụng |  | Không dùng Flash, ActiveX, NSAPI. |

### V2 Authentication

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 2.1.1 | 1 | Verify that user set passwords are at least 12 characters in length (after multiple spaces are combined). | Đạt (cấu hình có kiểm) | RTS, RTI | Mẫu realm cell: length(12) (matKhauBamArgon2VaChinhSachDoDai). Realm dev chỉ length(8): chỉ dùng cho dev, không triển khai. |
| 2.1.2 | 1 | Verify that passwords of at least 64 characters are permitted, and that passwords of more than 128 characters are denied. | Đạt (cấu hình có kiểm) | RTS | maxLength(128) trong passwordPolicy. |
| 2.1.3 | 1 | Verify that password truncation is not performed. However, consecutive multiple spaces may be replaced by a single space. | Đạt (cấu hình có kiểm) | RTI | Keycloak không cắt mật khẩu; Argon2 nhận toàn bộ chuỗi. |
| 2.1.4 | 1 | Verify that any printable Unicode character, including language neutral characters such as spaces and Emojis are permitted in passwords. | Đạt (cấu hình có kiểm) | RTI | Keycloak nhận mọi ký tự Unicode in được. |
| 2.1.5 | 1 | Verify users can change their password. | Đạt (cấu hình có kiểm) | TL | Đổi mật khẩu qua Keycloak Account Console. |
| 2.1.6 | 1 | Verify that password change functionality requires the user's current and new password. | Đạt (cấu hình có kiểm) | TL | Keycloak hỏi mật khẩu hiện tại khi đổi. |
| 2.1.7 | 1 | Verify that passwords submitted during account registration, login, and password change are checked against a set of breached passwords either locall… | Chưa đạt |  | Chưa có kiểm mật khẩu lộ (danh sách mật khẩu yếu hoặc HIBP k-anonymity) trong passwordPolicy. Đề xuất: passwordBlacklist với danh sách 10.000 mật khẩu phổ biến. |
| 2.1.8 | 1 | Verify that a password strength meter is provided to help users set a stronger password. | Chưa đạt |  | Trang đăng nhập theme mission chưa có thước đo độ mạnh mật khẩu khi đặt hoặc đổi. |
| 2.1.9 | 1 | Verify that there are no password composition rules limiting the type of characters permitted. There should be no requirement for upper or lower case… | Đạt (cấu hình có kiểm) | RTS | Không có quy tắc thành phần (không upperCase, digits, specialChars). |
| 2.1.10 | 1 | Verify that there are no periodic credential rotation or password history requirements. | Đạt một phần | RTS | Không bắt xoay vòng định kỳ (không forceExpiredPasswordChange), nhưng có passwordHistory(5) mà ASVS 2.1.10 khuyên không dùng; giữ theo quy định nội bộ, cần người phụ trách ATTT xác nhận. |
| 2.1.11 | 1 | Verify that "paste" functionality, browser password helpers, and external password managers are permitted. | Có thiết kế, chờ kiểm | TL | Trang đăng nhập không chặn dán hay trình quản lý mật khẩu; chưa có kiểm thử tự động. |
| 2.1.12 | 1 | Verify that the user can choose to either temporarily view the entire masked password, or temporarily view the last typed character of the password o… | Có thiết kế, chờ kiểm | TL | Theme Keycloak 26 có nút hiện mật khẩu; theme mission cần kiểm lại. |
| 2.2.1 | 1 | Verify that anti-automation controls are effective at mitigating breached credential testing, brute force, and account lockout attacks. Such controls… | Đạt (tự động) | RTI | Chống dò: khoá tạm sau 5 lần sai, khoá cố định ở lần 20 (sauLanSaiThuNam..., khoaCoDinhOLanSaiThuHaiMuoi); APISIX limit-req cho tuyến công khai. |
| 2.2.2 | 1 | Verify that the use of weak authenticators (such as SMS and email) is limited to secondary verification and transaction approval and not as a replace… | Không áp dụng |  | Không dùng SMS hay email làm yếu tố xác thực. |
| 2.2.3 | 1 | Verify that secure notifications are sent to users after updates to authentication details, such as credential resets, email or address changes, logg… | Có thiết kế, chờ kiểm | MEV | Sự kiện UPDATE_PASSWORD, UPDATE_TOTP được phát qua mission-events; gửi thông báo cho người dùng thuộc dịch vụ thông báo, chưa kiểm đầu cuối. |
| 2.2.4 | 3 | Verify impersonation resistance against phishing, such as the use of multi-factor authentication, cryptographic devices with intent (such as connecte… | Đạt (tự động) | RTI, KCE | Vai trò nhạy cảm bắt AAL2 (OTP) (vaiTroAal2KhongVaoDuocChiBangMatKhau, MfaPolicyTest); VNeID là IdP mức cao. |
| 2.2.5 | 3 | Verify that where a Credential Service Provider (CSP) and the application verifying authentication are separated, mutually authenticated TLS is in pl… | Đạt (tự động) | VNE | Môi giới VNeID qua TLS, PKCE, nonce, id_token RS256 kiểm chữ ký (VneidBrokerKeycloakIT). |
| 2.2.6 | 3 | Verify replay resistance through the mandated use of One-time Passwords (OTP) devices, cryptographic authenticators, or lookup codes. | Đạt (cấu hình có kiểm) | RTS | otpPolicyCodeReusable: false; bộ kiểm thử phủ định phải chờ chu kỳ mới khi đăng nhập lại (lib/oidc.mjs). |
| 2.2.7 | 3 | Verify intent to authenticate by requiring the entry of an OTP token or user-initiated action such as a button press on a FIDO hardware key. | Đạt (tự động) | RTI | Bước OTP là hành động chủ động của người dùng (thietBiMoiBiHoiOtp, xinAal2TraTokenAal2VaAuthTimeMoi). |
| 2.3.1 | 1 | Verify system generated initial passwords or activation codes SHOULD be securely randomly generated, SHOULD be at least 6 characters long, and MAY co… | Đạt (cấu hình có kiểm) | RTS | Không cấp mật khẩu ban đầu; tài khoản qua SCIM hoặc VNeID; resetPasswordAllowed false. |
| 2.3.2 | 2 | Verify that enrollment and use of user-provided authentication devices are supported, such as a U2F or FIDO tokens. | Đạt (tự động) | RTI | Đăng ký OTP do người dùng tự cài (thietBiMoiBiHoiOtp). |
| 2.3.3 | 2 | Verify that renewal instructions are sent with sufficient time to renew time bound authenticators. | Không áp dụng |  | Không có thiết bị xác thực có thời hạn cần gia hạn. |
| 2.4.1 | 2 | Verify that passwords are stored in a form that is resistant to offline attacks. Passwords SHALL be salted and hashed using an approved one-way key d… | Đạt (tự động) | RTI | Argon2 (hashAlgorithm(argon2)); mật khẩu cũ được băm lại khi đăng nhập (matKhauBamThuatToanCuDuocBamLai...). |
| 2.4.2 | 2 | Verify that the salt is at least 32 bits in length and be chosen arbitrarily to minimize salt value collisions among stored hashes. For each credenti… | Đạt (cấu hình có kiểm) | RTI | Muối ngẫu nhiên 128 bit của Keycloak Argon2. |
| 2.4.3 | 2 | Verify that if PBKDF2 is used, the iteration count SHOULD be as large as verification server performance will allow, typically at least 100,000 itera… | Không áp dụng |  | Không dùng PBKDF2 hay bcrypt. |
| 2.4.4 | 2 | Verify that if bcrypt is used, the work factor SHOULD be as large as verification server performance will allow, with a minimum of 10. | Không áp dụng |  | Không dùng PBKDF2 hay bcrypt. |
| 2.4.5 | 2 | Verify that an additional iteration of a key derivation function is performed, using a salt value that is secret and known only to the verifier. Gene… | Đạt một phần | TL | Chưa có pepper (khoá bí mật bổ sung ngoài CSDL) cho băm mật khẩu Keycloak. |
| 2.5.1 | 1 | Verify that a system generated initial activation or recovery secret is not sent in clear text to the user. | Đạt (cấu hình có kiểm) | RTS | Không gửi bí mật kích hoạt dạng rõ; mã khôi phục chỉ hiện một lần trong phiên AAL2. |
| 2.5.2 | 1 | Verify password hints or knowledge-based authentication (so-called "secret questions") are not present. | Đạt (cấu hình có kiểm) | RTS | Không có câu hỏi bí mật. |
| 2.5.3 | 1 | Verify password credential recovery does not reveal the current password in any way. | Đạt (cấu hình có kiểm) | RTS | Không có luồng quên mật khẩu tiết lộ mật khẩu (resetPasswordAllowed false). |
| 2.5.4 | 1 | Verify shared or default accounts are not present (e.g. "root", "admin", or "sa"). | Đạt (tự động) | RTS, PD6 | Không có tài khoản dùng chung trong mẫu realm (khongCoBiMatTrongMau); tài khoản dev chỉ trên dev. |
| 2.5.5 | 1 | Verify that if an authentication factor is changed or replaced, that the user is notified of this event. | Có thiết kế, chờ kiểm | MEV | Như 2.2.3. |
| 2.5.6 | 1 | Verify forgotten password, and other recovery paths use a secure recovery mechanism, such as time-based OTP (TOTP) or other soft token, mobile push,… | Đạt (tự động) | RTI, KCE | Mã khôi phục dùng một lần, thu hồi được (maKhoiPhucCapMotLanDungMotLanThuHoiVaKiemSoatQuyen, RecoveryCodesTest). |
| 2.5.7 | 2 | Verify that if OTP or multi-factor authentication factors are lost, that evidence of identity proofing is performed at the same level as during enrol… | Có thiết kế, chờ kiểm | TL | Mất OTP: cấp lại qua quản trị tenant có bốn mắt (QT-00); kiểm bằng kiểm thử xâm nhập. |
| 2.6.1 | 2 | Verify that lookup secrets can be used only once. | Đạt (tự động) | RTI, KCE | Mã khôi phục dùng một lần. |
| 2.6.2 | 2 | Verify that lookup secrets have sufficient randomness (112 bits of entropy), or if less than 112 bits of entropy, salted with a unique and random 32-… | Đạt (tự động) | KCE | RecoveryCodesTest kiểm độ dài và nguồn ngẫu nhiên SecureRandom. |
| 2.6.3 | 2 | Verify that lookup secrets are resistant to offline attacks, such as predictable values. | Đạt (tự động) | KCE | Mã khôi phục lưu dạng băm. |
| 2.7.1 | 1 | Verify that clear text out of band (NIST "restricted") authenticators, such as SMS or PSTN, are not offered by default, and stronger alternatives suc… | Không áp dụng |  | Không có xác thực ngoài băng (SMS, email, đẩy). |
| 2.7.2 | 1 | Verify that the out of band verifier expires out of band authentication requests, codes, or tokens after 10 minutes. | Không áp dụng |  | Không có xác thực ngoài băng (SMS, email, đẩy). |
| 2.7.3 | 1 | Verify that the out of band verifier authentication requests, codes, or tokens are only usable once, and only for the original authentication request. | Không áp dụng |  | Không có xác thực ngoài băng (SMS, email, đẩy). |
| 2.7.4 | 1 | Verify that the out of band authenticator and verifier communicates over a secure independent channel. | Không áp dụng |  | Không có xác thực ngoài băng (SMS, email, đẩy). |
| 2.7.5 | 2 | Verify that the out of band verifier retains only a hashed version of the authentication code. | Không áp dụng |  | Không có xác thực ngoài băng (SMS, email, đẩy). |
| 2.7.6 | 2 | Verify that the initial authentication code is generated by a secure random number generator, containing at least 20 bits of entropy (typically a six… | Không áp dụng |  | Không có xác thực ngoài băng (SMS, email, đẩy). |
| 2.8.1 | 1 | Verify that time-based OTPs have a defined lifetime before expiring. | Đạt (cấu hình có kiểm) | RTS | TOTP 30 giây, cửa sổ lệch 1. |
| 2.8.2 | 2 | Verify that symmetric keys used to verify submitted OTPs are highly protected, such as by using a hardware security module or secure operating system… | Có thiết kế, chờ kiểm | TL | Bí mật OTP trong CSDL Keycloak; CSDL mã hoá lưu trữ ở mức đĩa (TL-04). Chưa có HSM cho bí mật OTP. |
| 2.8.3 | 2 | Verify that approved cryptographic algorithms are used in the generation, seeding, and verification of OTPs. | Đạt (cấu hình có kiểm) | RTS | HmacSHA1 theo RFC 6238 (được NIST chấp nhận cho TOTP). |
| 2.8.4 | 2 | Verify that time-based OTP can be used only once within the validity period. | Đạt (cấu hình có kiểm) | RTS | otpPolicyCodeReusable: false. |
| 2.8.5 | 2 | Verify that if a time-based multi-factor OTP token is re-used during the validity period, it is logged and rejected with secure notifications being s… | Có thiết kế, chờ kiểm | MEV | Keycloak ghi sự kiện LOGIN_ERROR khi dùng lại OTP; cảnh báo theo luật bất thường chưa có kiểm thử. |
| 2.8.6 | 2 | Verify physical single-factor OTP generator can be revoked in case of theft or other loss. Ensure that revocation is immediately effective across log… | Đạt (cấu hình có kiểm) | TL | Quản trị gỡ OTP của người dùng qua Keycloak (bốn mắt QT-00). |
| 2.8.7 | 2 | Verify that biometric authenticators are limited to use only as secondary factors in conjunction with either something you have and something you kno… | Không áp dụng |  | Không dùng sinh trắc học. |
| 2.9.1 | 2 | Verify that cryptographic keys used in verification are stored securely and protected against disclosure, such as using a Trusted Platform Module (TP… | Đạt (tự động) | HSM, BAO | Khoá ký của Keycloak và dịch vụ ký số trong HSM hoặc OpenBao. |
| 2.9.2 | 2 | Verify that the challenge nonce is at least 64 bits in length, and statistically unique or unique over the lifetime of the cryptographic device. | Đạt (tự động) | VNE | nonce OIDC ngẫu nhiên ≥ 128 bit; VNeID IT kiểm nonce sai bị từ chối. |
| 2.9.3 | 2 | Verify that approved cryptographic algorithms are used in the generation, seeding, and verification. | Đạt (tự động) | RTI | Token ES256 (dangNhapCodePkceS256ThanhCongTokenEs256DungThoiHan). |
| 2.10.1 | 2 | Verify that intra-service secrets do not rely on unchanging credentials such as passwords, API keys or shared accounts with privileged access. | Đạt (tự động) | RTS, BAO | Service dùng client_credentials với secret từ OpenBao, token ngắn hạn; mTLS mesh. |
| 2.10.2 | 2 | Verify that if passwords are required for service authentication, the service account used is not a default credential. (e.g. root/root or admin/admi… | Đạt (tự động) | TEN | Tài khoản CSDL riêng từng service, NOBYPASSRLS, không dùng tài khoản mặc định. |
| 2.10.3 | 2 | Verify that passwords are stored with sufficient protection to prevent offline recovery attacks, including local system access. | Đạt (cấu hình có kiểm) | BAO | Mật khẩu dịch vụ trong OpenBao, External Secrets đồng bộ; không nằm trong mã. |
| 2.10.4 | 2 | Verify passwords, integrations with databases and third-party systems, seeds and internal secrets, and API keys are managed securely and not included… | Đạt (tự động) | GLK, RTS | gitleaks chặn; mẫu realm kiểm không có bí mật (khongCoBiMatTrongMau). |

### V3 Session Management

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 3.1.1 | 1 | Verify the application never reveals session tokens in URL parameters. | Đạt (tự động) | FE, ZAPA | Token không nằm trong URL (SEC-08); ZAP kiểm token trong URL. |
| 3.2.1 | 1 | Verify the application generates a new session token on user authentication. | Đạt (tự động) | RTI | Mỗi lần đăng nhập Keycloak cấp phiên và token mới. |
| 3.2.2 | 1 | Verify that session tokens possess at least 64 bits of entropy. | Đạt (cấu hình có kiểm) | RTI | Mã phiên Keycloak ≥ 128 bit; token ký ES256. |
| 3.2.3 | 1 | Verify the application only stores session tokens in the browser using secure methods such as appropriately secured cookies (see section 3.4) or HTML… | Đạt (tự động) | FE | Token chỉ trong bộ nhớ (token-storage.spec.ts); refresh qua cookie HttpOnly của Keycloak. |
| 3.2.4 | 2 | Verify that session tokens are generated using approved cryptographic algorithms. | Đạt (cấu hình có kiểm) | RTI | Keycloak sinh mã bằng SecureRandom. |
| 3.3.1 | 1 | Verify that logout and expiration invalidate the session token, such that the back button or a downstream relying party does not resume an authentica… | Đạt (tự động) | FE, REV | Đăng xuất gọi end_session của Keycloak; thu hồi phiên phát sự kiện tới revocation-consumer (RevocationListenerIT). |
| 3.3.2 | 1 | If authenticators permit users to remain logged in, verify that re-authentication occurs periodically both when actively used or after an idle period. | Đạt (cấu hình có kiểm) | RTS | ssoSessionIdleTimeout, ssoSessionMaxLifespan theo chart (30 phút nghỉ, 10 giờ tối đa); rememberMe false. |
| 3.3.3 | 2 | Verify that the application gives the option to terminate all other active sessions after a successful password change (including change via password… | Có thiết kế, chờ kiểm | TL | Keycloak Account Console cho đăng xuất mọi phiên; chưa có kiểm thử tự động sau đổi mật khẩu. |
| 3.3.4 | 2 | Verify that users are able to view and (having re-entered login credentials) log out of any or all currently active sessions and devices. | Đạt (cấu hình có kiểm) | TL | Keycloak Account Console liệt kê và đăng xuất phiên. |
| 3.4.1 | 1 | Verify that cookie-based session tokens have the 'Secure' attribute set. | Đạt (cấu hình có kiểm) | RTI | Cookie phiên của Keycloak có Secure, HttpOnly, SameSite; ứng dụng không dùng cookie phiên riêng. |
| 3.4.2 | 1 | Verify that cookie-based session tokens have the 'HttpOnly' attribute set. | Đạt (cấu hình có kiểm) | RTI | Cookie phiên của Keycloak có Secure, HttpOnly, SameSite; ứng dụng không dùng cookie phiên riêng. |
| 3.4.3 | 1 | Verify that cookie-based session tokens utilize the 'SameSite' attribute to limit exposure to cross-site request forgery attacks. | Đạt (cấu hình có kiểm) | RTI | Cookie phiên của Keycloak có Secure, HttpOnly, SameSite; ứng dụng không dùng cookie phiên riêng. |
| 3.4.4 | 1 | Verify that cookie-based session tokens use the "__Host-" prefix so cookies are only sent to the host that initially set the cookie. | Đạt một phần |  | Cookie Keycloak không dùng tiền tố __Host- (giới hạn của Keycloak 26); bù bằng miền đăng nhập riêng. |
| 3.4.5 | 1 | Verify that if the application is published under a domain name with other applications that set or use session cookies that might disclose the sessi… | Đạt (cấu hình có kiểm) | TL | Keycloak đặt path /realms/<realm>/; miền đăng nhập tách khỏi ứng dụng. |
| 3.5.1 | 2 | Verify the application allows users to revoke OAuth tokens that form trust relationships with linked applications. | Đạt (cấu hình có kiểm) | TL | Người dùng thu hồi ứng dụng đã cấp quyền trong Account Console; offline token tắt cho client web. |
| 3.5.2 | 2 | Verify the application uses session tokens rather than static API secrets and keys, except with legacy implementations. | Đạt (tự động) | PD1 | API chỉ nhận token phiên ngắn hạn, không có khoá API tĩnh. |
| 3.5.3 | 2 | Verify that stateless session tokens use digital signatures, encryption, and other countermeasures to protect against tampering, enveloping, replay,… | Đạt (tự động) | PD1, RTI | JWT ký ES256; bộ phủ định kiểm alg none, khoá lạ, nhầm HS256, sửa thân, exp, iss; refresh xoay vòng, dùng lại bị từ chối (refreshTokenXoayVongDungLaiBiTuChoi). |
| 3.6.1 | 3 | Verify that Relying Parties (RPs) specify the maximum authentication time to Credential Service Providers (CSPs) and that CSPs re-authenticate the us… | Có thiết kế, chờ kiểm | VNE | Mission là RP với VNeID; môi giới chưa gửi max_age tới VNeID, thời điểm xác thực do Keycloak quản lý (step-up AAL2). |
| 3.6.2 | 3 | Verify that Credential Service Providers (CSPs) inform Relying Parties (RPs) of the last authentication event, to allow RPs to determine if they need… | Đạt (tự động) | RTI | Keycloak trả auth_time và acr; xinAal2TraTokenAal2VaAuthTimeMoi. |
| 3.7.1 | 1 | Verify the application ensures a full, valid login session or requires re-authentication or secondary verification before allowing any sensitive tran… | Đạt (tự động) | OPA, RTI | Thao tác nhạy cảm bắt step-up AAL2 trong 5 phút (StepUpAndPermissionTest, MyContextService). |

### V4 Access Control

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 4.1.1 | 1 | Verify that the application enforces access control rules on a trusted service layer, especially if client-side access control is present and could b… | Đạt (tự động) | PD2, OPA | Kiểm ở máy chủ; trình duyệt chỉ ẩn hiện (AGENTS.md quy tắc 6). |
| 4.1.2 | 1 | Verify that all user and data attributes and policy information used by access controls cannot be manipulated by end users unless specifically author… | Đạt (tự động) | PD1, PD2 | Thuộc tính từ token đã ký; sửa claim hoặc gửi X-Tenant-Id tự đặt đều bị từ chối. |
| 4.1.3 | 1 | Verify that the principle of least privilege exists - users should only be able to access functions, data files, URLs, controllers, services, and oth… | Đạt (tự động) | PD2, OPA | Ma trận phân quyền tối thiểu; cán bộ không sửa đơn vị, không nhập cây tổ chức. |
| 4.1.4 | 3 | [DELETED, DUPLICATE OF 4.1.3] | ASVS đã xoá |  |  |
| 4.1.5 | 1 | Verify that access controls fail securely including when an exception occurs. | Đạt (tự động) | OPA | OpaAuthzIT: OPA lỗi thì từ chối. |
| 4.2.1 | 1 | Verify that sensitive data and APIs are protected against Insecure Direct Object Reference (IDOR) attacks targeting creation, reading, updating and d… | Đạt (tự động) | PD2, TEN | IDOR chéo tenant trả 404; *TenantIsolationIT, ApiTenantLeakIT kiểm RLS. |
| 4.2.2 | 1 | Verify that the application or framework enforces a strong anti-CSRF mechanism to protect authenticated functionality, and effective anti-automation… | Không áp dụng | PD5 | API dùng Bearer token, không dùng cookie nên không chịu CSRF (SEC-06); refresh cookie SameSite=Strict. |
| 4.3.1 | 1 | Verify administrative interfaces use appropriate multi-factor authentication to prevent unauthorized use. | Đạt (tự động) | RTI | Quản trị bắt OTP (vai trò tenant_admin, platform_admin trong luồng mission-browser). |
| 4.3.2 | 1 | Verify that directory browsing is disabled unless deliberately desired. Additionally, applications should not allow discovery or disclosure of file o… | Đạt (cấu hình có kiểm) | NGX | nginx không bật autoindex; không có .git hay tệp sao lưu trong ảnh. |
| 4.3.3 | 2 | Verify the application has additional authorization (such as step up or adaptive authentication) for lower value systems, and / or segregation of dut… | Đạt (tự động) | OPA, RTI | Step-up AAL2 cho thao tác giá trị cao; bốn mắt cho phê duyệt (DualApprovalTest). |

### V5 Validation, Sanitization and Encoding

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 5.1.1 | 1 | Verify that the application has defenses against HTTP parameter pollution attacks, particularly if the application framework makes no distinction abo… | Đạt (tự động) | PD4 | Tham số lặp không vượt được giới hạn (ca HTTP parameter pollution). |
| 5.1.2 | 1 | Verify that frameworks protect against mass parameter assignment attacks, or that the application has countermeasures to protect against unsafe param… | Đạt (tự động) | PD4 | DTO sinh từ hợp đồng, không gán tenantId hay rowVersion từ thân yêu cầu. |
| 5.1.3 | 1 | Verify that all input (HTML form fields, REST requests, URL parameters, HTTP headers, cookies, batch files, RSS feeds, etc) is validated using positi… | Đạt (tự động) | PD4, API | Kiểm kiểu, phạm vi, độ dài theo hợp đồng. |
| 5.1.4 | 1 | Verify that structured data is strongly typed and validated against a defined schema including allowed characters, length and pattern (e.g. credit ca… | Đạt (tự động) | PD4, API | JSON theo lược đồ OpenAPI; sai kiểu trả 400 hoặc 422. |
| 5.1.5 | 1 | Verify that URL redirects and forwards only allow destinations which appear on an allow list, or show a warning when redirecting to potentially untru… | Đạt (tự động) | PD6 | redirect_uri lạ bị Keycloak từ chối; ứng dụng không có chuyển hướng mở. |
| 5.2.1 | 1 | Verify that all untrusted HTML input from WYSIWYG editors or similar is properly sanitized with an HTML sanitizer library or framework feature. | Đạt (tự động) | FE | Không nhận HTML từ người dùng; Angular làm sạch; ESLint cấm innerHTML. |
| 5.2.2 | 1 | Verify that unstructured data is sanitized to enforce safety measures such as allowed characters and length. | Đạt (tự động) | PD4 | Chuỗi tìm kiếm có ký tự đặc biệt không gây lỗi máy chủ. |
| 5.2.3 | 1 | Verify that the application sanitizes user input before passing to mail systems to protect against SMTP or IMAP injection. | Có thiết kế, chờ kiểm | TL | Thư gửi qua notification-service dùng mẫu cố định; chưa có kiểm thử chèn SMTP. |
| 5.2.4 | 1 | Verify that the application avoids the use of eval() or other dynamic code execution features. Where there is no alternative, any user input being in… | Đạt (tự động) | FE, SEM | CSP không cho unsafe-eval; ESLint, Semgrep cấm eval và new Function; CEL dùng cel-js không sinh mã. |
| 5.2.5 | 1 | Verify that the application protects against template injection attacks by ensuring that any user input being included is sanitized or sandboxed. | Đạt (tự động) | FE | Biểu mẫu động (Formly, JSON Schema) và SDUI chỉ dựng component trong registry. |
| 5.2.6 | 1 | Verify that the application protects against SSRF attacks, by validating or sanitizing untrusted data or HTTP file metadata, such as filenames and UR… | Có thiết kế, chờ kiểm | TL | Gọi ra ngoài chỉ qua integration-gateway và cell-egress theo danh sách cho phép (TL-04); chưa có ca SSRF tự động. |
| 5.2.7 | 1 | Verify that the application sanitizes, disables, or sandboxes user-supplied Scalable Vector Graphics (SVG) scriptable content, especially as they rel… | Có thiết kế, chờ kiểm | FIL | ContentInspector nhận ảnh theo chữ ký đầu tệp; chưa có ca kiểm thử riêng cho SVG chứa mã kịch bản. |
| 5.2.8 | 1 | Verify that the application sanitizes, disables, or sandboxes user-supplied scriptable or expression template language content, such as Markdown, CSS… | Đạt (tự động) | FE | Biểu thức CEL chạy trong cel-js có giới hạn, không có truy cập ngoài. |
| 5.3.1 | 1 | Verify that output encoding is relevant for the interpreter and context required. For example, use encoders specifically for HTML values, HTML attrib… | Đạt (tự động) | FE | Angular mã hoá theo ngữ cảnh. |
| 5.3.2 | 1 | Verify that output encoding preserves the user's chosen character set and locale, such that any Unicode character point is valid and safely handled. | Đạt (cấu hình có kiểm) | FE | UTF-8 xuyên suốt; nội dung tiếng Việt có dấu. |
| 5.3.3 | 1 | Verify that context-aware, preferably automated - or at worst, manual - output escaping protects against reflected, stored, and DOM based XSS. | Đạt (tự động) | FE, ZAPA | XSS phản xạ trong quét chủ động ZAP (40012). |
| 5.3.4 | 1 | Verify that data selection or database queries (e.g. SQL, HQL, ORM, NoSQL) use parameterized queries, ORMs, entity frameworks, or are otherwise prote… | Đạt (tự động) | SEM, PD4, ZAPA | Semgrep vt-sql-string-concat chặn nối chuỗi SQL; ZAP 40018, 40022. |
| 5.3.5 | 1 | Verify that where parameterized or safer mechanisms are not present, context-specific output encoding is used to protect against injection attacks, s… | Không áp dụng |  | Luôn có truy vấn tham số hoá. |
| 5.3.6 | 1 | Verify that the application protects against JSON injection attacks, JSON eval attacks, and JavaScript expression evaluation. | Đạt (tự động) | FE | JSON.parse, không eval JSON; tiền tố JSONP không dùng. |
| 5.3.7 | 1 | Verify that the application protects against LDAP injection vulnerabilities, or that specific security controls to prevent LDAP injection have been i… | Không áp dụng |  | Không có truy vấn LDAP từ ứng dụng. |
| 5.3.8 | 1 | Verify that the application protects against OS command injection and that operating system calls use parameterized OS queries or use contextual comm… | Đạt (tự động) | ZAPA | Không gọi lệnh hệ điều hành từ dữ liệu người dùng; ZAP 90020. |
| 5.3.9 | 1 | Verify that the application protects against Local File Inclusion (LFI) or Remote File Inclusion (RFI) attacks. | Đạt (tự động) | PD4, ZAPA | Đường dẫn ../ mã hoá bị chặn; ZAP 6, 7. |
| 5.3.10 | 1 | Verify that the application protects against XPath injection or XML injection attacks. | Đạt (tự động) | XML, PD4 | EdxmlParserTest kiểm XXE; API không nhận XML (415). |
| 5.4.1 | 2 | Verify that the application uses memory-safe string, safer memory copy and pointer arithmetic to detect or prevent stack, buffer, or heap overflows. | Không áp dụng |  | Mã quản lý bộ nhớ (Java, TypeScript); không dùng mã không quản lý. |
| 5.4.2 | 2 | Verify that format strings do not take potentially hostile input, and are constant. | Không áp dụng |  | Mã quản lý bộ nhớ (Java, TypeScript); không dùng mã không quản lý. |
| 5.4.3 | 2 | Verify that sign, range, and input validation techniques are used to prevent integer overflows. | Không áp dụng |  | Mã quản lý bộ nhớ (Java, TypeScript); không dùng mã không quản lý. |
| 5.5.1 | 1 | Verify that serialized objects use integrity checks or are encrypted to prevent hostile object creation or data tampering. | Đạt (tự động) | SIG | CloudEvents có chữ ký và mTLS (CloudEventSigningTest). |
| 5.5.2 | 1 | Verify that the application correctly restricts XML parsers to only use the most restrictive configuration possible and to ensure that unsafe feature… | Đạt (tự động) | XML, PD4 | Bộ đọc XML tắt DTD và thực thể ngoài. |
| 5.5.3 | 1 | Verify that deserialization of untrusted data is avoided or is protected in both custom code and third-party libraries (such as JSON, XML and YAML pa… | Đạt (tự động) | XML | Không giải tuần tự Java; Avro theo lược đồ đăng ký. |
| 5.5.4 | 1 | Verify that when parsing JSON in browsers or JavaScript-based backends, JSON.parse is used to parse the JSON document. Do not use eval() to parse JSO… | Đạt (tự động) | FE | JSON.parse ở trình duyệt. |

### V6 Stored Cryptography

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 6.1.1 | 2 | Verify that regulated private data is stored encrypted while at rest, such as Personally Identifiable Information (PII), sensitive personal informati… | Đạt (tự động) | ENC | C2 (số định danh, liên hệ) mã hoá phong bì AES-256-GCM theo cột (FieldEncryptionIT). |
| 6.1.2 | 2 | Verify that regulated health data is stored encrypted while at rest, such as medical records, medical device details, or de-anonymized research recor… | Không áp dụng |  | Không lưu dữ liệu sức khoẻ. |
| 6.1.3 | 2 | Verify that regulated financial data is stored encrypted while at rest, such as financial accounts, defaults or credit history, tax records, pay hist… | Không áp dụng |  | Không lưu dữ liệu tài chính thanh toán. |
| 6.2.1 | 1 | Verify that all cryptographic modules fail securely, and errors are handled in a way that does not enable Padding Oracle attacks. | Đạt (tự động) | ENC | Lỗi giải mã không lộ khoá hay dữ liệu rõ (JcaCryptoProviderContractTest). |
| 6.2.2 | 2 | Verify that industry proven or government approved cryptographic algorithms, modes, and libraries are used, instead of custom coded cryptography. | Đạt (tự động) | ENC, SEM | AES-GCM, ECDSA P-256, SHA-256; Semgrep chặn thuật toán yếu (vt-crypto-weak-cipher, vt-crypto-weak-hash). |
| 6.2.3 | 2 | Verify that encryption initialization vector, cipher configuration, and block modes are configured securely using the latest advice. | Đạt (tự động) | ENC | IV 96 bit ngẫu nhiên mỗi lần mã hoá. |
| 6.2.4 | 2 | Verify that random number, encryption or hashing algorithms, key lengths, rounds, ciphers or modes, can be reconfigured, upgraded, or swapped at any… | Đạt (tự động) | ENC | Nhà cung cấp mật mã thay được (CryptoProviderContract: JCA, BCY). |
| 6.2.5 | 2 | Verify that known insecure block modes (i.e. ECB, etc.), padding modes (i.e. PKCS#1 v1.5, etc.), ciphers with small block sizes (i.e. Triple-DES, Blo… | Đạt (tự động) | SEM | Semgrep chặn ECB, PKCS#1 v1.5, MD5, SHA-1 cho mục đích bảo mật. |
| 6.2.6 | 2 | Verify that nonces, initialization vectors, and other single use numbers must not be used more than once with a given encryption key. The method of g… | Đạt (cấu hình có kiểm) | ENC | IV 96 bit ngẫu nhiên mỗi lần mã hoá theo thiết kế; FieldEncryptionTest kiểm vector cố định và từ chối khi sửa bản mã, chưa có ca riêng về không lặp IV. |
| 6.2.7 | 3 | Verify that encrypted data is authenticated via signatures, authenticated cipher modes, or HMAC to ensure that ciphertext is not altered by an unauth… | Đạt (tự động) | ENC, E2E | Mã hoá có xác thực (GCM); C3 dùng AEAD. |
| 6.2.8 | 3 | Verify that all cryptographic operations are constant-time, with no 'short-circuit' operations in comparisons, calculations, or returns, to avoid lea… | Đạt một phần | ENC | So sánh HMAC dùng MessageDigest.isEqual; chưa rà toàn bộ phép so sánh bí mật. |
| 6.3.1 | 2 | Verify that all random numbers, random file names, random GUIDs, and random strings are generated using the cryptographic module's approved cryptogra… | Đạt (cấu hình có kiểm) | KCE | Bí mật sinh bằng SecureRandom (RecoveryCodesTest kiểm entropy); chưa có quy tắc Semgrep cấm java.util.Random. |
| 6.3.2 | 2 | Verify that random GUIDs are created using the GUID v4 algorithm, and a Cryptographically-secure Pseudo-random Number Generator (CSPRNG). GUIDs creat… | Đạt (cấu hình có kiểm) | TL | Định danh dùng UUIDv7 (không phải bí mật); mã bí mật dùng SecureRandom. |
| 6.3.3 | 3 | Verify that random numbers are created with proper entropy even when the application is under heavy load, or that the application degrades gracefully… | Đạt (cấu hình có kiểm) | TL | SecureRandom của JDK lấy entropy từ hệ điều hành, không chặn khi tải cao. |
| 6.4.1 | 2 | Verify that a secrets management solution such as a key vault is used to securely create, store, control access to and destroy secrets. | Đạt (tự động) | BAO | OpenBao, External Secrets; không có bí mật trong repo (gitleaks). |
| 6.4.2 | 2 | Verify that key material is not exposed to the application but instead uses an isolated security module like a vault for cryptographic operations. | Đạt (tự động) | HSM, BAO | Khoá ký và KEK trong HSM hoặc OpenBao Transit, ứng dụng không thấy khoá. |

### V7 Error Handling and Logging

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 7.1.1 | 1 | Verify that the application does not log credentials or payment details. Session tokens should only be stored in logs in an irreversible, hashed form. | Đạt (tự động) | LOG, SEM | Semgrep vt-log-secret-token, vt-log-request-body; RedactionTest che token. |
| 7.1.2 | 1 | Verify that the application does not log other sensitive data as defined under local privacy laws or relevant security policy. | Đạt (tự động) | LOG | Che C2 trong nhật ký (RecordingMaskerTest, BanGhiKhongCoDuLieuCaNhanTest). |
| 7.1.3 | 2 | Verify that the application logs security relevant events including successful and failed authentication events, access control failures, deserializa… | Đạt (tự động) | MEV, AUD | Sự kiện xác thực thành công và thất bại (MissionEventsKeycloakIT), kiểm toán (AuditLoggerTest). |
| 7.1.4 | 2 | Verify that each log event includes necessary information that would allow for a detailed investigation of the timeline when an event happens. | Đạt (tự động) | AUD | Bản ghi có traceId, tenant, người, thao tác, kết quả. |
| 7.2.1 | 2 | Verify that all authentication decisions are logged, without storing sensitive session tokens or passwords. This should include requests with relevan… | Đạt (tự động) | MEV | Mọi quyết định xác thực ghi qua mission-events, không ghi token. |
| 7.2.2 | 2 | Verify that all access control decisions can be logged and all failed decisions are logged. This should include requests with relevant metadata neede… | Đạt (tự động) | AUD | Quyết định truy cập C2 ghi kiểm toán (C2AccessAuditIT). |
| 7.3.1 | 2 | Verify that all logging components appropriately encode data to prevent log injection. | Đạt (tự động) | LOG | Nhật ký JSON ECS mã hoá ký tự đặc biệt (chống chèn dòng). |
| 7.3.2 | 3 | [DELETED, DUPLICATE OF 7.3.1] | ASVS đã xoá |  |  |
| 7.3.3 | 2 | Verify that security logs are protected from unauthorized access and modification. | Có thiết kế, chờ kiểm | TL | Sổ cái kiểm toán băm chuỗi và niêm phong (LedgerSealServiceTest); quyền đọc nhật ký ở hệ quan sát chưa kiểm. |
| 7.3.4 | 2 | Verify that time sources are synchronized to the correct time and time zone. Strongly consider logging only in UTC if systems are global to assist wi… | Đạt (cấu hình có kiểm) | TL | NTP trên node; múi giờ Asia/Ho_Chi_Minh cấu hình trong service. |
| 7.4.1 | 1 | Verify that a generic message is shown when an unexpected or security sensitive error occurs, potentially with a unique ID which support personnel ca… | Đạt (tự động) | PD4, PD5, ZAPA | Problem Details chung, không lộ stack trace (khongLoNoiBo). Phát hiện: thân multipart hỏng ở org-service trả 500 (vẫn chung chung), chờ sửa. |
| 7.4.2 | 2 | Verify that exception handling (or a functional equivalent) is used across the codebase to account for expected and unexpected error conditions. | Đạt (tự động) | API | ProblemHandlingWebTest. |
| 7.4.3 | 2 | Verify that a "last resort" error handler is defined which will catch all unhandled exceptions. | Đạt (tự động) | API | Trình xử lý cuối ProblemErrorControllerTest. |

### V8 Data Protection

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 8.1.1 | 2 | Verify the application protects sensitive data from being cached in server components such as load balancers and application caches. | Đạt (tự động) | PD5 | Cache-Control no-store trên API. |
| 8.1.2 | 2 | Verify that all cached or temporary copies of sensitive data stored on the server are protected from unauthorized access or purged/invalidated after… | Có thiết kế, chờ kiểm | TL | Bộ đệm Valkey không giữ C2 dạng rõ (TL-04); chưa có kiểm thử tự động. |
| 8.1.3 | 2 | Verify the application minimizes the number of parameters in a request, such as hidden fields, Ajax variables, cookies and header values. | Đạt (cấu hình có kiểm) | TL | Hợp đồng API tối thiểu tham số; không có trường ẩn. |
| 8.1.4 | 2 | Verify the application can detect and alert on abnormal numbers of requests, such as by IP, user, total per hour or day, or whatever makes sense for… | Có thiết kế, chờ kiểm | GW | APISIX limit-count theo người và tenant; cảnh báo bất thường (AnomalyAlertApiIT) ở mức nghiệp vụ. |
| 8.1.5 | 3 | Verify that regular backups of important data are performed and that test restoration of data is performed. | Ngoài phạm vi |  | Mức 3 ngoài V2, V3, V6; sao lưu và khôi phục kiểm ở QA-14, QA-15. |
| 8.1.6 | 3 | Verify that backups are stored securely to prevent data from being stolen or corrupted. | Ngoài phạm vi |  | Mức 3 ngoài V2, V3, V6; sao lưu và khôi phục kiểm ở QA-14, QA-15. |
| 8.2.1 | 1 | Verify the application sets sufficient anti-caching headers so that sensitive data is not cached in modern browsers. | Đạt (tự động) | PD5, NGX | no-store trên API và trang shell. |
| 8.2.2 | 1 | Verify that data stored in browser storage (such as localStorage, sessionStorage, IndexedDB, or cookies) does not contain sensitive data. | Đạt (tự động) | FE | Không lưu dữ liệu nhạy cảm trong localStorage; sessionStorage chỉ giữ nonce, PKCE_verifier trong lúc chuyển hướng. |
| 8.2.3 | 1 | Verify that authenticated data is cleared from client storage, such as the browser DOM, after the client or session is terminated. | Đạt (tự động) | FE | Đăng xuất xoá token trong bộ nhớ (session.service.spec.ts). |
| 8.3.1 | 1 | Verify that sensitive data is sent to the server in the HTTP message body or headers, and that query string parameters from any HTTP verb do not cont… | Đạt (tự động) | PD3, ZAPA | Dữ liệu nhạy cảm trong thân hoặc tiêu đề, không trong query. |
| 8.3.2 | 1 | Verify that users have a method to remove or export their data on demand. | Có thiết kế, chờ kiểm | TL | Xuất dữ liệu cá nhân theo yêu cầu thuộc QT quản trị; chưa kiểm. |
| 8.3.3 | 1 | Verify that users are provided clear language regarding collection and use of supplied personal information and that users have provided opt-in conse… | Có thiết kế, chờ kiểm | TL | Thông báo xử lý dữ liệu cá nhân theo NĐ 13/2023 ở trang đăng nhập; chưa kiểm nội dung. |
| 8.3.4 | 1 | Verify that all sensitive data created and processed by the application has been identified, and ensure that a policy is in place on how to deal with… | Đạt (tự động) | TL | Phân lớp mọi cột (MigrationConventionsIT). |
| 8.3.5 | 2 | Verify accessing sensitive data is audited (without logging the sensitive data itself), if the data is collected under relevant data protection direc… | Đạt (tự động) | AUD | Truy cập C2 ghi kiểm toán không kèm dữ liệu (C2AccessAuditIT). |
| 8.3.6 | 2 | Verify that sensitive information contained in memory is overwritten as soon as it is no longer required to mitigate memory dumping attacks, using ze… | Đạt một phần | E2E | Khoá C3 xoá khỏi bộ nhớ sau dùng ở trình duyệt; JVM không bảo đảm ghi đè chuỗi. |
| 8.3.7 | 2 | Verify that sensitive or private information that is required to be encrypted, is encrypted using approved algorithms that provide both confidentiali… | Đạt (tự động) | ENC | Như 6.1.1. |
| 8.3.8 | 2 | Verify that sensitive personal information is subject to data retention classification, such that old or out of date data is deleted automatically, o… | Đạt (tự động) | TL | Lưu trữ theo thời hạn (RetentionIT, RetentionRulesTest). |

### V9 Communication

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 9.1.1 | 1 | Verify that TLS is used for all client connectivity, and does not fall back to insecure or unencrypted communications. | Có thiết kế, chờ kiểm | GW, NGX | TLS ở APISIX, HSTS; sslRequired external ở Keycloak. Dev cục bộ chạy HTTP; kiểm trên staging. |
| 9.1.2 | 1 | Verify using up to date TLS testing tools that only strong cipher suites are enabled, with the strongest cipher suites set as preferred. | Có thiết kế, chờ kiểm | GW | TLS 1.2+ (ưu tiên 1.3) ở APISIX (TL-04 mục 9.6); cần kiểm bằng testssl.sh trên staging. |
| 9.1.3 | 1 | Verify that only the latest recommended versions of the TLS protocol are enabled, such as TLS 1.2 and TLS 1.3. The latest version of the TLS protocol… | Có thiết kế, chờ kiểm | GW | TLS 1.2+ (ưu tiên 1.3) ở APISIX (TL-04 mục 9.6); cần kiểm bằng testssl.sh trên staging. |
| 9.2.1 | 2 | Verify that connections to and from the server use trusted TLS certificates. Where internally generated or self-signed certificates are used, the ser… | Có thiết kế, chờ kiểm | K8S | Chứng chỉ nội bộ do cert-manager và Istio cấp; kiểm trên cụm. |
| 9.2.2 | 2 | Verify that encrypted communications such as TLS is used for all inbound and outbound connections, including for management ports, monitoring, authen… | Đạt (tự động) | SEM, K8S | Semgrep vt-tls-trust-all chặn tắt kiểm chứng chỉ; mTLS nội bộ. |
| 9.2.3 | 2 | Verify that all encrypted connections to external systems that involve sensitive information or functions are authenticated. | Đạt (tự động) | SIG, VNE | Kết nối ngoài xác thực hai chiều hoặc qua TLS kiểm chứng chỉ. |
| 9.2.4 | 2 | Verify that proper certification revocation, such as Online Certificate Status Protocol (OCSP) Stapling, is enabled and configured. | Có thiết kế, chờ kiểm | TL | OCSP stapling ở APISIX; kiểm thu hồi chứng chỉ chữ ký số ở esign-service (CaProvidersFlowIT). |
| 9.2.5 | 3 | Verify that backend TLS connection failures are logged. | Ngoài phạm vi |  |  |

### V10 Malicious Code

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 10.1.1 | 3 | Verify that a code analysis tool is in use that can detect potentially malicious code, such as time functions, unsafe file operations and network con… | Ngoài phạm vi |  |  |
| 10.2.1 | 2 | Verify that the application source code and third party libraries do not contain unauthorized phone home or data collection capabilities. Where such… | Đạt (tự động) | SEM, TRV | Semgrep, Trivy; không có kết nối ra ngoài trái phép (egress theo danh sách). |
| 10.2.2 | 2 | Verify that the application does not ask for unnecessary or excessive permissions to privacy related features or sensors, such as contacts, cameras,… | Không áp dụng |  | Ứng dụng web không xin quyền thiết bị ngoài micro cho ghi âm biên bản (Permissions-Policy). |
| 10.2.3 | 3 | Verify that the application source code and third party libraries do not contain back doors, such as hard-coded or additional undocumented accounts o… | Ngoài phạm vi |  |  |
| 10.2.4 | 3 | Verify that the application source code and third party libraries do not contain time bombs by searching for date and time related functions. | Ngoài phạm vi |  |  |
| 10.2.5 | 3 | Verify that the application source code and third party libraries do not contain malicious code, such as salami attacks, logic bypasses, or logic bom… | Ngoài phạm vi |  |  |
| 10.2.6 | 3 | Verify that the application source code and third party libraries do not contain Easter eggs or any other potentially unwanted functionality. | Ngoài phạm vi |  |  |
| 10.3.1 | 1 | Verify that if the application has a client or server auto-update feature, updates should be obtained over secure channels and digitally signed. The… | Đạt (tự động) | PIPE | Cập nhật qua GitOps, ảnh ký cosign. |
| 10.3.2 | 1 | Verify that the application employs integrity protections, such as code signing or subresource integrity. The application must not load or execute co… | Đạt (tự động) | PIPE, FE | Ảnh ký; UI Pack ký ECDSA (KeyPairUiPackSignerTest), kiểm khoá công khai ở shell. |
| 10.3.3 | 1 | Verify that the application has protection from subdomain takeovers if the application relies upon DNS entries or DNS subdomains, such as expired dom… | Có thiết kế, chờ kiểm | TL | Quản lý DNS ở hạ tầng; kiểm khi triển khai. |

### V11 Business Logic

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 11.1.1 | 1 | Verify that the application will only process business logic flows for the same user in sequential step order and without skipping steps. | Đạt (tự động) | PD3 | If-Match, khoá idempotency gắn với người gọi. |
| 11.1.2 | 1 | Verify that the application will only process business logic flows with all steps being processed in realistic human time, i.e. transactions are not… | Đạt (tự động) | API | Máy trạng thái chuẩn chặn bỏ bước (PlanVersionStateMachineTest, *TransitionsTest). |
| 11.1.3 | 1 | Verify the application has appropriate limits for specific business actions or transactions which are correctly enforced on a per user basis. | Đạt (tự động) | API | Giới hạn nghiệp vụ (khoá kỳ, hạn mức nhập: PeriodLockedWriteIT, OrgImportApiIT). |
| 11.1.4 | 1 | Verify that the application has anti-automation controls to protect against excessive calls such as mass data exfiltration, business logic requests,… | Đạt (cấu hình có kiểm) | GW | APISIX limit-count và limit-req theo người, tenant, địa chỉ (không đo tải ở giai đoạn này, YC-PT-19). |
| 11.1.5 | 1 | Verify the application has business logic limits or validation to protect against likely business risks or threats, identified using threat modeling… | Đạt (tự động) | API | Luật nghiệp vụ BR kiểm ở service (*RulesTest). |
| 11.1.6 | 2 | Verify that the application does not suffer from "Time Of Check to Time Of Use" (TOCTOU) issues or other race conditions for sensitive operations. | Đạt (tự động) | API, PD3 | Khoá lạc quan If-Match, khoá tư vấn khi ghi cây tổ chức. |
| 11.1.7 | 2 | Verify that the application monitors for unusual events or activity from a business logic perspective. For example, attempts to perform actions out o… | Đạt (tự động) | API | Cảnh báo bất thường nghiệp vụ (AnomalyAlertApiIT, OverdueAlertServiceTest). |
| 11.1.8 | 2 | Verify that the application has configurable alerting when automated attacks or unusual activity is detected. | Có thiết kế, chờ kiểm | TL | Cảnh báo tấn công tự động qua WAF và hệ quan sát; chưa kiểm. |

### V12 Files and Resources

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 12.1.1 | 1 | Verify that the application will not accept large files that could fill up storage or cause a denial of service. | Đạt (tự động) | PD4, FIL | Tệp nhập > 10 MB bị từ chối; evidence-service giới hạn kích thước. |
| 12.1.2 | 2 | Verify that the application checks compressed files (e.g. zip, gz, docx, odt) against maximum allowed uncompressed size and against maximum number of… | Có thiết kế, chờ kiểm | FIL | Giới hạn 20.000 dòng khi nhập .xlsx (OrgImportService.MAX_ROWS) và giới hạn tỉ lệ nén mặc định của Apache POI; chưa có ca kiểm thử bom nén. |
| 12.1.3 | 2 | Verify that a file size quota and maximum number of files per user is enforced to ensure that a single user cannot fill up the storage with too many… | Có thiết kế, chờ kiểm | FIL | Hạn mức theo tenant ở evidence-service; chưa có kiểm thử hạn mức theo người. |
| 12.2.1 | 2 | Verify that files obtained from untrusted sources are validated to be of expected type based on the file's content. | Đạt (tự động) | PD4, FIL | Kiểm chữ ký đầu tệp (ContentInspectorTest; tệp thực thi đổi đuôi .xlsx bị từ chối). |
| 12.3.1 | 1 | Verify that user-submitted filename metadata is not used directly by system or framework filesystems and that a URL API is used to protect against pa… | Đạt (tự động) | FIL | Tên tệp người dùng không dùng làm đường dẫn; lưu theo khoá đối tượng sinh ở máy chủ. |
| 12.3.2 | 1 | Verify that user-submitted filename metadata is validated or ignored to prevent the disclosure, creation, updating or removal of local files (LFI). | Đạt (tự động) | FIL | Tên tệp người dùng không dùng làm đường dẫn; lưu theo khoá đối tượng sinh ở máy chủ. |
| 12.3.3 | 1 | Verify that user-submitted filename metadata is validated or ignored to prevent the disclosure or execution of remote files via Remote File Inclusion… | Đạt (tự động) | FIL | Tên tệp người dùng không dùng làm đường dẫn; lưu theo khoá đối tượng sinh ở máy chủ. |
| 12.3.4 | 1 | Verify that the application protects against Reflective File Download (RFD) by validating or ignoring user-submitted filenames in a JSON, JSONP, or U… | Đạt một phần | FIL | Tải xuống dùng attachment; chế độ xem trực tiếp (inline) vẫn cho phép với kiểu tệp đã kiểm, cần SEC-41 xác minh RFD. |
| 12.3.5 | 1 | Verify that untrusted file metadata is not used directly with system API or libraries, to protect against OS command injection. | Đạt (tự động) | FIL | Tên tệp người dùng không dùng làm đường dẫn; lưu theo khoá đối tượng sinh ở máy chủ. |
| 12.3.6 | 2 | Verify that the application does not include and execute functionality from untrusted sources, such as unverified content distribution networks, Java… | Đạt (tự động) | FE | Không nạp mã từ nguồn không tin cậy; remote và UI Pack theo danh sách và chữ ký. |
| 12.4.1 | 1 | Verify that files obtained from untrusted sources are stored outside the web root, with limited permissions. | Đạt (tự động) | FIL | Tệp ở Ceph RGW ngoài web root, Object Lock (S3ObjectLockIT). |
| 12.4.2 | 1 | Verify that files obtained from untrusted sources are scanned by antivirus scanners to prevent upload and serving of known malicious content. | Đạt (tự động) | FIL | Quét ClamAV (ClamdScannerTest). |
| 12.5.1 | 1 | Verify that the web tier is configured to serve only files with specific file extensions to prevent unintentional information and source code leakage… | Đạt (cấu hình có kiểm) | NGX | nginx chỉ phục vụ tệp tĩnh của bản dựng. |
| 12.5.2 | 1 | Verify that direct requests to uploaded files will never be executed as HTML/JavaScript content. | Đạt (tự động) | FIL | Tệp tải lên phục vụ từ RGW qua URL ký, attachment, nosniff. |
| 12.6.1 | 1 | Verify that the web or application server is configured with an allow list of resources or systems to which the server can send requests or load data… | Có thiết kế, chờ kiểm | TL | Egress qua cell-egress với danh sách đích; chưa có ca SSRF tự động. |

### V13 API and Web Service

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 13.1.1 | 1 | Verify that all application components use the same encodings and parsers to avoid parsing attacks that exploit different URI or file parsing behavio… | Đạt (cấu hình có kiểm) | API | Một bộ phân tích JSON (Jackson) cho mọi service. |
| 13.1.2 | 3 | [DELETED, DUPLICATE OF 4.3.1] | ASVS đã xoá |  |  |
| 13.1.3 | 1 | Verify API URLs do not expose sensitive information, such as the API key, session tokens etc. | Đạt (tự động) | PD1, ZAPA | Token không nằm trong URL. |
| 13.1.4 | 2 | Verify that authorization decisions are made at both the URI, enforced by programmatic or declarative security at the controller or router, and at th… | Đạt (tự động) | PD2, OPA | Phân quyền ở APISIX (tuyến) và service (OPA theo thao tác). |
| 13.1.5 | 2 | Verify that requests containing unexpected or missing content types are rejected with appropriate headers (HTTP response status 406 Unacceptable or 4… | Đạt một phần | PD4 | Content-Type sai: 415 (đạt). Thân multipart hỏng ở /org/imports trả 500 thay vì 400 (ca todo trong PD4). |
| 13.2.1 | 1 | Verify that enabled RESTful HTTP methods are a valid choice for the user or action, such as preventing normal users using DELETE or PUT on protected… | Đạt (tự động) | PD5 | Phương thức ngoài hợp đồng: 405; TRACE không phản hồi lại. |
| 13.2.2 | 1 | Verify that JSON schema validation is in place and verified before accepting input. | Đạt (tự động) | PD4, API | Kiểm theo lược đồ trước khi xử lý. |
| 13.2.3 | 1 | Verify that RESTful web services that utilize cookies are protected from Cross-Site Request Forgery via the use of at least one or more of the follow… | Không áp dụng |  | API không dùng cookie. |
| 13.2.4 | 3 | [DELETED, DUPLICATE OF 11.1.4] | ASVS đã xoá |  |  |
| 13.2.5 | 2 | Verify that REST services explicitly check the incoming Content-Type to be the expected one, such as application/xml or application/json. | Đạt (tự động) | PD4 | Content-Type khác JSON: 415. |
| 13.2.6 | 2 | Verify that the message headers and payload are trustworthy and not modified in transit. Requiring strong encryption for transport (TLS only) may be… | Đạt (tự động) | PD3, SIG | If-Match chống ghi đè; mTLS và chữ ký cho sự kiện. |
| 13.3.1 | 1 | Verify that XSD schema validation takes place to ensure a properly formed XML document, followed by validation of each input field before any process… | Không áp dụng |  | Không có SOAP. |
| 13.3.2 | 2 | Verify that the message payload is signed using WS-Security to ensure reliable transport between client and service. | Không áp dụng |  | Không có SOAP. |
| 13.4.1 | 2 | Verify that a query allow list or a combination of depth limiting and amount limiting is used to prevent GraphQL or data layer expression Denial of S… | Không áp dụng |  | Không có GraphQL. |
| 13.4.2 | 2 | Verify that GraphQL or other data layer authorization logic should be implemented at the business logic layer instead of the GraphQL layer. | Không áp dụng |  | Không có GraphQL. |

### V14 Configuration

| Mục | Mức | Yêu cầu (ASVS, rút gọn) | Trạng thái | Bằng chứng | Ghi chú |
|---|:-:|---|---|---|---|
| 14.1.1 | 2 | Verify that the application build and deployment processes are performed in a secure and repeatable way, such as CI / CD automation, automated config… | Đạt (tự động) | PIPE | Dựng lặp lại được, SLSA provenance (SEC-38). |
| 14.1.2 | 2 | Verify that compiler flags are configured to enable all available buffer overflow protections and warnings, including stack randomization, data execu… | Không áp dụng |  | Không có mã biên dịch native. |
| 14.1.3 | 2 | Verify that server configuration is hardened as per the recommendations of the application server and frameworks in use. | Đạt (cấu hình có kiểm) | NGX, K8S | server_tokens off, hệ thống tệp chỉ đọc, không root. |
| 14.1.4 | 2 | Verify that the application, configuration, and all dependencies can be re-deployed using automated deployment scripts, built from a documented and t… | Đạt (tự động) | PIPE | GitOps Argo CD dựng lại được toàn bộ. |
| 14.1.5 | 3 | Verify that authorized administrators can verify the integrity of all security-relevant configurations to detect tampering. | Ngoài phạm vi |  |  |
| 14.2.1 | 1 | Verify that all components are up to date, preferably using a dependency checker during build or compile time. | Đạt (tự động) | TRV | Trivy, Dependency-Track. |
| 14.2.2 | 1 | Verify that all unneeded features, documentation, sample applications and configurations are removed. | Đạt (tự động) | PD5 | Không có trang mẫu, actuator ở cổng quản trị riêng (8081) không mở ra ngoài. |
| 14.2.3 | 1 | Verify that if application assets, such as JavaScript libraries, CSS or web fonts, are hosted externally on a Content Delivery Network (CDN) or exter… | Đạt một phần | NGX | Tài nguyên cùng nguồn; UI Pack trên CDN kiểm chữ ký thay cho SRI. |
| 14.2.4 | 2 | Verify that third party components come from pre-defined, trusted and continually maintained repositories. | Đạt (cấu hình có kiểm) | PIPE | Gói từ Harbor, Nexus nội bộ theo danh sách. |
| 14.2.5 | 2 | Verify that a Software Bill of Materials (SBOM) is maintained of all third party libraries in use. | Đạt (tự động) | PIPE | SBOM CycloneDX (SEC-23). |
| 14.2.6 | 2 | Verify that the attack surface is reduced by sandboxing or encapsulating third party libraries to expose only the required behaviour into the applica… | Đạt (cấu hình có kiểm) | K8S | Tách service, NetworkPolicy; thư viện bên thứ ba chạy trong service riêng (Gotenberg, ClamAV). |
| 14.3.1 | 3 | [DELETED, DUPLICATE OF 7.4.1] | ASVS đã xoá |  |  |
| 14.3.2 | 1 | Verify that web or application server and application framework debug modes are disabled in production to eliminate debug features, developer console… | Đạt (tự động) | PD4, PD5 | Không có trang lỗi gỡ lỗi; không lộ stack trace. |
| 14.3.3 | 1 | Verify that the HTTP headers or any part of the HTTP response do not expose detailed version information of system components. | Đạt (tự động) | PD5, ZAPW | Không có X-Powered-By, Server không kèm phiên bản. |
| 14.4.1 | 1 | Verify that every HTTP response contains a Content-Type header. Also specify a safe character set (e.g., UTF-8, ISO-8859-1) if the content types are… | Đạt (tự động) | PD5 | Content-Type kèm charset. |
| 14.4.2 | 1 | Verify that all API responses contain a Content-Disposition: attachment; filename="api.json" header (or other appropriate filename for the content ty… | Chưa đạt | PD5 | Phản hồi API chưa có Content-Disposition: attachment (org-service). Đề xuất thêm ở APISIX response-rewrite cho /api/. |
| 14.4.3 | 1 | Verify that a Content Security Policy (CSP) response header is in place that helps mitigate impact for XSS attacks like HTML, DOM, JSON, and JavaScri… | Đạt (tự động) | ZAPW, NGX | CSP chặt (SEC-19); bản dựng mất CSP bị ZAP chặn (10038 nâng High). |
| 14.4.4 | 1 | Verify that all responses contain a X-Content-Type-Options: nosniff header. | Đạt (tự động) | PD5, NGX | nosniff trên API và web. |
| 14.4.5 | 1 | Verify that a Strict-Transport-Security header is included on all responses and for all subdomains, such as Strict-Transport-Security: max-age=157248… | Có thiết kế, chờ kiểm | NGX, GW | HSTS khi qua HTTPS (nginx $vt_hsts, APISIX SEC-24); dev cục bộ HTTP nên chưa kiểm. |
| 14.4.6 | 1 | Verify that a suitable Referrer-Policy header is included to avoid exposing sensitive information in the URL through the Referer header to untrusted… | Đạt (tự động) | NGX, ZAPW | Referrer-Policy strict-origin-when-cross-origin hoặc no-referrer. |
| 14.4.7 | 1 | Verify that the content of a web application cannot be embedded in a third-party site by default and that embedding of the exact resources is only al… | Đạt (tự động) | ZAPW, PD5 | frame-ancestors và X-Frame-Options DENY; 10020 nâng High trong ZAP web. |
| 14.5.1 | 1 | Verify that the application server only accepts the HTTP methods in use by the application/API, including pre-flight OPTIONS, and logs/alerts on any… | Đạt (tự động) | PD5 | 405 cho phương thức không dùng; TRACE không phản hồi lại. |
| 14.5.2 | 1 | Verify that the supplied Origin header is not used for authentication or access control decisions, as the Origin header can easily be changed by an a… | Đạt (tự động) | PD5 | Origin không dùng cho quyết định truy cập. |
| 14.5.3 | 1 | Verify that the Cross-Origin Resource Sharing (CORS) Access-Control-Allow-Origin header uses a strict allow list of trusted domains and subdomains to… | Đạt (tự động) | PD5, ZAPW | CORS API không cho origin lạ; * chỉ ở tệp tĩnh công khai (ngoai-le.yaml). |
| 14.5.4 | 2 | Verify that HTTP headers added by a trusted proxy or SSO devices, such as a bearer token, are authenticated by the application. | Đạt (tự động) | PD2 | X-Tenant-Id chỉ nhận từ client dịch vụ được uỷ quyền. |

## Liên quan

- [Bảo mật frontend](../../wiki/08-bao-mat-frontend.md)
- [Bảo mật phía máy chủ](../../wiki/20-bao-mat-phia-may-chu.md)
- [Kiểm thử và chất lượng](../../wiki/11-kiem-thu-va-chat-luong.md)
