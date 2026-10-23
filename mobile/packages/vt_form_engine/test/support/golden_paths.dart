import 'dart:io';

/// Đường dẫn tới `data/golden/` của repo, tính từ thư mục gói (`flutter test` chạy ở gốc gói).
String goldenPath(String rel) {
  var dir = Directory.current;
  for (var i = 0; i < 6; i++) {
    final f = File('${dir.path}/data/golden/$rel');
    if (f.existsSync()) return f.path;
    dir = dir.parent;
  }
  throw StateError('Không tìm thấy data/golden/$rel từ ${Directory.current.path}');
}
