# NEN-014 · ADR-21 vt-platform-starter: OpaClient, `@RequiresPermission`, đánh giá từng phần thành SQL, kiểm step-up

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-014` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (mốc M0 – Nền móng) |
| Luồng đề xuất | B1 |
| Cỡ việc | L (nên tách: a) `OpaClient` + `@RequiresPermission` + step-up; b) đánh giá từng phần → SQL) |
| Phụ thuộc | NEN-009, NEN-040 |
| Truy vết | ADR-21, ADR-08 → TL-01 "Uỷ quyền tập trung bằng ABAC", "Định danh và xác thực" → BR-00-01, BR-00-02 → `x-mission.roles/scope/aal/fourEyes` trong `contracts/openapi/modules/*.yaml` → `org_replica.unit_closure_v` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Đưa quyết định phân quyền của mọi service Java về một chỗ: OPA chạy cùng pod với gói chính sách của NEN-040. Thư viện nền gọi OPA trước mỗi use case được đánh dấu, từ chối mặc định khi OPA lỗi (fail closed), kiểm mức xác thực bước lên cho thao tác nhạy cảm, và với truy vấn danh sách thì dịch điều kiện còn lại của OPA thành mệnh đề SQL để chỉ đọc đúng phạm vi cây tổ chức. Khi xong, task nghiệp vụ không tự viết danh sách vai trò trong mã (AGENTS.md quy tắc 6) mà chỉ khai hành động và tài nguyên.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Uỷ quyền tập trung bằng ABAC" (L3018–3060, gồm chính sách mẫu `package okrkpi.task`), "Định danh và xác thực" (L3008–3012: AAL2, `acr`, `auth_time`, RFC 9470), ADR-21 (L3592–3600), mục "Cấu hình mẫu" SVC-05 (L880–886: địa chỉ OPA, timeout, đệm), "Timeout mặc định" (L2859–2874: OPA 50/100 ms, fail closed).
- `wiki/15-phan-quyen.md`, `wiki/15a-ma-tran-phan-quyen.md`; `docs/phan-quyen/ma-tran.json`.
- `contracts/openapi/README.md` mục `x-mission`; `contracts/openapi/common/common.yaml` (`securitySchemes`, phản hồi 401 `insufficient_user_authentication`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 "Quy tắc nghiệp vụ" (BR-00-01, BR-00-02).

## 3. Phạm vi công việc

- Gói `authz` trong `vt-platform-starter`: `OpaClient` gọi `POST /v1/data/okrkpi/authz` tới OPA cùng pod (địa chỉ cấu hình, mặc định `127.0.0.1:8181`), timeout theo TL-01 (50 ms, tối đa 100 ms), lỗi hoặc quá hạn thì từ chối và ghi metric; đệm quyết định theo (người dùng, hành động, tài nguyên, phiên bản bundle) với thời hạn cấu hình được.
- Annotation `@RequiresPermission(action = "task:update_progress", resource = ...)` áp ở lớp `application`; dựng `input` gồm chủ thể (sub, tenant, phân công đang chọn, vai trò, `acr`, `auth_time`), hành động, tài nguyên (loại, id, đơn vị, luồng, phân loại dữ liệu, người tham gia trước đó); kết quả `allow` và `deny[]` rỗng mới được đi tiếp, `deny` có lý do thì trả 403 với mã lỗi hợp đồng tương ứng (`COMMON-403-OUT-OF-SCOPE`, `...-403-SEPARATION-OF-DUTIES`…).
- Kiểm step-up: thao tác có `x-mission.aal: 2` (54 thao tác trong hợp đồng hiện tại) yêu cầu `acr=aal2` và `auth_time` trong cửa sổ cho phép, không đạt thì trả 401 với `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=<giây>` (RFC 9470); sinh bảng `operationId → aal, fourEyes, roles, scope` từ `x-mission` lúc build để dùng làm mặc định và để kiểm thử đối chiếu.
- Điểm lệch cần lưu ý: hợp đồng `common.yaml` và TL-01 ghi `auth_time` ≤ 5 phút, BR-00-02 và tham số Policy Pack `mfa_stepup_window_minutes` (`_ghi-chu-TL-02a.md` L74) ghi 15 phút; task đặt cửa sổ là cấu hình theo tenant, mặc định 5 phút theo hợp đồng, và ghi vào báo cáo.
- Đánh giá từng phần: gọi `POST /v1/compile` với `unknowns` là thuộc tính tài nguyên, dịch tập điều kiện trả về (bằng, thuộc tập, thuộc cây đơn vị qua `org_replica.unit_closure_v`) thành mệnh đề SQL có tham số; điều kiện không dịch được thì từ chối truy vấn thay vì bỏ qua; đệm theo (người dùng, hành động, phiên bản bundle).
- Kiểm thử tích hợp Testcontainers chạy OPA với bundle của NEN-040: các ca được phép, bị từ chối theo phạm vi, theo tách vai trò, thiếu AAL2, OPA tắt (fail closed), và truy vấn danh sách chỉ trả dòng trong cây đơn vị của người dùng.

## 4. Ngoài phạm vi

- Viết chính sách Rego: NEN-040; dữ liệu cây tổ chức cho OPA và `org_replica`: CORE-00-03.
- MFA và phát claim `acr`/`auth_time` ở Keycloak: DV-02-03; sidecar OPA trong Helm: INF-01.

## 5. Tệp được phép sửa

- `backend/libs/vt-platform-starter/**` (gói `authz`, công cụ sinh bảng `x-mission`, kiểm thử)
- `backend/platform-bom/pom.xml` (chỉ thêm phụ thuộc kiểm thử cần thiết)
- `wiki/15-phan-quyen.md` (chỉ mục liên quan tới task này; tạo trang nếu chưa có, theo quy tắc cập nhật wiki)

## 6. Tiêu chí hoàn thành

- `cd backend && ./mvnw -q -pl libs/vt-platform-starter -am verify` chạy xanh với các ca ở mục 3.
- Đo trong kiểm thử: độ trễ quyết định có đệm và không đệm, báo cáo p50/p99 (TL-01: 0,3–1 ms mỗi quyết định với OPA cùng pod).
- Bảng `operationId → aal` sinh ra có đúng 54 thao tác AAL2 và 15 thao tác bốn mắt như đếm từ hợp đồng (ghi lệnh đếm vào báo cáo).
- Báo cáo cuối task theo AGENTS.md mục 6; cập nhật `wiki/15-phan-quyen.md` mục "Hiện thực ở từng tầng" phần máy chủ.

## 7. Báo cáo

Theo AGENTS.md mục 6.
