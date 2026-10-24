# DV-06-06 · QT-08/SCR-0801 Saga chốt kỳ: tiền điều kiện, khoá, tính điểm, sinh biểu mẫu, ký lô, niêm phong, lập hồ sơ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-06-05, CORE-08-05, DV-07-06, DV-11-04, DV-10-06, DV-09-03 |
| Truy vết | QT-08 → SCR-0801 → `POST /api/v1/workflow/sagas/period-close` (`startPeriodCloseSaga`, client nội bộ do MOD-08 gọi sau `startPeriodLock`) → SVC-06 điều phối MOD-08, SVC-07, SVC-11, SVC-10, SVC-09, MOD-04 → bảng `flowable.saga_instance` (`CHOT_KY`), `flowable.saga_step`, `config.period`, `scoring.score_job` → EVT-09, EVT-10, EVT-17, EVT-18, EVT-19, EVT-21; BR-08-03..BR-08-08, BR-08-10; NFR-06 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực saga `CHOT_KY` trên khung DV-06-05: từ một lệnh khoá kỳ đã duyệt, hệ thống kiểm tiền điều kiện, khoá dữ liệu kỳ, tính điểm hàng loạt bằng phiên bản Policy Pack ghim của kỳ, sinh biểu mẫu và bảng chấm điểm, ký số niêm phong lô, niêm phong sổ cái, đưa kỳ sang `DA_KHOA` và mở bước lập hồ sơ đánh giá. Khi xong, một cell lớn chốt kỳ trong ngân sách NFR-06 (tính lại 1,2 triệu hồ sơ ≤ 2 giờ; sinh và ký 100.000 phiếu ≤ 4 giờ), mỗi kỳ chỉ một saga chạy, và tiến độ hiện được ở SCR-0801.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-08 (dòng 2089–2251): các bước (dòng 2118–2131), BR-08-01..12 (dòng 2163–2178), trạng thái kỳ (dòng 2146–2161), ngoại lệ (dòng 2180–2189), SCR-0801 (dòng 2194).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` saga chốt kỳ trong "Cơ chế đồng bộ dữ liệu" (dòng 4359–4378) và ngưỡng < 60 phút (dòng 4588).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-06 workflow-service" (dòng 1048–1070).
- Baseline `00-co-so-thiet-ke-chung.md` mục 3 (NFR-06), 7 (EVT-09, EVT-10, EVT-17..19, EVT-21), 9.1 (trạng thái kỳ).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` `startPeriodCloseSaga`; `contracts/openapi/modules/config.yaml` `startPeriodLock`, `getPeriodLockProgress`, `pausePeriodLock`, `resumePeriodLock`, `getPeriodLockDiagram` (MOD-08 gọi xuống SVC-06).
- Wiki: `wiki/05a-quy-trinh-va-man-hinh-qt00-08.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực `startPeriodCloseSaga` (client nội bộ, Idempotency-Key `CK-{năm}{kỳ}`, 409 `WORKFLOW-409-SAGA-EXISTS`, BR-08-08) khởi tạo saga `CHOT_KY` với `correlation_id` = kỳ.
- Bước 0 tiền điều kiện (≤ 5 phút): gọi API nội bộ các module để kiểm đã qua hạn nghiệm thu tồn đọng (BR-08-03), không còn đề nghị gia hạn chờ duyệt và lệch tổ chức mức Cao (BR-08-04), Policy Pack ghim hợp lệ, không còn lỗi đồng bộ; liệt kê minh chứng `DA_NOP` sẽ không tính; không đạt thì job `THAT_BAI` với Problem `PERIOD-422-PRECONDITION` (MSG-08-02).
- Bước khoá: yêu cầu MOD-08 chuyển kỳ `DANG_KHOA`, chờ bộ đệm trạng thái kỳ hết hạn và xả sự kiện bằng marker (TL-03), yêu cầu gắn cờ "không tính trong kỳ" cho minh chứng còn `DA_NOP`/`DANG_NGHIEM_THU` (BR-08-05, MSG-08-03).
- Bước tính điểm: tạo `score_job` loại `CHOT_KY` ở SVC-07 (DV-07-06), chờ hoàn tất qua sự kiện, theo dõi số hồ sơ, đã tính, lỗi, tốc độ; lô lỗi thử lại 3 lần, hồ sơ vẫn lỗi vào hàng đợi lỗi.
- Bước sinh biểu mẫu (SVC-11, chờ EVT-17) và bước ký lô niêm phong bảng tổng hợp (SVC-10, chờ EVT-18, thử lại lùi dần tối đa 4 giờ, không tính lại các bước trước).
- Bước hoàn tất: niêm phong Merkle đột xuất (SVC-09, EVT-19); MOD-08 đặt `DA_KHOA`; mục tiêu của kỳ `DONG`; MOD-04 tạo hồ sơ `CHUA_LAP`; thông báo toàn tenant (EVT-21, MSG-08-07); đánh dấu điểm không quay lại.
- Phát sự kiện tiến độ cho MOD-08 chiếu `period_lock_step`; ghi sổ cái mọi bước (BR-08-10); cảnh báo vận hành khi chậm ngân sách (MSG-08-04).
- Kiểm thử tích hợp với các dịch vụ giả theo hợp đồng: đường chính đủ bước; tiền điều kiện không đạt; lỗi ký tiếp tục sau 4 giờ giả lập; khởi động lại engine giữa bước tính điểm; gọi trùng khoá.
- Điểm lệch cần lưu ý: thời điểm phát EVT-09 và thứ tự bước không thống nhất. TL-02a và `config.yaml` (`startPeriodLock`) ghi "bước 1 phát EVT-09" rồi tính điểm, ký, và chỉ đặt `DA_KHOA` ở bước cuối; TL-03 (dòng 4365–4372) và `platform.yaml` (`startPeriodCloseSaga`) ghi đặt `DA_KHOA` + EVT-09 trước khi tính điểm và "kết thúc thì MOD-08 phát EVT-09". Baseline ghi SVC-07 và MOD-04 tiêu thụ EVT-09 để tính điểm và lập hồ sơ, nên chọn thứ tự của TL-02a (EVT-09 phát khi kỳ vào `DANG_KHOA`, `DA_KHOA` ở bước cuối, sau ký và niêm phong), dùng mã bước của TL-03 khi khớp nghĩa; báo người phụ trách chốt và ghi vào wiki trang 14 trước khi gộp.
- Gợi ý tách: (a) khởi động, bước 0, bước khoá; (b) bước tính điểm và theo dõi; (c) sinh biểu mẫu, ký lô, hoàn tất.

## 4. Ngoài phạm vi

- Bù trừ, chạy lại hồ sơ lỗi, kiểm tất định (DV-06-07); lệnh khoá kỳ và API điều khiển ở MOD-08 (CORE-08-05); tính điểm (DV-07-06); sinh biểu mẫu, ký lô, niêm phong (DV-11-04, DV-10-06, DV-09-03).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `ops/runbooks/` (mục theo dõi và xử lý saga chốt kỳ)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm kiểm thử tích hợp Testcontainers PostgreSQL + Kafka ở mục 3.
- Thử tải rút gọn với dịch vụ giả: saga điều phối 1,2 triệu hồ sơ chia lô không làm engine vượt ngưỡng job đến hạn của TL-01; ghi số đo thời gian từng bước vào báo cáo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
