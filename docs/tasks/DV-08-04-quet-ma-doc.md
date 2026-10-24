# DV-08-04 · QT-07/SVC-08 Quét mã độc ClamAV bất đồng bộ, cách ly, thông báo

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-08-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | DV-08-02 |
| Truy vết | QT-07 → SCR-0701 (cột "Kết quả quét") → `GET /api/v1/files/{objectId}/scan` (`getFileScanResult`) → SVC-08 → `evidence.scan_result` → sự kiện kỹ thuật `evidence.object.scanned.v1` (MOD-03 tiêu thụ), EVT-21; BR-07-04; MSG-07-02; SEC-47 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mọi tệp tải lên (trừ tệp C3) được quét mã độc và kiểm định dạng ở nền, không chặn đường găng tải lên, có kết luận trong thời gian ngắn; tệp nhiễm bị cách ly và người nộp được báo. Khi xong, MOD-03 chỉ cho nộp minh chứng có tệp sạch (BR-07-04) dựa trên sự kiện kết quả quét, và trình duyệt theo dõi được trạng thái quét.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-08 evidence-service" (dòng 1086–1106: ClamAV 1.4 daemon riêng, worker KEDA) và bảng phần bất đồng bộ (dòng 2366: quét 0,5 đến 2 s, C3 không quét).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema evidence (SVC-08)" (dòng 3397) và "Bảng evidence.scan_result"; mục SVC-08 (dòng 519: `evidence.object.scanned.v1`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07 BR-07-04 (dòng 1924), MSG-07-02 (dòng 1987).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` SEC-47, SEC-48 (mục 9.5) và mục "KEDA theo độ trễ consumer Kafka" (dòng 1250).
- Hợp đồng: `contracts/openapi/modules/products.yaml` `getFileScanResult`.

## 3. Phạm vi công việc

- Sau khi hoàn tất tải lên (sự kiện `evidence.object.stored.v1` hoặc hàng đợi nội bộ), worker gửi đối tượng tới ClamAV 1.4 (clamd, luồng INSTREAM), ghi `scan_result` với `engine`, `engine_version` (phiên bản chữ ký), `verdict`, `detail`, `scanned_at`.
- Kiểm định dạng: nội dung thật khớp kiểu khai báo, tài liệu Office không chứa macro (SEC-48); vi phạm cho kết luận không sạch.
- Phát `evidence.object.scanned.v1` qua outbox cho MOD-03; với tệp nhiễm, chuyển đối tượng sang vùng cách ly (từ chối cấp URL tải, giữ nguyên Object Lock) và phát EVT-21 để thông báo MSG-07-02 tới người nộp.
- `getFileScanResult` trả trạng thái hiện tại; trước khi có kết quả trả `CHO_QUET`.
- Lỗi bộ quét: thử lại có giãn cách; quá số lần thì kết luận `LOI` và báo vận hành; tệp `LOI` vẫn không nộp được (BR-07-04).
- Tệp C3 không quét ở máy chủ (đã quét trên thiết bị trước khi mã hoá theo hợp đồng); ghi rõ trạng thái không áp dụng.
- Chart: worker quét tách khỏi API, co giãn KEDA theo độ trễ hàng đợi; sidecar hoặc Deployment clamd có cập nhật chữ ký (freshclam qua kho nội bộ ở môi trường cách ly).
- Đo thời gian từ hoàn tất tải lên tới có kết luận; mục tiêu p95 ≤ 2 phút cuối kỳ (ngưỡng từ khung kế hoạch; TL-01 ước 0,5 đến 2 s mỗi tệp, suy luận cho p95 tổng gồm hàng đợi).
- Điểm lệch cần lưu ý: giá trị kết luận ở TL-03 là `SACH`, `NHIEM`, `NGHI_NGO`, `LOI`, còn hợp đồng là `CHO_QUET`, `SACH`, `NHIEM`, `LOI`; chọn lưu `verdict` theo TL-03, API ánh xạ `NGHI_NGO` về `NHIEM` cho người dùng (suy luận) và ghi wiki trang 14 để thống nhất hợp đồng.

## 4. Ngoài phạm vi

- Chặn nộp ở MOD-03 (CORE-03); gửi thông báo thật (SVC-12).

## 5. Tệp được phép sửa

- `backend/services/evidence-service/**`
- `deploy/charts/evidence-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/evidence-service -am verify` chạy xanh gồm Testcontainers với ClamAV thật: tệp EICAR cho `NHIEM`, URL tải bị từ chối, có sự kiện thông báo; tệp sạch cho `SACH`; DOCX có macro bị chặn; bộ quét tắt cho `LOI` sau số lần thử.
- `helm lint` và `kubeconform -strict` sạch cho chart có ScaledObject.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
