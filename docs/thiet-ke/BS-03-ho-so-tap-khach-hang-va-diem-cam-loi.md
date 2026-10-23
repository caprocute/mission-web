---
title: "Bổ sung BS-03: Hồ sơ tập khách hàng và điểm cắm của lõi chấm điểm – ADR-33"
subtitle: "Nền tảng đánh giá cán bộ theo OKR và KPI · Bổ sung cho TL-01, TL-03 và bộ hợp đồng OpenAPI · Phiên bản 0.1 · 22/10/2026"
---

# Mục đích và phạm vi

Tài liệu này ghi quyết định kiến trúc ADR-33. Quyết định đưa vào Mission khái niệm **hồ sơ tập khách hàng**, tức một bộ cấu hình có phiên bản gom mọi khác biệt về cách chấm điểm và quy tắc nghiệp vụ của một tập khách hàng. Tài liệu cũng đặc tả sáu **điểm cắm** (DC-01 đến DC-06) mà lõi phải bổ sung để các hồ sơ có đủ dữ liệu thô mà biểu diễn những cách chấm khác nhau.

Bối cảnh trực tiếp là bộ giải pháp KPI của UEB cho Ban Tổ chức Trung ương (UEB-BTCTW-QTCV-01..05, v1.2, 10/2026). Báo cáo phân tích nằm ở `/mnt/project-files/phan-tich-ueb/phan-tich-ueb-hap-thu-vao-mission.md`. Ngày 22/10/2026, anh Hiếu chấp thuận cả bốn phương án khuyến nghị của báo cáo, kèm một điều kiện bắt buộc. Nghiệp vụ UEB đi vào Mission thành **một bộ hồ sơ riêng cho TWĐ, cấu hình được, không hợp nhất cứng vào lõi**. Quyết định này hiện thực quy tắc YC-TK-11 của sổ tay yêu cầu.

Giống BS-01 và BS-02, tài liệu được viết thành tệp bổ sung riêng và chưa ghép vào các tệp .docx đã phát hành. Mục cuối nêu vị trí đưa vào TL khi bộ tài liệu được phát hành phiên bản tiếp theo. Lớp cấp trên giữa các tenant (B7 trong báo cáo) không thuộc BS-03, vì cần thiết kế riêng và đợt thí điểm chưa cần.

# ADR-33: Hồ sơ tập khách hàng trên một lõi chung

**Bối cảnh.** Policy Pack ở TL-01 đã tách công thức và bảng xếp loại ra khỏi mã. Tuy vậy, Policy Pack chỉ phân biệt theo hai luồng `DANG` và `NHA_NUOC`. Đầu vào (`inputs`) của gói lại là một tập biến đếm cố định, gồm số việc được duyệt, kế hoạch, mức chất lượng trung bình và số ngày chậm. Khi đối chiếu với giải pháp UEB, có sáu nhóm nghiệp vụ không biểu diễn được bằng cấu hình hiện có. Cụ thể là:

- điểm công việc theo hệ số;
- điểm quản lý theo phạm vi phụ trách;
- kiểu đo mốc;
- kế thừa tham số có khung giới hạn;
- chuỗi xếp loại nhiều bước;
- chế độ "mỗi công việc một người".

Nguyên nhân không nằm ở công thức. Lõi chưa cung cấp đủ dữ liệu thô cho công thức dùng. Nếu viết thẳng cách chấm của Ban Tổ chức vào mã, Mission sẽ mất khả năng phục vụ các tập khách hàng khác như khối Nhà nước, doanh nghiệp nhà nước và các tỉnh.

**Quyết định.** Mission tách ba lớp.

