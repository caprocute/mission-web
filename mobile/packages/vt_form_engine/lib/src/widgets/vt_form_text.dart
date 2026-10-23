/// Văn bản giao diện của bộ dựng biểu mẫu (tiếng Việt, cùng thuật ngữ với web). Khi ghép vào ứng
/// dụng MOB-01, các chuỗi này chuyển sang tệp ARB của ứng dụng.
abstract final class VtFormText {
  static const chooseOne = 'Chọn một giá trị';
  static const chooseDate = 'Chọn ngày';
  static const clearDate = 'Xoá ngày đã chọn';
  static const addRow = 'Thêm dòng';
  static String row(int n) => 'Dòng $n';
  static String removeRow(int n) => 'Xoá dòng $n';
  static String unsupported(String label) =>
      'Trường "$label" chưa hiển thị được trên ứng dụng di động. '
      'Đồng chí vui lòng nhập trường này trên trang web của hệ thống.';
}
