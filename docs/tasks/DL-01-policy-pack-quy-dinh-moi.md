# DL-01 · QT-15/MOD-05 Policy Pack "quy định mới": công thức CEL tính điểm luồng Đảng và luồng Nhà nước

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách: xem cuối mục 3) |
| Phụ thuộc | CORE-05-04 (đã kéo theo NEN-041 thư viện `vt-cel`) |
| Truy vết | QT-15 → BR-09-01..BR-09-05, BR-09-11, BR-15-01..BR-15-05, BR-15-13 → API `PUT /api/v1/policies/packs/{id}/versions/{v}/rules/{key}` (`savePolicyRule`), `POST /api/v1/policies/packs/{id}/versions/{v}/evaluate` (`evaluatePolicyVersion`) → MOD-05, SVC-07 → bảng `policy.policy_pack`, `policy.policy_pack_version`, `policy.policy_rule` → EVT-13 (chỉ khi phát hành, ngoài task này) |
| Trạng thái | Chưa bắt đầu (dùng bản giả lập); đã có bản nháp dữ liệu giả lập ngày 20/10/2026 (`data/policy-pack/`, trạng thái gói `NHAP`); kiểm thử nạp gói bằng vt-cel chờ CORE-05-04 |

## 1. Mục tiêu

Có hai Policy Pack dạng dữ liệu trong repo, một cho luồng Đảng (`khoi-dang/quy-dinh-moi`, `luong: DANG`) và một cho luồng Nhà nước (`luong: NHA_NUOC`), chứa đủ khai báo đầu vào, biểu thức CEL tính KPI nhiệm vụ, điểm nhóm, điểm kết quả, quy đổi tiêu chí chung, tham số và quy tắc làm tròn. Hai gói nạp được qua API quy tắc của MOD-05, mọi biểu thức qua kiểm kiểu CEL, và cho kết quả xem trước đúng trên hồ sơ mẫu. Đây là "quy tắc chấm điểm như mã" mà NFR-07 (phát hành Policy Pack ≤ 5 ngày) và toàn bộ QT-09, QT-10 dựa vào; thiếu nó scoring-service không có gì để tính.

Theo quyết định ngày 20/10/2026 (`docs/KE-HOACH-TONG-THE-HE-THONG.md` mục 1a), công thức CEL, tham số và căn cứ trong hai gói là bản giả lập theo QĐ 366-QĐ/TW và NĐ 335/2025, đánh dấu rõ là giả lập trong `metadata` của Policy Pack, và chỉ được nạp vào hệ thống qua Policy Pack. Khi nghiệp vụ đưa ra công thức chính thức hoặc quy định thay đổi, quản trị viên soạn phiên bản Policy Pack mới, chạy ca vàng, duyệt và phát hành theo QT-15 mà không sửa mã và không phát hành phần mềm.

## 2. Đầu vào bắt buộc đọc

- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, chương "Policy Pack: quy tắc chấm điểm như mã có phiên bản" (dòng 2380 trở đi), các mục "Cấu trúc gói" (ví dụ `policy.yaml` đầy đủ) và "Ngôn ngữ biểu thức CEL và bảng quyết định DMN".
- TL-02b `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md`: QT-09 mục "Quy tắc nghiệp vụ" (BR-09-01..BR-09-11); QT-15 mục "Quy tắc nghiệp vụ" (BR-15-01..BR-15-14) và đặc tả SCR-1501 "Config Studio – công thức" (bảng tham số, ví dụ YAML `policy_rule`).
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, mục "Schema policy (MOD-05)" (bảng `policy.policy_rule`: `rule_key`, `expression`, `params_json`, `rounding`, `overridable`).
- Ghi chú tham số mặc định: `/mnt/project-files/kien-truc-okr-kpi/src/_ghi-chu-TL-02a.md` mục 5 và `_ghi-chu-TL-02b.md` mục "Tham số Policy Pack mới".
- Hợp đồng: `contracts/openapi/modules/policies.yaml` (`savePolicyRule`, `listPolicyRules`, `evaluatePolicyVersion`, `resolvePolicyPack`).
- Wiki: `wiki/13-thuat-ngu.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`, `wiki/04-ma-dinh-danh-va-truy-vet.md`.

## 3. Phạm vi công việc

