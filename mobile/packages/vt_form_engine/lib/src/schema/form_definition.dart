/// Định nghĩa biểu mẫu động đọc từ `UiFormVersion` (`GET /api/v1/ui/forms/{key}/current`, MOD-08,
/// bảng `config.form_schema`): JSON Schema có `x-cel`, `x-classification` và phần trình bày `uiJson`
/// (nhãn, `hideExpression`, thông báo, nhóm trường). Chỉ nhận các loại trường có trong danh mục dùng
/// chung với web (SCR-1802); loại khác được đánh dấu không hỗ trợ để giao diện báo lỗi rõ.
library;

import '../cel/cel_type.dart';
import '../cel/mission_cel.dart';
import 'compiled_form.dart';

/// Loại trường dựng được trên di động.
enum FieldKind {
  /// Văn bản ngắn (`x-kind: text`, chuỗi tối đa 255 ký tự).
  text,

  /// Văn bản dài (`x-kind: textarea`, widget `vt.input.textarea`, chuỗi dài hơn 255 ký tự).
  textarea,

  /// Số thực (`type: number`).
  number,

  /// Số nguyên (`type: integer`).
  integer,

  /// Ngày (`format: date`), lưu dạng `YYYY-MM-DD`.
  date,

  /// Chọn một (`oneOf` có `const`, hoặc `enum`).
  select,

  /// Chọn nhiều (mảng chuỗi có `items.enum`).
  multi,

  /// Có hoặc không (`type: boolean`, widget `vt.input.checkbox`).
  checkbox,

  /// Khối lặp (mảng đối tượng, `x-kind: table`).
  repeat,

  /// Nhóm trường (`type: object`).
  group,

  /// Loại trường chưa có trên di động: hiện thông báo lỗi, không tự dựng.
  unsupported,
}

class FieldOption {
  const FieldOption(this.value, this.label);
  final Object? value;
  final String label;
}

class FieldSpec {
  FieldSpec({
    required this.key,
    required this.path,
    required this.kind,
    required this.label,
    this.hint,
    this.required = false,
    this.options = const [],
    this.c2 = false,
    this.maxLength,
    this.minItems,
    this.maxItems,
    this.hideExpression,
    this.children = const [],
    this.unsupportedReason,
  });

  /// Khoá dữ liệu (`ty_le_hsba_dat`).
  final String key;

  /// Đường dẫn chấm trong phiếu, trùng đường dẫn lỗi của bộ kiểm (`sang_kien.ten` với phần tử lặp).
  final String path;
  final FieldKind kind;
  final String label;
  final String? hint;
  final bool required;
  final List<FieldOption> options;

  /// Trường lớp C2 (BR-18-11): không lưu dạng rõ, không ghi nhật ký, màn hình chặn chụp.
  final bool c2;
  final int? maxLength;
  final int? minItems;
  final int? maxItems;

  /// Điều kiện ẩn CEL (biến `form`), đã biên dịch.
  final MissionCelProgram? hideExpression;

  /// Trường con của nhóm hoặc của một dòng khối lặp.
  final List<FieldSpec> children;
  final String? unsupportedReason;
}

/// Biểu mẫu đã đọc và biên dịch: danh sách trường để dựng, bộ kiểm dùng chung với máy chủ.
class FormDefinition {
  FormDefinition._({
    required this.formKey,
    required this.version,
    required this.rowVersion,
    required this.fields,
    required this.validator,
    required this.engine,
    required this.sections,
  });

  /// Đọc `UiFormVersion`; ném [FormDefinitionException] khi lược đồ hay biểu thức không biên dịch được.
  factory FormDefinition.fromUiFormVersion(Map<String, Object?> json, {MissionCelEngine? engine}) {
    final cel = engine ?? MissionCelEngine();
    final schema = _map(json['schemaJson']);
    final ui = _map(json['uiJson']);
    final rules = [for (final r in (json['celRules'] as List?) ?? const []) FormCelRule.fromJson(_map(r))];
    final validator = CompiledForm(cel, schema, rules);
    final widgets = _map(ui['widgets']);
    final fields = _fields(cel, schema, ui, widgets, '');
    final sections = <FormSection>[
      for (final s in (ui['sections'] as List?) ?? const [])
        FormSection('${_map(s)['title'] ?? ''}', [for (final f in (_map(s)['fields'] as List?) ?? const []) '$f']),
    ];
    return FormDefinition._(
      formKey: '${json['formKey'] ?? ''}',
      version: '${json['version'] ?? ''}',
      rowVersion: json['rowVersion'] is int ? json['rowVersion'] as int : null,
      fields: fields,
      validator: validator,
      engine: cel,
      sections: sections,
    );
  }

  final String formKey;
  final String version;
  final int? rowVersion;
  final List<FieldSpec> fields;
  final CompiledForm validator;
  final MissionCelEngine engine;

  /// Nhóm trường theo `uiJson.sections` (nếu có); trống thì dựng theo thứ tự lược đồ.
  final List<FormSection> sections;

  /// Có trường C2: màn hình phải bật chặn chụp màn hình.
  bool get hasC2 => validator.c2Fields.isNotEmpty;

  /// Các trường lạ (kể cả trong nhóm, khối lặp) không dựng được.
  List<FieldSpec> get unsupportedFields => [
    for (final f in _walk(fields))
      if (f.kind == FieldKind.unsupported) f,
  ];

