/// Dịch vụ của nền tảng mà phiếu tự đánh giá cần, do khung ứng dụng cung cấp khi ghép:
/// kho nháp mã hoá (MOB-02) và chặn chụp màn hình (MOB-01). Bản trong gói chỉ dùng cho kiểm thử.
library;

/// Bản nháp phiếu trên máy. Nội dung có trường C2 (giải trình, ưu điểm, hạn chế) nên chỉ được ghi
/// vào CSDL mã hoá của MOB-02, không ghi ra tệp thường, không ghi nhật ký.
class LocalDraft {
  const LocalDraft(this.baseRowVersion, this.input);

  /// Phiên bản hồ sơ mà bản nháp dựa trên; khác phiên bản trên máy chủ thì bỏ bản nháp.
  final int baseRowVersion;
  final Map<String, Object?> input;
}

abstract interface class EncryptedDraftStore {
  Future<LocalDraft?> read(String profileId);
  Future<void> write(String profileId, LocalDraft draft);
  Future<void> delete(String profileId);
}

/// Kho nháp trong bộ nhớ cho kiểm thử; không dùng ở bản phát hành.
class InMemoryDraftStore implements EncryptedDraftStore {
  final drafts = <String, LocalDraft>{};
  @override
  Future<LocalDraft?> read(String profileId) async => drafts[profileId];
  @override
  Future<void> write(String profileId, LocalDraft draft) async => drafts[profileId] = draft;
  @override
  Future<void> delete(String profileId) async => drafts.remove(profileId);
}

/// Chặn chụp, quay màn hình khi màn hình có dữ liệu C2 (FLAG_SECURE trên Android, che nội dung khi
/// chụp trên iOS). Khung ứng dụng MOB-01 hiện thực bằng kênh nền tảng.
abstract interface class ScreenSecurity {
  Future<void> protect();
  Future<void> release();
}

/// Bản không làm gì, chỉ cho kiểm thử và môi trường chưa có khung ứng dụng.
class NoopScreenSecurity implements ScreenSecurity {
  const NoopScreenSecurity();
  @override
  Future<void> protect() async {}
  @override
  Future<void> release() async {}
}