- **Lớp lõi** giữ nguyên kiến trúc đã chốt. Lõi chỉ cung cấp **sự kiện và đại lượng thô**: việc nào, điểm chuẩn bao nhiêu, *mức* của từng loại hệ số, mức chất lượng, số ngày chậm, nguyên nhân chậm, ai phụ trách mục nào. Lõi không chứa giá trị hệ số, công thức hay ngưỡng của bất kỳ khách hàng nào.
- **Lớp cấu hình** gồm Policy Pack, danh mục có kế thừa, mẫu luồng Flowable kèm tham số (BS-01), bộ quy tắc cảnh báo và UI Pack. Mọi thành phần của lớp này có phiên bản và hiệu lực theo kỳ.
- **Lớp hồ sơ tập khách hàng** là một bản kê có phiên bản, trỏ tới đúng phiên bản của từng thành phần cấu hình mà tập khách hàng dùng. Bản kê cũng bật hoặc tắt từng điểm cắm và khai báo khung tham số mà cấp dưới được phép chỉnh.

Mỗi điểm cắm DC-01..DC-06 là khả năng chung của lõi, không mang tên khách hàng. Mặc định của mỗi điểm cắm giữ đúng hành vi hiện tại, nên các gói đã phát hành (DL-01, DL-02) chạy không đổi. Giá trị riêng của UEB chỉ nằm trong **hồ sơ TWĐ** (mục "Hồ sơ TWĐ" bên dưới). Một tập khách hàng khác có thể dùng hồ sơ khác, hoặc tắt hẳn điểm cắm đó.

**Hệ quả.**

- Thêm một hồ sơ mới là việc cấu hình, nạp dữ liệu và ký bộ ca vàng, không phải một đợt phát triển.
- Lõi phải chịu thêm chi phí là mở rộng `inputs` của scoring và một vài cột hoặc bảng nhỏ ở TL-03.
- Tiêu chí nghiệm thu bắt buộc: **cùng một bản build của lõi phải đạt 100% ca vàng của ít nhất hai hồ sơ khác nhau**. Hai hồ sơ đó là hồ sơ TWĐ và hồ sơ "quy định mới" hiện có của DL-01. Kiểm thử này chạy trong CI của DV-07.
- Không được tạo nhánh mã `if (hoSo == TWĐ)` ở bất kỳ đâu. Kiểm tra ArchUnit và rà soát mã phải chặn mọi hằng số định danh hồ sơ trong `vn.viettel.okrkpi`.

**Trạng thái.** Đã chấp nhận (anh Hiếu, 22/10/2026). Lớp cấp trên giữa các tenant để ngoài phạm vi.

# Mô hình hồ sơ tập khách hàng

## Bản kê hồ sơ

Hồ sơ là một tài liệu YAML được đóng gói, ký và phát hành theo đúng vòng đời của Policy Pack: nháp, kiểm thử, đạt kiểm thử, chờ phê duyệt, đã phát hành, ngừng. Hai người khác nhau soạn và duyệt. Hồ sơ không chứa công thức. Hồ sơ chỉ chứa tham chiếu và cờ.

```yaml
apiVersion: policy.okrkpi.vn/v1
kind: CustomerProfile
metadata:
  id: twd/ban-to-chuc-ueb          # hồ sơ TWĐ theo giải pháp UEB
  version: 0.1.0
  ten: "Khối Đảng ở Trung ương – mẫu Ban Tổ chức (giả lập theo UEB)"
  gia_lap: true                    # YC-PT-14: dự thảo chưa ban hành
  can_cu: ["Dự thảo Quy định kiểm điểm, đánh giá, xếp loại (12/10/2026)", "UEB-BTCTW-QTCV-02 v1.2"]
thanh_phan:
  policy_pack: {id: twd/diem-cong-viec, version: "0.1.0", luong: DANG}
  danh_muc: {code: TWD-DM-25, version: 25}
  bo_quy_tac_canh_bao: {id: twd/diem-nghen, version: "0.1.0"}
  tham_so_luong: {mau: duyet-de-nghi, bo: twd-tham-quyen}
  ui_pack: null                    # dùng UI Pack của tenant
diem_cam:
  DC-01_diem_cong_viec: BAT
  DC-02_khung_ke_thua: BAT
  DC-03_pham_vi_phu_trach: BAT
  DC-04_kieu_do_moc: BAT
  DC-05_chuoi_xep_loai: BAT
  DC-06_che_do_phan_cong: MOT_NGUOI_MOT_VIEC
khung_tham_so: tham-so/khung-twd.yaml   # ràng buộc DC-02 cho cấp dưới
ap_dung_cho:
  tenant_tags: [khoi-dang-tw]
```

