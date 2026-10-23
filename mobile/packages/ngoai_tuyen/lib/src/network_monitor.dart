/// Trạng thái mạng; ứng dụng cắm bản dùng `connectivity_plus` (suy luận) cùng một phép thử tới máy chủ.
abstract interface class NetworkMonitor {
  bool get isOnline;

  /// Phát `true` khi có mạng trở lại, `false` khi mất mạng.
  Stream<bool> get changes;
}
