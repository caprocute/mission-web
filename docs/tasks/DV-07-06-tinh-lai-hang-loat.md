# DV-07-06 · NFR-06 Tính lại hàng loạt cuối kỳ: chia lô, worker co giãn, đọc đầu vào theo lô, hàng đợi lỗi

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-07-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | L |
| Phụ thuộc | DV-07-05, CORE-04-06 |
| Truy vết | QT-08 bước tính điểm → SCR-0801 → `GET /api/v1/appraisals/inputs` (`listAppraisalScoringInputs`, nội bộ, owner MOD-04), `POST /api/v1/appraisals/profiles/{id}/recompute` (`recomputeAppraisalProfile`, owner SVC-07) → SVC-07 → bảng `scoring.score_job` (`CHOT_KY`, `KIEM_TRA_TAI_LAP`), `scoring.score_input_v`, `scoring.score_result` → EVT-10; NFR-06; BR-08-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi saga chốt kỳ yêu cầu, scoring-service tính lại toàn bộ hồ sơ của kỳ trên ảnh chụp đóng băng bằng phiên bản Policy Pack ghim: chia lô theo đơn vị, worker co giãn theo hàng đợi, đọc đầu vào theo trang, ghi lô hiệu quả, lô lỗi thử lại 3 lần rồi hồ sơ lỗi vào hàng đợi lỗi. Khi xong, một cell lớn 1,2 triệu hồ sơ tính xong trong ≤ 2 giờ (NFR-06), tiến độ đo được để SCR-0801 hiển thị, và giám sát viên có thể yêu cầu tính lại một hồ sơ để đối chiếu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-08 bước 2 (dòng 2118–2131, đọc ảnh chụp theo lô, biên dịch một lần mỗi lô dòng 2144), BR-08-07.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-07 scoring-service" (dòng 1072–1084: đọc lô 500 hồ sơ/trang, KEDA).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema scoring (SVC-07)" thiết kế (dòng 3318–3328: 32 luồng, COPY 1.000 dòng, 8.000 hồ sơ/giây) và ngưỡng saga < 60 phút (dòng 4588).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "KEDA theo độ trễ consumer Kafka" (dòng 1250) và "KEDA theo lịch: tăng trước các mốc hạn" (dòng 1328).
- Hợp đồng: `contracts/openapi/modules/appraisals.yaml` `listAppraisalScoringInputs`, `recomputeAppraisalProfile`.
- Wiki: `wiki/09-hieu-nang-va-trai-nghiem.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Nhận yêu cầu tính chốt kỳ (từ saga DV-06-06 qua lệnh hoặc sự kiện), tạo `score_job` loại `CHOT_KY`, chia lô theo đơn vị, đẩy lô vào hàng đợi; trả mã theo dõi ngay.
- Worker: đọc đầu vào qua `listAppraisalScoringInputs` theo con trỏ (500 hồ sơ mỗi trang, không đọc CSDL lõi), biên dịch quy tắc một lần mỗi lô, tính mỗi hồ sơ độc lập cho cả hai luồng, ghi bằng đường ghi lô COPY → bảng tạm → `INSERT ... SELECT`, phát EVT-10.
- Cập nhật `total_items`, `done_items`, `failed_items` và tốc độ để tính thời gian dự kiến; lô lỗi thử lại tối đa 3 lần; hồ sơ vẫn lỗi vào hàng đợi lỗi có lý do, để DV-06-07 chạy lại.
- Chỉ dùng phiên bản Policy Pack ghim của kỳ (BR-08-07), kiểm ở đầu mỗi lô.
- Hiện thực `recomputeAppraisalProfile` (giám sát viên, Idempotency-Key, 202): tạo `score_job` loại `KIEM_TRA_TAI_LAP`, tính lại từ `score_input_v` với đúng phiên bản đã dùng, so `result_hash` với ảnh chụp hiện hành, không ghi đè.
- Cấu hình co giãn worker theo hàng đợi và cửa sổ tăng trước mốc hạn (KEDA cron cuối quý theo TL-04).
- Thử tải: dữ liệu tổng hợp 1,2 triệu hồ sơ trên môi trường perf hoặc thu nhỏ có nội suy (ghi rõ), đo thông lượng hồ sơ/giây và thời gian tổng.
- Điểm lệch cần lưu ý: TL-01 ước tính cần khoảng 167 hồ sơ/giây với 12 pod, TL-03 thiết kế đạt 8.000 hồ sơ/giây trên một cụm và saga < 60 phút, NFR-06 cho phép ≤ 2 giờ; tiêu chí đạt lấy NFR-06, ghi số đo thật để đối chiếu.
- Gợi ý tách: (a) chia lô, hàng đợi, worker, hàng đợi lỗi; (b) API tính lại đối chiếu, co giãn, thử tải.

## 4. Ngoài phạm vi

- Điều phối saga (DV-06-06); API `inputs` ở MOD-04 (CORE-04-06); chạy lại hồ sơ lỗi do người dùng yêu cầu (DV-06-07).

## 5. Tệp được phép sửa

- `backend/services/scoring-service/**`
- `deploy/charts/scoring-service/**`
- `infra/perf/` (kịch bản và dữ liệu tổng hợp thử tải tính điểm)
- `wiki/09-hieu-nang-va-trai-nghiem.md` (chỉ ghi kết quả đo), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/scoring-service -am verify` chạy xanh gồm Testcontainers cho chia lô, thử lại, hàng đợi lỗi, kiểm thử hợp đồng `recomputeAppraisalProfile`.
- Kết quả thử tải ghi trong báo cáo; đạt hoặc có nội suy rõ ràng cho mục tiêu 1,2 triệu hồ sơ ≤ 2 giờ.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
