import 'dart:math';

/// Kho bí mật của nền tảng: Android Keystore (EncryptedSharedPreferences) hoặc iOS Keychain.
///
/// Ứng dụng cắm bản hiện thực dùng `flutter_secure_storage`; package này không phụ thuộc Flutter để kiểm thử
/// được bằng `dart test`.
abstract interface class SecureKeyStore {
  Future<String?> read(String name);
  Future<void> write(String name, String value);
  Future<void> delete(String name);
}

/// Khoá ngẫu nhiên 256 bit cho mỗi CSDL, sinh lần đầu bằng bộ sinh số ngẫu nhiên an toàn, giữ ở dạng hex.
class DatabaseKeyManager {
  DatabaseKeyManager(this._store, {Random? random}) : _random = random ?? Random.secure();

  final SecureKeyStore _store;
  final Random _random;

  static String keyName(String databaseId) => 'mission.offline.key.$databaseId';

  /// Đọc khoá hiện có; [created] cho biết khoá vừa được sinh (CSDL cũ trên đĩa, nếu có, không mở được nữa).
  Future<({String hex, bool created})> obtain(String databaseId) async {
    final existing = await _store.read(keyName(databaseId));
    if (existing != null && RegExp(r'^[0-9a-f]{64}$').hasMatch(existing)) {
      return (hex: existing, created: false);
    }
    final bytes = List<int>.generate(32, (_) => _random.nextInt(256));
    final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    await _store.write(keyName(databaseId), hex);
    return (hex: hex, created: true);
  }

  Future<void> discard(String databaseId) => _store.delete(keyName(databaseId));
}
