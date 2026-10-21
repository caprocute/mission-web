# Trạng thái luồng B11 (tạm dừng 21/10/2026)

Luồng B11 tạm dừng để nhường chỗ cho backend (trần 5 luồng, YC-PT-12). DL-01, DL-02, DL-03, DL-05 bản giả lập đã có trên main. Nhánh `claude/b11-noi-dung-26rl4b` chứa phần dở dưới đây, chưa gộp.

## DL-04: kiểm thử nạp bảng chuyển trạng thái và tham số (dở)

- Đã viết `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/TaskLifecycleLoadTest.java` và `PolicyPackFixtures.java`. Kiểm thử đọc thẳng `data/policy-pack/`, kiểm bộ trạng thái chuẩn, lệnh, không trùng cặp from:command, vai trò theo `docs/phan-quyen/ma-tran.json`, biên dịch mọi guard bằng `vt-cel`, giá trị mặc định của tham số nằm trong miền, tham số `workflow-bindings` nằm trong miền QT-15.
- Lần chạy cuối: 9/10 đạt. Ca `guardNghiemThuChiChoQuaKhiMoiMinhChungDaXacNhan` lỗi `POLICY-422-CEL-TIMEOUT` vì lần chạy CEL đầu tiên khi JVM còn lạnh mất 57 ms, vượt giới hạn 50 ms của sandbox. Bước tiếp theo: làm nóng engine trước (gọi một lần trong `@BeforeAll`) hoặc tạo `CelEngine` với `CelSandboxOptions` có thời hạn dài hơn cho kiểm thử; cần cân nhắc cả việc giới hạn 50 ms gặp JVM lạnh ở máy chủ thật (ghi vào wiki 14).
- Cách chạy hiện tại phải tạm bỏ `architecture/ModuleApisUnitTest.java` khỏi thư mục (tệp này không biên dịch trên main: thiếu `PeriodStatus.KHOI_TAO`, WIP của CORE-00-04), rồi chạy `mvn -B -pl mission-core -am test -Dtest=TaskLifecycleLoadTest -Dsurefire.failIfNoSpecifiedTests=false`.
- Môi trường Ubuntu: `tools/contracts-bundle.sh` được gọi bằng `sh` (dash) nhưng dùng `set -o pipefail`, nên build `vt-api-contract` hỏng; tạm chạy với `sh` trỏ tới bash trong PATH. `./mvnw` tải Maven bằng wget thất bại qua proxy; dùng `mvn` của hệ thống.

## Việc tiếp theo của luồng

1. DL-04: xử lý lỗi thời gian chạy lạnh ở trên, chạy đủ 10/10.
2. DL-01, DL-03: kiểm thử chạy bộ ca mẫu giả lập qua `vt-cel` (biên dịch mọi biểu thức trong `policy.yaml`, so kết quả điểm, tỷ lệ vượt, KPI từng nhiệm vụ với `mong_doi`). Đã kiểm `CelEngine`: biến map khai báo `MAP_STRING_DYN`, danh sách `LIST_DYN`; cần kiểm làm tròn của số `double` ở ngưỡng 89,95 (rủi ro ra 89,9).
3. DL-07: migration lặp `R__` nạp danh mục khối, chức danh, lịch nghỉ; cần Docker cho Testcontainers (container cloud hiện không có Docker daemon chạy).
