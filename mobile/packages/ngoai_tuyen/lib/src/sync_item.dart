/// Trạng thái của một mục trong hàng đợi đồng bộ.
enum SyncItemState {
  /// Chờ gửi (lần đầu hoặc thử lại sau thời điểm `nextAttemptAt`).
  pending,

  /// Máy chủ từ chối theo cách thử lại không sửa được (409, 422, 4xx khác) hoặc khoá chống ghi trùng sắp hết
  /// hạn ở máy chủ; chờ người dùng xem lại, không tự gửi lại.
  needsReview,
}

/// Lý do mục chuyển sang [SyncItemState.needsReview].
enum ReviewReason {
  /// 409: dữ liệu trên máy chủ đã đổi (If-Match lệch phiên bản).
  conflict,

  /// 422: dữ liệu không hợp lệ theo quy tắc nghiệp vụ, hoặc khoá dùng lại với nội dung khác.
  rejected,

  /// 4xx khác (400, 403, 404, 412, 428): thử lại không sửa được.
  clientError,

  /// Lần gửi trước chưa rõ kết quả đã quá thời hạn máy chủ giữ khoá (48 giờ, TL-03 `idempotency_key`):
  /// gửi lại lúc này có thể tạo bản ghi trùng, nên phải để người dùng kiểm tra trước.
  idempotencyWindowExpired,

  /// Đã thử quá số lần cho phép.
  retriesExhausted,
}

/// Một lệnh ghi đã được người dùng bấm gửi, giữ nguyên [idempotencyKey] qua mọi lần gửi lại.
class SyncItem {
  const SyncItem({
    required this.id,
    required this.kind,
    required this.resourceKey,
    required this.method,
    required this.path,
    required this.body,
    required this.idempotencyKey,
    required this.ifMatch,
    required this.attempts,
    required this.state,
    required this.createdAt,
    required this.nextAttemptAt,
    required this.firstUncertainAt,
    required this.lastStatus,
    required this.reviewReason,
    required this.problemCode,
    required this.expiryWarned,
  });

  /// Số tăng dần theo thứ tự xếp hàng (FIFO).
  final int id;

  /// Loại thao tác do màn hình đặt, ví dụ `checkin`, `progress`; dùng cho thông báo.
  final String kind;

  /// Khoá tài nguyên: các mục cùng khoá gửi đúng thứ tự, mục sau chờ mục trước.
  final String resourceKey;
  final String method;
  final String path;
  final String? body;
  final String idempotencyKey;
  final String? ifMatch;
  final int attempts;
  final SyncItemState state;
  final DateTime createdAt;
  final DateTime nextAttemptAt;

  /// Thời điểm của lần gửi đầu tiên mà kết quả chưa rõ (máy chủ có thể đã ghi nhận).
  final DateTime? firstUncertainAt;
  final int? lastStatus;
  final ReviewReason? reviewReason;

  /// Mã lỗi RFC 9457 (`code`) của phản hồi gần nhất, không giữ `detail` vì có thể chứa dữ liệu C2.
  final String? problemCode;
  final bool expiryWarned;
}