- Tạo thư mục gói `data/policy-pack/khoi-dang/quy-dinh-moi/` đúng cấu trúc TL-01: `policy.yaml` (`apiVersion: policy.okrkpi.vn/v1`, `kind: PolicyPack`, `metadata` có `id`, `version` semver, `luong`, `ap_dung_cho`, `hieu_luc`, `can_cu_phap_ly`, `phe_duyet` để trống người soạn/duyệt), các thư mục `dmn/`, `tests/`, `templates/` để trống cho DL-02, DL-03, DL-05, và `CHANGELOG.md` viết bằng tiếng Việt.
- Đánh dấu gói là giả lập trong `metadata` của `policy.yaml`: `gia_lap: true` và `ghi_chu_gia_lap` nêu rõ công thức, tham số soạn theo QĐ 366-QĐ/TW, NĐ 335/2025 nhưng chưa được nghiệp vụ xác nhận (tên trường là suy luận, ghi vào `CHANGELOG.md` và wiki trang 14); không có công thức hay tham số nào được viết cứng trong mã.
- Khai báo đủ `inputs` với kiểu và `source` là API tổng hợp (ví dụ `task.aggregate.approved_count`, `product.aggregate.quality_avg`, `okr.aggregate.objective_ratio`, `task.aggregate.over_target_ratio`), sao cho mọi biến dùng trong `definitions` đều đã khai báo đúng kiểu (BR-15-01).
- Viết `definitions` CEL: `kpi_A`, `kpi_B`, `kpi_C`, `kpi_nhiem_vu` theo BR-09-01 (A = min(duyệt/kế hoạch, 1), kế hoạch = 0 thì A = 1; C = max(0, 1 − `tru_tien_do` × ngày làm việc chậm chủ quan)), điểm nhóm và điểm kết quả theo BR-09-03 bằng hàm `sum()`, tỷ lệ vượt ghi riêng theo BR-09-04, và quy đổi tiêu chí chung bốn mức theo BR-09-05; chỉ dùng hàm của thư viện `math`, `lists` và hàm mở rộng `sum`, `ngay_lam_viec`, `min_muc` của `vt-cel`, không dùng hàm phụ thuộc thời gian hay ngẫu nhiên.
- Khai báo tham số trong `params_json` với giá trị mặc định và miền: `tru_tien_do` 0,05 (0,00–0,20), `loai_ngay` `NGAY_LAM_VIEC` (BR-15-03), `cach_tinh_chat_luong` `NAM_MUC_PL3` (BR-15-02), `lam_tron`, trọng số nhóm thường xuyên 30 và then chốt 70 với tổng 100 (BR-15-05); đánh dấu `overridable` cho những quy tắc tenant được phép sửa (BR-15-13).
- Tạo gói luồng Nhà nước (định danh `<khối>/<tên>` do agent chọn theo quy ước TL-01, ghi lại trong wiki) với 30 điểm tiêu chí chung và 70 điểm kết quả nhiệm vụ, cùng bộ `inputs`, lưu kết quả thành snapshot riêng theo BR-09-11.
- Viết kiểm thử nạp gói trong mission-core: đọc mọi `policy.yaml` dưới `data/policy-pack/`, biên dịch từng biểu thức bằng `vt-cel`, báo lỗi khi có biến chưa khai báo hoặc sai kiểu, và đánh giá ba hồ sơ mẫu (trong đó có ví dụ của SCR-1501 và ca kế hoạch = 0).
- Ghi vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md` danh sách điểm đang dùng giá trị giả lập (tham số, căn cứ pháp lý, định danh gói Nhà nước), để khi có bản chính thức thì thay bằng phiên bản Policy Pack mới.
- Điểm lệch cần lưu ý: TL-01 (ví dụ `policy.yaml`) làm tròn `scale: 2`, còn BR-09-03 và SCR-1501 làm tròn 1 chữ số, nửa lên; chọn theo TL-02b (đặc tả nghiệp vụ mới hơn) và ghi rõ trong `CHANGELOG.md`. `AGENTS.md` nhắc NĐ 233/2026 nhưng TL chỉ dẫn NĐ 335/2025 cho luồng Nhà nước; ghi `can_cu_phap_ly` theo TL và đưa câu hỏi vào wiki trang 14.
- Gợi ý tách nếu quá lớn: DL-01a gói luồng Đảng kèm kiểm thử nạp gói; DL-01b gói luồng Nhà nước.

## 4. Ngoài phạm vi

- Bảng quyết định DMN xếp loại và bảng 13 trường hợp đặc thù (DL-02); bộ ca vàng (DL-03); `task_lifecycle` và tham số luồng duyệt (DL-04); mẫu biểu (DL-05).
- Engine CEL, API quy tắc, chạy kiểm thử, ký và phát hành gói (NEN-041, CORE-05-xx).

## 5. Tệp được phép sửa

- `data/policy-pack/khoi-dang/quy-dinh-moi/**` (trừ `dmn/`, `tests/`, `templates/` ngoài tệp giữ chỗ)
- `data/policy-pack/<khối-nhà-nước>/<tên>/**` (cùng giới hạn)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**` (chỉ kiểm thử nạp gói)
- `backend/mission-core/src/test/resources/policy-pack/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh, trong đó kiểm thử nạp gói biên dịch được 100% biểu thức của cả hai gói, không có biến chưa khai báo.
- Kiểm thử thuộc tính (jqwik) trên 10.000 bộ đầu vào ngẫu nhiên: điểm ∈ [0; 100], C ≥ 0, A ≤ 1; tổng điểm tối đa nhóm bằng 100 (Đảng) và 30 + 70 (Nhà nước).
- Với CORE-05-04 đã gộp: nạp gói qua `savePolicyRule` trên Testcontainers trả 200, gọi `evaluatePolicyVersion` cho hồ sơ mẫu SCR-1501 trả đúng điểm mong đợi.
- Ca chứng minh cấu hình động: nạp phiên bản gói 1.0.0 giả lập và tính điểm một hồ sơ mẫu, rồi nạp phiên bản 1.1.0 đổi biểu thức `kpi_C` và tham số `tru_tien_do`; cùng hồ sơ được tính lại theo công thức mới, kết quả đúng giá trị mong đợi của phiên bản mới, trên cùng bản build mission-core, không đổi mã.
- `metadata` của cả hai gói có `gia_lap: true`; báo cáo liệt kê các công thức, tham số đang là giả lập.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki trang 14 được cập nhật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
