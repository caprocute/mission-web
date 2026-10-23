import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:sqlite3/sqlite3.dart';

import 'data_class.dart';
import 'key_store.dart';
import 'sync_item.dart';

/// CSDL ngoại tuyến của một cặp người dùng và tenant, mã hoá bằng SQLCipher.
///
/// Mỗi cặp (người dùng, tenant) một tệp và một khoá riêng; tên tệp là băm của cặp đó để không lộ định danh
/// trên đĩa. Bảng `drafts` giữ bản nháp, `cached_views` giữ dữ liệu cần xem khi mất mạng (danh sách việc,
/// KR cần check-in), `sync_queue` giữ lệnh ghi chờ gửi. Không có bảng nào giữ dữ liệu C3.
class OfflineDatabase {
  OfflineDatabase._(this._db, this._file, this.databaseId, this._keys);

  final Database _db;
  final File _file;
  final DatabaseKeyManager _keys;

  /// Băm của (người dùng, tenant), dùng làm tên tệp và tên khoá.
  final String databaseId;

  static String idFor(String userId, String tenantId) =>
      sha256.convert(utf8.encode('$tenantId\u0000$userId')).toString().substring(0, 32);

  /// Mở hoặc tạo CSDL. Khi khoá trong Keystore/Keychain không còn (cài lại ứng dụng, người dùng xoá dữ liệu
  /// bảo mật), tệp cũ không giải mã được nữa nên bị xoá và tạo lại rỗng.
  static Future<OfflineDatabase> open({
    required Directory directory,
    required String userId,
    required String tenantId,
    required SecureKeyStore keyStore,
  }) async {
    final id = idFor(userId, tenantId);
    final keys = DatabaseKeyManager(keyStore);
    final key = await keys.obtain(id);
    final file = File('${directory.path}/mission_$id.db');
    if (key.created) _deleteFiles(file);
    final db = sqlite3.open(file.path);
    try {
      // Khoá thô 256 bit (x'..'), không qua KDF từ mật khẩu. Phải là lệnh đầu tiên trên kết nối.
      db.execute("PRAGMA key = \"x'${key.hex}'\"");
      final version = db.select('PRAGMA cipher_version');
      if (version.isEmpty || (version.first.values.first as String? ?? '').isEmpty) {
        throw StateError('Thư viện SQLite nạp được không phải SQLCipher; từ chối lưu dữ liệu không mã hoá');
      }
      // Bảng tạm, chỉ mục tạm giữ trong bộ nhớ, không ghi tệp tạm ra đĩa.
      db.execute('PRAGMA temp_store = MEMORY');
      db.execute('PRAGMA foreign_keys = ON');
      db.select('SELECT count(*) FROM sqlite_master');
      _migrate(db);
    } catch (_) {
      db.close();
      rethrow;
    }
    return OfflineDatabase._(db, file, id, keys);
  }

  static void _migrate(Database db) {
    final version = db.select('PRAGMA user_version').first.values.first as int;
    if (version < 1) {
      db.execute('''
        CREATE TABLE drafts (
          kind TEXT NOT NULL,
          ref TEXT NOT NULL,
          data TEXT NOT NULL,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (kind, ref)
        );
        CREATE TABLE cached_views (
          kind TEXT NOT NULL,
          ref TEXT NOT NULL,
          data TEXT NOT NULL,
          fetched_at INTEGER NOT NULL,
          PRIMARY KEY (kind, ref)
        );
        CREATE TABLE sync_queue (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          kind TEXT NOT NULL,
          resource_key TEXT NOT NULL,
          method TEXT NOT NULL,
          path TEXT NOT NULL,
          body TEXT,
          idempotency_key TEXT NOT NULL UNIQUE,
          if_match TEXT,
          attempts INTEGER NOT NULL DEFAULT 0,
          state TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          next_attempt_at INTEGER NOT NULL,
          first_uncertain_at INTEGER,
          last_status INTEGER,
          review_reason TEXT,
          problem_code TEXT,
          expiry_warned INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX sync_queue_resource ON sync_queue (resource_key, id);
        PRAGMA user_version = 1;
      ''');
    }
  }

  // Bản nháp -----------------------------------------------------------------------------------------------

  void saveDraft(
    String kind,
    String ref,
    Map<String, Object?> data, {
    DataClass dataClass = DataClass.c2,
    DateTime? now,
  }) {
    rejectC3(dataClass);
    _db.execute(
      'INSERT INTO drafts (kind, ref, data, updated_at) VALUES (?, ?, ?, ?) '
      'ON CONFLICT (kind, ref) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at',
      [kind, ref, jsonEncode(data), (now ?? DateTime.now()).millisecondsSinceEpoch],
    );
  }

  Map<String, Object?>? readDraft(String kind, String ref) {
    final rows = _db.select('SELECT data FROM drafts WHERE kind = ? AND ref = ?', [kind, ref]);
    return rows.isEmpty ? null : jsonDecode(rows.first['data'] as String) as Map<String, Object?>;
  }

  void deleteDraft(String kind, String ref) =>
      _db.execute('DELETE FROM drafts WHERE kind = ? AND ref = ?', [kind, ref]);

  // Dữ liệu xem ngoại tuyến --------------------------------------------------------------------------------

  void putCachedView(String kind, String ref, Object? data, {DataClass dataClass = DataClass.c2, DateTime? now}) {
    rejectC3(dataClass);
    _db.execute(
      'INSERT INTO cached_views (kind, ref, data, fetched_at) VALUES (?, ?, ?, ?) '
      'ON CONFLICT (kind, ref) DO UPDATE SET data = excluded.data, fetched_at = excluded.fetched_at',
      [kind, ref, jsonEncode(data), (now ?? DateTime.now()).millisecondsSinceEpoch],
    );
  }

