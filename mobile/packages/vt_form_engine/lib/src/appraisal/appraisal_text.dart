/// Văn bản giao diện của phiếu tự đánh giá, theo bảng thông báo SCR-0901, SCR-0902 của TL-02b
/// (mã MSG chỉ ghi trong chú thích). Khi ghép vào ứng dụng MOB-01, chuyển sang tệp ARB.
abstract final class AppraisalText {
  static const myProfilesTitle = 'Phiếu tự đánh giá';
  static const selfAssessmentTitle = 'Phiếu tự đánh giá';
  static const noProfiles = 'Đồng chí chưa có hồ sơ đánh giá nào trong kỳ.';

  // MSG-09-01 (không có thời điểm dự kiến trong dữ liệu hồ sơ)
  static const notReady = 'Kết quả quý của đồng chí đang được tính, vui lòng quay lại sau.';

  // MSG-09-02
  static const differsWarning =
      'Mức tự đề xuất khác mức hệ thống tham khảo. Đồng chí cần ghi giải trình trước khi nộp (QĐ Điều 16).';
  static const reasonRequired =
      'Mức tự đề xuất khác mức hệ thống tham khảo. Đồng chí cần ghi giải trình từ 20 đến 2.000 ký tự trước khi nộp.';

  // MSG-09-03
  static const breakthroughRequired =
      'Đề xuất mức Hoàn thành xuất sắc cần mô tả giải pháp đột phá và gắn ít nhất một minh chứng đã được xác nhận.';

  // MSG-09-07
  static const conflict = 'Hồ sơ vừa được người khác cập nhật. Vui lòng tải lại để xem thay đổi trước khi lưu.';

  static const awaitingSignature =
      'Phiếu đã được gửi và đang chờ ký số. Đồng chí xác nhận ký trên ứng dụng ký số để hoàn tất việc nộp.';
  static const awaitingSignatureTitle = 'Chờ ký số';
  static const saved = 'Đã lưu nháp phiếu tự đánh giá.';
  static const offlineSaved = 'Chưa kết nối được máy chủ. Phiếu đã được lưu trên máy và sẽ gửi khi có mạng.';
  static const offlineSubmit =
      'Chưa kết nối được máy chủ nên chưa nộp được phiếu. Đồng chí vui lòng thử lại khi có mạng.';
  static const offlineLoad = 'Chưa kết nối được máy chủ. Đồng chí vui lòng kiểm tra kết nối mạng rồi thử lại.';
  static const loadFailed = 'Không tải được hồ sơ đánh giá. Đồng chí vui lòng thử lại sau.';
  static const notFound = 'Không tìm thấy hồ sơ đánh giá.';
  static const formBroken =
      'Biểu mẫu tự đánh giá của đơn vị đang có lỗi cấu hình. Đồng chí vui lòng báo cán bộ phụ trách hệ thống.';
  static const fixErrors = 'Phiếu còn trường chưa hợp lệ. Đồng chí vui lòng kiểm tra các trường được đánh dấu.';
  static const forbidden = 'Đồng chí không có quyền sửa phiếu tự đánh giá này.';
  static const invalidStatus = 'Hồ sơ không còn ở bước tự đánh giá nên không sửa được phiếu.';
  static const saveFailed = 'Chưa lưu được phiếu. Đồng chí vui lòng thử lại sau.';

  static const criteriaTitle = 'Tiêu chí chung';
  static const noCriteria = 'Chưa có danh mục tiêu chí chung cho hồ sơ này.';
  static String criteriaSummary(int met, int total, String levelLabel) {
    final pct = total == 0 ? 0 : (met * 100 / total).round();
    return 'Đáp ứng $met/$total tiêu chí ($pct%): $levelLabel';
  }

  static const criteriaRule =
      'Quy đổi: 100% là Thực hiện tốt; từ 90% là Đảm bảo; từ 60% là Cơ bản đảm bảo; dưới 60% là Không đảm bảo.';
  static const ratingTitle = 'Tự đề xuất mức xếp loại';
  static String reference(String label) => 'Mức hệ thống tham khảo: $label';
  static String evidenceCount(int n) =>
      n == 0 ? 'Chưa gắn minh chứng đã xác nhận nào.' : 'Đã gắn $n minh chứng đã xác nhận.';
  static const saveDraft = 'Lưu nháp';
  static const submit = 'Ký số và nộp';
  static const reload = 'Tải lại';
  static const retry = 'Thử lại';
  static String due(String date, int? days) =>
      days == null ? 'Hạn nộp: $date' : 'Hạn nộp: $date, còn $days ngày làm việc';
}
