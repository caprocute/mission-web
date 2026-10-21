---
title: "Bổ sung BS-02: Hợp đồng API trước (API-first) – ADR-32"
subtitle: "Nền tảng đánh giá cán bộ theo OKR và KPI · Bổ sung cho TL-01, TL-02, TL-03 · Phiên bản 0.1 · 10/2026"
---

# Mục đích và phạm vi

Tài liệu này ghi quyết định kiến trúc ADR-32: chuyển toàn bộ API công khai của nền tảng sang hướng "hợp đồng trước" (API-first), trong đó bộ tệp OpenAPI 3.1 là nguồn sự thật của giao tiếp giữa frontend và backend. Tài liệu được viết thành tệp bổ sung riêng như BS-01, chưa ghép vào các tệp .docx đã phát hành, để không phải build lại bộ tài liệu. Khi bộ tài liệu được phát hành phiên bản tiếp theo, nội dung ở đây được đưa vào đúng vị trí nêu ở mục cuối.

Phần thứ hai của tài liệu là bảng các điểm mà TL-01 Phụ lục C, TL-02 (bảng sự kiện của từng SCR), TL-03 (cột và ràng buộc CHECK) và ma trận phân quyền ghi khác nhau. Khi viết hợp đồng, mỗi điểm đã được chọn một phương án mặc định có lý do. Theo quy tắc YC-TK-10 của sổ tay yêu cầu, các điểm này đã được đưa lên người phụ trách; ngày 19/10/2026 anh Hiếu chấp nhận toàn bộ phương án mặc định nêu trong tài liệu này. Việc sửa TL-03 theo các điểm "cần bổ sung" vẫn chờ lần phát hành tiếp theo của bộ tài liệu.

# ADR-32: Hợp đồng API trước cho toàn bộ API công khai

**Bối cảnh.** TL-01 mục "Hợp đồng API theo OpenAPI" chọn cho phần lõi hướng "mã trước, hợp đồng kiểm tra": springdoc sinh tài liệu từ annotation, CI so với tệp hợp đồng đã duyệt bằng `openapi-diff`. Chỉ API đối tác qua integration-gateway (SVC-13) theo hướng hợp đồng trước. Khi phân hệ web bắt đầu được phát triển song song bởi nhiều luồng (giao việc, OKR, đánh giá, giám sát, cấu hình) trong lúc backend chưa có mã, hướng "mã trước" để lại hai vấn đề. Thứ nhất, frontend không có hợp đồng để sinh kiểu và dữ liệu giả lập, nên mỗi luồng tự đặt đường dẫn và tên trường theo cách đọc riêng của mình. Thứ hai, những chỗ ba tài liệu TL ghi khác nhau về đường dẫn, tên cột hay giá trị liệt kê chỉ lộ ra khi hai phía ráp nối, tức là muộn và tốn kém.

**Quyết định.** Mọi API công khai dưới `/api/v1` (trừ API đối tác của SVC-13, vẫn giữ kho hợp đồng riêng) được mô tả trước bằng OpenAPI 3.1 trong thư mục `contracts/openapi/` của repo mission-web, mỗi module hoặc service một tệp, cộng một tệp thành phần dùng chung. Hợp đồng được viết và duyệt trước khi viết mã ở bất kỳ phía nào. Hai phía sinh mã từ hợp đồng:

- Frontend sinh kiểu TypeScript bằng openapi-typescript (chỉ sinh kiểu, không sinh mã chạy, phù hợp CSP của TL-01). Lệnh `api:check` trong CI báo lỗi khi tệp kiểu đã commit lệch với hợp đồng.
- Backend sinh interface controller và DTO bằng openapi-generator (Spring, chế độ `interfaceOnly`), controller cài đặt interface đó. springdoc vẫn được giữ, nhưng vai trò đổi thành công cụ đối chiếu ngược: CI chạy `openapi-diff` giữa tài liệu springdoc sinh lúc chạy và tệp hợp đồng đã gộp; mọi khác biệt làm build thất bại.

Mọi thay đổi API bắt đầu bằng một PR sửa tệp hợp đồng. Thay đổi phá vỡ tương thích phải đi kèm tăng phiên bản đường dẫn theo chính sách phiên bản hiện có của TL-01 (header `Deprecation`, `Sunset`). Hợp đồng được kiểm tra bằng Redocly CLI với bộ quy tắc ở `contracts/redocly.yaml`, gồm hai quy tắc riêng của dự án: mỗi thao tác phải có phần mở rộng `x-mission` để truy vết, và mỗi thao tác ghi phải khai `Idempotency-Key`.

