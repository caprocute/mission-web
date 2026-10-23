/// Lưu trữ ngoại tuyến và hàng đợi đồng bộ của ứng dụng di động Mission (MOB-02, SVC-18).
///
/// TL-01 "SVC-18 mobile-app": bản nháp lưu trong SQLite mã hoá bằng SQLCipher, khoá trong Android Keystore
/// hoặc iOS Keychain; khi có mạng, hàng đợi gửi lên với `Idempotency-Key` để không tạo bản ghi trùng.
library;

export 'src/data_class.dart';
export 'src/key_store.dart';
export 'src/network_monitor.dart';
export 'src/offline_database.dart';
export 'src/request_sender.dart';
export 'src/sync_engine.dart';
export 'src/sync_item.dart';
export 'src/sync_notice.dart';
