/// Sự kiện để giao diện hiển thị thông báo. Mã MSG chỉ dùng cho chú thích và nhật ký, không in lên màn hình.
sealed class SyncNotice {
  const SyncNotice();

  /// Câu thông báo theo TL-02 cho loại thao tác đã có văn bản chuẩn; `null` thì màn hình tự đặt câu chữ.
  String? get text;
}

/// MSG-03-06: lệnh ghi đã lưu trên máy vì đang mất mạng.
class SavedLocally extends SyncNotice {
  const SavedLocally(this.kind);

  final String kind;
  static const messageCode = 'MSG-03-06';

  @override
  String? get text => kind == 'checkin' ? 'Đã lưu trên máy. Check-in sẽ tự gửi khi có mạng.' : null;
}

/// MSG-03-07: đã gửi các mục chờ khi có mạng trở lại; [countByKind] đếm theo loại thao tác.
class SentPending extends SyncNotice {
  const SentPending(this.countByKind);

  final Map<String, int> countByKind;
  static const messageCode = 'MSG-03-07';

  @override
  String? get text {
    final n = countByKind['checkin'];
    return n != null && countByKind.length == 1 ? 'Đã gửi $n check-in đang chờ.' : null;
  }
}

/// Mục cần người dùng xem lại (409, 422…); không tự gửi lại.
class NeedsReview extends SyncNotice {
  const NeedsReview(this.itemId, this.kind, this.status);

  final int itemId;
  final String kind;
  final int? status;

  @override
  String? get text => null;
}

/// Phiên đăng nhập hết hiệu lực sau khi đã thử làm mới token; hàng đợi tạm dừng tới khi đăng nhập lại.
class SignInRequired extends SyncNotice {
  const SignInRequired();

  @override
  String? get text => null;
}

/// Mục chờ sắp quá thời hạn giữ trên máy và sẽ bị huỷ nếu vẫn chưa gửi được.
class ExpiringSoon extends SyncNotice {
  const ExpiringSoon(this.itemId, this.kind, this.discardAt);

  final int itemId;
  final String kind;
  final DateTime discardAt;

  @override
  String? get text => null;
}

/// Mục chờ đã bị huỷ vì quá thời hạn giữ trên máy.
class Discarded extends SyncNotice {
  const Discarded(this.itemId, this.kind);

  final int itemId;
  final String kind;

  @override
  String? get text => null;
}
