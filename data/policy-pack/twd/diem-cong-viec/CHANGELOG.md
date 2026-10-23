# Nhật ký thay đổi – Gói điểm công việc của hồ sơ TWĐ (`twd/diem-cong-viec`)

## 0.1.0 – bản nháp giả lập (22/10/2026, DL-16)

Gói là thành phần Policy Pack của hồ sơ tập khách hàng `twd/ban-to-chuc-ueb` (BS-03, ADR-33), kế thừa `khoi-dang/quy-dinh-moi` 2.1.0. Gói đánh dấu `metadata.gia_lap: true` theo YC-PT-14 vì dự thảo Quy định kiểm điểm, đánh giá, xếp loại (12/10/2026) chưa ban hành và nhiều tham số còn chờ Ban Tổ chức Trung ương chọn (UEB-BTCTW-QTCV-02 v1.2 mục 33.2). Trạng thái `NHAP`, chưa có người duyệt, chưa ca nào được ký.

### Nội dung

- Điểm công việc Đ = điểm chuẩn × hệ số khó × hệ số nhóm × các hệ số bổ sung đang bật (ba ô dự phòng `BO_SUNG_1..3`, mặc định tắt). Bảng hệ số khó 0,8 / 1 / 1,25 / 1,6 / 2,0 theo mức 1–5 và hệ số nhóm then chốt 1,3, thường xuyên 1,0 là tham số theo kỳ; lõi chỉ lưu mức (DC-01).
- Ba mặt điểm của một việc: số lượng bằng Đ khi được duyệt; chất lượng Đ·m với m trên thang 1,00 / 0,75 / 0,50 / 0,25 / 0; tiến độ max(0, Đ·(1 − 0,05·n)) với n là số ngày làm việc chậm do chủ quan. Điểm đạt là trung bình ba mặt. Việc chuyển kỳ hoặc ngoài phạm vi phụ trách không tính; việc chưa hoàn thành tính vào điểm được giao, đạt 0.
- Điểm quản lý theo phạm vi phụ trách (DC-03): SL = S·r, CL = Q − (1 − m)·S, TĐ = D − 0,05·n·S, áp cho KPI, nhiệm vụ, KR; mục cần khắc phục có m tối đa 0,75.
- Hai chế độ đưa về thang 100: "tỷ lệ có hệ số" 100·Σđạt/Σgiao (mặc định của hồ sơ) và "gói điểm" theo nhóm đối tượng, có tỷ trọng theo trục; một nhóm duy nhất trong kỳ nhận đủ 100. Tổng điểm làm tròn hai chữ số, rồi một chữ số để xếp loại.
- Điểm năm theo cách (a) chỉ các quý trên phần mềm (mặc định) hoặc (b) cộng kết quả các quý trước.
- Chuỗi xếp loại sáu bước (DC-05) bằng bảy bảng quyết định trong `dmn/`: trần theo điểm 90/80/60, trần theo tiêu chí chung, lấy mức thấp hơn, điều kiện Xuất sắc (100% việc trọng tâm, từ 30% việc vượt, giải pháp đột phá, không có trọng tâm trượt do chủ quan, chặn khi không có việc trọng tâm – DR09), điều kiện Tốt, ràng buộc loại trừ và ràng buộc tập thể – người đứng đầu, giữa các kỳ. Bước điều kiện Xuất sắc trả danh sách điều kiện còn thiếu để ghép câu giải thích.
- Không đặt `quotas` (giới hạn tỷ lệ mức xếp loại không áp cho hồ sơ này, `xep_loai.han_muc_xuat_sac: KHONG_AP_DUNG`).

### Bộ ca vàng

`tests/bo-ca-mau-twd.yaml` có 1441 ca sinh bằng `data/golden/twd/sinh_ca_twd.py` từ Phụ lục 01 và 01G của UEB (tệp nguồn không nằm trong repo): mọi việc lá của cây toàn trình và cây thường xuyên, mọi mục quản lý, điểm 120 người theo hai chế độ, điểm năm hai cách, chuỗi xếp loại 120 người (mức gợi ý 3 / 113 / 4 / 0) và 15 tập thể, các ca G1–G7, G10, G11, G13, 32.4 tình huống 1–4 và 20, cùng các ca CTT, CTB có kết quả là con số. Trình chạy tham chiếu DL-03 đạt 1441/1441. Các ca kiểm luồng, chặn, thông báo nằm ở `data/golden/twd/ca-nghiem-thu.yaml`, gắn task lõi chịu trách nhiệm.

### Suy luận khi ánh xạ sang hợp đồng

- Trạng thái "Đã chuyển kỳ" (CK) của UEB ánh xạ sang `WorkItem.deferredByDecision = true`.
- Trạng thái "Không hoàn thành" (KHT) của UEB ánh xạ sang `statusAtLock` khác `HOAN_THANH`; về điểm hai trường hợp như nhau (giao Đ, đạt 0).
- Mức chất lượng m dùng `QualityLevel` năm mức của baseline, trùng thang của UEB.

### Chỗ hợp đồng còn thiếu (ghi ở wiki 14, chuyển NEN-044, CORE-04-13)

- `ResponsibilityItem` chưa có điểm được giao của mục và nhóm tính điểm (`taskGroup`), nên lõi chưa tách được điểm quản lý theo trọng tâm và thường xuyên như UEB 11.5; gói đọc các trường này ở kiểu `MucQuanLy` của mình.
- `ResponsibilityItem` chưa có cờ "cần khắc phục" để áp trần m = 0,75.
- `WorkItem.statusAtLock` chưa có giá trị riêng cho "Không hoàn thành" và "Đã chuyển kỳ"; `WorkItem` chưa có cờ "lãnh đạo trực tiếp làm" để áp tham số `truc_tiep_lam.cap_ap_dung`.
- Tệp gói viết theo dạng khai báo chung của các gói hiện có (`inputs` dạng ánh xạ, `classification` dạng riêng), nên khi trả qua API vẫn đi qua phần chuẩn hoá của `PolicyPackController` đã ghi ở wiki 14 (CORE-05-09, mục 7).
