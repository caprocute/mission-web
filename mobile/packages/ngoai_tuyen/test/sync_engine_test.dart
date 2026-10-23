import 'dart:io';
import 'dart:math';

import 'package:ngoai_tuyen/ngoai_tuyen.dart';
import 'package:test/test.dart';

import 'support/fakes.dart';

void main() {
  late Directory dir;
  late OfflineDatabase db;
  late FakeNetwork net;
  late ManualClock clock;

  setUp(() async {
    dir = await tempDir();
    db = await OfflineDatabase.open(directory: dir, userId: 'u', tenantId: 't', keyStore: MemoryKeyStore());
    net = FakeNetwork(online: false);
    clock = ManualClock(DateTime.utc(2026, 10, 23, 8));
  });
  tearDown(() async {
    db.close();
    await dir.delete(recursive: true);
  });

  SyncEngine engine(ScriptedSender sender, {SyncPolicy policy = const SyncPolicy()}) =>
      SyncEngine(database: db, sender: sender, network: net, clock: clock.call, random: Random(1), policy: policy);

  Future<int> checkin(SyncEngine e, {String kr = 'kr-1', int value = 1}) => e.enqueue(
    kind: 'checkin',
    resourceKey: 'kr:$kr',
    method: 'POST',
    path: '/api/v1/okr/key-results/$kr/checkins',
    body: {'value': value},
  );

  test('gửi lại một mục ba lần dùng cùng một Idempotency-Key, xoá mục sau phản hồi thành công', () async {
    final sender = ScriptedSender([
      const TransportException('mất sóng'),
      const SyncResponse(status: 503, headers: {'retry-after': '5'}),
      const SyncResponse(status: 201),
    ]);
    final e = engine(sender);
    await checkin(e);
    net.online = true;
    for (var i = 0; i < 3; i++) {
      await e.runOnce();
      clock.advance(const Duration(minutes: 1));
    }
    expect(sender.received, hasLength(3));
    final keys = sender.received.map((r) => r.headers['Idempotency-Key']).toSet();
    expect(keys, hasLength(1));
    expect(
      keys.single,
      matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')),
      reason: 'UUIDv7',
    );
    expect(db.items(), isEmpty);
  });

  test('409 không bị gửi lại tự động, mục chờ người dùng xem lại', () async {
    final sender = ScriptedSender([
      const SyncResponse(status: 409, body: '{"type":"about:blank","code":"OKR-409-VERSION-CONFLICT","detail":"x"}'),
    ]);
    final e = engine(sender);
    final notices = <SyncNotice>[];
    e.notices.listen(notices.add);
    net.online = true;
    await checkin(e);
    await e.runOnce();
    clock.advance(const Duration(hours: 1));
    await e.runOnce();
    await e.runOnce();
    expect(sender.received, hasLength(1));
    final item = db.items().single;
    expect(item.state, SyncItemState.needsReview);
    expect(item.reviewReason, ReviewReason.conflict);
    expect(item.problemCode, 'OKR-409-VERSION-CONFLICT');
    expect(notices.whereType<NeedsReview>(), hasLength(1));
  });

  test('422 giữ mục chờ xem lại; gửi lại sau xem lại dùng khoá mới', () async {
    final sender = ScriptedSender([const SyncResponse(status: 422)]);
    final e = engine(sender);
    net.online = true;
    final id = await checkin(e);
    await e.runOnce();
    expect(db.item(id)!.reviewReason, ReviewReason.rejected);
    final oldKey = sender.received.single.headers['Idempotency-Key'];
    await e.resubmit(id, body: {'value': 2}, ifMatch: '"8"');
    await e.runOnce();
    expect(sender.received, hasLength(2));
    expect(sender.received.last.headers['Idempotency-Key'], isNot(oldKey));
    expect(sender.received.last.headers['If-Match'], '"8"');
    expect(db.items(), isEmpty);
  });

  test('FIFO theo tài nguyên: mục sau chờ mục trước, tài nguyên khác không bị chặn', () async {
    final sender = ScriptedSender([const SyncResponse(status: 500)]);
    final e = engine(sender);
    await checkin(e, kr: 'a', value: 1);
    await checkin(e, kr: 'a', value: 2);
    await checkin(e, kr: 'b', value: 3);
    net.online = true;
    await e.runOnce();
    expect(sender.received.map((r) => r.path), [
      '/api/v1/okr/key-results/a/checkins',
      '/api/v1/okr/key-results/b/checkins',
    ]);
    clock.advance(const Duration(minutes: 1));
    await e.runOnce();
    expect(sender.received.skip(2).map((r) => r.body), ['{"value":1}', '{"value":2}']);
    expect(db.items(), isEmpty);
  });

  test('401 dừng hàng đợi và báo đăng nhập lại, mục giữ nguyên khoá', () async {
    final sender = ScriptedSender([const SyncResponse(status: 401)]);
    final e = engine(sender);
    final notices = <SyncNotice>[];
    e.notices.listen(notices.add);
    await checkin(e, kr: 'a');
    await checkin(e, kr: 'b');
    net.online = true;
    await e.runOnce();
    expect(sender.received, hasLength(1));
    expect(notices.whereType<SignInRequired>(), hasLength(1));
    await e.runOnce();
    expect(sender.received, hasLength(3));
    expect(sender.received[0].headers['Idempotency-Key'], sender.received[1].headers['Idempotency-Key']);
    expect(db.items(), isEmpty);
  });

  test('thử lại lùi dần có giới hạn và tôn trọng Retry-After', () async {
    final sender = ScriptedSender([
      const SyncResponse(status: 503),
      const SyncResponse(status: 429, headers: {'retry-after': '120'}),
    ]);
    final e = engine(sender);
    net.online = true;
    final id = await checkin(e);
    await e.runOnce();
    final first = db.item(id)!.nextAttemptAt.difference(clock.now);
    expect(first.inMilliseconds, inInclusiveRange(2000, 2400));
    clock.advance(const Duration(seconds: 3));
    await e.runOnce();
    expect(db.item(id)!.nextAttemptAt.difference(clock.now), const Duration(seconds: 120));
    clock.advance(const Duration(seconds: 60));
    await e.runOnce();
    expect(sender.received, hasLength(2), reason: 'chưa tới Retry-After');
  });

  test('quá số lần thử thì chuyển sang chờ xem lại', () async {
    final sender = ScriptedSender(List.filled(5, const SyncResponse(status: 500)));
    final e = engine(sender, policy: const SyncPolicy(maxAttempts: 3));
    net.online = true;
    final id = await checkin(e);
    for (var i = 0; i < 5; i++) {
      await e.runOnce();
      clock.advance(const Duration(hours: 1));
    }
    expect(sender.received, hasLength(3));
    expect(db.item(id)!.reviewReason, ReviewReason.retriesExhausted);
  });

  test('lần gửi chưa rõ kết quả quá 47 giờ thì không tự gửi lại vì máy chủ chỉ giữ khoá 48 giờ', () async {
    final sender = ScriptedSender([const TransportException('cắt kết nối')]);
    final e = engine(sender);
    net.online = true;
    final id = await checkin(e);
    await e.runOnce();
    clock.advance(const Duration(hours: 48));
    await e.runOnce();
    expect(sender.received, hasLength(1));
    expect(db.item(id)!.reviewReason, ReviewReason.idempotencyWindowExpired);
  });

  test('mục chờ quá 7 ngày bị huỷ, có báo trước một ngày', () async {
    final e = engine(ScriptedSender());
    final notices = <SyncNotice>[];
    e.notices.listen(notices.add);
    final id = await checkin(e);
    clock.advance(const Duration(days: 6, hours: 1));
    await e.runOnce();
    await e.runOnce();
    expect(notices.whereType<ExpiringSoon>().map((n) => n.itemId), [id]);
    clock.advance(const Duration(days: 1));
    await e.runOnce();
    expect(notices.whereType<Discarded>().map((n) => n.itemId), [id]);
    expect(db.items(), isEmpty);
  });

  test('thông báo theo TL-02: lưu trên máy khi mất sóng, đã gửi n check-in khi có mạng', () async {
    final e = engine(ScriptedSender())..start();
    final notices = <SyncNotice>[];
    e.notices.listen(notices.add);
    await checkin(e, kr: 'a');
    await checkin(e, kr: 'b');
    expect(notices.whereType<SavedLocally>().map((n) => n.text).toSet(), {
      'Đã lưu trên máy. Check-in sẽ tự gửi khi có mạng.',
    });
    net.online = true;
    await e.runOnce();
    expect(notices.whereType<SentPending>().single.text, 'Đã gửi 2 check-in đang chờ.');
    await e.dispose();
  });

  test('không xếp dữ liệu C3 vào hàng đợi', () async {
    final e = engine(ScriptedSender());
    expect(
      () => e.enqueue(kind: 'khieu-nai', resourceKey: 'k', method: 'POST', path: '/x', dataClass: DataClass.c3),
      throwsArgumentError,
    );
  });

  test('đọc Retry-After dạng ngày giờ HTTP', () {
    expect(HttpDateParser.parse('Fri, 23 Oct 2026 08:01:00 GMT'), DateTime.utc(2026, 10, 23, 8, 1));
  });
}
