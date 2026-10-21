# Sổ tay vận hành: Phát lại trạng thái tổ chức (`org-republish`)

> **Mã truy vết:** DV-04-08 · SVC-04 · EVT-14, EVT-15, EVT-16 · TL-03 "Event-carried State Transfer cho dữ liệu tổ chức" và bảng đối soát bản sao tổ chức (256 bucket băm) · DL-12.
> **Công cụ:** ảnh `org-service`, hồ sơ Spring `republish`, chạy như Kubernetes Job dựng từ `deploy/charts/org-service/templates/republish-job.yaml`.
> **Topic:** `org.bootstrap.v1` (thông điệp khởi tạo), `sys.audit.v1` (một sự kiện kiểm toán mỗi lần chạy).

## 1. Khi nào dùng

Topic `org.*` chỉ giữ sự kiện 30 ngày, nên một bản sao tổ chức trống hoặc bị lệch không thể chờ dữ liệu tự hội tụ. Lệnh này đẩy trạng thái hiện hành của mọi đơn vị, nhân sự và phân công của **một tenant** lên `org.bootstrap.v1`. Bên nhận đọc topic này, ghi vào bản sao bằng upsert có điều kiện `source_version`, rồi tiếp tục đọc topic thường. Có ba tình huống điển hình:

1. Thêm một bên nhận mới cần bản sao tổ chức, ví dụ một service mới đăng ký consumer `org_replica`.
2. Dựng lại bản sao sau sự cố, ví dụ khôi phục CSDL của bên nhận từ bản sao lưu cũ hơn 30 ngày.
3. Job đối soát DL-12 báo một số bucket lệch: chỉ phát lại các bucket đó bằng `--bucket`.

Không dùng lệnh này để sửa dữ liệu nguồn. Nếu nguồn sai thì sửa ở SVC-04 qua quyết định tổ chức; sự kiện thường sẽ tự lan ra.

## 2. Tham số

| Tham số | Ý nghĩa |
|---|---|
| `--tenant=<uuid>` | Bắt buộc. Tenant cần phát lại. |
| `--entity=unit\|assignment\|person` | Tuỳ chọn, lặp lại hoặc phân tách bằng dấu phẩy. Bỏ trống là cả ba loại. |
| `--bucket=<0..255>` | Tuỳ chọn, lặp lại hoặc phân tách bằng dấu phẩy. Bỏ trống là mọi bucket. |
| `--as-of=<ISO-8601>` | Tuỳ chọn, có múi giờ, ví dụ `2026-10-21T00:00:00+07:00`. Bỏ trống là thời điểm hiện tại. Đây là thời điểm ghi nhận của ảnh chụp. |
| `--dry-run` | Chỉ đếm và tính băm, không phát thông điệp khởi tạo; vẫn ghi kiểm toán. |
| `--operator=<mã tài khoản hoặc tên>` | Người chạy. Mã tài khoản dạng UUID được ghi vào tiêu đề `actorid` của sự kiện kiểm toán. |

Mã thoát của tiến trình là 0 khi thành công, 2 khi tham số sai và 1 khi lỗi lúc chạy. Job đặt `backoffLimit: 0`, nên lỗi không tự chạy lại; người vận hành xem log rồi chạy lại khi cần. Chạy lại an toàn vì bên nhận bỏ qua bản ghi có `source_version` không lớn hơn bản đang giữ.

## 3. Chạy thử

Giá trị mặc định của chart là `republish.dryRun=true`. Lần chạy thử cho biết số thực thể và băm tổng theo bucket mà không phát gì lên `org.bootstrap.v1`:

```bash
helm template org-service deploy/charts/org-service \
  -f deploy/environments/<môi-trường>/org-service.yaml \
  -s templates/republish-job.yaml \
  --set republish.enabled=true \
  --set republish.tenant=<uuid-tenant> \
  --set republish.operator=<mã-tài-khoản-người-chạy> \
  | kubectl -n <namespace-org> create -f -

kubectl -n <namespace-org> logs -f job/<tên-job-vừa-tạo>
```

Dòng log `org-republish batch=... dryRun=true entities={ORG_UNIT=..., PERSON=..., ASSIGNMENT=...}` cho số thực thể. Đối chiếu số này với số dòng hiện hành ở CSDL nguồn trước khi chạy thật.

## 4. Chạy thật

Thêm `--set republish.dryRun=false`. Muốn phát lại có chọn lọc thì thêm `--set 'republish.entities={person}'` hoặc `--set 'republish.buckets={17,203}'`. Job cần quyền mạng ra Kafka và Apicurio Registry; chính sách mạng của namespace phải cho phép, giống pod ứng dụng khi phát qua outbox. Thông tin xác thực Kafka, nếu có, nằm trong Secret do External Secrets đồng bộ, khai ở `republish.kafkaClientSecret` dưới dạng biến môi trường `OKRKPI_ORG_REPUBLISH_KAFKA_<KHOÁ>`. Không ghi mật khẩu vào values hay lệnh.

Khi xong, log có dòng `org-republish xong batch=<mã> entities=<số> throughput=<số>/s`. Mã `batch` là `batchId` của thông điệp khởi tạo, đồng thời là `objectId` của sự kiện kiểm toán `PHAT_LAI_TO_CHUC` trên `sys.audit.v1`. Đo trên Testcontainers, tenant 100.000 nhân sự, 100.000 phân công và 1.111 đơn vị phát xong khoảng 4,3 giây; môi trường thật chậm hơn do mạng và xác thực, nhưng mục tiêu dưới 30 phút còn dư nhiều.

## 5. Xác nhận bên nhận đã đủ và chuyển sang topic thường

1. Bên nhận nhận thông điệp `BAT_DAU`, các thông điệp thực thể, và thông điệp `KET_THUC` mang `entityCounts` và `bucketHashes`. Topic có nhiều partition nên `KET_THUC` có thể đến trước một số thông điệp thực thể; bên nhận coi là đủ khi số thông điệp thực thể của `batchId` khớp `entityCounts`.
2. Kiểm tra độ trễ consumer của bên nhận trên `org.bootstrap.v1` về 0, rồi kiểm tra bên nhận đã đọc tiếp các topic thường `org.unit.changed.v1`, `org.assignment.changed.v1`, `org.person.changed.v1`.
3. Chạy job đối soát DL-12 cho tenant đó. Băm tổng của từng bucket ở bản sao phải bằng giá trị trong `bucketHashes` của `KET_THUC`, hoặc bằng kết quả chạy thử mới của lệnh nếu nguồn đã thay đổi sau lần phát lại. Cách tính băm ở wiki trang 18, mục ECST.
4. Nếu còn bucket lệch, chạy lại lệnh với `--bucket` của các bucket đó. Nếu lệch lặp lại ở cùng bucket, báo luồng phụ trách bên nhận vì lỗi nằm ở phía ghi bản sao.

## 6. Ghi chú

- Lệnh đọc bằng tài khoản `svc_org` trong một giao dịch `REPEATABLE READ` chỉ đọc, có RLS theo tenant; không đọc được dữ liệu tenant khác.
- Thông điệp chỉ mang dữ liệu C1 như EVT-14, EVT-15, EVT-16; không mang số định danh, ngày sinh hay số thẻ đảng viên.
- Metric `org.republish.duration` và `org.republish.entities` được ghi trong tiến trình. Job ngắn nên Prometheus có thể không kịp thu; log của Job là bản ghi chính (suy luận).
