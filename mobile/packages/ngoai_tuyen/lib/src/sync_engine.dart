import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:uuid/uuid.dart';

import 'data_class.dart';
import 'network_monitor.dart';
import 'offline_database.dart';
import 'request_sender.dart';
import 'sync_item.dart';
import 'sync_notice.dart';

/// Tham số của hàng đợi. Giá trị mặc định là đề xuất của MOB-02 (suy luận), chỉnh được theo cấu hình tenant.
class SyncPolicy {
  const SyncPolicy({
    this.baseDelay = const Duration(seconds: 2),
    this.maxDelay = const Duration(minutes: 15),
    this.maxAttempts = 50,
    this.retention = const Duration(days: 7),
    this.expiryWarning = const Duration(days: 1),
    this.idempotencyWindow = const Duration(hours: 47),
  });

  /// Độ trễ lần thử lại đầu; mỗi lần sau gấp đôi, có dao động ngẫu nhiên tới 20%.
  final Duration baseDelay;
  final Duration maxDelay;
  final int maxAttempts;

  /// Thời hạn giữ mục chờ trên máy kể từ lúc bấm gửi; quá hạn thì huỷ.
  final Duration retention;

  /// Báo người dùng trước khi huỷ khoảng thời gian này.
  final Duration expiryWarning;

  /// Máy chủ giữ khoá chống ghi trùng 48 giờ (TL-03 `idempotency_key`); để biên an toàn một giờ cho lệch đồng
  /// hồ, mục có lần gửi chưa rõ kết quả cũ hơn mức này không được tự gửi lại.
  final Duration idempotencyWindow;
}

/// Hàng đợi đồng bộ: xếp lệnh ghi khi người dùng bấm gửi, gửi khi có mạng, giữ nguyên `Idempotency-Key`.
///
/// Thứ tự: FIFO trong cùng `resourceKey`; mục đứng sau chờ mục trước gửi xong hoặc được người dùng xử lý,
/// các tài nguyên khác nhau không chặn nhau. Phân loại phản hồi theo MOB-02:
/// 2xx (kể cả phản hồi phát lại của khoá đã dùng) xoá mục; 409, 422 và 4xx khác giữ mục chờ xem lại;
/// 401 dừng hàng đợi chờ đăng nhập lại (lớp gửi của ứng dụng đã thử làm mới token một lần);
/// 408, 429, 5xx và lỗi mạng thử lại lùi dần, tôn trọng `Retry-After`.
class SyncEngine {
  SyncEngine({
    required this.database,
    required this.sender,
    required this.network,
    this.policy = const SyncPolicy(),
    DateTime Function()? clock,
    Random? random,
    String Function()? newIdempotencyKey,
  }) : _clock = clock ?? DateTime.now,
       _random = random ?? Random(),
       _newKey = newIdempotencyKey ?? (() => const Uuid().v7());

  final OfflineDatabase database;
  final RequestSender sender;
  final NetworkMonitor network;
  final SyncPolicy policy;
  final DateTime Function() _clock;
  final Random _random;
  final String Function() _newKey;

  // Đồng bộ để thông báo tới giao diện ngay trong lượt gửi, theo đúng thứ tự xảy ra.
  final _notices = StreamController<SyncNotice>.broadcast(sync: true);
  StreamSubscription<bool>? _networkSub;
  Future<int>? _running;

  Stream<SyncNotice> get notices => _notices.stream;

  /// Bắt đầu theo dõi mạng: mỗi khi có mạng trở lại thì chạy một lượt.
  void start() {
    _networkSub ??= network.changes.listen((online) {
      if (online) unawaited(runOnce());
    });
  }

  Future<void> stop() async {
    await _networkSub?.cancel();
    _networkSub = null;
    await _running;
  }

  Future<void> dispose() async {
    await stop();
    await _notices.close();
  }

  /// Xếp một lệnh ghi. Gọi đúng lúc người dùng bấm gửi: khoá chống ghi trùng UUIDv7 sinh tại đây và đi theo
  /// mục tới khi máy chủ xác nhận. Trả về số thứ tự của mục.
  Future<int> enqueue({
    required String kind,
    required String resourceKey,
    required String method,
    required String path,
    Object? body,
    String? ifMatch,
    DataClass dataClass = DataClass.c2,
  }) async {
    rejectC3(dataClass);
    final id = database.insertItem(
      kind: kind,
      resourceKey: resourceKey,
      method: method,
      path: path,
      body: body == null ? null : jsonEncode(body),
      idempotencyKey: _newKey(),
      ifMatch: ifMatch,
      now: _clock(),
    );
    if (network.isOnline) {
      unawaited(runOnce());
    } else {
      _emit(SavedLocally(kind));
    }
    return id;
  }

  /// Người dùng đã xem lại một mục cần xem lại và gửi lại với nội dung hoặc phiên bản mới.
  Future<void> resubmit(int id, {Object? body, String? ifMatch}) async {
    database.resubmit(
      id,
      body: body == null ? null : jsonEncode(body),
      ifMatch: ifMatch,
      idempotencyKey: _newKey(),
      now: _clock(),
    );
    if (network.isOnline) unawaited(runOnce());
  }

  /// Người dùng bỏ một mục.
  void discard(int id) => database.deleteItem(id);

  /// Chạy một lượt gửi. Gọi từ cơ chế nền của hệ điều hành (WorkManager, BGTaskScheduler) hoặc khi có mạng.
  /// Các lời gọi chồng nhau dùng chung một lượt. Trả về số mục đã gửi thành công.
  Future<int> runOnce() => _running ??= _run().whenComplete(() => _running = null);

