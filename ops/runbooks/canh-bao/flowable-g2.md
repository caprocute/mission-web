# Chỉ số giám sát Flowable của workflow-service (G2)

> **Truy vết:** TL-01 bảng "Chỉ số giám sát và ngưỡng cảnh báo" của chương workflow (G2) · DV-06-11 · SVC-06 workflow-service · luật `deploy/charts/workflow-service/templates/canh-bao-g2.yaml` (ngưỡng ở `monitoring.alerts.thresholds` của values).
> **Cảnh báo:** 23 cảnh báo `Flowable*`, mỗi chỉ số hai mức (`...Cao` hoặc tên không hậu tố là warning, `...NghiemTrong` là critical); mỗi cảnh báo trỏ tới đúng mục bên dưới.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Một chỉ số của engine Flowable vượt ngưỡng của bảng G2. Chỉ số do workflow-service xuất (lấy mẫu 15 giây trên bản sao đọc, listener job, interceptor lệnh, bộ đệm định nghĩa), p95 API hoàn thành việc lấy từ `http.server.requests`, pool từ HikariCP.

## Tác động

Engine chậm làm chậm mọi luồng duyệt (kế hoạch, minh chứng, khoá kỳ, khiếu nại). Warning: SEV-3; critical: SEV-2; critical trong tuần khoá kỳ: báo IC.

## Kiểm tra nhanh

```bash
kubectl get pods -n <namespace> -l app.kubernetes.io/name=workflow-service
kubectl logs -n <namespace> deploy/workflow-service --tail=200 | grep -iE 'error|optimistic|deadletter'
```

Dashboard workflow-service G2 (các chỉ số dưới đây theo thời gian); dashboard CSDL `pg-flowable`.

## Các bước

### Job đến hạn

`FlowableJobDenHanChuaKhoaCao`, `FlowableJobDenHanChuaKhoaNghiemTrong` (số job đến hạn chưa được khoá vượt 2.000, 10.000 trong 5 phút): async executor không theo kịp. Kiểm số pod executor so với `maxReplicas` và KEDA; kiểm CSDL `pg-flowable` (CPU, khoá chờ); nếu tải đỉnh thật, tăng `maxReplicas` executor qua kho GitOps trong ngân sách kết nối. Chạm liên tục là tín hiệu NG-02 ([nang-bac](nang-bac.md)).

### Độ trễ nhận job

`FlowableDoTreNhanJobCao`, `FlowableDoTreNhanJobNghiemTrong` (p95 từ đến hạn tới bắt đầu chạy vượt 10 giây, 60 giây): cùng nguyên nhân với mục "Job đến hạn"; ngoài ra kiểm cấu hình chu kỳ lấy job và số luồng executor chưa bị đổi ở bản phát hành gần nhất.

### Deadletter

`FlowableDeadletterTang`, `FlowableDeadletterNghiemTrong` (hơn 10, 100 job chuyển sang `ACT_RU_DEADLETTER_JOB` trong một giờ): job lỗi hết số lần thử. Đọc lỗi của job trong deadletter qua API quản trị của workflow-service hoặc log; lỗi do dịch vụ đích (mission-core, notification) thì sửa phía đó rồi đưa job về hàng đợi; lỗi mã thì báo chủ service. Không xoá job deadletter khi chưa phân loại.

### Optimistic locking

`FlowableXungDotKhoaLacQuanCao`, `FlowableXungDotKhoaLacQuanNghiemTrong` (tỷ lệ lệnh gặp `FlowableOptimisticLockingException` vượt 0,5%, 2%): hai giao dịch cùng cập nhật một execution. Thường do nhánh song song hoặc timer chạm cùng lúc với người dùng; kiểm định nghĩa vừa phát hành (đánh dấu async đúng chỗ), báo chủ service.

### Complete API

`FlowableHoanThanhViecChamCao`, `FlowableHoanThanhViecChamNghiemTrong` (p95 API hoàn thành việc vượt 300 ms, 800 ms): người duyệt thấy bấm duyệt chậm. Kiểm CSDL `pg-flowable` ([pg-cpu](pg-cpu.md)), pool kết nối (mục "Pool"), và số dòng runtime (mục "Execution").

