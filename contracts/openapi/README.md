# Hợp đồng OpenAPI 3.1 của nền tảng Mission

> Nguồn: ADR-32 (bổ sung BS-02 `kien-truc-okr-kpi/bo-sung/BS-02-hop-dong-API-truoc.md`); baseline mục 9, 9.1, 10, 13; TL-01 mục "Hợp đồng API theo OpenAPI", chương 11 (idempotency, RFC 9457), Phụ lục C; TL-02a, TL-02b (bảng sự kiện của từng SCR); TL-03 (bảng, cột, ràng buộc CHECK); ma trận phân quyền `docs/phan-quyen/ma-tran.json`.

Thư mục này là **nguồn sự thật của giao tiếp giữa frontend và backend** theo hướng hợp đồng trước (API-first, ADR-32). Frontend sinh kiểu TypeScript từ đây (`frontend/tools/openapi/generate.mjs`); backend sinh interface controller và DTO bằng openapi-generator (Spring, `interfaceOnly`), còn springdoc của backend được `openapi-diff` đối chiếu ngược với các tệp ở đây trong CI. Mọi thay đổi API bắt đầu bằng một PR sửa tệp hợp đồng, sau đó mới sửa mã hai phía.

## Bố cục

| Tệp | Phạm vi | Tiền tố đường dẫn | Đợt |
|---|---|---|---|
| `common/common.yaml` | Lỗi RFC 9457, phân trang, `Idempotency-Key`, `If-Match`, job 202, bảo mật Keycloak, kiểu dùng chung | – | 1 |
| `modules/tasks.yaml` | MOD-02 `task`: nhiệm vụ, kế hoạch quý, danh mục công việc, văn bản đến | `/tasks`, `/plans`, `/catalog` | 1 |
| `modules/org.yaml` | SVC-04 org-service: hồ sơ người dùng, phân công, cây đơn vị, đối soát | `/org` | 1 |
| `modules/jobs.yaml` | Tác vụ dài dùng chung | `/jobs` | 1 |
| `modules/okr.yaml` | MOD-01 `okr` | `/okr` | 2 |
| `modules/products.yaml` | MOD-03 `product` và SVC-08 evidence-service | `/products`, `/evidences`, `/files` | 2 |
| `modules/appraisals.yaml` | MOD-04 `appraisal` (SVC-07 phần đọc điểm) | `/appraisals` | 2 |
| `modules/dossiers.yaml` | MOD-06 `dossier`, SVC-11, SVC-10 | `/dossiers`, `/esign` | 2 |
| `modules/oversight.yaml` | MOD-07 `oversight`, SVC-09 | `/oversight`, `/ledger` | 3 |
| `modules/policies.yaml` | MOD-05 `policy` | `/policies` | 3 |
| `modules/config.yaml` | MOD-08 `config` | `/config`, `/periods`, `/ui` | 3 |
| `modules/platform.yaml` | SVC-03, SVC-06, SVC-12, SVC-14, SVC-15, SVC-16 | `/registry`, `/workflow`, `/notifications`, `/ai`, `/analytics`, `/search` | 3 |

Mỗi tệp module là một tài liệu OpenAPI 3.1 đầy đủ (`servers: [{url: /api/v1}]`), tham chiếu thành phần dùng chung bằng `$ref: '../common/common.yaml#/components/...'`. API đối tác qua SVC-13 (integration-gateway) không nằm ở đây; theo TL-01 nó đã theo hướng hợp đồng trước và có kho riêng.

## Quy ước bắt buộc khi viết hợp đồng

**Đường dẫn và phương thức.** Đường dẫn tương đối với `/api/v1`, chữ thường, nối bằng gạch ngang, danh từ số nhiều. Hành động nghiệp vụ không phải CRUD là `POST /<tài nguyên>/{id}/<động-từ>` (ví dụ `/tasks/{id}/accept`). Khi TL-01 Phụ lục C và bảng sự kiện SCR của TL-02 ghi khác nhau, hợp đồng chọn một bên và ghi lý do ở bảng "Điểm đã chốt" dưới đây; không tạo hai đường dẫn cho cùng một việc.

**Định danh và tên trường.** `operationId` dạng camelCase động từ trước (`listMyTasks`, `assignTask`), duy nhất trên toàn bộ thư mục vì frontend sinh kiểu vào chung một không gian. Tên lược đồ PascalCase tiếng Anh, duy nhất toàn thư mục (thêm tiền tố module khi dễ trùng, ví dụ `PlanItem`, `CatalogItem`). Tên trường JSON camelCase tiếng Anh, ánh xạ từ cột TL-03 (`due_date` → `dueDate`). Giá trị liệt kê giữ nguyên mã tiếng Việt viết hoa của baseline và TL-03 (`DA_GIAO`, `CHU_TRI`); không dịch, không đặt giá trị mới khi TL đã có.

**Ghi.** Mọi `POST`, `PUT`, `PATCH`, `DELETE` khai tham số `IdempotencyKey`. Mọi thao tác sửa hoặc chuyển trạng thái một đối tượng đã có khai thêm `IfMatch`, trả header `ETag` khi thành công, và khai phản hồi `409` (`Conflict`) và `428` (`PreconditionRequired`). Thao tác chạy lâu hơn khoảng 2 giây hoặc xử lý lô trả `202` với `$ref: '../common/common.yaml#/components/responses/Accepted'`.

**Đọc.** Danh sách dùng `Limit` và `Cursor`, trả đối tượng có `items` và `nextCursor` (`NextCursor`). Không có phân trang theo số trang hay tổng số bản ghi, trừ khi SCR ghi rõ cần số đếm (khi đó trả trường đếm riêng như `counts`). Đọc một đối tượng có thể sửa phải trả `ETag`.

