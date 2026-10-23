/// Hàm mở rộng của Mission (`sum`, `min_muc`, `ngay_lam_viec`, `workdays`) và làm tròn điểm,
/// chuyển nguyên từ `MissionCelFunctions`, `CelRounding` của vt-cel và `mission-cel.ts` của web.
library;

/// Chế độ làm tròn điểm ở lớp sau biểu thức CEL (TL-02 BR-022).
enum CelRoundingMode {
  halfUp('HALF_UP'),
  nuaLen('NUA_LEN'),
  down('DOWN'),
  catBo('CAT_BO');

  const CelRoundingMode(this.code);
  final String code;

  bool get isHalfUp => this == halfUp || this == nuaLen;

  static CelRoundingMode parse(String raw) {
    final n = raw.trim().toUpperCase();
    return values.firstWhere(
      (m) => m.code == n,
      orElse: () => throw ArgumentError.value(raw, 'raw', 'Unsupported rounding mode'),
    );
  }
}

/// Lịch ngày làm việc cho `ngay_lam_viec` và `workdays`.
class WorkingCalendar {
  const WorkingCalendar({this.holidays = const {}, this.compensatoryWorkdays = const {}});

  /// Ngày nghỉ lễ dạng `YYYY-MM-DD`.
  final Set<String> holidays;

  /// Thứ Bảy, Chủ nhật đi làm bù dạng `YYYY-MM-DD`.
  final Set<String> compensatoryWorkdays;

  /// Lịch kiểm thử năm 2026, trùng `StaticWorkingCalendar.defaultTestCalendar()` của vt-cel.
  static const defaultTest = WorkingCalendar(
    holidays: {
      '2026-01-01', '2026-02-16', '2026-02-17', '2026-02-18', '2026-02-19', '2026-02-20', //
      '2026-04-27', '2026-04-30', '2026-05-01', '2026-09-02', '2026-09-03',
    },
    compensatoryWorkdays: {'2026-02-14', '2026-04-25', '2026-08-29'},
  );
}

/// Số thập phân chính xác `unscaled × 10^-scale`, tương đương `BigDecimal.valueOf(double)`.
class _Dec {
  const _Dec(this.unscaled, this.scale);
  final BigInt unscaled;
  final int scale;

  factory _Dec.of(num value) {
    if (value is int) return _Dec(BigInt.from(value), 0);
    final d = value as double;
    if (!d.isFinite) throw ArgumentError('Non-finite numeric value is not allowed: $d');
    if (d == 0) return _Dec(BigInt.zero, 0);
    // toString của Dart cho chuỗi ngắn nhất khứ hồi được, như Double.toString/Number#toString
    final str = d.toString();
    final parts = str.split(RegExp('[eE]'));
    final mantissa = parts[0];
    final negative = mantissa.startsWith('-');
    final unsigned = mantissa.replaceFirst(RegExp('^[+-]'), '');
    final ip = unsigned.split('.');
    final frac = ip.length > 1 ? ip[1] : '';
    var unscaled = BigInt.parse('${ip[0]}$frac') * (negative ? BigInt.from(-1) : BigInt.one);
    var scale = frac.length;
    if (parts.length > 1) {
      final exp = int.parse(parts[1]);
      if (exp >= 0) {
        if (exp >= scale) {
          unscaled *= BigInt.from(10).pow(exp - scale);
          scale = 0;
        } else {
          scale -= exp;
        }
      } else {
        scale += -exp;
      }
    }
    return _Dec(unscaled, scale);
  }

  double toDouble() {
    if (unscaled == BigInt.zero) return 0;
    if (scale <= 0) return (unscaled * BigInt.from(10).pow(-scale)).toDouble();
    final negative = unscaled.isNegative;
    final abs = unscaled.abs().toString().padLeft(scale + 1, '0');
    final ip = abs.substring(0, abs.length - scale);
    final fp = abs.substring(abs.length - scale);
    return double.parse('${negative ? '-' : ''}$ip.$fp');
  }
}

/// Tổng danh sách số bằng số học thập phân chính xác, tránh sai số IEEE-754 (0.1 + 0.2 + 0.3 = 0.6).
double sumCelValues(List<Object?> values) {
  if (values.isEmpty) return 0;
  final parsed = <_Dec>[];
  for (var i = 0; i < values.length; i++) {
    final v = values[i];
    if (v is! num || !v.isFinite) {
      throw ArgumentError('sum(list) element at index $i is not a finite number');
    }
    parsed.add(_Dec.of(v));
  }
  final maxScale = parsed.fold(0, (m, d) => d.scale > m ? d.scale : m);
  var total = BigInt.zero;
  for (final d in parsed) {
    total += d.unscaled * BigInt.from(10).pow(maxScale - d.scale);
  }
  return _Dec(total, maxScale).toDouble();
}

