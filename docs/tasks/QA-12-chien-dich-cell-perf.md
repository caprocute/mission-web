# QA-12 · TL-05 ch.12 Chiến dịch thử tải trên CELL-PERF và hiệu chuẩn bảng định cỡ theo hồ sơ cell

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh QA-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (hồ sơ M ở QM-1..QM-3; bộ đầy đủ hồ sơ L trước QM-4) |
| Luồng đề xuất | B9 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | QA-09, QA-10, QA-11, INF-21 |
| Truy vết | NFR-01, NFR-03, NFR-06 → TL-04 mục 7.3, 7.8; TL-05 mục 12.1–12.3, 11.2 → PERF-01..PERF-26, GD-xx, NG-01..NG-14 → `CELL-PERF`, cụm `perf-gen` |
| Trạng thái | Bị chặn bởi bên ngoài: cần `CELL-PERF` trên phần cứng cùng loại sản xuất (kiểm tạm bằng chiến dịch thu nhỏ trên kind hoặc staging) |

## 1. Mục tiêu

Chạy chương trình thử tải theo hồ sơ cell, lập báo cáo theo mẫu cố định và đưa số đo về mô hình định cỡ: cập nhật `assumptions.yaml`, sinh lại các bảng định cỡ, điều chỉnh `maxReplicas` và ngân sách kết nối khi lệch vượt ngưỡng. Khi xong, mỗi hồ sơ cell dùng cho sản xuất có bộ kịch bản tối thiểu đạt ở đúng tải thiết kế, và quy trình hiệu chuẩn chạy lặp được sau mỗi chiến dịch và mỗi kỳ đánh giá quý.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` mục 12.1 "Nguyên tắc" (dòng 731), 12.2 "Bộ kịch bản theo hồ sơ" (dòng 735), 12.3 "Vòng hiệu chuẩn" (dòng 751); mục 11.2 "Danh mục ngưỡng NG" (dòng 700), 11.3 "Quy trình nâng bậc" (dòng 721); mục 7.2 "Môi trường phi sản xuất" (dòng 526).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục 7.3, 7.4 (đoạn "Lịch chạy"), 7.8 (mẫu báo cáo, chỉ số "Phiên đồng thời đạt SLO"); mục 4.7 (ngân sách kết nối).
- `docs/tasks/QA-08-k6-perf-01-10.md`, `QA-09`, `QA-10`, `QA-11`, `INF-03-values-dinh-co-cell.md`, `INF-21-moi-truong-phi-san-xuat.md`.

## 3. Phạm vi công việc

- Viết kế hoạch chiến dịch `infra/perf/chien-dich/<năm>-<quý>.md`: thứ tự kịch bản, tải theo hồ sơ (bảng mục 12.2), dữ liệu, cửa sổ thời gian (≥ 3 tuần trước tuần cuối quý, ngoài cửa sổ đóng băng OPS-06).
- Script điều phối `infra/perf/chay-chien-dich.sh` tạo `TestRun` k6 và Gatling theo danh sách, chờ xong, thu báo cáo; không chạy hai kịch bản tải nặng chồng nhau.
- Báo cáo theo mẫu cố định của mục 7.8 lưu tại `infra/perf/ket-qua/<ngày>-<PERF-xx>/` kèm digest ảnh, tệp values và mã kịch bản, để tái lập.
- Dùng `deploy/capacity/assumptions.yaml`, `tinh_dinh_co.py` và `kiem_ngan_sach_ket_noi.py` do INF-03 viết; bổ sung script `deploy/capacity/trich_so_do.py` lấy số đo từ báo cáo chiến dịch để điền GD-xx (nguồn = số đo, ngày, kịch bản).
- Vòng hiệu chuẩn năm bước theo mục 12.3: trích số đo (Prometheus, Faro, báo cáo k6); merge request cập nhật `assumptions.yaml` ghi giá trị cũ, mới; tính lại; cập nhật bảng mục 4.3 khi thông lượng một pod lệch > 15% (PERF-02, OPS-25) và yêu cầu thay đổi values khi values thiếu > 20% so với mô hình tính lại (TL-05 mục 12.3); kiểm ngân sách kết nối CSDL bằng CI.
- Ghi chỉ số "Phiên đồng thời đạt SLO (p95 ≤ 1 s)" sau mỗi chiến dịch vào bảng chỉ số của VH-09.
- Kiểm tạm khi chưa có `CELL-PERF`: chạy chiến dịch thu nhỏ hồ sơ compact trên kind hoặc staging, đi trọn vòng hiệu chuẩn (số đo giả định được đánh dấu "không dùng cho sản xuất").
- Gợi ý tách: (a) điều phối và báo cáo; (b) `assumptions.yaml`, script tính và kiểm CI; (c) chiến dịch thật hồ sơ M; (d) chiến dịch hồ sơ L trước QM-4.

## 4. Ngoài phạm vi

- Dựng `CELL-PERF` và `perf-gen` (INF-21); viết kịch bản (QA-08..QA-11); quyết định mua sắm phần cứng (đầu ra là đề xuất).

## 5. Tệp được phép sửa

- `infra/perf/chien-dich/**`, `infra/perf/chay-chien-dich.sh`, `infra/perf/ket-qua/**`, `deploy/capacity/assumptions.yaml` (giá trị đo), `deploy/capacity/trich_so_do.py`, `deploy/environments/perf/**` (chỉ values điều chỉnh sau hiệu chuẩn).

## 6. Tiêu chí hoàn thành

- `python deploy/capacity/trich_so_do.py --bao-cao infra/perf/ket-qua/<lần-chạy>` cập nhật được GD-08, GD-09 từ báo cáo PERF-02; `python deploy/capacity/kiem_ngan_sach_ket_noi.py` chạy xanh trên values hiện hành; một merge request thử tăng `maxReplicas` vượt ngân sách bị CI từ chối.
- Chiến dịch thu nhỏ trên kind hoặc staging chạy trọn và sinh đủ báo cáo cho PERF-01, PERF-02, PERF-04, PERF-11.
- Khi có `CELL-PERF`: bộ kịch bản mục 12.2 đạt ở hồ sơ M; báo cáo nêu thành phần chạm giới hạn đầu tiên và các GD đã cập nhật. Khi chưa có: báo cáo ghi "Bị chặn bởi bên ngoài".

## 7. Báo cáo

Theo AGENTS.md mục 6.

### Điểm lệch cần lưu ý

- Ngưỡng hiệu chuẩn: TL-04 (PERF-02, OPS-25) dùng 15% giữa số đo và mô hình; TL-05 mục 12.3 dùng 20% giữa values và mô hình tính lại. Hai ngưỡng đo hai khoảng cách khác nhau, giữ cả hai.
- TL-04 mục 7.8 lưu kết quả trong repo riêng `perf-results`; TL-05 mục 12.3 lưu mô hình ở `capacity/assumptions.yaml` của kho GitOps. Thẻ này đặt cả hai trong repo hệ thống (`infra/perf/ket-qua/`, `deploy/capacity/`) theo cấu trúc chung (suy luận); nếu tách repo, chỉ đổi vị trí.
