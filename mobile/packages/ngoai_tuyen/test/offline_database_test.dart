import 'dart:convert';
import 'dart:io';

import 'package:ngoai_tuyen/ngoai_tuyen.dart';
import 'package:sqlite3/sqlite3.dart';
import 'package:test/test.dart';

import 'support/fakes.dart';

void main() {
  late Directory dir;
  late MemoryKeyStore keys;

  setUp(() async {
    dir = await tempDir();
    keys = MemoryKeyStore();
  });
  tearDown(() => dir.delete(recursive: true));

  Future<OfflineDatabase> open({String user = 'u-1', String tenant = 't-1'}) =>
      OfflineDatabase.open(directory: dir, userId: user, tenantId: tenant, keyStore: keys);

  test('khoá 256 bit sinh lần đầu, giữ trong kho bí mật, mở lại đọc được dữ liệu', () async {
    final db = await open();
    db.saveDraft('checkin', 'kr-1', {'value': 42});
    db.close();
    final key = keys.values.values.single;
    expect(key, matches(RegExp(r'^[0-9a-f]{64}$')));
    final again = await open();
    expect(again.readDraft('checkin', 'kr-1'), {'value': 42});
    again.close();
  });

  test('tệp CSDL thô không có khoá không đọc được chuỗi đánh dấu đã ghi', () async {
    const marker = 'DAU-HIEU-MOB02-7f3a9c';
    final db = await open();
    db.saveDraft('progress', 'task-1', {'note': marker});
    db.putCachedView('my-tasks', 'list', [
      {'title': marker},
    ]);
    final file = File('${dir.path}/mission_${db.databaseId}.db');
    db.close();
    final raw = file.readAsBytesSync();
    expect(raw.length, greaterThan(0));
    expect(latin1.decode(raw).contains(marker), isFalse);
    expect(latin1.decode(raw).contains('SQLite format 3'), isFalse, reason: 'tiêu đề tệp SQLite rõ phải bị mã hoá');
    // Mở bằng kết nối không có khoá: không phải CSDL đọc được.
    final plain = sqlite3.open(file.path);
    expect(() => plain.select('SELECT data FROM drafts'), throwsA(isA<SqliteException>()));
    plain.close();
    // Khoá sai cũng không mở được.
    final wrong = sqlite3.open(file.path)..execute("PRAGMA key = \"x'${'00' * 32}'\"");
    expect(() => wrong.select('SELECT count(*) FROM sqlite_master'), throwsA(isA<SqliteException>()));
    wrong.close();
  });

  test('mỗi cặp người dùng và tenant một tệp, một khoá; tên tệp không lộ định danh', () async {
    final a = await open(user: 'can-bo-a', tenant: 'tinh-x');
    final b = await open(user: 'can-bo-a', tenant: 'tinh-y');
    a.saveDraft('checkin', 'kr-1', {'v': 1});
    expect(b.readDraft('checkin', 'kr-1'), isNull);
    expect(a.databaseId, isNot(b.databaseId));
    expect(keys.values.length, 2);
    final names = dir.listSync().map((f) => f.uri.pathSegments.last).join(' ');
    expect(names.contains('can-bo-a') || names.contains('tinh-x'), isFalse);
    a.close();
    b.close();
  });

  test('đăng xuất hoặc đổi tenant xoá tệp và khoá', () async {
    final db = await open();
    db.saveDraft('checkin', 'kr-1', {'v': 1});
    await db.destroy();
    expect(dir.listSync(), isEmpty);
    expect(keys.values, isEmpty);
  });

  test('mất khoá trong kho bí mật thì tệp cũ bị xoá, tạo CSDL rỗng mới', () async {
    final db = await open();
    db.saveDraft('checkin', 'kr-1', {'v': 1});
    db.close();
    keys.values.clear();
    final fresh = await open();
    expect(fresh.readDraft('checkin', 'kr-1'), isNull);
    fresh.close();
  });

  test('từ chối lưu dữ liệu C3', () async {
    final db = await open();
    expect(() => db.saveDraft('khieu-nai', 'k-1', {'x': 1}, dataClass: DataClass.c3), throwsArgumentError);
    expect(() => db.putCachedView('khieu-nai', 'k-1', {}, dataClass: DataClass.c3), throwsArgumentError);
    db.close();
  });
}