**Phần mở rộng truy vết `x-mission`.** Mỗi thao tác mang `qt`, `scr`, `owner` (MOD hoặc SVC), `evt`, `roles` (tên vai trò của baseline mục 10), `scope` (ký hiệu phạm vi của ma trận phân quyền), `aal`, `fourEyes`, `rules` (BR) và `source` (dòng TL đã dùng). Nhờ vậy chuỗi truy vết QT → SCR → API → MOD/SVC → EVT mà AGENTS.md yêu cầu được kiểm tra tự động trên chính hợp đồng, và ma trận phân quyền có thể được đối chiếu với hợp đồng bằng công cụ thay vì đọc tay.

**Hệ quả.** Các luồng frontend dùng chung một bộ đường dẫn và tên trường; dữ liệu giả lập và kiểm thử hợp đồng sinh được từ `examples` trong tệp. Backend không còn tự do đặt tên trường khi viết controller, và mọi chỗ TL ghi khác nhau phải được chốt sớm, ngay khi viết hợp đồng. Chi phí là mỗi thay đổi API cần thêm một bước sửa hợp đồng và chờ duyệt. Quyết định này thay thế câu "Lõi theo hướng mã trước, hợp đồng kiểm tra" ở TL-01 mục "Hợp đồng API theo OpenAPI"; phần còn lại của mục đó (quy ước JSON, phân trang, idempotency, RFC 9457, chính sách phiên bản) giữ nguyên.

**Trạng thái.** Đã chấp nhận (anh Hiếu yêu cầu hướng API-first và chấp nhận toàn bộ phương án mặc định ngày 19/10/2026); đã có bộ hợp đồng bản nháp 1.0.0-draft.1 (WEB-063) gồm 11 tệp module, 351 thao tác, đều đạt lint.

# Bố cục bộ hợp đồng

| Tệp | Phạm vi | Tiền tố đường dẫn | Số thao tác |
|---|---|---|---|
| `common/common.yaml` | Lỗi RFC 9457, phân trang con trỏ, `Idempotency-Key`, `If-Match`, job 202, bảo mật Keycloak, kiểu dùng chung | – | – |
| `modules/tasks.yaml` | MOD-02: nhiệm vụ, kế hoạch quý, danh mục công việc, văn bản đến | `/tasks`, `/plans`, `/catalog` | 58 |
| `modules/org.yaml` | SVC-04: người dùng hiện tại, tra cứu, cây tổ chức, đồng bộ và đối soát | `/org` | 20 |
| `modules/jobs.yaml` | Tác vụ dài dùng chung | `/jobs` | 2 |
| `modules/okr.yaml` | MOD-01 | `/okr` | 23 |
| `modules/products.yaml` | MOD-03, SVC-08 | `/products`, `/evidences`, `/files` | 22 |
| `modules/appraisals.yaml` | MOD-04, SVC-07 (đọc điểm) | `/appraisals` | 24 |
| `modules/dossiers.yaml` | MOD-06, SVC-11, SVC-10 | `/dossiers`, `/esign` | 31 |
| `modules/oversight.yaml` | MOD-07, SVC-09 | `/oversight`, `/ledger` | 36 |
| `modules/policies.yaml` | MOD-05 | `/policies` | 34 |
| `modules/config.yaml` | MOD-08 | `/config`, `/periods`, `/ui` | 54 |
| `modules/platform.yaml` | SVC-03, SVC-06, SVC-12, SVC-14, SVC-15, SVC-16 | `/registry`, `/workflow`, `/notifications`, `/ai`, `/analytics`, `/search` | 47 |

# Các điểm đã chốt khi ghép nguồn

Các bảng dưới liệt kê từng chỗ nguồn ghi khác nhau, phương án hợp đồng đã chốt và lý do. Những giá trị liệt kê, đường dẫn hay trường mà nguồn không ghi và hợp đồng phải tự đặt được đánh dấu "(suy luận)" ngay trong tệp hợp đồng; bảng này chỉ nêu những điểm có ảnh hưởng đáng kể.

## Quy ước chung