const _ratingRanks = {
  'KHTNV': 1, 'KHONG_HOAN_THANH': 1, //
  'HTNV': 2, 'HOAN_THANH': 2,
  'HTTNV': 3, 'HOAN_THANH_TOT': 3,
  'HTXSNV': 4, 'HOAN_THANH_XUAT_SAC': 4,
};

/// Mức xếp loại thấp hơn theo thứ tự KHTNV < HTNV < HTTNV < HTXSNV; trả đúng mã người gọi
/// truyền vào (mã viết tắt hay mã đầy đủ), đã bỏ khoảng trắng và viết hoa.
String minMucXepLoai(String a, String b) {
  final na = a.trim().toUpperCase();
  final nb = b.trim().toUpperCase();
  final ra = _ratingRanks[na];
  final rb = _ratingRanks[nb];
  if (ra == null || rb == null) {
    throw ArgumentError("Unsupported rating level in min_muc('$a', '$b')");
  }
  return ra <= rb ? na : nb;
}

DateTime _parseIsoDate(String iso) {
  final m = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$').firstMatch(iso);
  if (m == null) {
    throw ArgumentError("Invalid ISO-8601 date format (expected YYYY-MM-DD): '$iso'");
  }
  final y = int.parse(m[1]!), mo = int.parse(m[2]!), d = int.parse(m[3]!);
  final dt = DateTime.utc(y, mo, d);
  if (dt.year != y || dt.month != mo || dt.day != d) {
    throw ArgumentError("Invalid calendar date: '$iso'");
  }
  return dt;
}

String _isoDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-'
    '${d.day.toString().padLeft(2, '0')}';

/// Số ngày làm việc trong nửa khoảng `(from, to]`: ngày làm bù được tính, ngày lễ, thứ Bảy và
/// Chủ nhật không tính; `to <= from` cho 0.
int countWorkingDays(String fromIso, String toIso, [WorkingCalendar calendar = const WorkingCalendar()]) {
  final from = _parseIsoDate(fromIso);
  final to = _parseIsoDate(toIso);
  if (!to.isAfter(from)) return 0;
  var count = 0;
  var cursor = from;
  while (cursor.isBefore(to)) {
    cursor = cursor.add(const Duration(days: 1));
    final iso = _isoDate(cursor);
    if (calendar.compensatoryWorkdays.contains(iso)) {
      count++;
    } else if (!calendar.holidays.contains(iso) &&
        cursor.weekday != DateTime.saturday &&
        cursor.weekday != DateTime.sunday) {
      count++;
    }
  }
  return count;
}

/// Làm tròn điểm bằng số thập phân chính xác, như `CelRounding.round(double, scale, mode)`.
double roundCelScore(num value, [int scale = 2, CelRoundingMode mode = CelRoundingMode.halfUp]) {
  if (scale < 0) throw ArgumentError('scale must be >= 0, got: $scale');
  final d = _Dec.of(value);
  if (d.scale <= scale) return value.toDouble();
  final divisor = BigInt.from(10).pow(d.scale - scale);
  final negative = d.unscaled.isNegative;
  final abs = d.unscaled.abs();
  var q = abs ~/ divisor;
  final r = abs.remainder(divisor);
  if (mode.isHalfUp && r * BigInt.two >= divisor) q += BigInt.one;
  return _Dec(negative ? -q : q, scale).toDouble();
}

/// So sánh chính xác hai số theo giá trị thập phân (như `BigDecimal.compareTo`).
int compareDecimal(num a, num b) {
  final x = _Dec.of(a), y = _Dec.of(b);
  final s = x.scale > y.scale ? x.scale : y.scale;
  final ux = x.unscaled * BigInt.from(10).pow(s - x.scale);
  final uy = y.unscaled * BigInt.from(10).pow(s - y.scale);
  return ux.compareTo(uy);
}

/// `a` là bội số của `m` theo số thập phân chính xác (như `BigDecimal.remainder(m).signum() == 0`).
bool isDecimalMultiple(num a, num m) {
  final x = _Dec.of(a), y = _Dec.of(m);
  final s = x.scale > y.scale ? x.scale : y.scale;
  final ux = x.unscaled * BigInt.from(10).pow(s - x.scale);
  final uy = y.unscaled * BigInt.from(10).pow(s - y.scale);
  return uy != BigInt.zero && ux.remainder(uy) == BigInt.zero;
}

/// Số viết dạng thường, bỏ số 0 thừa (như `stripTrailingZeros().toPlainString()`).
String plainDecimal(num v) {
  final d = _Dec.of(v);
  if (d.scale <= 0) return (d.unscaled * BigInt.from(10).pow(-d.scale)).toString();
  final negative = d.unscaled.isNegative;
  var abs = d.unscaled.abs().toString().padLeft(d.scale + 1, '0');
  final ip = abs.substring(0, abs.length - d.scale);
  final fp = abs.substring(abs.length - d.scale).replaceFirst(RegExp(r'0+$'), '');
  abs = fp.isEmpty ? ip : '$ip.$fp';
  return '${negative ? '-' : ''}$abs';
}