  Future<int> _run() async {
    _expire();
    if (!network.isOnline) return 0;
    final sent = <String, int>{};
    final blocked = <String>{};
    var stop = false;
    for (final item in database.items()) {
      if (stop) break;
      if (blocked.contains(item.resourceKey)) continue;
      final now = _clock();
      if (item.state != SyncItemState.pending || item.nextAttemptAt.isAfter(now)) {
        blocked.add(item.resourceKey);
        continue;
      }
      final uncertain = item.firstUncertainAt;
      if (uncertain != null && now.difference(uncertain) > policy.idempotencyWindow) {
        _review(item, ReviewReason.idempotencyWindowExpired, item.lastStatus, null);
        blocked.add(item.resourceKey);
        continue;
      }
      database.markAttempt(item.id, now);
      SyncResponse res;
      try {
        res = await sender.send(
          SyncRequest(
            method: item.method,
            path: item.path,
            headers: {
              'Idempotency-Key': item.idempotencyKey,
              if (item.ifMatch != null) 'If-Match': item.ifMatch!,
              if (item.body != null) 'Content-Type': 'application/json',
            },
            body: item.body,
          ),
        );
      } on TransportException {
        // Mất mạng giữa chừng: các mục còn lại nhiều khả năng cũng lỗi, dừng lượt này.
        _retryLater(item, null, null);
        stop = true;
        continue;
      }
      final s = res.status;
      if (s >= 200 && s < 300) {
        database.deleteItem(item.id);
        sent.update(item.kind, (n) => n + 1, ifAbsent: () => 1);
      } else if (s == 401) {
        // Lớp gửi đã thử làm mới token; không tính là một lần thử của mục.
        database.scheduleRetry(item.id, now, s);
        _emit(const SignInRequired());
        stop = true;
      } else if (s == 408 || s == 429 || s >= 500) {
        _retryLater(item, s, _retryAfter(res, now));
        blocked.add(item.resourceKey);
      } else {
        final reason = switch (s) {
          409 => ReviewReason.conflict,
          422 => ReviewReason.rejected,
          _ => ReviewReason.clientError,
        };
        _review(item, reason, s, _problemCode(res));
        blocked.add(item.resourceKey);
      }
    }
    if (sent.isNotEmpty) _emit(SentPending(sent));
    return sent.values.fold<int>(0, (a, b) => a + b);
  }

  void _retryLater(SyncItem item, int? status, DateTime? retryAfter) {
    final attempts = item.attempts + 1;
    if (attempts >= policy.maxAttempts) {
      _review(item, ReviewReason.retriesExhausted, status, null);
      return;
    }
    final exp = policy.baseDelay * pow(2, min(attempts - 1, 20)).toInt();
    var delay = exp > policy.maxDelay ? policy.maxDelay : exp;
    delay += delay * (_random.nextDouble() * 0.2);
    var at = _clock().add(delay);
    if (retryAfter != null && retryAfter.isAfter(at)) at = retryAfter;
    database.scheduleRetry(item.id, at, status);
  }

  void _review(SyncItem item, ReviewReason reason, int? status, String? problemCode) {
    database.markNeedsReview(item.id, reason, status, problemCode);
    _emit(NeedsReview(item.id, item.kind, status));
  }

  void _expire() {
    final now = _clock();
    for (final item in database.items()) {
      final discardAt = item.createdAt.add(policy.retention);
      if (!now.isBefore(discardAt)) {
        database.deleteItem(item.id);
        _emit(Discarded(item.id, item.kind));
      } else if (!item.expiryWarned && !now.isBefore(discardAt.subtract(policy.expiryWarning))) {
        database.markExpiryWarned(item.id);
        _emit(ExpiringSoon(item.id, item.kind, discardAt));
      }
    }
  }

  /// `Retry-After` dạng số giây hoặc ngày giờ HTTP.
  DateTime? _retryAfter(SyncResponse res, DateTime now) {
    final v = res.headers['retry-after'];
    if (v == null) return null;
    final secs = int.tryParse(v.trim());
    if (secs != null) return now.add(Duration(seconds: secs));
    try {
      return HttpDateParser.parse(v);
    } on FormatException {
      return null;
    }
  }

  /// Chỉ giữ `code` của RFC 9457; `detail` và `errors[]` có thể chứa dữ liệu người dùng nhập.
  String? _problemCode(SyncResponse res) {
    final body = res.body;
    if (body == null || body.isEmpty) return null;
    try {
      final j = jsonDecode(body);
      return j is Map && j['code'] is String ? j['code'] as String : null;
    } on FormatException {
      return null;
    }
  }

  void _emit(SyncNotice n) {
    if (!_notices.isClosed) _notices.add(n);
  }
}

/// Đọc ngày giờ HTTP (RFC 9110, dạng IMF-fixdate) mà không cần `dart:io` để chạy được ở mọi nền tảng.
abstract final class HttpDateParser {
  static const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  static DateTime parse(String v) {
    final m = RegExp(r'^\w{3}, (\d{2}) (\w{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$').firstMatch(v.trim());
    final month = m == null ? -1 : _months.indexOf(m.group(2)!);
    if (m == null || month < 0) throw FormatException('Retry-After không hợp lệ', v);
    int g(int i) => int.parse(m.group(i)!);
    return DateTime.utc(g(3), month + 1, g(1), g(4), g(5), g(6));
  }
}
