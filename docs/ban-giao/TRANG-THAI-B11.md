# Trạng thái luồng B11 (21/10/2026)

DL-01, DL-02, DL-03, DL-05 bản giả lập đã có trên main. Nhánh `claude/b11-noi-dung-26rl4b` thêm phần dưới đây.

## Đã xong trên nhánh

- **DL-04:** `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/TaskLifecycleLoadTest.java` đạt 10/10 (bộ trạng thái chuẩn, lệnh, không trùng cặp from:command, vai trò theo ma trận, guard CEL biên dịch và chạy bằng `vt-cel`, miền tham số, tham số luồng duyệt).
- **DL-01, DL-03 trên cel-java:** `PolicyGoldenCelTest` biên dịch mọi biểu thức CEL của hai gói và chạy bộ ca mẫu quý, năm, Nhà nước; 4/4 đạt. Biểu thức `kpi_B`, `diem_nam` được ép `double()` (CEL không tự đổi int sang double). Phát hiện làm tròn trên số double sai ở ngưỡng (69,95 thành 69,9); kiểm thử dùng làm tròn hai bước, đề xuất cho CORE-05-04 ghi ở wiki trang 14.

## Cách chạy

`tools/contracts-bundle.sh` cần bash (được gọi bằng `sh`, hỏng trên Ubuntu nơi `sh` là dash); `./mvnw` không tải được Maven qua proxy, dùng `mvn` của hệ thống. `architecture/ModuleApisUnitTest.java` trên main không biên dịch (thiếu `PeriodStatus.KHOI_TAO`), nên khi chạy phải tạm đưa tệp đó ra ngoài:

```bash
cd backend && mvn -B -pl mission-core -am test -Dtest='TaskLifecycleLoadTest,PolicyGoldenCelTest' -Dsurefire.failIfNoSpecifiedTests=false
```

## Còn chặn

- **DL-07:** cột `org.subject_group.name` bắt buộc mà chưa có tên 9 khối (PL 2A); chưa có danh mục chức danh (PL 2B); bảng `config.holiday_calendar` chưa có trong mission-core (CORE-08-01). Lịch nghỉ 2026–2027 đã sẵn trong `data/seed/config/` để viết migration lặp khi bảng có.
- **DL-03:** so khớp cel-js, jqwik, nạp ca qua API chờ CORE-05-06; bộ ca chưa được nghiệp vụ ký.