| Mã | Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn | Lý do |
|---|---|---|---|---|
| HD-01 | Mã lỗi xung đột `If-Match` | Baseline mục 13, TL-02: 409; TL-01 chương 11: 412 | 409, mã `<MODULE>-409-VERSION-CONFLICT`; thiếu `If-Match` trả 428 | Baseline là nguồn chung; frontend đã xử lý 409 |
| HD-02 | Thời hạn giữ `Idempotency-Key` | TL-01: 24 giờ; TL-03: 48 giờ | 48 giờ | TL-03 chi tiết hơn, có lý do (ứng dụng di động gửi lại sau mất mạng) |
| HD-03 | Cập nhật tiến độ | TL-01 PL C: `PATCH /tasks/{id}/progress`; TL-02 (8 chỗ), mã: `POST` | `POST` | Mỗi lần cập nhật tạo một dòng `task.task_progress` chỉ ghi thêm |
| HD-04 | Danh sách "Việc của tôi" | PL C, SCR-0503: `GET /tasks?assignee=me`; ma trận: `/tasks/my`; mã: `/tasks/mine` | `GET /tasks/mine`; `GET /tasks` cho danh sách đơn vị | Hai danh sách trả lược đồ khác nhau |
| HD-05 | Tên vai trò | PL C: `staff`, `org_officer`, `policy_approver`; baseline mục 10 và ma trận: `member`, `appraiser`, `tenant_admin`, `decider` | Tên của baseline mục 10 | Baseline và realm Keycloak dùng các tên này |
| HD-06 | Lược đồ job | TL chưa đặc tả | Giữ lược đồ `Job` của WEB-007 | Đã có mã dùng, không mâu thuẫn TL |
| HD-07 | Mâu thuẫn còn lại của HD-05 | TL-03 CHECK `appraisal_*.actor_role` dùng `staff` | Giữ giá trị `staff` trong dữ liệu, `member` trong `x-mission.roles` | Không tự đổi CHECK của TL-03; cần chốt đổi TL-03 sang `member` |

## Giao việc, kế hoạch, danh mục (tasks.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Điểm chuẩn dòng kế hoạch | TL-02: `score_weight`; TL-03: `standard_score` | `standardScore` theo TL-03 |
| Tên sản phẩm dòng kế hoạch | TL-02 có `product_name`; TL-03 thiếu cột | `productName`; TL-03 cần bổ sung cột |
| Mở nháp để giao | Mã frontend: `GET /tasks/{id}?view=assign` | `GET /tasks/{id}/draft` (một đường dẫn trả một lược đồ) |
| Gia hạn | PL C: `/tasks/{id}/extensions`; TL-02: đề nghị điều chỉnh | `/tasks/{id}/change-requests` |
| Gửi duyệt kế hoạch | PL C: `/plans/{id}/submit` | `/plans/{id}/versions/{v}/submit` |
| Thêm mục danh mục | PL C: `POST /catalog/items` | `POST /catalog/{catalogId}/items` |
| Loại đề nghị điều chỉnh | TL-02 và TL-03 CHECK khác nhau | Theo TL-02 |

## Tổ chức và nhân sự (org.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Cây con | PL C: `/org/units/{id}/subtree`; TL-02: `/tree` | `/subtree` theo PL C |
| Trục ghi nhận | PL C: `knownAt`; TL-02: `recordedAsOf` | `recordedAsOf` |
| Loại thay đổi tổ chức | SCR-0103 có thành lập, đổi tên, hợp nhất; TL-03 `org_change_order.kind` thiếu các giá trị này | `THANH_LAP`, `DOI_TEN`, `SAP_NHAP`, `HOP_NHAT`, `CHIA_TACH`, `GIAI_THE` (suy luận); TL-03 cần bổ sung |
| Độ dài mã đơn vị | TL-02: 30 ký tự; TL-03: `varchar(50)` | 30 |
| Đồng bộ ngay | Ma trận TL-02b: `/org/sync` | `POST /org/sync-runs` (có danh sách lần chạy cùng tài nguyên) |

