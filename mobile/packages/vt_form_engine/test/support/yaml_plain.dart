import 'package:yaml/yaml.dart';

/// Đổi cây YAML sang Map/List thuần như kết quả `jsonDecode`.
Object? yamlToPlain(Object? v) => switch (v) {
  YamlMap() => <String, Object?>{for (final e in v.entries) '${e.key}': yamlToPlain(e.value)},
  YamlList() => [for (final x in v) yamlToPlain(x)],
  _ => v,
};
