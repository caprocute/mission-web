# DV-06-04 · ADR-04 Mẫu BPMN luồng duyệt phần 2: hồ sơ đánh giá, khiếu nại, mở lại kỳ, phát hành Policy Pack

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-06-03 |
| Truy vết | QT-09 (mẫu `duyet-ho-so-danh-gia` v3), QT-12 (mẫu `xu-ly-khieu-nai` v2), QT-13 (mẫu `mo-lai-ky-hai-nguoi` v1), QT-15 (luồng duyệt phát hành Policy Pack) → SVC-06 → bảng `flowable.wf_binding`, `flowable.wf_task_projection` → tiêu thụ EVT-11, EVT-13, EVT-18, EVT-25; BR-09-14, BR-09-15, BR-12-04, BR-12-05, BR-13-02, BR-13-03, BR-15-09 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn thiện các luồng duyệt có con người còn lại của ADR-04 tầng 2: hồ sơ đánh giá quý và năm (tự đánh giá, thẩm định, đề xuất có thể bỏ qua, quyết định), xử lý khiếu nại, mở lại kỳ hai người duyệt, và duyệt phát hành Policy Pack. Khi xong, mỗi mẫu chạy theo tham số `wf_binding`, có timer hạn theo ngày làm việc, nhắc trước hạn, chuyển cấp khi quá hạn, chờ ký số hoàn tất (EVT-18) trước khi chuyển bước, và bảo đảm tách vai trò bằng OPA khi giao việc.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương G2 "Ví dụ BPMN rút gọn: duyệt hồ sơ đánh giá" (dòng 1897–1953) và "Quy tắc quản trị chống mạng nhện" (dòng 2149).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-09 (dòng 9–334: user task bước 3–12, timer 3/3/2/3 ngày làm việc, nhắc trước 1 ngày, số lần trả lại ≤ 2, BR-09-14, BR-09-15), QT-12 (dòng 732, mẫu `xu-ly-khieu-nai` dòng 759, chọn người xử lý dòng 778 và 822, BR-12-04, BR-12-05), QT-13 (mẫu `mo-lai-ky-hai-nguoi` dòng 1063, BR-13-02, BR-13-03), QT-15 (dòng 1284, BR-15-09) và SCR-1504 (dòng 1541–1555).
- Baseline `00-co-so-thiet-ke-chung.md` mục 7 (EVT-11, EVT-13, EVT-18, EVT-25).
- Hợp đồng: `contracts/openapi/modules/appraisals.yaml`, `oversight.yaml`, `policies.yaml` (thao tác mà external worker gọi lại).
- Wiki: `wiki/05b-quy-trinh-va-man-hinh-qt09-18.md`, `wiki/15-phan-quyen.md` (tách vai trò, bốn mắt), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Mẫu `duyet-ho-so-danh-gia`: các bước theo trạng thái `appraisal_profile` (`TU_DANH_GIA` → `CHO_THAM_DINH` → `DA_THAM_DINH` → `CHO_QUYET_DINH` → `DA_QUYET_DINH`), bước đề xuất bỏ qua được theo `wf_binding` và điều kiện CEL cấp mẫu, trả lại tối đa 2 lần, timer hạn từng bước và nhắc trước 1 ngày, quá hạn chuyển cấp phó được uỷ quyền; ba mốc ký số chờ EVT-18 qua receive task hoặc external worker (BR-09-15); dùng chung cho kỳ năm bằng tham số.
- Mẫu `xu-ly-khieu-nai`: khởi tạo khi nhận EVT-25; gọi OPA lấy ứng viên hợp lệ (không là người nghiệm thu, thẩm định, đề xuất, quyết định hồ sơ – BR-12-04), chọn người ít việc nhất, danh sách rỗng thì chuyển cấp trên theo cây; timer tiếp nhận 2 ngày, giải trình 3 ngày, tổng 15 ngày làm việc, nhắc trước 2 ngày (BR-12-05).
- Mẫu `mo-lai-ky-hai-nguoi`: hai lượt duyệt tuần tự `approver_1 ≠ approver_2 ≠ requester`, người duyệt chưa tham gia hồ sơ bị mở (BR-13-02, BR-13-03), mỗi lượt có step-up và ký (gọi API lõi), timer 2 ngày mỗi lượt; đơn vị thiếu người duyệt thứ hai thì chuyển cấp trên, không bao giờ hạ xuống một người.
- Luồng duyệt phát hành Policy Pack: bước phê duyệt bởi người khác người soạn (BR-15-09), xong thì gọi khởi chạy saga phát hành (CORE-05-10 dùng khung DV-06-05).
- Mỗi mẫu có Flowable test cho đường chính, trả lại, quá hạn, chuyển cấp; test DMN liên quan; qua linter của DV-06-03.
- Điểm lệch cần lưu ý: TL-02b chưa đặt tên mẫu cho luồng duyệt phát hành Policy Pack; đặt tên theo quy ước `duyet-...` và ghi "(suy luận)" trong mô tả mẫu và wiki trang 14. Mẫu `duyet-kep-chi-huy-cap-uy` v1 (lực lượng vũ trang, SCR-1504) không có hàng trong khung kế hoạch, không làm trong task này.
- Gợi ý tách: (a) `duyet-ho-so-danh-gia`; (b) `xu-ly-khieu-nai` + `mo-lai-ky-hai-nguoi`; (c) duyệt phát hành Policy Pack.

## 4. Ngoài phạm vi

- Nghiệp vụ hồ sơ, khiếu nại, mở lại kỳ trong mission-core (CORE-04, CORE-07); saga phát hành Policy Pack (CORE-05-10); ký số (DV-10).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm Flowable test, test DMN, linter.
- Ca tách vai trò: người vừa thẩm định không nhận được việc xử lý khiếu nại cùng hồ sơ; người tạo yêu cầu mở lại không duyệt được.
- Timer tính theo ngày làm việc của tenant (kiểm thử với lịch có ngày nghỉ, làm bù).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