Hồ sơ gắn vào tenant bằng thẻ `tenant_tags`, theo đúng cách Policy Pack hiện dùng. Trong một kỳ, `policy_binding` vẫn là nơi ghim phiên bản gói. Bổ sung một cột `customer_profile_version_id` để snapshot ghi được cả phiên bản hồ sơ. Khi kỳ đã khoá, mọi thành phần mà hồ sơ trỏ tới đều bị đóng băng cùng với kỳ.

## Lưu trữ (bổ sung TL-03, schema `policy`)

| Bảng | Nội dung | Ghi chú |
|---|---|---|
| `policy.customer_profile` | `code`, `name`, `is_simulated`, `legal_basis`, cột chung | Mã duy nhất trong tenant gốc toàn cục |
| `policy.customer_profile_version` | `version`, `status` (cùng tập giá trị với `policy_pack_version.status`), `manifest jsonb`, `content_hash`, `signature`, `approved_by` | Cùng ràng buộc CHECK "đã phát hành phải có chữ ký và người duyệt khác người soạn" |
| `policy.param_constraint` | `customer_profile_version_id`, `param_key`, `can_toggle`, `can_change_levels`, `min_value`, `max_value`, `tighten_direction` (`TANG`, `GIAM`, `KHONG`), `allowed_scopes` | Khung tham số của DC-02 |
| `policy.policy_binding` | thêm `customer_profile_version_id uuid` (FK, cho phép NULL với dữ liệu cũ) | Snapshot đọc từ đây |

Tất cả là dữ liệu C0 hoặc C1, đi theo RLS tenant và quy ước cột chung ở TL-03. Hồ sơ gốc do tenant gốc toàn cục phát hành và được phát tán qua saga phát hành đa cell (CORE-05-10, EVT-13) giống Policy Pack.

# Sáu điểm cắm của lõi

Mỗi điểm cắm dưới đây được mô tả theo bốn ý: lõi bổ sung gì, mặc định khi không bật, hồ sơ TWĐ dùng thế nào, và tác động tới hợp đồng.

## DC-01 · Đầu vào tính điểm theo điểm công việc

**Lõi bổ sung.** API đọc lô `GET /api/v1/appraisals/inputs` (`listAppraisalScoringInputs`) trả thêm cho mỗi hồ sơ danh sách `workItems`, gồm các việc lá của người đó trong kỳ. Mỗi việc lá mang các trường sau:

- `taskId`, `standardScore`;
- `coefficientLevels`, là bản đồ từ mã loại hệ số sang **mức**;
- `taskGroup`, `axisCode`;
- `statusAtLock`, `qualityLevel`;
- `subjectiveLateWorkdays`, `objectiveLateWorkdays`;
- `exceeded`, `outOfScope`, `deferredByDecision`.

Khai báo `inputs` của Policy Pack nhận kiểu `list<WorkItem>` với nguồn `task.aggregate.work_items`.

Lõi chỉ lưu **mức** của hệ số, không lưu giá trị. Bảng quy đổi từ mức ra giá trị nằm trong gói, theo kỳ. Nhờ vậy, đổi hệ số chỉ cần phát hành phiên bản gói mới, không phải sửa dữ liệu nhiệm vụ. Về dữ liệu, `task.task` và `task.catalog_item` đã có `difficulty` (1–5) và `task_group`. Bổ sung `coefficient_levels jsonb` cho các loại hệ số mở, mặc định `{}`. Bổ sung thêm `axis_code varchar(16)` (NULL được phép).

**Mặc định.** Các biến đếm cũ (`so_luong_duyet`, `ke_hoach`…) giữ nguyên. Gói không khai báo `work_items` thì không nhận danh sách, và chi phí đọc lô không đổi.

**Hồ sơ TWĐ dùng.** Điểm công việc được tính theo công thức:

`Đ = điểm chuẩn × hệ số khó × hệ số nhóm` (dành sẵn ba ô hệ số dự phòng)