  static Iterable<FieldSpec> _walk(List<FieldSpec> fs) sync* {
    for (final f in fs) {
      yield f;
      yield* _walk(f.children);
    }
  }

  /// Trường có bị ẩn theo `hideExpression` với dữ liệu hiện tại không. Biểu thức lỗi khi chạy thì
  /// vẫn hiện trường để người dùng thấy và nhập được; máy chủ kiểm lại khi lưu.
  bool isHidden(FieldSpec f, Map<String, Object?> flatForm) {
    final p = f.hideExpression;
    if (p == null) return false;
    try {
      return engine.evaluate(p, {'form': flatForm}) == true;
    } on MissionCelError {
      return false;
    }
  }

  static Map<String, Object?> _map(Object? v) => v is Map ? v.cast<String, Object?>() : <String, Object?>{};

  static List<FieldSpec> _fields(
    MissionCelEngine cel,
    Map<String, Object?> schema,
    Map<String, Object?> ui,
    Map<String, Object?> widgets,
    String base,
  ) {
    final props = _map(schema['properties']);
    final required = {for (final r in (schema['required'] as List?) ?? const []) '$r'};
    return [
      for (final e in props.entries)
        _field(
          cel,
          e.key,
          _map(e.value),
          base.isEmpty ? e.key : '$base.${e.key}',
          required.contains(e.key),
          ui,
          widgets,
        ),
    ];
  }

  static FieldSpec _field(
    MissionCelEngine cel,
    String key,
    Map<String, Object?> p,
    String path,
    bool required,
    Map<String, Object?> ui,
    Map<String, Object?> widgets,
  ) {
    final u = _map(ui[key]);
    final type = p['type'] is List ? (p['type'] as List).firstWhere((t) => t != 'null', orElse: () => null) : p['type'];
    final xKind = p['x-kind'] as String?;
    final widget = (widgets[key] ?? u['widget']) as String?;
    final items = _map(p['items']);
    String? reason;
    var kind = switch ((type, xKind)) {
      (_, 'file' || 'signature') => null,
      ('string', _) when p['oneOf'] is List || p['enum'] is List => FieldKind.select,
      ('string', _) when p['format'] == 'date' || xKind == 'date' => FieldKind.date,
      ('string', 'textarea') => FieldKind.textarea,
      ('string', _) when widget == 'vt.input.textarea' || ((p['maxLength'] as num?) ?? 0) > 255 => FieldKind.textarea,
      ('string', _) when p['format'] == null || p['format'] == 'email' => FieldKind.text,
      ('number', _) => FieldKind.number,
      ('integer', _) => FieldKind.integer,
      ('boolean', _) => FieldKind.checkbox,
      ('object', _) => FieldKind.group,
      ('array', _) when items['type'] == 'object' => FieldKind.repeat,
      ('array', _) when items['type'] == 'string' && (items['enum'] is List || items['oneOf'] is List) =>
        FieldKind.multi,
      _ => null,
    };
    if (kind == null) {
      reason = xKind != null
          ? 'Loại trường "$xKind" chưa hỗ trợ trên ứng dụng di động'
          : 'Kiểu dữ liệu chưa hỗ trợ trên ứng dụng di động';
      kind = FieldKind.unsupported;
    }
    MissionCelProgram? hide;
    final hideExpr = u['hideExpression'];
    if (hideExpr is String && hideExpr.trim().isNotEmpty) {
      try {
        hide = cel.compile(hideExpr, {'form': CelVariableType.MAP_STRING_DYN}, CelVariableType.BOOL);
      } on MissionCelError catch (e) {
        throw FormDefinitionException('Điều kiện hiển thị không hợp lệ ở $key', e);
      }
    }
    final optSource = kind == FieldKind.multi ? items : p;
    final options = <FieldOption>[
      if (optSource['oneOf'] is List)
        for (final o in (optSource['oneOf'] as List).map(_map)) FieldOption(o['const'], '${o['title'] ?? o['const']}')
      else if (optSource['enum'] is List)
        // giống @vt/form-engine: `enum` không có nhãn thì hiện chính giá trị; nhãn nghiệp vụ dùng `oneOf`
        for (final v in optSource['enum'] as List) FieldOption(v, '$v'),
    ];
    final children = switch (kind) {
      FieldKind.group => _fields(cel, p, ui, widgets, path),
      // phần tử khối lặp: đường dẫn lỗi mang chỉ số dòng, nên trường con dùng đường dẫn tương đối
      FieldKind.repeat => _fields(cel, items, ui, widgets, ''),
      _ => const <FieldSpec>[],
    };
    return FieldSpec(
      key: key,
      path: path,
      kind: kind,
      label: '${u['label'] ?? p['title'] ?? key}',
      hint: (u['description'] ?? p['description']) as String?,
      required: required,
      options: options,
      c2: p['x-classification'] == 'C2',
      maxLength: (p['maxLength'] as num?)?.toInt(),
      minItems: (p['minItems'] as num?)?.toInt(),
      maxItems: (p['maxItems'] as num?)?.toInt(),
      hideExpression: hide,
      children: children,
      unsupportedReason: reason,
    );
  }
}

class FormSection {
  const FormSection(this.title, this.fields);
  final String title;
  final List<String> fields;
}
