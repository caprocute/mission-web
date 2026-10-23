// Các tính chất của động cơ CEL ngoài bộ ca vàng: giới hạn an toàn, lỗi tràn số, ngữ nghĩa
// && || bỏ qua lỗi, macro, lưu đệm chương trình.
import 'package:flutter_test/flutter_test.dart';
import 'package:vt_form_engine/vt_form_engine.dart';

void main() {
  final engine = MissionCelEngine();
  Object? ev(
    String e, {
    Map<String, CelVariableType> t = const {},
    Map<String, Object?> i = const {},
    CelVariableType r = CelVariableType.DYN,
  }) => engine.evaluateExpression(e, variableTypes: t, returnType: r, inputs: i);
  Matcher code(MissionCelErrorCode c) => throwsA(isA<MissionCelError>().having((e) => e.errorCode, 'errorCode', c));

  test('macro và toán tử thường dùng', () {
    const l = {'ds': CelVariableType.LIST_INT};
    expect(
      ev(
        'ds.exists_one(x, x > 2)',
        t: l,
        i: {
          'ds': [1, 2, 3],
        },
      ),
      true,
    );
    expect(
      ev(
        'ds.map(x, x % 2 == 0, x * 10)',
        t: l,
        i: {
          'ds': [1, 2, 3, 4],
        },
      ),
      [20, 40],
    );
    expect(
      ev(
        'size(ds) == 3 && ds[1] == 2',
        t: l,
        i: {
          'ds': [1, 2, 3],
        },
      ),
      true,
    );
    expect(ev("'ab' + 'c' == 'abc' && 'mission'.startsWith('mis')"), true);
    expect(ev('{"a": 1}["a"] + 1'), 2);
    expect(ev('[1, 2] + [3]'), [1, 2, 3]);
    expect(ev('int(7.9) + 1'), 8);
    expect(ev('1 == 1.0'), true);
    expect(ev('2 < 2.5'), true);
    expect(ev("size('Ngắn')"), 4);
  });

  test('&& và || bỏ qua lỗi khi vế còn lại đã quyết định kết quả', () {
    const m = {'m': CelVariableType.MAP_STRING_DYN};
    expect(ev('m.khong_co == 1 || true', t: m, i: {'m': <String, Object?>{}}), true);
    expect(ev('false && m.khong_co == 1', t: m, i: {'m': <String, Object?>{}}), false);
    expect(
      () => ev('m.khong_co == 1 || false', t: m, i: {'m': <String, Object?>{}}),
      code(MissionCelErrorCode.missingVar),
    );
  });

  test('lỗi khi chạy: chia cho 0, tràn số, vô hạn', () {
    expect(() => ev('1 / 0'), code(MissionCelErrorCode.evalError));
    expect(() => ev('9223372036854775807 + 1'), code(MissionCelErrorCode.evalError));
    expect(() => ev('1.0 / 0.0', r: CelVariableType.DOUBLE), code(MissionCelErrorCode.evalError));
  });

  test('giới hạn an toàn: độ dài, chiều sâu cây, số vòng lặp', () {
    expect(() => ev('1 + ' * 1100 + '1'), code(MissionCelErrorCode.type));
    expect(() => ev('${'(' * 30}1${')' * 30} + ${List.filled(30, '1').join(' + ')}'), code(MissionCelErrorCode.type));
    final big = List.generate(1001, (i) => i);
    expect(
      () => ev('ds.all(x, x >= 0)', t: {'ds': CelVariableType.LIST_INT}, i: {'ds': big}),
      code(MissionCelErrorCode.sandboxLimit),
    );
  });

  test('cú pháp sai, hàm lạ, sai kiểu là lỗi kiểu khi biên dịch', () {
    expect(() => ev('1 +'), code(MissionCelErrorCode.type));
    expect(() => ev('khong_co_ham(1)'), code(MissionCelErrorCode.type));
    expect(() => ev("'a' < 1"), code(MissionCelErrorCode.type));
    expect(() => ev('true ? 1 : "a"'), code(MissionCelErrorCode.type));
    expect(() => ev('   '), code(MissionCelErrorCode.type));
  });

  test('NULLABLE_STRING nhận null, kiểu khác thiếu thì báo thiếu biến', () {
    expect(ev('s == null', t: {'s': CelVariableType.NULLABLE_STRING}, i: {'s': null}), true);
    expect(
      () => ev('s == null', t: {'s': CelVariableType.STRING}, i: {'s': null}),
      code(MissionCelErrorCode.missingVar),
    );
  });

  test('chương trình đã biên dịch được lưu đệm', () {
    final e = MissionCelEngine();
    final a = e.compile('x > 1', {'x': CelVariableType.INT}, CelVariableType.BOOL);
    final b = e.compile('x > 1', {'x': CelVariableType.INT}, CelVariableType.BOOL);
    expect(identical(a, b), isTrue);
    expect(e.cachedProgramCount, 1);
  });
}
