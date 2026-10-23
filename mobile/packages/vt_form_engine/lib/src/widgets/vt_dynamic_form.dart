import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../schema/form_definition.dart';
import 'form_controller.dart';
import 'vt_form_text.dart';

/// Bộ dựng biểu mẫu động: dựng trường từ [FormDefinition] (JSON Schema và `uiJson` của
/// `config.form_schema`), ẩn hiện theo `hideExpression`, hiện lỗi của bộ kiểm dùng chung với máy chủ.
/// Trường lạ không tự dựng mà hiện thông báo lỗi rõ. Không ghi nhật ký giá trị nào (trường C2).
class VtDynamicForm extends StatefulWidget {
  const VtDynamicForm({super.key, required this.controller, this.readOnly = false, this.onChanged});

  final VtFormController controller;
  final bool readOnly;

  /// Gọi sau mỗi lần dữ liệu đổi, với dữ liệu gửi đi ([VtFormController.value]).
  final ValueChanged<Map<String, Object?>>? onChanged;

  @override
  State<VtDynamicForm> createState() => _VtDynamicFormState();
}

class _VtDynamicFormState extends State<VtDynamicForm> {
  final _text = <String, TextEditingController>{};

  VtFormController get c => widget.controller;

  @override
  void initState() {
    super.initState();
    c.addListener(_changed);
  }

  @override
  void didUpdateWidget(VtDynamicForm old) {
    super.didUpdateWidget(old);
    if (old.controller != widget.controller) {
      old.controller.removeListener(_changed);
      widget.controller.addListener(_changed);
      _disposeText((_) => true);
    }
  }

  @override
  void dispose() {
    c.removeListener(_changed);
    _disposeText((_) => true);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
    widget.onChanged?.call(c.value);
  }

  void _disposeText(bool Function(String key) test) {
    for (final k in _text.keys.where(test).toList()) {
      _text.remove(k)!.dispose();
    }
  }

  TextEditingController _controllerFor(String path, String initial) {
    final existing = _text[path];
    if (existing != null) {
      // dữ liệu bị thay từ ngoài (tải lại hồ sơ): đồng bộ lại ô nhập
      if (existing.text != initial && !_editing.contains(path)) existing.text = initial;
      return existing;
    }
    return _text[path] = TextEditingController(text: initial);
  }

  final _editing = <String>{};

  @override
  Widget build(BuildContext context) {
    final def = c.definition;
    final byKey = {for (final f in def.fields) f.key: f};
    final children = <Widget>[];
    if (def.sections.isEmpty) {
      children.addAll(_fieldList(def.fields, const [], ''));
    } else {
      final placed = <String>{};
      for (final s in def.sections) {
        final fs = [for (final k in s.fields) ?byKey[k]];
        placed.addAll(fs.map((f) => f.key));
        final built = _fieldList(fs, const [], '');
        if (built.isEmpty) continue;
        children.add(_SectionTitle(s.title));
        children.addAll(built);
      }
      children.addAll(
        _fieldList(
          [
            for (final f in def.fields)
              if (!placed.contains(f.key)) f,
          ],
          const [],
          '',
        ),
      );
    }
    return FocusTraversalGroup(
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
    );
  }

  List<Widget> _fieldList(List<FieldSpec> fields, List<Object> base, String pathBase) => [
    for (final f in fields)
      if (!c.isHidden(f))
        Padding(
          key: ValueKey('vt-field-${_join(pathBase, f.key)}'),
          padding: const EdgeInsets.only(bottom: 16),
          child: _field(f, [...base, f.key], _join(pathBase, f.key)),
        ),
  ];

  static String _join(String a, String b) => a.isEmpty ? b : '$a.$b';

  String _label(FieldSpec f) => f.required ? '${f.label} *' : f.label;

