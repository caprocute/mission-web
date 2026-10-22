# Danh mục cờ tính năng nền tảng (CORE-08-07)

Tệp `feature-flags.csv` khai báo các cờ tính năng do nền tảng định nghĩa sẵn cho mọi tenant (OPS-04,
TL-03 bảng `config.feature_flag`). Mỗi dòng gồm mã cờ, mô tả, giá trị bật mặc định và tỷ lệ người dùng
mặc định. Danh sách hiện là bản giả lập (suy luận), chủ sản phẩm bổ sung khi có tính năng mới phải nằm
sau cờ; cờ đã bật cho mọi tenant quá 90 ngày phải được dọn khỏi danh mục.

mission-core đọc bản sao trên classpath
(`backend/mission-core/src/main/resources/vn/viettel/okrkpi/config/domain/feature_flags.csv`); kiểm
thử `FeatureFlagCatalogTest` bảo đảm hai tệp trùng nhau. Tenant chưa có dòng trong
`config.feature_flag` dùng giá trị mặc định ở đây; lần ghi đầu tiên tạo dòng của tenant.