Điểm đạt của một việc tính theo công thức `(Đ + Đ·m + Đ·(1 − 0,05·n)) / 3`. Từ đó có hai chế độ đưa về thang 100. Chế độ "tỷ lệ có hệ số" tính `100 × Σ đạt / Σ giao` và là mặc định của hồ sơ. Chế độ "gói điểm" được bật bằng tham số.

**Hiệu năng.** Danh sách được trả theo hồ sơ và có giới hạn 200 việc lá mỗi hồ sơ. Nếu vượt giới hạn, API phân trang con việc bằng con trỏ con. CEL đánh giá trên chương trình đã biên dịch, nên vẫn giữ được NFR-06 với hồ sơ trung bình 25 nhiệm vụ như TL-01 ước tính.

**Hợp đồng.** `appraisals.yaml` bổ sung `WorkItem` vào `AppraisalScoringInput` dưới dạng trường tùy chọn, nên không phá tương thích. `tasks.yaml` bổ sung `coefficientLevels`, `axisCode` cho `CatalogItem`, `Task`, `PlanItem`.

## DC-02 · Khung tham số kế thừa có giới hạn

**Lõi bổ sung.** Cơ chế `extends` của Policy Pack hiện chỉ cho tenant "siết ngưỡng" qua cờ `policy_rule.overridable`. Cơ chế này được mở rộng thành ràng buộc khai báo cho từng tham số, lưu trong `policy.param_constraint`. Mỗi ràng buộc gồm các thuộc tính:

- tham số có được bật tắt không;
- có được đổi số mức không;
- khoảng tối thiểu và tối đa;
- chiều được phép siết;
- các cấp được phép chỉnh.

Công cụ phát hành từ chối mọi phiên bản cấp dưới vi phạm khung, và trả lỗi RFC 9457 nêu rõ tham số, giá trị và khung.

Phạm vi kế thừa có bốn cấp: hệ thống → khối → tỉnh → đơn vị. `task.work_catalog.scope` bổ sung hai giá trị `KHOI` và `TINH` bên cạnh `VI_TRI`, `DON_VI`, `QUOC_GIA`. `task.catalog_item` bổ sung `score_adjust_min` và `score_adjust_max` (tỷ lệ chỉnh cho phép so với dòng cha, NULL nghĩa là không được chỉnh).

**Mặc định.** Khi không có ràng buộc, hành vi giữ đúng `overridable` hiện tại.

**Hồ sơ TWĐ dùng.** Được chỉnh điểm chuẩn ±20% so với dòng cha. Hệ số nhóm nằm trong khung [1,0; 1,5]. Mức trừ mỗi ngày chậm chỉ được siết, tức chỉ tăng. Ngưỡng cảnh báo phân bố mức khó 60/20/10% được chỉnh theo khung.

**Hợp đồng.** `policies.yaml` bổ sung tài nguyên `param-constraints` thuộc phiên bản hồ sơ. `tasks.yaml` mở rộng enum `scope`.

## DC-03 · Phạm vi phụ trách cho điểm quản lý

**Lõi bổ sung.** Với hồ sơ của người giữ chức vụ lãnh đạo, quản lý, `inputs` trả thêm `responsibilityScope`. Đây là danh sách các mục mà người đó phụ trách, gồm KPI, nhiệm vụ, KR và đơn vị, mỗi mục có các trường:

- `itemType`, `itemId`;
- các tổng S, Q, D đã tổng hợp;
- `targetAchievedRatio` (r);
- `confirmedLevel` (m);
- `ownLateWorkdays` (n).

Các số này tính trên chính mục đó, không cộng dồn hai lần phần giảm của cấp dưới. Phạm vi phụ trách lấy từ quan hệ "người phụ trách" đã có ở `okr` và `task`, cùng với phân công lãnh đạo của SVC-04, theo tổ chức tại thời điểm khoá kỳ.

**Mặc định.** Gói không khai báo thì không nhận dữ liệu này.

**Hồ sơ TWĐ dùng.** Điểm quản lý theo phạm vi phụ trách. Các phần giảm r, m, n áp đúng tại cấp KPI, nhiệm vụ hoặc KR. Cách làm này gắn kết quả của người lãnh đạo với kết quả của đơn vị, theo dự thảo Điều 11 khoản 1.

