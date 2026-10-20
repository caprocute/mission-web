# NEN-043 · ADR-32/YC-PT-09 Sửa hợp đồng OpenAPI phần nền tảng: cờ tính năng, cấu hình tenant, thông báo đẩy, MFA, phân công, Superset, kiểm quyền theo lô, ký sổ cái, OCR, chuyển cell

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-043` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c (gộp trước các task DV, CORE, MOB dùng thao tác này) |
| Luồng đề xuất | B1 |
| Cỡ việc | M (gợi ý tách nếu quá lớn: (a) `config.yaml`, `platform.yaml` phần thông báo, MFA, chuyển cell; (b) `org.yaml`, `tasks.yaml`, `dossiers.yaml`, `platform.yaml` phần AI và phân tích) |
| Phụ thuộc | NEN-001; người phụ trách hợp đồng duyệt; nên gộp sau NEN-042 vì cùng sửa `tasks.yaml` (tránh xung đột) |
| Truy vết | ADR-32, YC-PT-09 → `contracts/openapi/modules/config.yaml` (MOD-08), `platform.yaml` (SVC-02, SVC-03, SVC-12, SVC-14, SVC-15), `org.yaml` (SVC-04), `tasks.yaml` (MOD-02 cho SVC-16), `dossiers.yaml` (SVC-10) → bảng `config.tenant_setting`, `config.feature_flag`, `notify.channel_pref`, `org.assignment`; OPS-04, BR-00-01, BR-16-04, ADR-29 |
| Trạng thái | Chờ người dùng chốt (người phụ trách hợp đồng duyệt danh sách thao tác) |

## 1. Mục tiêu

Các thẻ DV, CORE-08, MOB, CNH đã ghi lại những năng lực nền tảng mà TL yêu cầu nhưng hợp đồng chưa có thao tác, nên các task đó đang phải dùng "cổng nội bộ (suy luận)" hoặc tắt tính năng bằng cờ. Task này bổ sung một lần, có duyệt, toàn bộ thao tác nền tảng còn thiếu vào hợp đồng: chỉ thêm, không đổi thao tác cũ, chứng minh tương thích bằng `openapi-diff`, sinh lại kiểu cho frontend. Thao tác dành cho dịch vụ gọi dịch vụ theo tiền lệ `listAppraisalScoringInputs` và `resolvePolicyPack` (`roles: [service_client]`), không mở cho người dùng.

## 2. Đầu vào bắt buộc đọc

- `contracts/openapi/README.md` (quy ước, bảng "Bố cục" tiền tố từng tệp, mục "Kiểm tra"); `docs/quy-dinh/BACKEND.md` đoạn API-first.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: "Bảng config.tenant_setting" (L1481), "Bảng config.feature_flag" (L1586), "Schema notify (SVC-12)" (L3797–3854, `channel_pref`, `delivery.channel = PUSH`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: SVC-04 "API, sự kiện" (L1042: `POST /api/v1/org/assignments`), SVC-10 "Vai trò" (ký niêm phong sổ cái cho ledger-service), SVC-14 OCR (L3094), SVC-15 (L1194–1204, Superset nhúng), SVC-16 (L1208: kiểm quyền lại ở lõi), ADR-29 (L3672).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 ngoại lệ mất SmartOTP (L371: quản trị tenant cấp mã khôi phục một lần, ghi sổ cái); `TL-04-trien-khai-van-hanh.md` OPS-04 (bật tính năng theo tenant bằng cờ, mục "GitOps và phát hành theo đợt cell").
- Thẻ đã ghi điểm thiếu: `docs/tasks/CORE-08-07-*.md`, `DV-12-03-*.md`, `MOB-09-*.md`, `DV-02-03-*.md`, `DV-04-04-*.md`, `DV-15-05-*.md`, `DV-16-02-*.md`, `DV-09-03-*.md`, `DV-14-05-*.md`, `DV-03-07-*.md`, `CNH-01-*.md`.

## 3. Phạm vi công việc

Tên đường dẫn và `operationId` là đề xuất theo quy ước README; được đổi khi người phụ trách duyệt, mọi thao tác bị bỏ phải có lý do trong báo cáo.

- `config.yaml` (MOD-08): thêm `GET /ui/feature-flags` (`getEffectiveFeatureFlags`) trả cờ đã tính cho tenant, đơn vị và người gọi (chỉ khoá và giá trị bật/tắt, không lộ quy tắc `rollout`), mọi vai trò, để shell và di động ẩn chức năng (WEB-076); thêm `GET /config/settings` (`listTenantSettings`) và `PUT /config/settings/{key}` (`setTenantSetting`, `IfMatch`, giá trị kiểm theo `value_schema`, tham số đơn vị tuỳ chọn), roles `tenant_admin`; khoá bí mật không bao giờ trả về.
- `platform.yaml` (SVC-12): thêm `POST /notifications/devices` (`registerPushDevice`: nền tảng `FCM`/`APNS`, mã thiết bị đánh dấu `x-classification: C2`, phiên bản ứng dụng), `GET /notifications/devices` (`listMyPushDevices`), `DELETE /notifications/devices/{deviceId}` (`unregisterPushDevice`); thêm `GET` và `PUT /notifications/preferences` (`getNotificationPreferences`, `updateNotificationPreferences`) theo cột `notify.channel_pref` (nhóm thông báo, `channels[]`, `quiet_from`, `quiet_to`), roles là mọi người dùng, phạm vi `B`.
- `platform.yaml` (SVC-02, tiền tố mới `/iam`, cập nhật bảng "Bố cục" của README): thêm `POST /iam/users/{personId}/mfa-recovery-codes` (`issueMfaRecoveryCode`, roles `tenant_admin`, `aal: 2`, ghi sổ cái, trả mã một lần chỉ hiển thị một lần) và `POST /iam/users/{personId}/mfa-recovery-codes/revoke` (`revokeMfaRecoveryCodes`); việc dùng mã diễn ra trong luồng đăng nhập Keycloak, không qua REST.
- `org.yaml` (SVC-04): thêm `POST /org/assignments` (`createOrgAssignment`: bổ nhiệm, kiêm nhiệm, điều động có `validFrom` tương lai, nguồn TL-01 L1042) và `POST /org/assignments/{id}/end` (`endOrgAssignment`), roles `tenant_admin`, `IfMatch` với thao tác kết thúc. Điểm lệch cần lưu ý: tên cột loại phân công `assignment_kind` (TL-03 ECST) và `kind` lệch nhau giữa các nguồn; dùng đúng giá trị CHECK của TL-03 trong enum.
- `platform.yaml` (SVC-15): thêm `POST /analytics/embed-tokens` (`createAnalyticsEmbedToken`) trả guest token Superset thời hạn ngắn có `rls` theo tenant và cây đơn vị từ JWT, roles `leader`, `manager`; mô tả nêu rõ dữ liệu C2 không có trong Superset (BR-16-04).
- `tasks.yaml` (MOD-02, dành cho SVC-16): thêm `POST /tasks/visibility-check` (`checkTaskVisibilityBatch`, roles `service_client`, tối đa 200 mã nhiệm vụ, văn bản chỉ đạo hoặc mục danh mục kèm mã người dùng, trả tập được phép xem) để tìm kiếm "kiểm quyền lại ở lõi" theo lô.
- `dossiers.yaml` (SVC-10): thêm `POST /esign/seals` (`signLedgerSeal`, roles `service_client`, đầu vào gốc Merkle và mã niêm phong, đầu ra chữ ký và chuỗi chứng thư) cho ledger-service ký niêm phong (DV-09-03).
- `platform.yaml` (SVC-14): thêm `POST /ai/ocr` (`ocrEvidenceObject`, roles `service_client`, tham chiếu đối tượng đã quét sạch, trả văn bản theo trang, độ tin cậy; `202` khi hàng đợi đầy) cho evidence-service (DV-08-05, DV-14-05); không nhận tệp C3.
- `platform.yaml` (SVC-03): thêm `GET /registry/tenants/{id}/move/{moveId}` (`getTenantMove`, trạng thái và bước hiện tại) và `POST /registry/tenants/{id}/move/{moveId}/cancel` (`cancelTenantMove`, chỉ trước cắt chuyển, `fourEyes`), roles `platform_admin`. Bảng lưu đề nghị chuyển cell chưa có trong TL-03 là việc của DV-03-07, không thuộc hợp đồng.
- Mỗi thao tác có `IdempotencyKey`, `IfMatch` + `409`/`428` khi sửa đối tượng đã có, `default: Problem`, mã lỗi `<MODULE>-<HTTP>-<TÊN>`, `examples` tiếng Việt, `x-mission` đầy đủ; cập nhật `docs/phan-quyen/ma-tran.json` mục `api` nếu vai trò có thêm động từ HTTP, rồi sinh lại `wiki/15a`; ghi từng quyết định vào `wiki/14`.

## 4. Ngoài phạm vi

- Thao tác nghiệp vụ (OKR, kế hoạch, nhiệm vụ, E2EE, hồ sơ, khiếu nại): NEN-042.
- Hiện thực: CORE-08-07 (cờ, cấu hình), DV-12-05 (thiết bị, tuỳ chọn kênh), DV-02-08 (mã khôi phục), DV-04-04 (phân công), DV-15-05 (Superset), DV-16-02 (kiểm quyền lô), DV-09-03 (ký niêm phong), DV-14-05 (OCR), DV-03-07 (chuyển cell).
- API tuỳ chọn cá nhân và bộ lọc đã lưu (CNH-01 đề xuất, chờ duyệt thiết kế); API "chấp nhận để lại hồ sơ lỗi" của saga chốt kỳ (DV-06-07) và API thư chết: ghi vào báo cáo làm đề xuất cho lần sửa sau.

## 5. Tệp được phép sửa

- `contracts/openapi/modules/config.yaml`, `platform.yaml`, `org.yaml`, `tasks.yaml`, `dossiers.yaml` (chỉ thêm thao tác, lược đồ, trường tuỳ chọn)
- `contracts/openapi/README.md` (chỉ bảng "Bố cục" khi thêm tiền tố `/iam`)
- `frontend/projects/api-client/src/lib/generated/**` (chỉ sinh lại bằng `npm run api:gen`)
- `docs/phan-quyen/ma-tran.json`, `wiki/15a-ma-tran-phan-quyen.md` (chỉ khi phải thêm quyền API)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd contracts && npm ci && npm run lint && npm run bundle` không lỗi; `operationId` mới duy nhất toàn thư mục.
- `openapi-diff` từng tệp module trước và sau chỉ báo thay đổi tương thích; tóm tắt dán vào báo cáo.
- `cd frontend && npm run api:gen && npm run api:check && npx ng build fe-task && npx ng build fe-shell` chạy xanh.
- Thao tác `service_client` không xuất hiện trong danh sách tuyến công khai của cổng (DV-01-03 sinh lại không đổi phần công khai).
- Báo cáo có bảng "thao tác → task hiện thực"; báo cáo cuối task theo AGENTS.md mục 6; wiki trang 14 đã cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
