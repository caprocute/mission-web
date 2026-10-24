# DV-01-01 · SVC-01 Tuyến APISIX theo module, xác thực JWT qua JWKS, plugin gán claim tenant

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-01-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | INF-08 (cụm cell có APISIX ingress); cần realm Keycloak của DV-02-01 để kiểm thử đầu cuối (suy luận) |
| Truy vết | SVC-01 api-gateway → mọi tiền tố `/api/v1/<module>` của baseline mục 13 → SVC-05 (MOD-01..MOD-08), SVC-03, SVC-04, SVC-06, SVC-08…; ADR-08, ADR-11; TL-01 "SVC-01 api-gateway" |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng lớp tuyến của cổng API trong mỗi cell: mọi tiền tố `/api/v1/<module>` có `ApisixRoute` khai báo trong Git, đi tới đúng upstream (mission-core theo module hoặc microservice), xác thực token Keycloak bằng JWKS (ES256) ngay tại cổng và ghi đè `X-Tenant-Id` từ claim của token. Khi xong, một request mang token hợp lệ tới cell đi đúng dịch vụ, một request không token hoặc token sai chữ ký bị chặn 401 tại cổng, và client không thể giả mạo tenant bằng header tự đặt. Đây là điểm vào duy nhất cho web, di động và hệ ngoài, nên mọi dịch vụ phía sau đều phụ thuộc.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-01 api-gateway" (dòng 903–985): trích cấu hình `openid-connect` (dòng 946–953), ví dụ route `mission-core-tasks` (dòng 925–945), plugin Lua gán `$jwt_sub`, `$jwt_tenant` (dòng 985).
- Cùng tệp, mục "Đa khách thuê" → "Lan truyền ngữ cảnh tenant từ JWT tới RLS" (dòng 2670) và "Định tuyến tới cell" (dòng 2718).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Cổng vào: Apache APISIX" (dòng 534) và mục "Cắt tải và giới hạn tốc độ ở APISIX" (dòng 1579) cho plugin `vt-claims` và `ApisixPluginConfig protected-api`.
- Baseline `00-co-so-thiet-ke-chung.md` mục 5 (SVC-01) và mục 13 (danh sách tiền tố).
- Hợp đồng: `contracts/openapi/modules/*.yaml` (đường dẫn thật và `x-mission.owner` từng thao tác để biết upstream), `contracts/openapi/common/common.yaml` (lỗi RFC 9457 401/403).
- Wiki: `wiki/06-quy-uoc-api.md`, `wiki/07-xac-thuc-phan-quyen.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Viết bảng ánh xạ tiền tố → upstream lấy từ `x-mission.owner` trong hợp đồng: `okr`, `tasks`, `plans`, `catalog`, `products`, `evidences`, `appraisals` (trừ thao tác owner SVC-07), `policies`, `dossiers`, `oversight`, `config`, `periods`, `ui` → mission-core; `org` → org-service; `files` → evidence-service; `workflow` → workflow-service; `appraisals/score-preview`, `.../simulate`, `.../recompute`, `.../score-trace` → scoring-service; `registry` → tenant-registry (chỉ ở lớp toàn cục, xem DV-01-04).
- Tạo `ApisixRoute` cho từng nhóm trên trong chart `api-gateway`, timeout theo mẫu TL-01 (connect 2 s, send/read 10 s), công bố `/.well-known/` và cấu hình `request-id`, `opentelemetry` (parent_base), `prometheus`.
- Cấu hình `openid-connect` dạng `bearer_only`, `use_jwks`, thuật toán ES256, `set_userinfo_header: false`, discovery trỏ realm Keycloak của cell qua giá trị Helm (không ghi địa chỉ thật vào repo).
- Viết plugin Lua `vt-claims` (khoảng 30 dòng, ưu tiên cao hơn plugin giới hạn tốc độ) đọc JWT đã kiểm, đăng ký biến `vt_tenant`, `vt_sub`, xoá mọi `X-Tenant-Id` client gửi lên rồi ghi lại từ claim; từ chối 403 Problem Details khi claim `cell` khác cell của cổng (TL-04 dòng 1601–1604).
- Đặt các plugin dùng chung vào `ApisixPluginConfig protected-api` để route chỉ tham chiếu, không lặp cấu hình.
- Viết kiểm thử tích hợp: dựng APISIX + Keycloak bằng Docker Compose hoặc Testcontainers cục bộ, chứng minh 401 khi thiếu token, 401 khi token ký sai khoá, 403 khi lệch cell, header `X-Tenant-Id` giả bị ghi đè.
- Điểm lệch cần lưu ý: tên claim tenant khác nhau giữa các nguồn (TL-01 SVC-02 `tenant_id`; TL-01 chương "Đa khách thuê" và TL-04 `tenant`, `cell`; hợp đồng `platform.yaml` nhắc claim `tid`); chọn theo hợp đồng API và realm thật do DV-02-02 phát hành, cấu hình tên claim bằng giá trị Helm, ghi quyết định vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.
- Điểm lệch cần lưu ý: tiền tố `workflow`, `registry`, `jobs` không có trong danh sách baseline mục 13 nhưng có trong hợp đồng; theo API-first giữ đường dẫn của hợp đồng.

## 4. Ngoài phạm vi

- Giới hạn tốc độ, cắt tải, WAF (DV-01-02); danh sách tuyến công khai và kiểm thử quét CRD (DV-01-03); APISIX lớp ngoài toàn cục (DV-01-04).
- Cài APISIX và etcd lên cụm (INF-08). Không có định tuyến Strangler Fig sang hệ cũ: Mission là hệ mới độc lập, phần Strangler của ADR-20 không áp dụng (quyết định ngày 20/10/2026).

## 5. Tệp được phép sửa

- `deploy/charts/api-gateway/**` (route, plugin config, plugin Lua `vt-claims`, values mặc định)
- `deploy/environments/*/api-gateway/**` (values theo môi trường, chỉ giá trị giả hoặc tham chiếu bí mật)
- `infra/dev/` (chỉ thêm tệp compose kiểm thử APISIX + Keycloak nếu cần)
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `helm lint deploy/charts/api-gateway` và `helm template deploy/charts/api-gateway | kubeconform -strict` (kèm schema CRD APISIX) không lỗi; `kube-linter` không lỗi mức cao.
- Kiểm thử tích hợp cục bộ chạy xanh cho bốn ca: thiếu token 401, sai chữ ký 401, lệch cell 403, header tenant giả bị ghi đè; mọi phản hồi lỗi là `application/problem+json`.
- Mọi đường dẫn trong `contracts/openapi/modules/*.yaml` khớp ít nhất một route (kịch bản kiểm đối chiếu chạy được trong CI).
- Không có địa chỉ máy chủ, client secret hay khoá nào trong repo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