**Hợp đồng.** `appraisals.yaml` bổ sung `ResponsibilityItem` (trường tùy chọn).

## DC-04 · Danh mục kiểu đo mở và kiểu mốc

**Lõi bổ sung.** `okr.key_result.metric_type` bổ sung giá trị `MOC`, nghĩa là mốc có thời hạn. Bảng mới `okr.kr_milestone` lưu `key_result_id`, `seq`, `due_date`, `recognition_condition`, `achieved_at`, `evidence_ref`, gồm cả các mốc trung gian. `direction` bổ sung giá trị `TOI_DA` (không vượt quá). KR kiểu tỷ lệ cho phép khai `numerator_label` và `denominator_label` để check-in nhập cả tử số và mẫu số.

Lõi chỉ ghi lại ngày đạt mốc và số ngày làm việc chậm, tính theo lịch nghỉ của CORE-08-04. Cách quy đổi ra tỷ lệ hoàn thành nằm trong gói.

Khi khuôn phân rã của hồ sơ yêu cầu, KPI trở thành nút đo được giữa nhiệm vụ và công việc. Việc này dùng lại `task.task.parent_task_id`, vốn đã có trong CORE-02-02, và bổ sung `task.task.node_kind`, nhận các giá trị `NHIEM_VU`, `KPI`, `CONG_VIEC`, `CONG_VIEC_CON`, mặc định `NHIEM_VU`.

**Mặc định.** Bốn kiểu đo cũ và cây hai tầng hiện có giữ nguyên.

**Hồ sơ TWĐ dùng.** Trừ 5% mỗi ngày làm việc chậm của mốc. Cây bảy bậc O → KR → NV → KPI → CV → CVc.

**Hợp đồng.** `okr.yaml` mở rộng `OkrMetricType` và bổ sung tài nguyên `milestones`. `tasks.yaml` bổ sung `nodeKind`.

## DC-05 · Chuỗi xếp loại nhiều bước và bốn giá trị mức

**Lõi bổ sung.** `classification` của Policy Pack nhận thêm dạng `chuoi`. Dạng này là danh sách bước có thứ tự, mỗi bước là một bảng DMN hoặc một biểu thức CEL. Mỗi bước ghi vào `score_trace` gồm `stepNo`, `ruleCode`, `inputs`, `output` và câu lý do. Hợp đồng `appraisals.yaml` đã có cấu trúc này. Nhờ đó, phiếu đánh giá hiển thị được "vì sao bị hạ mức".

Về bốn giá trị mức, TL-03 đã đáp ứng nên DC-05 không thêm cột mới. Mức hệ thống gợi ý nằm ở `appraisal.score_snapshot.auto_rating_code`, kèm `downgrade_reasons`. Bốn mức do con người đưa ra là bốn dòng của `appraisal.rating_proposal`, phân biệt bằng `stage`: tự đề xuất, thẩm định, đề xuất của cấp có thẩm quyền và quyết định. Như vậy phiếu có đủ các giá trị mà UEB yêu cầu: gợi ý, tự nhận, đề xuất và quyết định.

DC-05 chỉ bổ sung một ràng buộc. Khi mức của một dòng `rating_proposal` khác `auto_rating_code` của ảnh chụp mà người đó đã nhìn thấy, dòng đó phải có lý do. Lý do được ghi vào `appraisal.rating_explanation`, với lời giải trình được mã hoá C2 như TL-03 đã thiết kế. Ràng buộc này được kiểm ở máy trạng thái của CORE-04-03. `downgrade_reasons` được điền từ các bước của chuỗi xếp loại.

Bảng mới `appraisal.incident` ghi các "sự việc" đã có kết luận của cấp có thẩm quyền: loại sự việc, ngày kết luận, văn bản và tác động. Điều kiện loại trừ trong gói đọc bảng này qua `inputs` để tự bật.

`quotas` trở thành **tùy chọn** của gói. Ví dụ `HTXSNV 20%` ở TL-01 chỉ còn là ví dụ cấu hình, không phải mặc định. Lý do là dự thảo Quy định 12/10/2026 không quy định trần.

