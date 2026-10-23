// Bộ ca vàng CEL dùng chung với vt-cel (cel-java) và @vt/form-engine (cel-js):
// data/golden/cel/golden-cases.yaml phải khớp 100%.
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:vt_form_engine/vt_form_engine.dart';
import 'package:yaml/yaml.dart';

import 'support/golden_paths.dart';
import 'support/yaml_plain.dart';

void main() {
  final doc = loadYaml(File(goldenPath('cel/golden-cases.yaml')).readAsStringSync()) as YamlMap;
  final cal = doc['calendar'] as YamlMap;
  final engine = MissionCelEngine(
    calendar: WorkingCalendar(
      holidays: {for (final d in cal['holidays'] as YamlList) d as String},
      compensatoryWorkdays: {for (final d in cal['compensatoryWorkingDays'] as YamlList) d as String},
    ),
  );
  final cases = doc['cases'] as YamlList;

  test('bộ ca vàng có đủ 76 ca', () => expect(cases.length, 76));

  for (final raw in cases) {
    final c = raw as YamlMap;
    test('${c['id']} [${c['category']}]: ${c['description']}', () {
      final rounding = c['rounding'] as YamlMap?;
      final scale = (rounding?['scale'] ?? c['scale']) as int?;
      final mode = (rounding?['mode'] ?? c['roundingMode']) as String?;
      Object? run() => engine.evaluateExpression(
        c['expression'] as String,
        variableTypes: {
          for (final e in ((c['variableTypes'] as YamlMap?) ?? YamlMap()).entries)
            e.key as String: CelVariableType.fromName(e.value as String),
        },
        returnType: CelVariableType.fromName(c['returnType'] as String),
        inputs: (yamlToPlain(c['inputs']) as Map<String, Object?>?) ?? const {},
        scale: scale,
        mode: mode == null ? null : CelRoundingMode.parse(mode),
      );
      final expectedError = c['expectedError'] as String?;
      if (expectedError != null) {
        expect(run, throwsA(isA<MissionCelError>().having((e) => e.errorCode.code, 'errorCode', expectedError)));
      } else {
        final expected = c['expected'];
        final actual = run();
        expect(actual, expected is int && actual is double ? expected.toDouble() : expected);
      }
    });
  }

  test('biên các hàm sum, min_muc, ngay_lam_viec và làm tròn', () {
    expect(sumCelValues([0.1, 0.2, 0.3]), 0.6);
    expect(sumCelValues(const []), 0.0);
    expect(roundCelScore(59.995, 2, CelRoundingMode.nuaLen), 60.0);
    expect(roundCelScore(59.995, 2, CelRoundingMode.catBo), 59.99);
    expect(roundCelScore(89.995, 2, CelRoundingMode.halfUp), 90.0);
    expect(roundCelScore(89.995, 2, CelRoundingMode.down), 89.99);
    expect(minMucXepLoai('HOAN_THANH_XUAT_SAC', 'HOAN_THANH_TOT'), 'HOAN_THANH_TOT');
    expect(countWorkingDays('2026-03-02', '2026-03-06', WorkingCalendar.defaultTest), 4);
  });
}