**Lỗi.** Mọi thao tác có `default: { $ref: '../common/common.yaml#/components/responses/Problem' }`, cộng các mã cụ thể mà thao tác có nghĩa riêng: `401`, `403`, `404`, `409`, `422`, `428`. Mã lỗi nghiệp vụ dạng `<MODULE>-<HTTP>-<TÊN>` (`TASK-409-PERIOD-LOCKED`) được liệt kê trong `description` của phản hồi và ánh xạ sang MSG của TL-02 ở trường `msg` của Problem Details.

**Truy vết và phân quyền.** Mỗi thao tác có phần mở rộng `x-mission`:

```yaml
x-mission:
  qt: [QT-05]                 # quy trình
  scr: [SCR-0501]             # màn hình dùng thao tác này
  owner: MOD-02               # module hoặc service sở hữu
  evt: [EVT-04, EVT-21]       # sự kiện phát ra (nếu có)
  roles: [manager, leader]    # vai trò baseline mục 10 được phép; khớp ma trận docs/phan-quyen
  scope: Đv                   # phạm vi theo ký hiệu của ma trận (B, Gn, Đv, ĐvD, Tn, N)
  aal: 2                      # có mặt khi cần xác thực bước lên AAL2
  fourEyes: true              # có mặt khi cần người duyệt thứ hai khác người đề nghị
  rules: [BR-05-01, BR-05-06] # quy tắc nghiệp vụ máy chủ kiểm
  source: TL-02a SCR-0501; TL-01 PL C
```

Vai trò dùng tên của baseline mục 10 (`member`, không dùng `staff` của TL-01 Phụ lục C; xem bảng dưới). Máy chủ vẫn là nơi quyết định quyền cuối cùng qua OPA; `roles` chỉ để frontend và kiểm thử hợp đồng đối chiếu.

**Ví dụ.** Lược đồ chính và phản hồi thành công có `examples` bằng dữ liệu tiếng Việt hợp lý (không dữ liệu cá nhân thật), để dữ liệu giả lập và kiểm thử hợp đồng sinh được từ chính tệp này.

**Phân loại dữ liệu.** Trường chứa dữ liệu C2 ghi `x-classification: C2` trong lược đồ; trường C3 không bao giờ xuất hiện dạng rõ trong hợp đồng (chỉ bản mã và mã tham chiếu, ADR-06).

## Điểm đã chốt khi ghép nguồn

Các điểm dưới đây là chỗ các tài liệu thiết kế ghi khác nhau. Hợp đồng chọn phương án mặc định, ghi lý do, và các điểm này đang chờ anh Hiếu xác nhận theo YC-TK-10 (bảng dưới chỉ gồm quy ước chung; các điểm riêng của từng module nằm ở BS-02 mục "Các điểm đã chốt khi ghép nguồn", và khối chú thích đầu mỗi tệp module).

| Mã | Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn | Lý do |
|---|---|---|---|---|
| HD-01 | Mã lỗi xung đột `If-Match` | Baseline mục 13, TL-02: 409; TL-01 chương 11: 412 | 409, `code` `<MODULE>-409-VERSION-CONFLICT`, `type` `.../concurrent-modification`; thiếu `If-Match` trả 428 | Baseline là nguồn chung của bộ tài liệu; frontend đã xử lý 409 |
| HD-02 | Thời hạn giữ `Idempotency-Key` | TL-01: 24 giờ; TL-03: 48 giờ | 48 giờ | TL-03 chi tiết hơn và có lý do (ứng dụng di động gửi lại sau mất mạng) |
| HD-03 | Cập nhật tiến độ | TL-01 mục 4 và Phụ lục C: `PATCH /tasks/{id}/progress`; TL-02 (8 chỗ) và mã: `POST` | `POST /tasks/{id}/progress` | Mỗi lần cập nhật tạo một dòng `task.task_progress` chỉ ghi thêm, đúng ngữ nghĩa POST |
| HD-04 | Danh sách "Việc của tôi" | TL-01 PL C, TL-02 SCR-0503: `GET /tasks?assignee=me`; ma trận TL-02: `/tasks/my`; mã: `/tasks/mine` | `GET /tasks/mine`; `GET /tasks` dành cho danh sách theo đơn vị | Hai danh sách có lược đồ trả khác nhau (Việc của tôi có số đếm bộ lọc nhanh); OPA không phải kiểm phạm vi đơn vị cho `/tasks/mine` |
| HD-05 | Tên vai trò | TL-01 PL C: `staff`; baseline mục 10 và ma trận: `member` | `member` | Baseline và realm Keycloak dev dùng `member` |
| HD-06 | Lược đồ job | TL chưa đặc tả | Giữ lược đồ `Job` của WEB-007 | Đã có mã dùng, không mâu thuẫn TL |
| HD-07 | Giá trị `actor_role` trong dữ liệu đánh giá | TL-03 CHECK `appraisal_*.actor_role`: `staff`; baseline mục 10: `member` | Giữ `staff` trong dữ liệu (đúng CHECK), `member` trong `x-mission.roles` | Không tự đổi CHECK của TL-03; đề nghị TL-03 đổi sang `member` |

## Kiểm tra

```bash
cd contracts && npm ci
npm run lint        # Redocly: cú pháp OpenAPI 3.1, $ref, operationId duy nhất, quy tắc riêng ở redocly.yaml
npm run bundle      # gộp mỗi module thành một tệp không còn $ref ngoài, ra dist/ (không commit)
```
