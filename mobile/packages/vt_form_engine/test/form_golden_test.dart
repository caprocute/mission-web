// Bộ ca biểu mẫu dùng chung (data/golden/form-tests/*.yaml, BR-18-05): máy chủ chạy bằng
// FormGoldenSuiteTest của mission-core, web chạy bằng @vt/form-engine; bản Dart phải cho cùng kết quả.
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:vt_form_engine/vt_form_engine.dart';
import 'package:yaml/yaml.dart';

import 'support/golden_paths.dart';
import 'support/yaml_plain.dart';

void main() {
  final dir = Directory(goldenPath('form-tests/README.md')).parent;
  final files = dir.listSync().whereType<File>().where((f) => f.path.endsWith('.yaml')).toList()
    ..sort((a, b) => a.path.compareTo(b.path));
  final engine = MissionCelEngine();

  test('có ít nhất một bộ ca biểu mẫu', () => expect(files, isNotEmpty));

  for (final file in files) {
    final doc = yamlToPlain(loadYaml(file.readAsStringSync())) as Map<String, Object?>;
    final form = CompiledForm(engine, doc['schema'] as Map<String, Object?>, [
      for (final r in (doc['celRules'] as List?) ?? const []) FormCelRule.fromJson(r as Map<String, Object?>),
    ]);
    group('${doc['form']}', () {
      test('trường C2 khớp khai báo c2', () {
        expect(form.c2Fields, unorderedEquals((doc['c2'] as List?) ?? const []));
      });
      for (final c in (doc['cases'] as List).cast<Map<String, Object?>>()) {
        test('${c['id']}', () {
          final errors = form.check(c['input']);
          expect(errors.isEmpty, c['valid'], reason: '$errors');
          expect(
            errors.map((e) => e.path).toSet(),
            unorderedEquals(((c['errors'] as List?) ?? const []).map((e) => '$e')),
            reason: '$errors',
          );
        });
      }
    });
  }
}