## OKR (okr.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Sửa mục tiêu | PL C: `PATCH /okr/objectives/{id}`; TL-02, ma trận: `PUT` | `PUT`, lưu cả O và KR |
| Liên kết OKR | TL-02: `PARENT`/`PEER`; TL-03: `PHAN_RA`/`DONG_GOP`/`NGANG`/`CAN_CU` | Theo TL-03 |
| Thang trọng số và tiến độ | TL-03 0..1; nhiệm vụ trong mã frontend 0..100 | OKR 0..1, nhiệm vụ 0..100; cần chốt thống nhất |
| Người phụ trách KR | SCR-0002 cần; TL-03 thiếu `okr.key_result.owner_id` | Có trường (suy luận); TL-03 cần bổ sung |
| Sự kiện khi gửi OKR | TL-02a: không phát; TL-02b: EVT-21 | Không phát |
| Chưa phủ | Điều chỉnh OKR giữa kỳ (BR-02-10), đóng mục tiêu, check-in thay, tạo căn cứ | Chờ TL-02 đặc tả API |

## Sản phẩm và minh chứng (products.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Đường dẫn nghiệm thu | PL C: `/products/{id}/evidence-versions`, `/acceptance`; TL-02: `/evidences/{id}/versions/{v}/...` | Theo TL-02 |
| Thời hạn URL tải | TL-01: 5 phút; TL-03: 15 phút | 5 phút |
| Độ tươi AAL2 khi nghiệm thu | BR-07-11: 15 phút; TL-01 `auth_time`: 5 phút | 5 phút; cần chốt |
| Tải nội dung | Ma trận SCR-1603: `/evidences/{id}/versions/{v}/content` | Gộp về `/files/{objectId}/download-url` |
| Tải tệp lên | Ma trận SCR-1201: `POST /files` | `POST /files/uploads` |
| Phân loại tên tệp | TL-03 ghi C1 ở chỗ này, C2 ở chỗ khác | C2 |
| Số tệp mỗi phiên bản minh chứng | TL-02 SCR-0701 (Hình 8.13): bảng nhiều tệp trong một phiên bản, cảnh báo trùng theo từng tệp; TL-03 `product.evidence_version`: một `object_id` | Nhiều tệp (`files[]`, tối đa 20, suy luận); quét và cờ trùng ở cấp phiên bản là tổng hợp. Bổ sung 19/10/2026 theo phát hiện của WEB-032 |
| Nguyên nhân chỉnh sửa | TL-03 `revision_cause`: 5 giá trị theo mức chỉnh sửa; TL-02 SCR-0703 trường 4: Chủ quan, Khách quan, Không có; hợp đồng bản đầu dùng hai bộ ở hai module | Một enum dùng chung `RevisionCause` (common, theo TL-03) cộng cờ `isObjectiveRevision` cho chủ quan, khách quan, ở cả nghiệm thu và xem trước điểm. Bổ sung 19/10/2026 theo WEB-033 |
| Trình xem minh chứng theo trang | TL-02 SCR-0703 Hình 8.15 vẽ trình xem "trang 1 trên 6"; TL chưa có API, hợp đồng bản đầu chỉ có URL tải | Thêm `GET /files/{objectId}/preview` trả ảnh từng trang do SVC-08 dựng (suy luận); tệp C3 trả `KHONG_HO_TRO` và giải mã tại trình duyệt. Bổ sung 19/10/2026 theo WEB-033 |

## Đánh giá (appraisals.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Kiểu đường dẫn | PL C: `/appraisals/{id}/...`, `/appraisals/decisions/batch`; TL-02b: `/appraisals/profiles/{id}/...`, `/appraisals/batch-decisions` | Theo TL-02b |
| Quyết định một hồ sơ | PL C: `POST /appraisals/{id}/decision` | Gộp vào `batch-decisions` (lô một hồ sơ) |
| Hồ sơ của tôi | PL C: `GET /appraisals?period=&flow=` | `GET /appraisals/profiles/mine` |
| Mức xếp loại | TL-02b `level`; TL-03 `rating_code` | `ratingCode` |
| Tỉ lệ vượt | TL-02b `over_target_ratio`; TL-03 `exceed_ratio` | `exceedRatio` |
| Bước 9 QT-10 | Không có API trong TL | Chưa phủ |

## Hồ sơ số và ký số (dossiers.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Ký số | PL C: `/esign/sessions`; TL-02 (4 chỗ), TL-03: `sign_request` | `/esign/requests` |
| Gói hồ sơ | TL-02b: một gói cho mỗi đơn vị và kỳ; TL-03: `dossier_package.person_id NOT NULL` | Theo TL-02b; TL-03 cần sửa |
| Mục đích ký | TL-03 `sign_request.purpose` thiếu giá trị cho duyệt kế hoạch (SCR-0403) và trả lời khiếu nại (SCR-1202) | Giữ enum TL-03; TL-03 cần bổ sung |
| Hạn nộp | TL-02 `due_date`; TL-03 `deadline` | `deadline` |
| Lập gói | PL C: `POST /dossiers/{id}/packages` | `POST /dossiers/packages` |

