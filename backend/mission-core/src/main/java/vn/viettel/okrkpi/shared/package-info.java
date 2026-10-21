/**
 * Thành phần hạ tầng dùng chung của {@code mission-core} (multi-DataSource, định tuyến đọc/ghi,
 * kiểm tra phiên bản lược đồ và đầu dò sẵn sàng). Được đánh dấu {@code OPEN} để tám mô-đun nghiệp
 * vụ và {@code replica} có thể tham chiếu mà không tạo chu trình phụ thuộc.
 */
@org.springframework.modulith.ApplicationModule(
    displayName = "Hạ tầng dùng chung (shared)",
    type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package vn.viettel.okrkpi.shared;
