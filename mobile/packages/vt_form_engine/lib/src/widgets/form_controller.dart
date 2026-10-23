import 'package:flutter/foundation.dart';

import '../schema/compiled_form.dart';
import '../schema/form_definition.dart';

/// Trạng thái của một biểu mẫu động: dữ liệu đang nhập, trường đã chạm, lỗi máy chủ trả về.
/// Kiểm ở máy dùng đúng bộ kiểm của máy chủ ([CompiledForm]) để phản hồi nhanh; máy chủ vẫn kiểm lại.
class VtFormController extends ChangeNotifier {
  VtFormController(this.definition, [Map<String, Object?> initial = const {}])
    : _model = _deepCopy(initial) as Map<String, Object?>;

  final FormDefinition definition;
  Map<String, Object?> _model;
  final _touched = <String>{};
  var _showAll = false;
  Map<String, String> _serverErrors = const {};
  List<FormViolation>? _violations;

  /// Dữ liệu thô đang nhập (gồm cả trường đang ẩn).
  Map<String, Object?> get model => _model;

  /// Bảng phẳng `form` cho điều kiện hiển thị.
  Map<String, Object?> get flatForm => _flat ??= definition.validator.flatten(_model);
  Map<String, Object?>? _flat;

  /// Dữ liệu gửi đi: bỏ trường đang ẩn (như Formly bỏ giá trị khi ẩn) và giá trị rỗng.
  Map<String, Object?> get value => _prune(definition.fields, _model, flatForm);

  /// Lỗi của dữ liệu gửi đi theo bộ kiểm dùng chung.
  List<FormViolation> get violations => _violations ??= definition.validator.check(value);

  bool get isValid => violations.isEmpty && _serverErrors.isEmpty && definition.unsupportedFields.isEmpty;

  /// Thông báo lỗi hiện dưới trường; chỉ hiện khi trường đã được chạm hoặc sau khi bấm lưu, nộp.
  String? errorFor(String path) {
    final server = _serverErrors[path];
    if (server != null) return server;
    if (!_showAll && !_touched.contains(path)) return null;
    for (final v in violations) {
      if (v.path == path) return v.message;
    }
    return null;
  }

  /// Gán lỗi theo trường từ Problem Details (`errors[]`) của máy chủ.
  set serverErrors(Map<String, String> errors) {
    _serverErrors = Map.unmodifiable(errors);
    notifyListeners();
  }

  /// Hiện mọi lỗi (khi người dùng bấm lưu hoặc nộp).
  void showAllErrors() {
    _showAll = true;
    notifyListeners();
  }

  void touch(String path) {
    if (_touched.add(path)) notifyListeners();
  }

  bool isHidden(FieldSpec f) => definition.isHidden(f, flatForm);

  Object? read(List<Object> path) {
    Object? cur = _model;
    for (final p in path) {
      if (cur is Map && p is String) {
        cur = cur[p];
      } else if (cur is List && p is int && p < cur.length) {
        cur = cur[p];
      } else {
        return null;
      }
    }
    return cur;
  }

  /// Ghi giá trị theo đường dẫn (khoá chuỗi, chỉ số dòng); `null` là xoá.
  void write(List<Object> path, Object? v, {String? touchedPath}) {
    _model = _deepCopy(_model) as Map<String, Object?>;
    Object? cur = _model;
    for (var i = 0; i < path.length - 1; i++) {
      final p = path[i];
      final nextIsIndex = path[i + 1] is int;
      if (cur is Map<String, Object?> && p is String) {
        cur = cur[p] ??= nextIsIndex ? <Object?>[] : <String, Object?>{};
      } else if (cur is List<Object?> && p is int) {
        cur = cur[p];
      }
    }
    final last = path.last;
    if (cur is Map<String, Object?> && last is String) {
      if (v == null) {
        cur.remove(last);
      } else {
        cur[last] = v;
      }
    } else if (cur is List<Object?> && last is int) {
      cur[last] = v;
    }
    if (touchedPath != null) _touched.add(touchedPath);
    if (_serverErrors.containsKey(touchedPath)) {
      _serverErrors = Map.of(_serverErrors)..remove(touchedPath);
    }
    _violations = null;
    _flat = null;
    notifyListeners();
  }

  /// Thay toàn bộ dữ liệu (ví dụ sau khi tải lại hồ sơ).
  void reset(Map<String, Object?> initial) {
    _model = _deepCopy(initial) as Map<String, Object?>;
    _touched.clear();
    _showAll = false;
    _serverErrors = const {};
    _violations = null;
    _flat = null;
    notifyListeners();
  }

  static Object? _deepCopy(Object? v) => switch (v) {
    Map() => <String, Object?>{for (final e in v.entries) '${e.key}': _deepCopy(e.value)},
    List() => <Object?>[for (final x in v) _deepCopy(x)],
    _ => v,
  };

  Map<String, Object?> _prune(List<FieldSpec> fields, Map<String, Object?> m, Map<String, Object?> flat) {
    final known = {for (final f in fields) f.key};
    final out = <String, Object?>{
      // khoá không có trong lược đồ giữ nguyên để bộ kiểm báo "Trường không có trong biểu mẫu"
      for (final e in m.entries)
        if (!known.contains(e.key)) e.key: e.value,
    };
    for (final f in fields) {
      final v = m[f.key];
      if (v == null || definition.isHidden(f, flat)) continue;
      switch (f.kind) {
        case FieldKind.group when v is Map:
          final inner = _prune(f.children, v.cast<String, Object?>(), flat);
          if (inner.isNotEmpty) out[f.key] = inner;
        case FieldKind.repeat when v is List:
          out[f.key] = [
            for (final row in v)
              if (row is Map) _prune(f.children, row.cast<String, Object?>(), flat) else row,
          ];
        case FieldKind.multi when v is List && v.isEmpty:
          break;
        default:
          if (v is String && v.isEmpty) continue;
          out[f.key] = v;
      }
    }
    return out;
  }
}
