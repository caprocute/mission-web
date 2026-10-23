import 'dart:convert';
import 'dart:io';

import 'package:ngoai_tuyen/ngoai_tuyen.dart';
import 'package:test/test.dart';

import 'support/fakes.dart';

/// Máy chủ giả lập theo hợp đồng `common.yaml`: giữ khoá chống ghi trùng, cùng khoá trả lại phản hồi lần đầu.
/// Lệnh đầu tiên được ghi nhận nhưng kết nối bị cắt trước khi trả lời, như khi mất sóng giữa chừng.
class FakeServer {
  late final HttpServer _server;
  final records = <String, Map<String, Object?>>{};
  final requests = <String>[];
  var _cutFirstResponse = true;

  Uri get uri => Uri.parse('http://${_server.address.host}:${_server.port}');

  Future<void> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server.listen((req) async {
      final key = req.headers.value('idempotency-key');
      final body = await utf8.decodeStream(req);
      requests.add(key ?? '');
      if (key == null) {
        req.response.statusCode = 428;
        await req.response.close();
        return;
      }
      final replay = records.containsKey(key);
      records.putIfAbsent(key, () => {'path': req.uri.path, 'body': jsonDecode(body)});
      if (!replay && _cutFirstResponse) {
        _cutFirstResponse = false;
        final socket = await req.response.detachSocket(writeHeaders: false);
        socket.destroy();
        return;
      }
      req.response
        ..statusCode = 201
        ..headers.contentType = ContentType.json
        ..write(jsonEncode({'id': key}));
      await req.response.close();
    });
  }

  Future<void> stop() => _server.close(force: true);
}

void main() {
  test('ngắt mạng, nhập ba lần, bật mạng: máy chủ nhận đúng ba lệnh, không trùng', () async {
    final dir = await tempDir();
    final server = FakeServer();
    await server.start();
    final db = await OfflineDatabase.open(directory: dir, userId: 'u', tenantId: 't', keyStore: MemoryKeyStore());
    final net = FakeNetwork(online: false);
    final sender = HttpRequestSender(server.uri, timeout: const Duration(seconds: 5));
    final engine = SyncEngine(
      database: db,
      sender: sender,
      network: net,
      policy: const SyncPolicy(baseDelay: Duration(milliseconds: 10), maxDelay: Duration(milliseconds: 50)),
    )..start();
    final notices = <SyncNotice>[];
    engine.notices.listen(notices.add);

    for (var i = 1; i <= 3; i++) {
      await engine.enqueue(
        kind: 'checkin',
        resourceKey: 'kr:kr-1',
        method: 'POST',
        path: '/api/v1/okr/key-results/kr-1/checkins',
        body: {'value': i * 10},
      );
    }
    expect(server.requests, isEmpty);
    expect(notices.whereType<SavedLocally>(), hasLength(3));

    net.online = true;
    for (var i = 0; i < 20 && db.items().isNotEmpty; i++) {
      await engine.runOnce();
      await Future<void>.delayed(const Duration(milliseconds: 60));
    }

    expect(db.items(), isEmpty);
    expect(server.records, hasLength(3));
    expect(server.records.values.map((r) => (r['body'] as Map)['value']), [10, 20, 30]);
    expect(server.requests, hasLength(4), reason: 'lệnh đầu gửi lại một lần sau khi mất phản hồi, cùng khoá');
    expect(server.requests.toSet(), hasLength(3));
    expect(notices.whereType<SentPending>().fold<int>(0, (a, n) => a + n.countByKind['checkin']!), 3);

    await engine.dispose();
    db.close();
    await server.stop();
    await dir.delete(recursive: true);
  });
}
