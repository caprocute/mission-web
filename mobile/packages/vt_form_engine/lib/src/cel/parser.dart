/// Bộ phân tích cú pháp CEL viết tay theo văn phạm của cel-spec, không sinh mã lúc chạy.
library;

import 'ast.dart';

/// Lỗi cú pháp; tầng trên đổi thành `POLICY-422-CEL-TYPE` như cel-java.
class CelSyntaxError implements Exception {
  CelSyntaxError(this.message, this.offset);
  final String message;
  final int offset;
  @override
  String toString() => 'Syntax error at $offset: $message';
}

enum _T { int, double, string, ident, op, eof }

class _Tok {
  const _Tok(this.kind, this.text, this.offset, [this.value]);
  final _T kind;
  final String text;
  final int offset;
  final Object? value;
}

const _reserved = {
  'as', 'break', 'const', 'continue', 'else', 'for', 'function', 'if', 'import', //
  'let', 'loop', 'package', 'namespace', 'return', 'var', 'void', 'while',
};

const _ops = [
  '||', '&&', '==', '!=', '<=', '>=', //
  '<', '>', '+', '-', '*', '/', '%', '!', '?', ':', '.', ',', '(', ')', '[', ']', '{', '}',
];

List<_Tok> _lex(String src) {
  final out = <_Tok>[];
  var i = 0;
  bool isDigit(int c) => c >= 0x30 && c <= 0x39;
  bool isIdStart(int c) => (c >= 0x41 && c <= 0x5a) || (c >= 0x61 && c <= 0x7a) || c == 0x5f;
  while (i < src.length) {
    final c = src.codeUnitAt(i);
    if (c == 0x20 || c == 0x09 || c == 0x0a || c == 0x0d) {
      i++;
      continue;
    }
    if (src.startsWith('//', i)) {
      while (i < src.length && src[i] != '\n') {
        i++;
      }
      continue;
    }
    final start = i;
    if (isDigit(c) || (c == 0x2e && i + 1 < src.length && isDigit(src.codeUnitAt(i + 1)))) {
      if (src.startsWith('0x', i) || src.startsWith('0X', i)) {
        i += 2;
        while (i < src.length && RegExp('[0-9a-fA-F]').hasMatch(src[i])) {
          i++;
        }
        if (i < src.length && (src[i] == 'u' || src[i] == 'U')) {
          throw CelSyntaxError('unsigned integers are not supported', start);
        }
        out.add(_Tok(_T.int, src.substring(start, i), start, int.parse(src.substring(start + 2, i), radix: 16)));
        continue;
      }
      var isDouble = false;
      while (i < src.length && isDigit(src.codeUnitAt(i))) {
        i++;
      }
      if (i < src.length && src[i] == '.' && i + 1 < src.length && isDigit(src.codeUnitAt(i + 1))) {
        isDouble = true;
        i++;
        while (i < src.length && isDigit(src.codeUnitAt(i))) {
          i++;
        }
      }
      if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
        var j = i + 1;
        if (j < src.length && (src[j] == '+' || src[j] == '-')) j++;
        if (j < src.length && isDigit(src.codeUnitAt(j))) {
          isDouble = true;
          i = j;
          while (i < src.length && isDigit(src.codeUnitAt(i))) {
            i++;
          }
        }
      }
      if (i < src.length && (src[i] == 'u' || src[i] == 'U')) {
        throw CelSyntaxError('unsigned integers are not supported', start);
      }
      final text = src.substring(start, i);
      if (isDouble) {
        out.add(_Tok(_T.double, text, start, double.parse(text)));
      } else {
        final v = int.tryParse(text);
        // 9223372036854775808 chỉ hợp lệ khi đứng sau dấu trừ; giữ dạng chữ để ghép ở bước sau
        out.add(_Tok(_T.int, text, start, v));
      }
      continue;
    }
    var raw = false;
    var q = i;
    if ((c == 0x72 || c == 0x52) && i + 1 < src.length && (src[i + 1] == '"' || src[i + 1] == "'")) {
      raw = true;
      q = i + 1;
    }
    if (src[q] == '"' || src[q] == "'") {
      final quote = src.startsWith('"""', q) || src.startsWith("'''", q) ? src.substring(q, q + 3) : src[q];
      i = q + quote.length;
      final buf = StringBuffer();
      var closed = false;
      while (i < src.length) {
        if (src.startsWith(quote, i)) {
          i += quote.length;
          closed = true;
          break;
        }
        final ch = src[i];
        if (quote.length == 1 && (ch == '\n' || ch == '\r')) break;
        if (ch == r'\' && !raw) {
          i++;
          if (i >= src.length) break;
          final e = src[i];
          switch (e) {
            case 'n':
              buf.write('\n');
            case 't':
              buf.write('\t');
            case 'r':
              buf.write('\r');
            case 'b':
              buf.write('\b');
            case 'f':
              buf.write('\f');
            case 'v':
              buf.write('\v');
            case 'a':
              buf.writeCharCode(7);
            case r'\' || '"' || "'" || '`' || '?':
              buf.write(e);
            case 'u' || 'U' || 'x' || 'X':
              final n = e == 'u' ? 4 : (e == 'U' ? 8 : 2);
              final hex = i + 1 + n <= src.length ? src.substring(i + 1, i + 1 + n) : '';
              final cp = int.tryParse(hex, radix: 16);
              if (hex.length != n || cp == null) {
                throw CelSyntaxError('invalid escape sequence', i);
              }
              buf.writeCharCode(cp);
              i += n;
            default:
              throw CelSyntaxError('invalid escape sequence \\$e', i);
          }
          i++;
          continue;
        }
        buf.write(ch);
        i++;
      }
      if (!closed) throw CelSyntaxError('unterminated string literal', start);
      out.add(_Tok(_T.string, src.substring(start, i), start, buf.toString()));
      continue;
    }
    if (isIdStart(c)) {
      while (i < src.length && (isIdStart(src.codeUnitAt(i)) || isDigit(src.codeUnitAt(i)))) {
        i++;
      }
      out.add(_Tok(_T.ident, src.substring(start, i), start));
      continue;
    }
    final op = _ops.firstWhere((o) => src.startsWith(o, i), orElse: () => '');
    if (op.isEmpty) throw CelSyntaxError("unexpected character '${src[i]}'", i);
    out.add(_Tok(_T.op, op, start));
    i += op.length;
  }
  out.add(_Tok(_T.eof, '', src.length));
  return out;
}

/// Phân tích biểu thức CEL thành cây; mở rộng macro `has`, `all`, `exists`, `exists_one`,
/// `map`, `filter` như cel-spec.
CelExpr parseCel(String source) => _Parser(_lex(source)).parseAll();

class _Parser {
  _Parser(this.toks);
  final List<_Tok> toks;
  var pos = 0;

  _Tok get cur => toks[pos];
  bool isOp(String s) => cur.kind == _T.op && cur.text == s;
  bool isWord(String s) => cur.kind == _T.ident && cur.text == s;
  _Tok next() => toks[pos++];
  void expect(String s) {
    if (!isOp(s)) {
      throw CelSyntaxError("expected '$s' but found '${cur.text.isEmpty ? '<eof>' : cur.text}'", cur.offset);
    }
    pos++;
  }

  CelExpr parseAll() {
    final e = expr();
    if (cur.kind != _T.eof) throw CelSyntaxError("unexpected '${cur.text}'", cur.offset);
    return e;
  }

  CelExpr expr() {
    final c = orExpr();
    if (isOp('?')) {
      final at = next().offset;
      final a = orExpr();
      expect(':');
      final b = expr();
      return CelCall('_?_:_', [c, a, b], at);
    }
    return c;
  }

  CelExpr orExpr() {
    var l = andExpr();
    while (isOp('||')) {
      final at = next().offset;
      l = CelCall('_||_', [l, andExpr()], at);
    }
    return l;
  }

  CelExpr andExpr() {
    var l = relation();
    while (isOp('&&')) {
      final at = next().offset;
      l = CelCall('_&&_', [l, relation()], at);
    }
    return l;
  }

  static const _rel = {'<': '_<_', '<=': '_<=_', '>': '_>_', '>=': '_>=_', '==': '_==_', '!=': '_!=_'};

  CelExpr relation() {
    var l = addition();
    while (true) {
      if (cur.kind == _T.op && _rel.containsKey(cur.text)) {
        final t = next();
        l = CelCall(_rel[t.text]!, [l, addition()], t.offset);
      } else if (isWord('in')) {
        final at = next().offset;
        l = CelCall('@in', [l, addition()], at);
      } else {
        return l;
      }
    }
  }

  CelExpr addition() {
    var l = multiplication();
    while (isOp('+') || isOp('-')) {
      final t = next();
      l = CelCall(t.text == '+' ? '_+_' : '_-_', [l, multiplication()], t.offset);
    }
    return l;
  }

  CelExpr multiplication() {
    var l = unary();
    while (isOp('*') || isOp('/') || isOp('%')) {
      final t = next();
      l = CelCall('_${t.text}_', [l, unary()], t.offset);
    }
    return l;
  }

  CelExpr unary() {
    if (isOp('!')) {
      final at = next().offset;
      return CelCall('!_', [unary()], at);
    }
    if (isOp('-')) {
      final at = next().offset;
      // -<số> gộp thành hằng âm để biểu diễn được số nguyên nhỏ nhất của int64
      if (cur.kind == _T.int || cur.kind == _T.double) {
        final lit = cur;
        final after = toks[pos + 1];
        final postfix = after.kind == _T.op && (after.text == '.' || after.text == '[');
        if (!postfix) {
          pos++;
          if (lit.kind == _T.double) return CelLiteral(-(lit.value as double), at);
          final v = int.tryParse('-${lit.text}');
          if (v == null) throw CelSyntaxError('integer literal out of range', lit.offset);
          return CelLiteral(v, at);
        }
      }
      return CelCall('-_', [unary()], at);
    }
    return member();
  }

  CelExpr member() {
    var e = primary();
    while (true) {
      if (isOp('.')) {
        pos++;
        final id = next();
        if (id.kind != _T.ident) throw CelSyntaxError('expected field name', id.offset);
        if (isOp('(')) {
          pos++;
          final args = exprList(')');
          e = _receiverCall(e, id, args);
        } else {
          e = CelSelect(e, id.text, id.offset);
        }
      } else if (isOp('[')) {
        final at = next().offset;
        final idx = expr();
        expect(']');
        e = CelCall('_[_]', [e, idx], at);
      } else {
        return e;
      }
    }
  }

  CelExpr _receiverCall(CelExpr target, _Tok id, List<CelExpr> args) {
    final macro = switch ((id.text, args.length)) {
      ('all', 2) => CelMacro.all,
      ('exists', 2) => CelMacro.exists,
      ('exists_one', 2) => CelMacro.existsOne,
      ('map', 2 || 3) => CelMacro.map,
      ('filter', 2) => CelMacro.filter,
      _ => null,
    };
    if (macro == null) return CelCall(id.text, args, id.offset, target: target);
    final v = args.first;
    if (v is! CelIdent) {
      throw CelSyntaxError('argument is not an identifier', v.offset);
    }
    if (macro == CelMacro.map && args.length == 3) {
      return CelComprehension(macro, target, v.name, args[2], id.offset, filter: args[1]);
    }
    return CelComprehension(macro, target, v.name, args[1], id.offset);
  }

  List<CelExpr> exprList(String close) {
    final out = <CelExpr>[];
    if (isOp(close)) {
      pos++;
      return out;
    }
    while (true) {
      out.add(expr());
      if (isOp(',')) {
        pos++;
        if (isOp(close)) break; // dấu phẩy cuối được phép như cel-spec
        continue;
      }
      break;
    }
    expect(close);
    return out;
  }

  CelExpr primary() {
    final t = next();
    switch (t.kind) {
      case _T.int:
        if (t.value == null) throw CelSyntaxError('integer literal out of range', t.offset);
        return CelLiteral(t.value, t.offset);
      case _T.double || _T.string:
        return CelLiteral(t.value, t.offset);
      case _T.ident:
        switch (t.text) {
          case 'true':
            return CelLiteral(true, t.offset);
          case 'false':
            return CelLiteral(false, t.offset);
          case 'null':
            return CelLiteral(null, t.offset);
          case 'in':
            throw CelSyntaxError("unexpected 'in'", t.offset);
        }
        if (_reserved.contains(t.text)) {
          throw CelSyntaxError("reserved identifier '${t.text}'", t.offset);
        }
        if (isOp('(')) {
          pos++;
          final args = exprList(')');
          if (t.text == 'has') {
            if (args.length != 1 || args.first is! CelSelect) {
              throw CelSyntaxError('invalid argument to has() macro', t.offset);
            }
            final s = args.first as CelSelect;
            return CelSelect(s.operand, s.field, s.offset, testOnly: true);
          }
          return CelCall(t.text, args, t.offset);
        }
        return CelIdent(t.text, t.offset);
      case _T.op:
        switch (t.text) {
          case '(':
            final e = expr();
            expect(')');
            return e;
          case '[':
            return CelList(exprList(']'), t.offset);
          case '{':
            final entries = <CelMapEntry>[];
            if (!isOp('}')) {
              while (true) {
                final k = expr();
                expect(':');
                entries.add(CelMapEntry(k, expr()));
                if (isOp(',')) {
                  pos++;
                  if (isOp('}')) break;
                  continue;
                }
                break;
              }
            }
            expect('}');
            return CelMap(entries, t.offset);
          case '.':
            // tên đầy đủ bắt đầu bằng dấu chấm (.a.b): không dùng trong Mission
            throw CelSyntaxError('leading dot identifiers are not supported', t.offset);
        }
        throw CelSyntaxError("unexpected '${t.text}'", t.offset);
      case _T.eof:
        throw CelSyntaxError('unexpected end of expression', t.offset);
    }
  }
}