**Mặc định.** Gói dùng `classification.dmn` một bảng `FIRST` như cũ vẫn hợp lệ.

**Hồ sơ TWĐ dùng.** Xếp loại gợi ý theo sáu bước. Mức Xuất sắc bao gồm điều kiện của mức thấp hơn và làm tròn hai bước, theo quyết định ngày 22/10. Không đặt trần, nhưng báo cáo tỷ lệ.

**Hợp đồng.** `appraisals.yaml` bổ sung tài nguyên `incidents` và mã lỗi bắt buộc lý do khi mức khác mức gợi ý (nếu hợp đồng chưa có). `policies.yaml` cho phép `classification.chain`.

## DC-06 · Chế độ phân công theo hồ sơ

**Lõi bổ sung.** Lõi giữ mô hình "một chủ trì, nhiều phối hợp" (`task.task_assignee`) làm mặc định. Hồ sơ có tham số `che_do_phan_cong` nhận một trong hai giá trị:

| Giá trị | Hành vi |
|---|---|
| `CHU_TRI_PHOI_HOP` | Mặc định, giữ mô hình hiện tại |
| `MOT_NGUOI_MOT_VIEC` | Mỗi công việc lá đúng một người; điểm của việc cha chỉ tính khi mọi việc con xong; điểm thành phần của việc con cộng lại bằng điểm của việc cha |

Quy tắc của chế độ `MOT_NGUOI_MOT_VIEC` được kiểm ở bước giao việc (CORE-02-14) bằng tham số đọc qua `PolicyQueryApi`, không viết cứng. Việc con lưu `component_score numeric(6,3)` trên `task.task`.

**Hồ sơ TWĐ dùng.** `MOT_NGUOI_MOT_VIEC`.

**Hợp đồng.** `tasks.yaml` bổ sung `componentScore` và mã lỗi `TASK-422-ASSIGNMENT-MODE`.

# Hồ sơ TWĐ

Hồ sơ TWĐ (`twd/ban-to-chuc-ueb`) là bản đầu tiên dùng các điểm cắm trên và được đánh dấu **giả lập** theo YC-PT-14, vì dự thảo chưa ban hành và nhiều tham số còn chờ Ban Tổ chức chọn. Hồ sơ gồm:

1. **Policy Pack `twd/diem-cong-viec`.** Gói `extends` từ gói chung của khối Đảng. Gói gồm:
   - công thức ba mặt điểm trên điểm công việc và hai chế độ đưa về thang 100;
   - bảng gói điểm theo nhóm đối tượng và tỷ trọng theo trục;
   - mức trừ 5% mỗi ngày;
   - thang mức chất lượng 1,00 / 0,75 / 0,50 / 0,25 / 0 và trần 0,75 cho mục cần khắc phục;
   - bảng hệ số khó 0,8 / 1 / 1,25 / 1,6 / 2,0 và hệ số nhóm 1 / 1,3;
   - chuỗi xếp loại sáu bước.
2. **Ca vàng.** Lấy từ mục 11.8, mục 32 (120 người, kết quả mong đợi 3 / 113 / 4 / 0), các ca G1–G13 của Phụ lục 01G và CTB1–CTB30. Bộ ca phải đạt 100% trước khi phát hành.
3. **Danh mục điểm chuẩn tạm thời của Ban.** Lấy theo dự thảo số 25, kể cả các dòng tách như 34a, 34b, ở phạm vi `KHOI`. Đi kèm khung tham số DC-02.
4. **Danh mục dùng chung theo dự thảo:**
   - 6 trục TR1–TR6;
   - 5 chỉ tiêu CT1–CT5, địa phương mở từ CT6;
   - điều kiện công nhận kiểu mốc;
   - bộ tiêu chí chung 12/10/11;
   - loại sự việc dẫn tới loại trừ.
