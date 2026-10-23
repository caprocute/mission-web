/// Cây cú pháp của tập con CEL mà Mission dùng (TL-01 mục 4.3, ADR-10).
library;

sealed class CelExpr {
  const CelExpr(this.offset);

  /// Vị trí ký tự bắt đầu trong biểu thức, dùng cho thông điệp lỗi.
  final int offset;
}

final class CelLiteral extends CelExpr {
  const CelLiteral(this.value, super.offset);

  /// `int`, `double`, `bool`, `String` hoặc `null`.
  final Object? value;
}

final class CelIdent extends CelExpr {
  const CelIdent(this.name, super.offset);
  final String name;
}

/// `operand.field`; `testOnly` là dạng `has(operand.field)`.
final class CelSelect extends CelExpr {
  const CelSelect(this.operand, this.field, super.offset, {this.testOnly = false});
  final CelExpr operand;
  final String field;
  final bool testOnly;
}

/// Lời gọi hàm toàn cục (`target == null`) hoặc hàm thành viên (`target.fn(...)`).
/// Toán tử cũng là lời gọi với tên nội bộ như `_+_`, `_?_:_`, `!_`.
final class CelCall extends CelExpr {
  const CelCall(this.function, this.args, super.offset, {this.target});
  final String function;
  final CelExpr? target;
  final List<CelExpr> args;
}

final class CelList extends CelExpr {
  const CelList(this.elements, super.offset);
  final List<CelExpr> elements;
}

final class CelMapEntry {
  const CelMapEntry(this.key, this.value);
  final CelExpr key;
  final CelExpr value;
}

final class CelMap extends CelExpr {
  const CelMap(this.entries, super.offset);
  final List<CelMapEntry> entries;
}

enum CelMacro { all, exists, existsOne, map, filter }

/// Vòng lặp của macro `all`, `exists`, `exists_one`, `map`, `filter`.
final class CelComprehension extends CelExpr {
  const CelComprehension(this.macro, this.range, this.variable, this.body, super.offset, {this.filter});
  final CelMacro macro;
  final CelExpr range;
  final String variable;

  /// Vị từ (`all`, `exists`, `exists_one`, `filter`) hoặc biểu thức biến đổi (`map`).
  final CelExpr body;

  /// Vị từ lọc của `map(x, p, e)` ba tham số.
  final CelExpr? filter;
}

/// Duyệt mọi nút của cây (gồm nút gốc) theo thứ tự trước.
Iterable<CelExpr> celNodes(CelExpr e) sync* {
  yield e;
  switch (e) {
    case CelLiteral() || CelIdent():
      break;
    case CelSelect(:final operand):
      yield* celNodes(operand);
    case CelCall(:final target, :final args):
      if (target != null) yield* celNodes(target);
      for (final a in args) {
        yield* celNodes(a);
      }
    case CelList(:final elements):
      for (final a in elements) {
        yield* celNodes(a);
      }
    case CelMap(:final entries):
      for (final en in entries) {
        yield* celNodes(en.key);
        yield* celNodes(en.value);
      }
    case CelComprehension(:final range, :final body, :final filter):
      yield* celNodes(range);
      if (filter != null) yield* celNodes(filter);
      yield* celNodes(body);
  }
}

/// Chiều sâu của cây, tính nút lá là 1.
int celDepth(CelExpr e) {
  int maxOf(Iterable<CelExpr> xs) {
    var m = 0;
    for (final x in xs) {
      final d = celDepth(x);
      if (d > m) m = d;
    }
    return m;
  }

  return 1 +
      switch (e) {
        CelLiteral() || CelIdent() => 0,
        CelSelect(:final operand) => celDepth(operand),
        CelCall(:final target, :final args) => maxOf([?target, ...args]),
        CelList(:final elements) => maxOf(elements),
        CelMap(:final entries) => maxOf([
          for (final en in entries) ...[en.key, en.value],
        ]),
        CelComprehension(:final range, :final body, :final filter) => maxOf([range, body, ?filter]),
      };
}