## Giám sát và sổ cái (oversight.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Cảnh báo | PL C: `/oversight/anomalies`; TL-02b (3 chỗ): `/oversight/alerts` | `/oversight/alerts`, `/alerts/{id}/actions` |
| Trả lời khiếu nại | PL C: `POST /complaints/{id}/responses`; TL-02b: `/respond` (202) | `/respond`; tách thêm `PUT /conclusion` (suy luận) |
| Duyệt mở lại kỳ | PL C: `/approve`; TL-02b: `/approvals` | `/approvals` |
| Hành động cảnh báo | TL-03 thiếu `KET_LUAN` mà TL-02b dùng | Thêm `KET_LUAN`; TL-03 cần bổ sung |
| Khiếu nại nháp | TL-02b có nháp; TL-03 không có `NHAP`, `filed_at NOT NULL` | `status: null` khi chưa gửi; cần chốt |

## Policy Pack (policies.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Tiền tố | PL C: `/policies/{id}/versions`; TL-02b: `/policies/packs/{id}/versions/{v}` | Theo TL-02b |
| Phát hành | PL C: `/publish`, vai trò `policy_approver`; TL-02b: `/approve` | `/approve` (202), vai trò `decider` |
| Mã phiên bản | TL-02b `v2.1`; TL-03 CHECK semver | Semver ba phần `2.1.0` |
| Trạng thái mẫu luồng | TL-03: 3 giá trị; BS-01: 5 giá trị | Theo BS-01 |

## Cấu hình và kỳ (config.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Người khởi động khoá kỳ | PL C: `org_officer`; SCR-0801, ma trận: AC-02 | `tenant_admin` |
| Mô hình thuê bao | `registry.tenant` chữ hoa, có `AIRGAP`; `tenant_cell`, TL-02a chữ thường | Chữ thường ở `/config`; `/registry` giữ chữ hoa theo `registry.tenant` |
| Giao thức IdP | TL-02a nhắc LDAP liên kết; TL-03 CHECK chỉ `OIDC`, `SAML` | Theo TL-03 |
| Cột trùng nghĩa | `ui_pack_version` có `tokens`/`tokens_json`; `form_schema` có `form_code`/`form_key` | Một trường cho mỗi cặp; TL-03 cần gỡ cột thừa |

## Dịch vụ nền (platform.yaml)

| Chủ đề | Nguồn ghi khác nhau | Hợp đồng chọn |
|---|---|---|
| Quyết định gợi ý AI | SCR-1701, ma trận: `/accept`, `/dismiss`; SCR-0502, mã: `/decision` | `POST /ai/suggestions/{id}/decision` |
| Dữ liệu bảng điều hành | PL C: `GET /analytics/dashboards/{id}`; SCR-1601: `POST /analytics/query` | Giữ cả hai (định nghĩa kèm dữ liệu, và truy vấn tự do) |
| Mẫu luồng (BS-01) | Có thể đặt dưới `/policies` hoặc `/workflow` | `/workflow/templates` cho SCR-1505; `/policies/workflow-templates` chỉ đọc cho SCR-1504 |

# Những thay đổi cần đưa vào TL ở phiên bản sau

Khi phát hành phiên bản tiếp theo của bộ tài liệu, TL-01 mục "Hợp đồng API theo OpenAPI" thay câu về hướng "mã trước" bằng tóm tắt ADR-32 và thêm ADR-32 vào bảng ADR; Phụ lục C trỏ sang bộ hợp đồng thay vì liệt kê đường dẫn. TL-02 thay các đường dẫn trong bảng sự kiện SCR bằng `operationId` tương ứng. TL-03 sửa các điểm đã nêu "cần bổ sung" hoặc "cần sửa" ở trên: bảng con `product.evidence_version_file` cho nhiều tệp mỗi phiên bản minh chứng, cột `plan_item.product_name`, `okr.key_result.owner_id`, giá trị liệt kê của `org_change_order.kind`, `sign_request.purpose`, `alert_action`, quan hệ `dossier_package` theo đơn vị, và các cột trùng nghĩa của `config`.