  Widget _field(FieldSpec f, List<Object> at, String path) {
    final error = c.errorFor(path);
    final enabled = !widget.readOnly;
    final value = c.read(at);
    switch (f.kind) {
      case FieldKind.text || FieldKind.textarea || FieldKind.number || FieldKind.integer:
        final numeric = f.kind == FieldKind.number || f.kind == FieldKind.integer;
        final ctl = _controllerFor(path, value == null ? '' : _display(value));
        return Focus(
          onFocusChange: (has) {
            if (has) {
              _editing.add(path);
            } else {
              _editing.remove(path);
              c.touch(path);
            }
          },
          child: TextField(
            controller: ctl,
            enabled: enabled,
            minLines: f.kind == FieldKind.textarea ? 3 : 1,
            maxLines: f.kind == FieldKind.textarea ? 8 : 1,
            keyboardType: switch (f.kind) {
              FieldKind.number => const TextInputType.numberWithOptions(decimal: true, signed: true),
              FieldKind.integer => const TextInputType.numberWithOptions(signed: true),
              FieldKind.textarea => TextInputType.multiline,
              _ => TextInputType.text,
            },
            maxLength: f.maxLength,
            maxLengthEnforcement: MaxLengthEnforcement.none,
            decoration: InputDecoration(
              labelText: _label(f),
              helperText: f.hint,
              helperMaxLines: 3,
              errorText: error,
              errorMaxLines: 3,
              border: const OutlineInputBorder(),
            ),
            onChanged: (s) =>
                c.write(at, numeric ? _parseNumber(s, f.kind == FieldKind.integer) : s, touchedPath: path),
          ),
        );
      case FieldKind.date:
        final iso = value is String ? value : null;
        return _DateField(
          label: _label(f),
          hint: f.hint,
          error: error,
          iso: iso,
          enabled: enabled,
          onChanged: (v) => c.write(at, v, touchedPath: path),
        );
      case FieldKind.select:
        final idx = f.options.indexWhere((o) => o.value == value);
        return InputDecorator(
          decoration: InputDecoration(
            labelText: _label(f),
            helperText: f.hint,
            helperMaxLines: 3,
            errorText: error,
            errorMaxLines: 3,
            border: const OutlineInputBorder(),
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          ),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<int>(
              isExpanded: true,
              value: idx < 0 ? null : idx,
              hint: const Text(VtFormText.chooseOne),
              onChanged: enabled ? (i) => c.write(at, i == null ? null : f.options[i].value, touchedPath: path) : null,
              items: [
                for (var i = 0; i < f.options.length; i++)
                  DropdownMenuItem(
                    value: i,
                    child: Text(f.options[i].label, overflow: TextOverflow.ellipsis),
                  ),
              ],
            ),
          ),
        );
      case FieldKind.multi:
        final picked = value is List ? value : const [];
        return _Labeled(
          label: _label(f),
          hint: f.hint,
          error: error,
          child: Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              for (final o in f.options)
                FilterChip(
                  label: Text(o.label),
                  selected: picked.contains(o.value),
                  onSelected: enabled
                      ? (on) => c.write(
                          at,
                          on
                              ? [...picked, o.value]
                              : [
                                  for (final x in picked)
                                    if (x != o.value) x,
                                ],
                          touchedPath: path,
                        )
                      : null,
                ),
            ],
          ),
        );
      case FieldKind.checkbox:
        return _Labeled(
          error: error,
          child: CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: Text(_label(f)),
            subtitle: f.hint == null ? null : Text(f.hint!),
            value: value == true,
            onChanged: enabled ? (v) => c.write(at, v ?? false, touchedPath: path) : null,
          ),
        );
      case FieldKind.group:
        return _Labeled(
          label: f.label,
          hint: f.hint,
          error: error,
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: _fieldList(f.children, at, path)),
        );
      case FieldKind.repeat:
        final rows = value is List ? value : const [];
        final canAdd = enabled && (f.maxItems == null || rows.length < f.maxItems!);
        return _Labeled(
          label: _label(f),
          hint: f.hint,
          error: error,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (var i = 0; i < rows.length; i++)
                Card(
                  margin: const EdgeInsets.only(bottom: 8),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(12, 4, 4, 0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Row(
                          children: [
                            Expanded(child: Text(VtFormText.row(i + 1), style: Theme.of(context).textTheme.titleSmall)),
                            if (enabled)
                              IconButton(
                                tooltip: VtFormText.removeRow(i + 1),
                                icon: const Icon(Icons.delete_outline),
                                onPressed: () {
                                  _disposeText((k) => k.startsWith('$path.'));
                                  c.write(at, [
                                    for (var j = 0; j < rows.length; j++)
                                      if (j != i) rows[j],
                                  ], touchedPath: path);
                                },
                              ),
                          ],
                        ),
                        ..._fieldList(f.children, [...at, i], '$path.$i'),
                      ],
                    ),
                  ),
                ),
              Align(
                alignment: Alignment.centerLeft,
                child: OutlinedButton.icon(
                  onPressed: canAdd ? () => c.write(at, [...rows, <String, Object?>{}], touchedPath: path) : null,
                  icon: const Icon(Icons.add),
                  label: const Text(VtFormText.addRow),
                ),
              ),
            ],
          ),
        );
      case FieldKind.unsupported:
        return Semantics(
          container: true,
          liveRegion: true,
          child: Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.errorContainer,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.error_outline, color: Theme.of(context).colorScheme.onErrorContainer),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    VtFormText.unsupported(f.label),
                    style: TextStyle(color: Theme.of(context).colorScheme.onErrorContainer),
                  ),
                ),
              ],
            ),
          ),
        );
    }
  }

  static String _display(Object v) => switch (v) {
    double() when v == v.truncateToDouble() && v.abs() < 1e15 => v.toInt().toString(),
    _ => '$v',
  };

  /// Chuỗi số theo kiểu Việt Nam (dấu phẩy thập phân) hoặc dấu chấm; không đọc được thì giữ chuỗi
  /// để bộ kiểm báo "Giá trị phải là số" như máy chủ.
  static Object? _parseNumber(String s, bool integer) {
    final t = s.trim();
    if (t.isEmpty) return null;
    final norm = t.replaceAll(' ', '').replaceAll(',', '.');
    if (integer) return int.tryParse(norm) ?? double.tryParse(norm) ?? t;
    return double.tryParse(norm) ?? t;
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.title);
  final String title;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 8, bottom: 12),
    child: Semantics(header: true, child: Text(title, style: Theme.of(context).textTheme.titleMedium)),
  );
}