5. **Bộ quy tắc cảnh báo `twd/diem-nghen`.** Gồm chín quy tắc tự sinh điểm nghẽn và rủi ro (mục 11.6), viết bằng CEL. Mỗi quy tắc có điều kiện, mức, người nhận, ngưỡng, giờ chạy và cờ bật tắt. Job của lõi chỉ đánh giá và ghi `task.task_blocker`. Bảng này hiện chưa có cột phân biệt nguồn, nên bổ sung `origin` (`NGUOI_DUNG` mặc định, `TU_DONG`) và `rule_code`. Khoá idempotent của dòng tự sinh là `(task_id, rule_code, ngày chạy)`.
6. **Tham số luồng.** Gồm bảng thẩm quyền duyệt thay đổi, thẩm quyền rà soát, thẩm định, quyết định và hạn 2/2/1/1 ngày làm việc. Các tham số này được đặt qua SCR-1504 theo phạm vi BS-01.

Mọi giá trị trên đều là dữ liệu của hồ sơ. Không giá trị nào được trở thành mặc định của lõi hay của hồ sơ khác.

# Ngoài phạm vi

- **Lớp cấp trên giữa các tenant (B7).** Gồm O, KR của Trung ương, tỉnh, xã; liên kết; số cuốn lên. Hướng đã ghi trong báo cáo: danh mục dùng chung chỉ đọc, chỉ lưu liên kết, tổng hợp bên analytics qua sự kiện. Hướng này sẽ được làm ở một bản bổ sung sau.
- **Kết cấu dịch vụ và yêu cầu phi chức năng của UEB.** Gồm mục 34.1 và 34.4, cùng giao diện mockup và chức năng ghi thời gian thực hiện (mục 6.6). Báo cáo đã xếp các nội dung này vào nhóm C.

# Bảng truy vết sang task

| Điểm cắm | Task mới | Task được bổ sung phạm vi |
|---|---|---|
| Hợp đồng cho DC-01..DC-06 | NEN-044 | – |
| Hồ sơ, DC-02 (kể cả phân giải tham số theo khung trong `PolicyQueryApi` đã gộp) | CORE-05-12 | – |
| DC-01, DC-02 phía danh mục | CORE-02-27 | – |
| DC-04 (cây KPI), DC-06 | CORE-02-28 | CORE-02-14, CORE-02-20 |
| DC-04 (kiểu mốc) | CORE-01-14 | CORE-01-03, CORE-01-08 |
| DC-01, DC-03, DC-05 phía MOD-04 | CORE-04-13 | CORE-04-06, CORE-04-03 |
| DC-01, DC-05 phía SVC-07 | – | DV-07-03, DV-07-05 |
| Hồ sơ TWĐ | DL-16 | – |

Thứ tự thực hiện là hợp đồng (NEN-044) trước, theo ADR-32 và YC-PT-09. Sau đó đến lõi (CORE-05-12, CORE-02-27, CORE-01-14, CORE-02-28, CORE-04-13). Tiếp theo là scoring (DV-07-03, DV-07-05). Cuối cùng là nạp hồ sơ TWĐ (DL-16). Giao diện cho các điểm cắm sẽ được giao thành task WEB khi hợp đồng đã gộp.

# Vị trí đưa vào bộ TL

- **Baseline:** thêm ADR-33 vào bảng ADR.
- **TL-01:**
  - chương "Policy Pack" bổ sung mục "Hồ sơ tập khách hàng", phần `inputs` mở rộng (DC-01, DC-03), dạng `classification.chain` (DC-05);
  - sửa ví dụ `quotas` thành tùy chọn có chú thích;
  - SVC-07 bổ sung đầu vào lô mới.
- **TL-03:**
  - schema `policy` thêm ba bảng và một cột;
  - schema `task` thêm `coefficient_levels`, `axis_code`, `node_kind`, `component_score`, `score_adjust_min`, `score_adjust_max`, giá trị `scope` mới và cột `origin`, `rule_code` của `task_blocker`;
  - schema `okr` thêm `MOC`, `TOI_DA`, `kr_milestone`;
  - schema `appraisal` thêm bảng `incident` và ràng buộc lý do khi mức của `rating_proposal` khác mức gợi ý.
- **Wiki:** trang 14 ghi quyết định. Trang 17 và 21 cập nhật khi các task lõi gộp.
