# DL-02 · QT-09/QT-10/QT-11 Bảng DMN xếp loại quý, năm và bảng 13 trường hợp đặc thù

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | DL-01, CORE-05-05 |
| Truy vết | QT-09 (BR-09-05..BR-09-10, BR-09-13, BR-09-17), QT-10 (BR-10-02, BR-10-03, BR-10-05, BR-10-07, BR-10-09), QT-11 (BR-11-05), QT-15 (BR-15-04, BR-15-06) → API `PUT /api/v1/policies/packs/{id}/versions/{v}/decision-tables/{key}` (`saveDecisionTable`), `GET .../decision-tables/{key}/dmn` (`exportDecisionTableDmn`), `GET /api/v1/policies/packs/{id}/prospect-criteria` (`getProspectCriteria`) → MOD-05, SVC-07, MOD-06 → bảng `policy.decision_table`, `dossier.special_case` → EVT-10 (khi SVC-07 tính) |
| Trạng thái | Bản giả lập xong 20/10/2026 (DMN quý, năm, Nhà nước; 13 trường hợp đặc thù), đã sửa điều kiện then chốt ở dòng xuất sắc nhờ bộ ca DL-03; kiểm thử Flowable DMN chờ CORE-05-05 |

## 1. Mục tiêu

Hai gói của DL-01 có đủ phần xếp loại: bảng quyết định DMN 1.4 cho mức quý luồng Đảng, luồng Nhà nước, mức năm, quy tắc người đứng đầu, hạn mức HTXSNV và bảng 13 trường hợp đặc thù dạng tham số. Mỗi dòng DMN có căn cứ điều khoản và mẫu câu giải thích, nên SVC-07 sinh được câu giải thích "chuyển xuống mức liền kề" theo BR-09-10 mà không cần người viết. Khi xong, Config Studio (SCR-1502) mở được các bảng này và xuất lại đúng XML.

Theo quyết định ngày 20/10/2026 (`docs/KE-HOACH-TONG-THE-HE-THONG.md` mục 1a), ngưỡng, điều kiện và câu giải thích trong các bảng DMN là bản giả lập theo QĐ 366-QĐ/TW và NĐ 335/2025, đánh dấu rõ là giả lập trong `metadata` của Policy Pack, và chỉ được nạp vào hệ thống qua Policy Pack. Khi nghiệp vụ đưa ra bảng xếp loại chính thức hoặc quy định thay đổi, quản trị viên soạn phiên bản Policy Pack mới, chạy ca vàng, duyệt và phát hành theo QT-15 mà không sửa mã và không phát hành phần mềm.

## 2. Đầu vào bắt buộc đọc