### External worker

`FlowableExternalWorkerChamCao`, `FlowableExternalWorkerChamNghiemTrong` (job external worker chờ p95 vượt 30 giây, 5 phút): worker không lấy kịp job. Kiểm worker (mission-core và dịch vụ khác) còn chạy và đăng ký đúng chủ đề; khởi động lại worker treo.

### Execution

`FlowableExecutionNhieu`, `FlowableExecutionNghiemTrong` (`ACT_RU_EXECUTION` vượt 5 triệu, 10 triệu dòng): runtime phình. Kiểm tiến trình kẹt không kết thúc (timer không bao giờ chạy, chờ tín hiệu không tới), định nghĩa tạo execution thừa; báo chủ service trước khi đụng dữ liệu.

### Lịch sử

`FlowableLichSuTangNhanh` (`ACT_HI_*` tăng trong ngày vượt 1,5 lần trung bình 30 ngày), `FlowableDonLichSuLoi` (job dọn lịch sử lỗi hoặc hai ngày chưa chạy xong): kiểm job dọn lịch sử trong log và lịch chạy; chạy lại sau khi sửa nguyên nhân (thường là quá hạn câu lệnh trên bảng lớn).

### Cache

`FlowableCacheDinhNghiaTruotCao`, `FlowableCacheDinhNghiaTruotNghiemTrong` (tỷ lệ trượt bộ đệm định nghĩa vượt 1%, 5%): số định nghĩa dùng vượt kích thước bộ đệm. Kiểm mục "Định nghĩa" trước; nếu số định nghĩa hợp lệ, tăng kích thước bộ đệm qua values.

### Định nghĩa

`FlowableDinhNghiaHoatDongNhieu`, `FlowableDinhNghiaHoatDongNghiemTrong` (hơn 300, 1.000 định nghĩa tiến trình đang hoạt động): chỉ số "chống mạng nhện", nghĩa là quy tắc quản trị mẫu tiến trình đang bị vi phạm (nhân bản định nghĩa thay vì tham số theo tenant). Báo chủ sản phẩm và chủ service; không xoá định nghĩa đang có tiến trình chạy.

### Pool

`FlowablePoolKetNoiCao`, `FlowablePoolKetNoiNghiemTrong` (pool HikariCP `workflow-service-hikari` dùng quá 70%, 90%): kiểm giao dịch dài, CSDL chậm ([pg-cpu](pg-cpu.md), [pgbouncer](pgbouncer.md)); không tăng kích thước pool khi chưa kiểm ngân sách kết nối (`deploy/capacity/kiem_ngan_sach_ket_noi.py`).

### Lấy mẫu

`FlowableLayMauChiSoLoi` (lấy mẫu chỉ số G2 từ bản sao đọc lỗi hơn 3 lần trong 15 phút): các chỉ số khác đang giữ giá trị đọc được gần nhất, nên có thể không phản ánh thực tế. Kiểm kết nối tới bản sao đọc của `pg-flowable` và quyền đọc của tài khoản lấy mẫu.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Khởi động lại workflow-service hoặc worker | `vtctl -c <cell> consumer restart --deployment <tên> -n <namespace> --confirm` | `kubectl rollout restart deployment/<tên> -n <namespace>` |

## Kiểm sau khi làm

Chỉ số về dưới ngưỡng warning; cảnh báo tự đóng; hộp việc của người duyệt cập nhật bình thường.

## Quay lui

Revert thay đổi values (replica, bộ đệm, pool) qua kho GitOps.

## Leo thang

Critical kéo dài quá 30 phút, hoặc bất kỳ critical nào trong tuần khoá kỳ: gọi chủ service workflow và DBA cấp 2.

## Liên quan

[nang-bac](nang-bac.md) (NG-02), `../saga-chot-ky.md`, `../doi-soat-ban-sao-to-chuc.md`.
