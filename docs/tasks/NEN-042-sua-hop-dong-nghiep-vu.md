# NEN-042 · ADR-32/YC-PT-09 Sửa hợp đồng OpenAPI phần nghiệp vụ: thêm thao tác còn thiếu và sửa mô tả

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh NEN-042` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c (hợp đồng phải gộp trước khi các task CORE liên quan viết mã) |
| Luồng đề xuất | B1 |
| Cỡ việc | M (gợi ý tách nếu quá lớn: (a) `okr.yaml` và `tasks.yaml`; (b) E2EE trên `org.yaml`, `tasks.yaml`, `products.yaml`; (c) `dossiers.yaml` và `oversight.yaml`) |
| Phụ thuộc | NEN-001; người phụ trách hợp đồng duyệt từng thao tác trước khi gộp |
| Truy vết | ADR-32, YC-PT-09 → `contracts/openapi/modules/okr.yaml` (MOD-01), `tasks.yaml` (MOD-02), `products.yaml` (MOD-03), `org.yaml` (SVC-04), `dossiers.yaml` (MOD-06), `oversight.yaml` (MOD-07) → QT-02, QT-03, QT-04, QT-05, QT-06, QT-11, QT-12; BR-02-10, BR-04-05, BR-04-13, BR-04-14, BR-05-08, BR-11-05, BR-12-11; ADR-27 |
| Trạng thái | Chờ người dùng chốt (người phụ trách hợp đồng duyệt danh sách thao tác) |

## 1. Mục tiêu

Khi viết thẻ các task CORE, nhiều bước nghiệp vụ mà TL-02 đã đặc tả lại không có thao tác tương ứng trong hợp đồng OpenAPI, nên theo nguyên tắc API-first các task đó không được mở endpoint. Task này gom toàn bộ phần thiếu của nhóm nghiệp vụ vào một lần sửa hợp đồng có duyệt: chỉ thêm thao tác và trường tuỳ chọn, không đổi hay xoá thao tác cũ, chứng minh bằng `openapi-diff` là không phá tương thích, và sinh lại kiểu TypeScript cho frontend. Khi xong, các task CORE-01-06, CORE-01-07, CORE-01-12, CORE-02-03, CORE-02-09, CORE-02-10, CORE-02-11, CORE-02-17, CORE-02-20, CORE-02-24, CORE-07-06, CORE-06-05, CORE-07-05 có đường dẫn hợp đồng để hiện thực thay cho các "đề nghị bổ sung" đang ghi trong thẻ.

## 2. Đầu vào bắt buộc đọc

- `contracts/openapi/README.md` mục "Quy ước bắt buộc khi viết hợp đồng" (đường dẫn `POST /<tài nguyên>/{id}/<động-từ>`, `operationId` duy nhất, `IdempotencyKey`, `IfMatch` + `409`/`428`, `x-mission`), mục "Kiểm tra"; `contracts/redocly.yaml`.
- `docs/quy-dinh/BACKEND.md` đoạn API-first (YC-PT-09: hợp đồng sai thì mở task sửa riêng và chạy `openapi-diff`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`: QT-02 bảng trạng thái (L798–813, dòng huỷ L813; điều chỉnh giữa kỳ L804, L812), ngoại lệ L836; QT-03 ngoại lệ L1063 (check-in thay), L1065; QT-04 BR-04-05, BR-04-13, BR-04-14 (L1211–1230), ngoại lệ L1237 (thêm một lần điều chỉnh), L1239 (đơn vị chủ trì khác xác nhận); QT-05 bước 3 L1428 (duyệt hoặc từ chối đăng ký); QT-06 bước 4 L1700 (chuyển cấp trên).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md`: QT-11 BR-11-05 và bảng 13 trường hợp (L579), SCR-1102 (L675); QT-12 BR-12-11 (L816), SCR-1202 (L879).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Mã hoá đầu cuối cho dữ liệu C3" (L2309–2335: danh bạ khoá, chia sẻ bằng bọc lại CEK L2327, phục hồi L2331); TL-03 `org.person_public_key` (L1196), `task.task_secret_content` (L2387), `task.e2ee_key_wrap` (L2401), `product.e2ee_key_wrap` (L2603), `dossier.special_case` (L3059).
- Các thẻ đã ghi điểm thiếu: `docs/tasks/CORE-01-06-*.md`, `CORE-01-07-*.md`, `CORE-01-12-*.md`, `CORE-02-03-*.md`, `CORE-02-09-*.md`, `CORE-02-10-*.md`, `CORE-02-11-*.md`, `CORE-02-17-*.md`, `CORE-02-20-*.md`, `CORE-06-05-*.md`, `CORE-07-03-*.md`, `CORE-07-05-*.md`, `CORE-07-06-*.md`.
- `wiki/15a-ma-tran-phan-quyen.md` (vai trò của từng SCR) và `docs/phan-quyen/ma-tran.json` mục `api`.

## 3. Phạm vi công việc

Tên đường dẫn và `operationId` dưới đây là đề xuất theo quy ước của README hợp đồng; được đổi tên khi người phụ trách duyệt, nhưng không được bỏ thao tác nào mà không ghi lý do trong báo cáo.

- `okr.yaml`: thêm `POST /okr/objectives/{id}/cancel` (`cancelObjective`, `DA_DUYET`/`CHO_DUYET` → `DONG`, lý do bắt buộc, `IfMatch`, roles `leader`, nguồn TL-02a L813); thêm `POST /okr/objectives/{id}/revisions` (`createObjectiveRevision`, tạo phiên bản điều chỉnh từ mục tiêu `DA_DUYET`, BR-02-10, nguồn L804, L812, L836) và sửa mô tả `versionNo` cho đúng nghĩa "phiên bản mục tiêu"; thêm `PUT` và `DELETE /okr/key-results/{id}/checkin-delegate` (`setKrCheckinDelegate`, `revokeKrCheckinDelegate`, roles `leader`, có hạn hiệu lực, nguồn L1063) khớp trường `checkinDelegate` đã có.
- `tasks.yaml` phần danh mục và kế hoạch: thêm `POST /catalog/{catalogId}/activate` (`activateCatalogVersion`, `NHAP` → `AP_DUNG`, phiên bản đang áp dụng chuyển `HET_HIEU_LUC`, kỳ bắt đầu áp dụng, BR-04-13, BR-04-14); thêm `PUT /plans/{id}/versions/{v}/items/{itemId}/score` (`scoreOffCatalogPlanItem`, roles `leader`, người duyệt chấm việc ngoài danh mục trước `approvePlanVersion`, BR-04-05); thêm `POST /plans/{id}/adjustment-exceptions` (`grantPlanAdjustmentException`, người đứng đầu cho thêm một lần điều chỉnh có lý do, L1237, ghi vết); thêm `POST /plans/items/{itemId}/lead-confirmation` (`confirmPlanItemLead`, đơn vị chủ trì xác nhận hoặc từ chối nhận dòng, L1239).
- `tasks.yaml` phần nhiệm vụ: thêm `POST /tasks/{id}/registration-decision` (`decideTaskRegistration`, `APPROVE`/`REJECT` kèm lý do khi từ chối, roles `manager`, MSG-05-12, BR-05-08, L1428); thêm `POST /tasks/{id}/blockers/{blockerId}/escalate` (`escalateTaskBlocker`, chuyển điểm nghẽn lên cấp trên, phát EVT-21, roles `manager`, `leader`, L1700) thay cho cách "đổi `handlerPersonId`" mà CORE-02-20 đang tạm dùng.
- E2EE C3 (ADR-27), `org.yaml` (owner SVC-04): `POST /org/me/public-keys` (`registerMyPublicKey`, SPKI, `keyAlg`, `source`, AAL2), `GET /org/persons/{id}/public-keys` (`listPersonPublicKeys`, chỉ khoá `HIEU_LUC`, kèm chữ ký danh bạ), `POST /org/me/public-keys/{fingerprint}/revoke` (`revokeMyPublicKey`), `GET /org/e2ee/recovery-key` (`getTenantRecoveryPublicKey`); không có trường khoá riêng nào.
- E2EE C3, `tasks.yaml` và `products.yaml`: `PUT`/`GET /tasks/{id}/secret-content` (`saveTaskSecretContent`, `getTaskSecretContent`: bản mã, `nonce`, `contentAlg`, `aadHash`, `titleBidx`, khoá đã bọc của chính người gọi), `POST /tasks/{id}/secret-content/key-wraps` (`shareTaskSecretContent`, danh sách CEK bọc lại cho người nhận mới), `POST /tasks/{id}/secret-content/key-wraps/{fingerprint}/revoke` (`revokeTaskSecretShare`); tương tự `POST /evidences/{id}/versions/{v}/key-wraps` (`shareEvidenceKey`) và `.../key-wraps/{fingerprint}/revoke` (`revokeEvidenceKeyShare`) trong `products.yaml`. Bản mã ghi `x-classification: C3`, không có trường rõ.
- `dossiers.yaml`: thêm `POST /dossiers/special-cases` (`createDossierSpecialCase`, ghi trường hợp đặc thù thủ công, `detectedBy = THU_CONG`, roles `appraiser`) và `POST /dossiers/special-cases/{id}/confirm` (`confirmDossierSpecialCase`, ghi `confirmed_by`, roles `decider`, BR-11-05).
- `oversight.yaml`: thêm `GET /oversight/complaints/{id}/duplicate-candidates` (`listComplaintDuplicateCandidates`, gợi ý khiếu nại cùng nội dung đã trả lời, BR-12-11, roles `appraiser`); kiểm lại mô tả `acceptComplaint`. Điểm lệch cần lưu ý: CORE-07-03 ghi mô tả `acceptComplaint` chép nhầm của `rejectComplaint`, nhưng ở bản hợp đồng hiện tại mô tả đã là "Chuyển `MOI` sang `DA_TIEP_NHAN`"; task đối chiếu lại toàn bộ thao tác (mô tả, phản hồi, `x-mission`) với TL-02b SCR-1202, chỉ sửa nếu còn sai, và ghi kết quả đối chiếu vào báo cáo.
- Mỗi thao tác mới có đủ `IdempotencyKey` (ghi), `IfMatch` + `409`/`428` (chuyển trạng thái), `default: Problem`, mã lỗi `<MODULE>-<HTTP>-<TÊN>`, `examples` tiếng Việt và `x-mission` (`qt`, `scr`, `owner`, `evt`, `roles`, `scope`, `aal`, `rules`, `source` trỏ đúng dòng TL); vai trò khớp ma trận `wiki/15a`, nếu phải thêm động từ HTTP mới cho một vai trò ở `docs/phan-quyen/ma-tran.json` mục `api` thì ghi rõ và sinh lại trang 15a.
- Ghi mỗi thao tác đã thêm vào bảng điểm lệch của `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (chuyển từ "câu hỏi mở" sang "đã chốt"), kèm mã task CORE sẽ hiện thực.

