# CORE-06-05 · BR-11-05 Phát hiện 13 trường hợp đặc thù theo bảng trong Policy Pack

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-06-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B4 |
| Cỡ việc | L (nên tách: A khung bộ phát hiện, cổng cho MOD-04, API đọc; B hiện thực các quy tắc §1–§13 theo nhóm dữ liệu) |
| Phụ thuộc | CORE-06-01, CORE-05-03 (bổ sung đề xuất: CORE-00-03 vì đọc `org_replica` và nhận EVT-15) |
| Truy vết | QT-11 bước 2 → SCR-1102 → API `GET /api/v1/dossiers/special-cases` (`listDossierSpecialCases`), `GET .../special-cases/{id}` → MOD-06 → `dossier.special_case`; đọc `policy` qua `PolicyQueryApi`, `org_replica.*` → nhận EVT-15 (`org.assignment.changed.v1`) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

MOD-06 tự đối chiếu dữ liệu tổ chức, phân công và trạng thái hồ sơ với 13 trường hợp đặc thù của PL 6 do Policy Pack mô hình hoá, ghi `dossier.special_case` với hệ quả xếp loại (`KHONG_XEP_LOAI`, `XEP_LOAI_TAI_DON_VI_CU`…) và cung cấp kết quả cho MOD-04 khi lập hồ sơ. Khi văn bản quy định đổi, chỉ cần phát hành Policy Pack mới, không sửa mã.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-11: "Mô tả các bước" (dòng 539, bước 2), "Quy tắc nghiệp vụ" (dòng 579, BR-11-05 và bảng 13 trường hợp §1–§13 ngay sau), "SCR-1102. Gói hồ sơ số và ký số" (dòng 675, danh sách trường hợp đặc thù); QT-09 "Ngoại lệ và xử lý" (dòng 113, ngoại lệ 2).
- TL-03 bảng `dossier.special_case` (dòng 3059) và đoạn đầu "Schema dossier" (dòng 2975: MOD-04 đọc qua cổng công bố của MOD-06).
- TL-01 mục "Policy Pack" (chương 10) để biết bảng quy tắc `special_case` trong gói.
- `contracts/openapi/modules/dossiers.yaml`: `listDossierSpecialCases`, `getDossierSpecialCase`.
- Bản trích `tl02b §QT-11 BE-Q11-05`.

## 3. Phạm vi công việc

- Viết bộ phát hiện chạy theo bảng quy tắc đọc từ `PolicyQueryApi` (biểu thức CEL trên dữ liệu tổ chức, phân công, thời gian nghỉ, kỷ luật, hồ sơ); mỗi trường hợp ghi `case_code`, `effect`, `legal_ref`, `effective_from/to`, `detected_by = 'TU_DONG'`, chi tiết C2 vào `detail_enc`.
- Kích hoạt khi tạo hội nghị (CORE-06-02), khi lập hồ sơ quý (cổng gọi từ consumer EVT-09 của MOD-04) và khi nhận EVT-15 thay đổi phân công trong kỳ; idempotent theo (người, kỳ, `case_code`).
- Hiện thực cổng `SpecialCaseQuery` mà MOD-04 khai báo (đảo phụ thuộc, xem điểm lệch ở CORE-04-02) để không tạo vòng phụ thuộc module.
- `listDossierSpecialCases`, `getDossierSpecialCase`: phân trang con trỏ, lọc theo gói, gom nhóm theo `case_code`; chi tiết C2 chỉ giải mã cho cơ quan tổ chức trong phạm vi, có nhật ký truy cập.
- Viết ca kiểm thử cho từng trường hợp §1–§13 với dữ liệu tổng hợp, kể cả biên (điều động đúng 2 tháng, nghỉ đúng 50% thời gian kỳ).
- Điểm lệch cần lưu ý: TL-03 có `detected_by = 'THU_CONG'` và cột `confirmed_by`, nhưng hợp đồng chưa có API ghi trường hợp thủ công hay xác nhận; không tự thêm endpoint, ghi đề xuất mở task sửa hợp đồng vào `wiki/14`.

## 4. Ngoài phạm vi

- Soạn bảng quy tắc trong Policy Pack (CORE-05-04, WEB-045); áp hệ quả vào tính điểm (DV-07-05).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/dossier/**`
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/appraisal/api/**` (chỉ khai báo cổng `SpecialCaseQuery` nếu CORE-04-02 chưa có)
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/dossier/**`
- `data/golden/` (ca dữ liệu mẫu 13 trường hợp, nếu dùng chung với kiểm thử vàng)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; `ApplicationModules.verify()` không báo vòng phụ thuộc.
- Có ít nhất một ca kiểm thử đạt cho mỗi trường hợp §1–§13; EVT-15 trùng không tạo bản ghi trùng.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
