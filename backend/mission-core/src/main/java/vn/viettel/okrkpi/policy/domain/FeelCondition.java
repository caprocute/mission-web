package vn.viettel.okrkpi.policy.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Điều kiện của một ô đầu vào bảng DMN sau khi phân tích FEEL đơn giản (simple unary tests): tập
 * khoảng số, hoặc tập giá trị chuỗi, logic có thể phủ định. Hỗ trợ khớp giá trị và so bao hàm để
 * phát hiện dòng bị che (MSG-15-04) và khoảng hở (CORE-05-05).
 */
public sealed interface FeelCondition {

  /** Ô trống hoặc {@code -}: khớp mọi giá trị. */
  record Any() implements FeelCondition {
    @Override
    public boolean matches(Object value) {
      return true;
    }

    @Override
    public boolean covers(FeelCondition other) {
      return true;
    }

    @Override
    public boolean universal() {
      return true;
    }
  }

  /** Một khoảng số; đầu mút {@code null} là vô cực. */
  record Interval(BigDecimal lo, boolean loIncl, BigDecimal hi, boolean hiIncl) {

    static final Interval ALL = new Interval(null, false, null, false);

    boolean contains(BigDecimal v) {
      if (lo != null) {
        int c = v.compareTo(lo);
        if (c < 0 || (c == 0 && !loIncl)) {
          return false;
        }
      }
      if (hi != null) {
        int c = v.compareTo(hi);
        return c < 0 || (c == 0 && hiIncl);
      }
      return true;
    }

    boolean empty() {
      if (lo == null || hi == null) {
        return false;
      }
      int c = lo.compareTo(hi);
      return c > 0 || (c == 0 && !(loIncl && hiIncl));
    }

    /** Khoảng này nằm trọn trong {@code o}. */
    boolean within(Interval o) {
      boolean loOk =
          o.lo == null
              || (lo != null
                  && (lo.compareTo(o.lo) > 0
                      || (lo.compareTo(o.lo) == 0 && (o.loIncl || !loIncl))));
      boolean hiOk =
          o.hi == null
              || (hi != null
                  && (hi.compareTo(o.hi) < 0
                      || (hi.compareTo(o.hi) == 0 && (o.hiIncl || !hiIncl))));
      return loOk && hiOk;
    }
  }

  /** Hợp các khoảng số, đã gộp và sắp xếp. */
  record NumberSet(List<Interval> intervals) implements FeelCondition {

    public NumberSet {
      intervals = merge(intervals);
    }

    @Override
    public boolean matches(Object value) {
      BigDecimal v = number(value);
      return v != null && intervals.stream().anyMatch(i -> i.contains(v));
    }

    @Override
    public boolean covers(FeelCondition other) {
      if (other instanceof Any) {
        return universal();
      }
      if (!(other instanceof NumberSet n)) {
        return false;
      }
      return n.intervals.stream().allMatch(b -> intervals.stream().anyMatch(b::within));
    }

    @Override
    public boolean universal() {
      return intervals.size() == 1
          && intervals.get(0).lo() == null
          && intervals.get(0).hi() == null;
    }

    /** Phần bù trên trục số thực. */
    NumberSet complement() {
      List<Interval> out = new ArrayList<>();
      BigDecimal prevHi = null;
      boolean prevHiIncl = false;
      boolean started = false;
      for (Interval i : intervals) {
        if (i.lo() != null) {
          out.add(
              new Interval(started ? prevHi : null, started && !prevHiIncl, i.lo(), !i.loIncl()));
        }
        if (i.hi() == null) {
          return new NumberSet(out);
        }
        prevHi = i.hi();
        prevHiIncl = i.hiIncl();
        started = true;
      }
      out.add(new Interval(started ? prevHi : null, started && !prevHiIncl, null, false));
      return new NumberSet(out);
    }

    /** Các đầu mút để lấy điểm mẫu khi tìm khoảng hở. */
    List<BigDecimal> bounds() {
      List<BigDecimal> b = new ArrayList<>();
      for (Interval i : intervals) {
        if (i.lo() != null) {
          b.add(i.lo());
        }
        if (i.hi() != null) {
          b.add(i.hi());
        }
      }
      return b;
    }

    private static List<Interval> merge(List<Interval> raw) {
      List<Interval> sorted =
          raw.stream()
              .filter(i -> !i.empty())
              .sorted(
                  Comparator.comparing(
                      Interval::lo, Comparator.nullsFirst(Comparator.naturalOrder())))
              .toList();
      List<Interval> out = new ArrayList<>();
      for (Interval i : sorted) {
        if (out.isEmpty()) {
          out.add(i);
          continue;
        }
        Interval last = out.get(out.size() - 1);
        boolean touches =
            last.hi() == null
                || i.lo() == null
                || i.lo().compareTo(last.hi()) < 0
                || (i.lo().compareTo(last.hi()) == 0 && (i.loIncl() || last.hiIncl()));
        if (!touches) {
          out.add(i);
          continue;
        }
        boolean keepLastHi =
            last.hi() == null
                || (i.hi() != null
                    && (last.hi().compareTo(i.hi()) > 0
                        || (last.hi().compareTo(i.hi()) == 0 && last.hiIncl())));
        out.set(
            out.size() - 1,
            new Interval(
                last.lo(),
                last.loIncl(),
                keepLastHi ? last.hi() : i.hi(),
                keepLastHi ? last.hiIncl() : i.hiIncl()));
      }
      return List.copyOf(out);
    }
  }

  /**
   * Tập giá trị chuỗi hoặc đúng/sai; {@code negated} nghĩa là mọi giá trị ngoài tập. Với kiểu
   * đúng/sai tập luôn được viết tường minh trên miền {@code {true, false}}.
   */
  record ValueSet(Set<Object> values, boolean negated) implements FeelCondition {

    public ValueSet {
      values = Set.copyOf(values);
    }

    @Override
    public boolean matches(Object value) {
      return values.contains(value) != negated;
    }

    @Override
    public boolean covers(FeelCondition other) {
      if (other instanceof Any) {
        return universal();
      }
      if (!(other instanceof ValueSet o)) {
        return false;
      }
      if (!negated && !o.negated) {
        return values.containsAll(o.values);
      }
      if (negated && !o.negated) {
        return o.values.stream().noneMatch(values::contains);
      }
      if (negated) {
        return o.values.containsAll(values);
      }
      return false;
    }

    @Override
    public boolean universal() {
      return negated && values.isEmpty();
    }

    static ValueSet booleans(Collection<Boolean> allowed) {
      Set<Object> v = new LinkedHashSet<>(allowed);
      return v.size() == 2 ? new ValueSet(Set.of(), true) : new ValueSet(v, false);
    }
  }

  boolean matches(Object value);

  /** Mọi giá trị khớp {@code other} đều khớp điều kiện này. */
  boolean covers(FeelCondition other);

  /** Khớp mọi giá trị của miền. */
  boolean universal();

  static BigDecimal number(Object value) {
    if (value instanceof BigDecimal b) {
      return b;
    }
    if (value instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return null;
  }

  static FeelCondition any() {
    return new Any();
  }

  static NumberSet numbers(List<Interval> intervals) {
    return new NumberSet(Objects.requireNonNull(intervals, "intervals"));
  }
}
