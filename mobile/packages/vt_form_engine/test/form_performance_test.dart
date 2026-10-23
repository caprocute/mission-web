// Đo thời gian dựng biểu mẫu 50 trường (MOB-06 mục 6, đối chiếu BR-18-08). Số đo trên máy kiểm
// thử (chế độ debug, không có GPU) chỉ để ghi nhận; số chính thức đo trên trình giả lập tầm trung.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vt_form_engine/vt_form_engine.dart';

Map<String, Object?> fiftyFields() {
  final props = <String, Object?>{};
  final ui = <String, Object?>{};
  for (var i = 1; i <= 50; i++) {
    final key = 'truong_$i';
    props[key] = switch (i % 5) {
      0 => {'type': 'string', 'title': 'Văn bản $i', 'maxLength': 255, 'x-classification': i.isEven ? 'C2' : 'C1'},
      1 => {
        'type': 'number',
        'title': 'Số $i',
        'minimum': 0,
        'maximum': 100,
        'x-cel': 'self <= 100.0',
        'x-classification': 'C1',
      },
      2 => {
        'type': 'string',
        'title': 'Chọn $i',
        'oneOf': [
          {'const': 'CO', 'title': 'Có'},
          {'const': 'KHONG', 'title': 'Không'},
        ],
        'x-classification': 'C1',
      },
      3 => {'type': 'string', 'title': 'Ngày $i', 'format': 'date', 'x-classification': 'C1'},
      _ => {'type': 'string', 'title': 'Ghi chú $i', 'maxLength': 2000, 'x-kind': 'textarea', 'x-classification': 'C2'},
    };
    if (i % 5 == 4) ui[key] = {'hideExpression': 'form.truong_${i - 2} == "KHONG"'};
  }
  return {
    'formKey': 'nam-muoi-truong',
    'version': '1',
    'schemaJson': {
      'type': 'object',
      'required': ['truong_1'],
      'properties': props,
    },
    'uiJson': ui,
    'celRules': [
      {'field': 'truong_5', 'expr': 'size(form.truong_5) <= 255', 'message': 'Quá dài'},
    ],
  };
}

void main() {
  testWidgets('biểu mẫu 50 trường: đọc định nghĩa và dựng khung hình đầu', (tester) async {
    final json = fiftyFields();
    // lượt khởi động để JIT biên dịch mã, giống lần mở màn hình thứ hai trên máy
    FormDefinition.fromUiFormVersion(json);

    final parse = Stopwatch()..start();
    final def = FormDefinition.fromUiFormVersion(json, engine: MissionCelEngine());
    parse.stop();
    final c = VtFormController(def, {'truong_1': 50.0});
    Widget app(Widget body) => MaterialApp(home: Scaffold(body: body));
    // khung hình khởi động: nạp phông chữ, biên dịch widget Material lần đầu
    await tester.pumpWidget(app(ListView(children: [VtDynamicForm(controller: VtFormController(def))])));
    await tester.pumpWidget(app(const SizedBox()));

    final build = Stopwatch()..start();
    await tester.pumpWidget(app(ListView(children: [VtDynamicForm(controller: c)])));
    build.stop();

    // đối chứng: 50 ô nhập Material trần trên cùng máy, để tách phần tốn của khung Flutter chế độ debug
    Widget plain() => ListView(
      children: [
        Column(
          children: [
            for (var i = 0; i < 50; i++)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: TextField(
                  decoration: InputDecoration(labelText: 'Ô $i', border: const OutlineInputBorder()),
                ),
              ),
          ],
        ),
      ],
    );
    await tester.pumpWidget(app(plain()));
    await tester.pumpWidget(app(const SizedBox()));
    final base = Stopwatch()..start();
    await tester.pumpWidget(app(plain()));
    base.stop();

    expect(def.fields.length, 50);
    expect(def.validator.celCount, 11);
    // ignore: avoid_print
    print(
      'MOB-06 do dung 50 truong: doc dinh nghia ${parse.elapsedMicroseconds / 1000} ms, '
      'dung khung hinh dau ${build.elapsedMicroseconds / 1000} ms, '
      'doi chung 50 o nhap tran ${base.elapsedMicroseconds / 1000} ms',
    );
    // ngưỡng 50 ms áp cho bản release trên trình giả lập; ở đây chỉ chặn phần tốn thêm của bộ dựng
    // so với ô nhập trần (không quá gấp đôi) và việc đọc định nghĩa (dưới 50 ms)
    expect(parse.elapsed, lessThan(const Duration(milliseconds: 50)));
    expect(build.elapsedMicroseconds, lessThan(base.elapsedMicroseconds * 2 + 50000));
  });
}
