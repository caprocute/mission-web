# DV-09-04 · QT-13/EVT-20 Kiểm tra toàn vẹn định kỳ, chỉ ra bản ghi bị phá

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-09-03 |
| Truy vết | QT-13 (BR-13-10, BR-13-11, MSG-13-05, MSG-13-06) → API `POST /api/v1/ledger/verify-runs`, `GET /api/v1/ledger/verify-runs/{id}` → SVC-09 → bảng `ledger.verify_run`, `ledger.chain_head` → EVT-20 `analytics.anomaly.detected.v1` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực bộ kiểm tra toàn vẹn sổ cái chạy song song theo chuỗi: kiểm liên kết `prev_hash`, tính lại `entry_hash`, đối chiếu gốc Merkle với niêm phong, chỉ ra chuỗi và bản ghi đầu tiên bị phá, khoá ghi phân vùng đó và phát EVT-20 mức Cao. Khi xong, cơ quan kiểm tra chạy được kiểm tra theo yêu cầu (202) và hệ thống tự kiểm theo lịch, mọi lần chạy có kết quả lưu trong `verify_run`.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` từ điển `ledger.verify_run` (L3666) và "Kiểm tra toàn vẹn" (L4138).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-13 "Quy tắc nghiệp vụ" (L999, BR-13-10, BR-13-11), "Ngoại lệ và xử lý" (L1016), SCR-1302 (L1078, bảng chức năng và MSG-13-05, MSG-13-06).
- `contracts/openapi/modules/oversight.yaml` các operation `listLedgerVerifyRuns`, `createLedgerVerifyRun`, `getLedgerVerifyRun`; `contracts/openapi/modules/jobs.yaml` (`getJob`).
- `TL-04-trien-khai-van-hanh.md` "Kịch bản riêng cho góp ý G3" (L1810, PERF-16 phần `verify_run`).
- Bản trích `extract/tl02b-may-chu.md` QT-13 (BE-Q13-09), QT-14 (BR-14-07).

## 3. Phạm vi công việc

- Viết gói `internal/verify` chạy song song theo chuỗi (mặc định 8 luồng), mỗi chuỗi kiểm `seq_no` liên tục, `prev_hash` bằng `entry_hash` liền trước, tính lại `entry_hash` từ `payload_hash`, `metadata_hash`; dựng lại gốc tenant tại mỗi niêm phong trong phạm vi và so với `merkle_seal.tenant_root`, kiểm đường đi lên `cell_seal`.
- Hiện thực bốn loại `verify_kind`: `DAU_CHUOI` (đầu chuỗi so với niêm phong gần nhất, mỗi 15 phút), `NIEM_PHONG` (ngay sau mỗi lần niêm phong), `TOAN_BO` (hằng tuần, mỗi tuần 1/4 số tenant), `LUU_TRU` (khung gọi cho DL-12/DL-13, đọc tệp Parquet mẫu 5% mỗi quý; phần đọc Parquet có thể để giao diện chờ).
- Hiện thực `createLedgerVerifyRun` (202 + `Location: /api/v1/jobs/{id}`, chỉ `inspector`, có `Idempotency-Key`), `getLedgerVerifyRun`, `listLedgerVerifyRuns` (phân trang con trỏ, `limit` ≤ 200) đúng lược đồ hợp đồng; ghi `mismatches`, `first_mismatch`, `result_json` (sơ đồ khối prev, hash, trạng thái khớp cho SCR-1302 trường 5).
- Khi phát hiện phá vỡ: khoá ghi phân vùng chuỗi tương ứng (cờ trong `chain_head` hoặc bảng trạng thái của service, suy luận), phát EVT-20 mức Cao qua outbox cùng giao dịch ghi `verify_run`, gửi cảnh báo nghiêm trọng (metric + log kiểm toán) cho SOC; nội dung thông báo theo MSG-13-06.
- Mọi lần gọi API kiểm tra được ghi lại thành sự kiện kiểm toán vào sổ cái qua `sys.audit.v1` ("mọi lần tra cứu được ghi sổ cái", SCR-1302).
- Kiểm quyền bằng vai trò trong token và OPA theo `wiki/15a` dòng SCR-1302 (`inspector` chạy, `appraiser` chỉ xem trong phạm vi đơn vị).

## 4. Ngoài phạm vi

- Consumer EVT-20 tạo `anomaly_alert` ở MOD-07 (CORE-07) và thông báo tới quản trị ATTT (DV-12-01).
- Công cụ dòng lệnh độc lập `ledger-verify` và API xuất bằng chứng (DV-09-05); job đối soát R2 sổ cái ↔ nghiệp vụ (DL-12).

## 5. Tệp được phép sửa

- `services-go/ledger/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (nếu chốt cách khoá ghi phân vùng)

## 6. Tiêu chí hoàn thành

- `cd services-go/ledger && go test ./...` xanh; kiểm thử tích hợp sửa trực tiếp một bản ghi bằng tài khoản superuser trong Testcontainers (tắt trigger), lần kiểm tra sau chỉ đúng chuỗi và `seq_no` bị phá, phát đúng một EVT-20 và khoá ghi phân vùng.
- Kiểm thử hợp đồng: phản hồi của ba operation khớp `oversight.yaml` (so khớp lược đồ JSON); 403 với vai trò không được phép; 202 kèm `Location`.
- Kiểm 2 giờ dữ liệu ở tải PERF-16 ≤ 5 phút; 1,28 triệu bản ghi với 8 luồng trong khoảng 48 giây trên máy thử là mục tiêu tham khảo (ghi số đo thật vào báo cáo).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