class _Labeled extends StatelessWidget {
  const _Labeled({this.label, this.hint, this.error, required this.child});
  final String? label;
  final String? hint;
  final String? error;
  final Widget child;
  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (label != null) Text(label!, style: t.textTheme.titleSmall),
        if (hint != null) Text(hint!, style: t.textTheme.bodySmall),
        if (label != null || hint != null) const SizedBox(height: 6),
        child,
        if (error != null)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(error!, style: t.textTheme.bodySmall?.copyWith(color: t.colorScheme.error)),
          ),
      ],
    );
  }
}

class _DateField extends StatelessWidget {
  const _DateField({
    required this.label,
    required this.iso,
    required this.enabled,
    required this.onChanged,
    this.hint,
    this.error,
  });
  final String label;
  final String? hint;
  final String? error;
  final String? iso;
  final bool enabled;
  final ValueChanged<String?> onChanged;

  static String? _dmy(String? iso) {
    final m = iso == null ? null : RegExp(r'^(\d{4})-(\d{2})-(\d{2})$').firstMatch(iso);
    return m == null ? iso : '${m[3]}/${m[2]}/${m[1]}';
  }

  @override
  Widget build(BuildContext context) {
    final current = iso == null ? null : DateTime.tryParse(iso!);
    return InkWell(
      onTap: !enabled
          ? null
          : () async {
              final now = DateTime.now();
              final picked = await showDatePicker(
                context: context,
                initialDate: current ?? now,
                firstDate: DateTime(now.year - 50),
                lastDate: DateTime(now.year + 10),
                helpText: label,
              );
              if (picked != null) {
                onChanged(
                  '${picked.year.toString().padLeft(4, '0')}-${picked.month.toString().padLeft(2, '0')}-'
                  '${picked.day.toString().padLeft(2, '0')}',
                );
              }
            },
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: label,
          helperText: hint,
          helperMaxLines: 3,
          errorText: error,
          errorMaxLines: 3,
          border: const OutlineInputBorder(),
          suffixIcon: iso != null && enabled
              ? IconButton(
                  tooltip: VtFormText.clearDate,
                  icon: const Icon(Icons.clear),
                  onPressed: () => onChanged(null),
                )
              : const Icon(Icons.calendar_today_outlined),
        ),
        child: Text(_dmy(iso) ?? VtFormText.chooseDate),
      ),
    );
  }
}
