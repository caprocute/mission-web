package vn.viettel.okrkpi.org.application.orgimport;

/**
 * Một lỗi trong báo cáo nhập: số dòng trong tệp, tên cột như tiêu đề mẫu, lý do bằng tiếng Việt nghiệp vụ.
 *
 * @param rule mã quy tắc nghiệp vụ nếu có (chỉ để tra cứu, không hiển thị)
 */
public record ImportError(int row, String column, String message, String rule) {
}
