import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vt_form_engine/vt_form_engine.dart';
import 'package:yaml/yaml.dart';

import 'support/golden_paths.dart';
import 'support/yaml_plain.dart';

/// `UiFormVersion` dựng từ bộ ca biểu mẫu dùng chung, thêm phần trình bày như ví dụ của hợp đồng.
Map<String, Object?> vcYteForm() {
  final doc = yamlToPlain(
    loadYaml(File(goldenPath('form-tests/phieu-vc-yte.yaml')).readAsStringSync()),
  ) as Map<String, Object?>;
  return {
    'formKey': 'phieu-vc-yte',
    'version': '12',
    'status': 'DA_PHAT_HANH',
    'schemaJson': doc['schema'],
    'uiJson': {
      'ty_le_hsba_dat': {'label': 'Tỷ lệ hồ sơ bệnh án đạt (%)'},
      'su_co_y_khoa': {'label': 'Có sự cố y khoa'},
      'mo_ta_su_co': {'hideExpression': 'form.su_co_y_khoa != "CO"', 'label': 'Mô tả sự cố và biện pháp khắc phục'},
      'sang_kien': {'label': 'Sáng kiến'},
    },
    'celRules': doc['celRules'],
    'rowVersion': 9,
  };
}

Widget host(VtFormController c, {bool readOnly = false}) => MaterialApp(
  home: Scaffold(
    body: SingleChildScrollView(
      child: VtDynamicForm(controller: c, readOnly: readOnly),
    ),
  ),
);

void main() {
  testWidgets('dựng phiếu từ lược đồ, ẩn hiện theo điều kiện, hiện lỗi của bộ kiểm dùng chung', (tester) async {
    final def = FormDefinition.fromUiFormVersion(vcYteForm());
    expect(def.hasC2, isTrue);
    expect(def.unsupportedFields, isEmpty);
    final c = VtFormController(def, {'su_co_y_khoa': 'KHONG'});
    await tester.pumpWidget(host(c));

    expect(find.text('Tỷ lệ hồ sơ bệnh án đạt (%) *'), findsOneWidget);
    expect(find.text('Mô tả sự cố và biện pháp khắc phục'), findsNothing);

    // chọn "CO" thì trường mô tả hiện ra
    await tester.tap(find.text('KHONG'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('CO').last);
    await tester.pumpAndSettle();
    expect(c.model['su_co_y_khoa'], 'CO');
    expect(find.text('Mô tả sự cố và biện pháp khắc phục'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextField, 'Tỷ lệ hồ sơ bệnh án đạt (%) *'), '104');
    await tester.enterText(find.widgetWithText(TextField, 'Mô tả sự cố và biện pháp khắc phục'), 'Ngắn');
    c.showAllErrors();
    await tester.pump();
    expect(find.text('Giá trị phải từ 0 đến 100'), findsOneWidget);
    expect(find.text('Mô tả sự cố cần ít nhất 10 ký tự'), findsOneWidget);
    expect(c.isValid, isFalse);

    // dấu phẩy thập phân kiểu Việt Nam đọc thành số thực
    await tester.enterText(find.widgetWithText(TextField, 'Tỷ lệ hồ sơ bệnh án đạt (%) *'), '95,5');
    await tester.enterText(
      find.widgetWithText(TextField, 'Mô tả sự cố và biện pháp khắc phục'),
      'Bệnh nhân té ngã tại khoa',
    );
    await tester.pump();
    expect(c.value['ty_le_hsba_dat'], 95.5);
    expect(c.violations, isEmpty);

    // ẩn trở lại thì giá trị của trường ẩn không được gửi đi
    c.write(['su_co_y_khoa'], 'KHONG');
    await tester.pump();
    expect(c.value.containsKey('mo_ta_su_co'), isFalse);
    expect(c.model['mo_ta_su_co'], 'Bệnh nhân té ngã tại khoa');
  });

  testWidgets('khối lặp: thêm dòng, lỗi mang chỉ số dòng, giới hạn số dòng', (tester) async {
    final c = VtFormController(FormDefinition.fromUiFormVersion(vcYteForm()), {
      'ty_le_hsba_dat': 90,
      'su_co_y_khoa': 'KHONG',
    });
    await tester.pumpWidget(host(c));
    final add = find.widgetWithText(OutlinedButton, VtFormText.addRow);
    Future<void> tapAdd() async {
      await tester.ensureVisible(add);
      await tester.tap(add);
      await tester.pump();
    }

    await tapAdd();
    await tapAdd();
    expect(find.text('Dòng 2'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'ten *').first, 'Sổ tay điện tử');
    await tester.enterText(find.widgetWithText(TextField, 'ten *').last, 'AB');
    await tester.pump();
    expect(c.violations.map((v) => v.path), ['sang_kien.1.ten']);
    c.showAllErrors();
    await tester.pump();
    expect(find.text('Cần ít nhất 3 ký tự'), findsOneWidget);
    await tapAdd();
    // tối đa 3 dòng theo maxItems
    expect(tester.widget<OutlinedButton>(add).onPressed, isNull);
    await tester.ensureVisible(find.byTooltip('Xoá dòng 2'));
    await tester.tap(find.byTooltip('Xoá dòng 2'));
    await tester.pump();
    expect((c.value['sang_kien'] as List).length, 2);
  });

  testWidgets('trường lạ không tự dựng mà báo lỗi rõ, phiếu không hợp lệ', (tester) async {
    final def = FormDefinition.fromUiFormVersion({
      'formKey': 'thu',
      'version': '1',
      'schemaJson': {
        'type': 'object',
        'properties': {
          'ten': {'type': 'string', 'title': 'Tên', 'x-classification': 'C1'},
          'tep': {'type': 'string', 'title': 'Tệp minh chứng', 'x-kind': 'file', 'x-classification': 'C1'},
          'toa_do': {
            'type': 'object',
            'title': 'Toạ độ',
            'properties': {
              'x': {'type': 'null', 'x-classification': 'C1'},
            },
          },
        },
      },
      'uiJson': <String, Object?>{},
    });
    expect(def.unsupportedFields.map((f) => f.key), ['tep', 'x']);
    final c = VtFormController(def);
    await tester.pumpWidget(host(c));
    expect(find.text(VtFormText.unsupported('Tệp minh chứng')), findsOneWidget);
    expect(c.isValid, isFalse);
  });

  testWidgets('chế độ chỉ đọc không cho sửa', (tester) async {
    final c = VtFormController(FormDefinition.fromUiFormVersion(vcYteForm()), {'su_co_y_khoa': 'KHONG'});
    await tester.pumpWidget(host(c, readOnly: true));
    final f = tester.widget<TextField>(find.widgetWithText(TextField, 'Tỷ lệ hồ sơ bệnh án đạt (%) *'));
    expect(f.enabled, isFalse);
  });

  test('định nghĩa có biểu thức sai bị từ chối rõ ràng', () {
    final bad = vcYteForm()
      ..['uiJson'] = {
        'mo_ta_su_co': {'hideExpression': 'form.su_co_y_khoa +'},
      };
    expect(() => FormDefinition.fromUiFormVersion(bad), throwsA(isA<FormDefinitionException>()));
  });
}