## 4. Ngoài phạm vi

- Hiện thực máy chủ của các thao tác (các task CORE nêu ở mục 1) và màn hình (WEB).
- Thao tác phần nền tảng (cờ tính năng, thông báo đẩy, MFA, `/org/assignments`, Superset, kiểm quyền theo lô, ký sổ cái, OCR, chuyển cell): NEN-043.
- Thao tác nội bộ cho `delay_risk` (CORE-02-26) và `dashboard_def` (CORE-08-17): mỗi task tự thêm có duyệt.

## 5. Tệp được phép sửa

- `contracts/openapi/modules/okr.yaml`, `tasks.yaml`, `products.yaml`, `org.yaml`, `dossiers.yaml`, `oversight.yaml` (chỉ thêm thao tác, lược đồ, trường tuỳ chọn; sửa mô tả)
- `frontend/projects/api-client/src/lib/generated/**` (chỉ sinh lại bằng `npm run api:gen`)
- `docs/phan-quyen/ma-tran.json`, `wiki/15a-ma-tran-phan-quyen.md` (chỉ khi phải thêm quyền API, sinh bằng `gen_matrix.py`)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd contracts && npm ci && npm run lint && npm run bundle` không lỗi, không cảnh báo mới.
- `openapi-diff` giữa bản bundle trước và sau sửa cho từng tệp module chỉ báo thay đổi tương thích (thêm thao tác, thêm trường tuỳ chọn); dán tóm tắt vào báo cáo.
- `cd frontend && npm run api:gen && npm run api:check && npx ng build fe-task && npx ng build fe-shell` chạy xanh với kiểu đã sinh lại.
- Mỗi thao tác trong mục 3 có mặt hoặc có lý do bỏ được người phụ trách hợp đồng xác nhận; báo cáo liệt kê bảng "thao tác → task CORE hiện thực".
- Báo cáo cuối task theo AGENTS.md mục 6; wiki trang 14 đã cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
