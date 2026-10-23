// Kiểm thử tích hợp với máy chủ giả lập (MOB-06 mục 6): lưu nháp gửi If-Match đúng phiên bản,
// 409 hiện thông báo tải lại, nộp nhận 202 và hiện "chờ ký".
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vt_form_engine/appraisal.dart';

import 'support/fake_mission_server.dart';

class RecordingScreenSecurity implements ScreenSecurity {
  final calls = <String>[];
  @override
  Future<void> protect() async => calls.add('protect');
  @override
  Future<void> release() async => calls.add('release');
}

void main() {
  late FakeMissionServer server;
  late AppraisalApi api;
  var keys = 0;

  setUp(() {
    server = FakeMissionServer();
    api = AppraisalApi(baseUri: Uri.parse('https://may-chu-gia-lap.test/api/v1/'), client: server.client);
    keys = 0;
  });

  SelfAssessmentController controller({EncryptedDraftStore? drafts, String ref = 'HTTNV'}) => SelfAssessmentController(
    api: api,
    profileId: FakeMissionServer.id,
    referenceRating: ref,
    drafts: drafts,
    autosaveInterval: null,
    newKey: () => 'khoa-${++keys}',
  );

  Future<void> open(
    WidgetTester tester,
    SelfAssessmentController c, {
    ScreenSecurity? security,
    ValueChanged<String?>? onSign,
  }) async {
    // màn hình cao để ListView dựng đủ mọi trường của phiếu
    tester.view.physicalSize = const Size(800, 3200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        home: SelfAssessmentScreen(
          controller: c,
          screenSecurity: security ?? const NoopScreenSecurity(),
          onAwaitingSignature: onSign,
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Finder field(String label) => find.widgetWithText(TextField, label);

  Future<void> tapText(WidgetTester tester, String text) async {
    await tester.ensureVisible(find.text(text).last);
    await tester.tap(find.text(text).last);
    await tester.pumpAndSettle();
  }

  testWidgets('danh sách hồ sơ của tôi mở được hồ sơ', (tester) async {
    AppraisalProfileRow? opened;
    await tester.pumpWidget(
      MaterialApp(
        home: MyAppraisalsScreen(api: api, onOpen: (r) => opened = r),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Quý IV/2026 · Luồng Đảng'), findsOneWidget);
    expect(find.textContaining('Đang tự đánh giá'), findsOneWidget);
    await tester.tap(find.text('Quý IV/2026 · Luồng Đảng'));
    expect(opened?.autoRatingCode, 'HTTNV');
  });

  testWidgets('tiêu chí chung quy đổi bốn mức, lưu nháp gửi If-Match và Idempotency-Key', (tester) async {
    final security = RecordingScreenSecurity();
    final c = controller();
    await open(tester, c, security: security);
    expect(security.calls, ['protect']);
    expect(find.text('Đáp ứng 9/9 tiêu chí (100%): Thực hiện tốt'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('criterion-TCC-01')));
    await tester.pump();
    expect(find.text('Đáp ứng 8/9 tiêu chí (89%): Cơ bản đảm bảo'), findsOneWidget);

    await tester.enterText(field('Ưu điểm, hạn chế, hướng khắc phục *'), 'Hoàn thành đúng hạn các nhiệm vụ then chốt.');
    await tapText(tester, AppraisalText.saveDraft);
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    expect(put.headers['If-Match'], '"3"');
    expect(put.headers['Idempotency-Key'], 'khoa-1');
    final body = jsonDecode(put.body) as Map<String, Object?>;
    expect(body['selfRatingCode'], 'HTTNV');
    expect((body['criteria'] as List).first, {'criteriaCode': 'TCC-01', 'met': false});
    expect(find.text(AppraisalText.saved), findsOneWidget);

    // lần lưu sau dùng ETag mới mà máy chủ trả về
    await tester.enterText(field('Ưu điểm, hạn chế, hướng khắc phục *'), 'Hoàn thành đúng hạn, còn chậm một báo cáo.');
    await tapText(tester, AppraisalText.saveDraft);
    expect(server.requests.lastWhere((r) => r.method == 'PUT').headers['If-Match'], '"4"');

    await tester.pumpWidget(const SizedBox());
    expect(security.calls, ['protect', 'release']);
  });

  testWidgets('409 khi người khác đã sửa: hiện thông báo và tải lại được', (tester) async {
    final c = controller();
    await open(tester, c);
    server.rowVersion = 7; // người khác vừa lưu
    await tester.enterText(field('Ưu điểm, hạn chế, hướng khắc phục *'), 'Nội dung đồng chí đang nhập.');
    await tapText(tester, AppraisalText.saveDraft);
    expect(find.text(AppraisalText.conflict), findsOneWidget);
    await tapText(tester, AppraisalText.reload);
    expect(c.etag, '"7"');
    expect(find.text(AppraisalText.conflict), findsNothing);
  });

  testWidgets('khác mức tham khảo thì bắt buộc giải trình 20 ký tự; nộp nhận 202 rồi chờ ký', (tester) async {
    String? signLocation;
    final c = controller();
    await open(tester, c, onSign: (l) => signLocation = l);
    await tester.enterText(field('Ưu điểm, hạn chế, hướng khắc phục *'), 'Hoàn thành đúng hạn các nhiệm vụ then chốt.');
    await tapText(tester, 'Hoàn thành tốt nhiệm vụ');
    await tapText(tester, 'Hoàn thành nhiệm vụ');
    expect(find.text(AppraisalText.differsWarning), findsOneWidget);

    await tester.enterText(field('Giải trình khi khác mức tham khảo'), 'Quá ngắn');
    await tapText(tester, AppraisalText.submit);
    expect(find.text(AppraisalText.reasonRequired), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);

    await tester.enterText(
      field('Giải trình khi khác mức tham khảo'),
      'Còn chậm một báo cáo chuyên đề do chủ quan nên tự nhận mức thấp hơn.',
    );
    await tapText(tester, AppraisalText.submit);
    final post = server.requests.lastWhere((r) => r.method == 'POST');
    expect(post.url.path, '/api/v1/appraisals/profiles/${FakeMissionServer.id}/submit');
    expect(post.headers['If-Match'], '"4"');
    expect(c.phase, SelfAssessmentPhase.awaitingSignature);
    expect(find.text(AppraisalText.awaitingSignatureTitle), findsOneWidget);
    expect(signLocation, '/api/v1/jobs/job-1');
  });

  testWidgets('đề xuất xuất sắc cần giải pháp đột phá và minh chứng đã xác nhận', (tester) async {
    final c = controller(ref: 'HTXSNV');
    await open(tester, c);
    expect(find.text('Giải pháp đột phá'), findsOneWidget);
    await tester.enterText(field('Ưu điểm, hạn chế, hướng khắc phục *'), 'Hoàn thành đúng hạn các nhiệm vụ then chốt.');
    await tester.enterText(field('Giải pháp đột phá'), 'Số hoá quy trình tổng hợp báo cáo tuần của đơn vị.');
    await tester.pump();
    expect(c.platformErrors(), {'breakthrough': AppraisalText.breakthroughRequired});
    expect(c.readyToSubmit, isFalse);
  });

  test('mất mạng: phiếu giữ trên máy, lần thử lại dùng lại Idempotency-Key', () async {
    final drafts = InMemoryDraftStore();
    final c = controller(drafts: drafts);
    await c.load();
    c.form!.write(['content'], 'Nội dung nhập khi mất mạng.');
    server.offline = true;
    expect(await c.saveDraft(), isFalse);
    expect(c.banner?.text, AppraisalText.offlineSaved);
    expect(drafts.drafts[FakeMissionServer.id]?.input['content'], 'Nội dung nhập khi mất mạng.');
    server.offline = false;
    expect(await c.saveDraft(), isTrue);
    final puts = server.requests.where((r) => r.method == 'PUT').toList();
    expect(puts.map((r) => r.headers['Idempotency-Key']).toSet(), {'khoa-1'});

    // mở lại khi hồ sơ chưa đổi phiên bản: bản nháp trên máy được dùng lại
    final again = controller(drafts: drafts);
    await again.load();
    expect(again.form!.model['content'], 'Nội dung nhập khi mất mạng.');
  });

  test('hồ sơ chưa lập thì báo kết quả đang được tính', () async {
    server.status = 'CHUA_LAP';
    final c = controller();
    await c.load();
    expect(c.phase, SelfAssessmentPhase.notReady);
  });

  test('UUIDv7 đúng định dạng', () {
    expect(uuidV7(), matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
  });
}