- TL-02b `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md`: QT-09 mục "Quy tắc nghiệp vụ" và "Ngoại lệ và xử lý"; QT-10 mục "Quy tắc nghiệp vụ" (BR-10-01..BR-10-10); QT-11 mục "Quy tắc nghiệp vụ" và bảng 13 trường hợp §1–§13 (ngay trước "Ngoại lệ và xử lý"); QT-15 BR-15-04, BR-15-06.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, chương "Policy Pack…", mục "Ngôn ngữ biểu thức CEL và bảng quyết định DMN" (ví dụ `xep-loai-dang`, hit policy `FIRST`, hậu xử lý hạn mức).
- TL-03 mục "Schema policy (MOD-05)" (bảng `policy.decision_table`: `dmn_xml`, `hit_policy`, `rules_json`, `explanation_templates_json`, `legal_ref`).
- Hợp đồng `contracts/openapi/modules/policies.yaml` và `contracts/openapi/modules/dossiers.yaml` (`listDossierSpecialCases`).
- `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Ghi chú giả lập: mỗi tệp `.dmn` có chú thích đầu tệp và `CHANGELOG.md` ghi rõ ngưỡng, điều kiện là giả lập; `metadata` của gói giữ `gia_lap: true` do DL-01 đặt; không có ngưỡng hay điều kiện xếp loại nào được viết cứng trong mã.
- Viết `dmn/xep-loai-dang.dmn` (DMN 1.4, FEEL, hit policy `FIRST` từ mức cao xuống) với các dòng HTXSNV, HTTNV, HTNV, KHTNV và dòng loại trừ đúng điều kiện BR-09-07, BR-09-08, BR-09-09; ngưỡng 30% nhiệm vụ vượt và cách đếm là tham số (BR-15-04).
- Gắn cho mỗi dòng `legal_ref` (≤ 100 ký tự) và mẫu câu giải thích (≤ 200 ký tự) có biến điều kiện thiếu, giá trị thực tế, điều khoản và phiên bản gói (BR-09-10, BR-15-06); hiện thực mức cuối = `min_muc`(mức theo điểm, mức theo tiêu chí chung) theo BR-09-06.
- Viết bảng quy tắc người đứng đầu theo BR-09-13 (tập thể không đạt 05 chỉ tiêu, không đạt mọi mục tiêu O, hoặc trên 50% đơn vị phụ trách KHTNV).
- Viết `dmn/xep-loai-nha-nuoc.dmn` cho gói luồng Nhà nước với ngưỡng ≥ 90, 70 đến dưới 90, 50 đến dưới 70, dưới 50 (BR-09-11).
- Viết bảng xếp loại năm: tham số `nam.cach_tinh` mặc định `TRUNG_BINH_QUY` (BR-10-02), dòng chặn HTXSNV năm khi có quý KHTNV và nêu quý (BR-10-03), điều kiện mức năm trên số liệu cả năm (BR-10-05), tham số `phan_nhom.mo_o_ky_quy` = `false` (BR-10-07) và tiêu chí phân nhóm triển vọng mà `getProspectCriteria` trả về (BR-10-09).
- Khai báo hạn mức `quotas.HTXSNV` tỷ lệ tối đa 0,20 trong phạm vi `DON_VI_CAP_TREN`, chỉ đánh dấu vượt, không tự hạ mức (BR-09-17); tham số `phan_bo.toi_da_htxsnv`.
- Khai báo bảng 13 trường hợp đặc thù §1–§13 trong `policy.yaml` (khoá do agent đặt, ghi "(suy luận)" trong `CHANGELOG.md`), mỗi mục có mã, mô tả, cách xử lý mặc định và tham số, để MOD-04 và MOD-06 đọc khi lập hồ sơ (BR-11-05).
- Kiểm tra tĩnh mọi bảng: không có dòng không bao giờ khớp, không có khoảng hở trên miền điểm [0; 100], FEEL hợp lệ.
- Điểm lệch cần lưu ý: ví dụ DMN trong TL-01 cho HTXSNV chỉ có điểm, tỷ lệ O và tỷ lệ vượt, còn BR-09-07 thêm "giải pháp đột phá được kiểm chứng" và tiêu chí chung "Thực hiện tốt"; chọn theo TL-02b và ghi lại trong wiki trang 14.

## 4. Ngoài phạm vi

- Công thức CEL tính điểm (DL-01); ca kiểm thử vàng (DL-03).
- API lưu và kiểm FEEL, engine DMN trong scoring-service (CORE-05-05 và nhóm DV-07); bộ phát hiện 13 trường hợp ở MOD-06.

## 5. Tệp được phép sửa

- `data/policy-pack/khoi-dang/quy-dinh-moi/dmn/**`, `data/policy-pack/<khối-nhà-nước>/<tên>/dmn/**`
- Các mục `classification`, `quotas`, bảng trường hợp đặc thù trong `policy.yaml` và `CHANGELOG.md` của hai gói
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`, `backend/mission-core/src/test/resources/policy-pack/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử nạp mọi tệp `.dmn` bằng Flowable DMN, mỗi dòng có ít nhất một ca đơn vị cho kết quả đúng mức và đúng câu giải thích.
- Phân tích tĩnh báo 0 dòng bị che, 0 khoảng hở; ca biên 59,995 và 89,995 cho mức đúng sau làm tròn của DL-01.
- Với CORE-05-05 đã gộp: `saveDecisionTable` trên Testcontainers trả 200 cho mọi bảng và `exportDecisionTableDmn` trả XML có `dmn_hash` trùng với tệp trong repo.
- Ca chứng minh cấu hình động: nạp phiên bản gói mới đổi ngưỡng HTXSNV (ví dụ 90 thành 92); cùng hồ sơ mẫu được xếp loại lại theo bảng mới, trên cùng bản build, không đổi mã.
- Danh sách ngưỡng, điều kiện đang là giả lập được liệt kê trong báo cáo và wiki trang 14.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