  ({Object? data, DateTime fetchedAt})? readCachedView(String kind, String ref) {
    final rows = _db.select('SELECT data, fetched_at FROM cached_views WHERE kind = ? AND ref = ?', [kind, ref]);
    if (rows.isEmpty) return null;
    return (
      data: jsonDecode(rows.first['data'] as String),
      fetchedAt: DateTime.fromMillisecondsSinceEpoch(rows.first['fetched_at'] as int),
    );
  }

  // Hàng đợi đồng bộ (SyncEngine dùng) ---------------------------------------------------------------------

  int insertItem({
    required String kind,
    required String resourceKey,
    required String method,
    required String path,
    required String? body,
    required String idempotencyKey,
    required String? ifMatch,
    required DateTime now,
  }) {
    _db.execute(
      'INSERT INTO sync_queue (kind, resource_key, method, path, body, idempotency_key, if_match, state, created_at, '
      'next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
      [
        kind,
        resourceKey,
        method,
        path,
        body,
        idempotencyKey,
        ifMatch,
        SyncItemState.pending.name,
        now.millisecondsSinceEpoch,
        now.millisecondsSinceEpoch,
      ],
    );
    return _db.lastInsertRowId;
  }

  List<SyncItem> items() => _db.select('SELECT * FROM sync_queue ORDER BY id').map(_toItem).toList();

  SyncItem? item(int id) {
    final rows = _db.select('SELECT * FROM sync_queue WHERE id = ?', [id]);
    return rows.isEmpty ? null : _toItem(rows.first);
  }

  /// Ghi nhận một lần gửi sắp bắt đầu, trước khi gửi, để lần gửi bị cắt ngang (ứng dụng bị tắt) vẫn được tính.
  void markAttempt(int id, DateTime now) => _db.execute(
    'UPDATE sync_queue SET attempts = attempts + 1, first_uncertain_at = coalesce(first_uncertain_at, ?) '
    'WHERE id = ?',
    [now.millisecondsSinceEpoch, id],
  );

  void scheduleRetry(int id, DateTime at, int? status) => _db.execute(
    'UPDATE sync_queue SET next_attempt_at = ?, last_status = ? WHERE id = ?',
    [at.millisecondsSinceEpoch, status, id],
  );

  void markNeedsReview(int id, ReviewReason reason, int? status, String? problemCode) => _db.execute(
    'UPDATE sync_queue SET state = ?, review_reason = ?, last_status = ?, problem_code = ? WHERE id = ?',
    [SyncItemState.needsReview.name, reason.name, status, problemCode, id],
  );

  void markExpiryWarned(int id) => _db.execute('UPDATE sync_queue SET expiry_warned = 1 WHERE id = ?', [id]);

  void deleteItem(int id) => _db.execute('DELETE FROM sync_queue WHERE id = ?', [id]);

  /// Người dùng đã xem lại và chọn gửi lại với nội dung, phiên bản mới: đây là một ý định ghi mới nên
  /// nhận khoá chống ghi trùng mới.
  void resubmit(
    int id, {
    required String? body,
    required String? ifMatch,
    required String idempotencyKey,
    required DateTime now,
  }) => _db.execute(
    'UPDATE sync_queue SET body = ?, if_match = ?, idempotency_key = ?, state = ?, attempts = 0, '
    'next_attempt_at = ?, first_uncertain_at = NULL, last_status = NULL, review_reason = NULL, '
    'problem_code = NULL WHERE id = ?',
    [body, ifMatch, idempotencyKey, SyncItemState.pending.name, now.millisecondsSinceEpoch, id],
  );

  SyncItem _toItem(Row r) {
    DateTime? t(Object? v) => v == null ? null : DateTime.fromMillisecondsSinceEpoch(v as int);
    return SyncItem(
      id: r['id'] as int,
      kind: r['kind'] as String,
      resourceKey: r['resource_key'] as String,
      method: r['method'] as String,
      path: r['path'] as String,
      body: r['body'] as String?,
      idempotencyKey: r['idempotency_key'] as String,
      ifMatch: r['if_match'] as String?,
      attempts: r['attempts'] as int,
      state: SyncItemState.values.byName(r['state'] as String),
      createdAt: t(r['created_at'])!,
      nextAttemptAt: t(r['next_attempt_at'])!,
      firstUncertainAt: t(r['first_uncertain_at']),
      lastStatus: r['last_status'] as int?,
      reviewReason: r['review_reason'] == null ? null : ReviewReason.values.byName(r['review_reason'] as String),
      problemCode: r['problem_code'] as String?,
      expiryWarned: (r['expiry_warned'] as int) == 1,
    );
  }

  // Vòng đời -----------------------------------------------------------------------------------------------

  void close() => _db.close();

  /// Đăng xuất hoặc đổi tenant: đóng, xoá tệp CSDL (cả nhật ký giao dịch) và xoá khoá khỏi Keystore/Keychain.
  Future<void> destroy() async {
    _db.close();
    _deleteFiles(_file);
    await _keys.discard(databaseId);
  }

  static void _deleteFiles(File file) {
    for (final suffix in ['', '-journal', '-wal', '-shm']) {
      final f = File('${file.path}$suffix');
      if (f.existsSync()) f.deleteSync();
    }
  }
}
