package vn.viettel.okrkpi.platform.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** Băm tổng hợp không phụ thuộc thứ tự dòng (tiêu chí DL-12) và tương thích org-service. */
class HashAggregateProperties {

  record Row(UUID id, String a, long v) {}

  @Provide
  Arbitrary<List<Row>> rows() {
    Arbitrary<Row> row =
        Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.strings().ofMaxLength(12),
                Arbitraries.longs())
            .as(Row::new);
    return row.list().ofMaxSize(300);
  }

  @Property(tries = 200)
  void daoThuTuDongChoCungBam(@ForAll("rows") List<Row> rows, @ForAll long seed) {
    for (HashAggregate.Mode mode : HashAggregate.Mode.values()) {
      List<Row> shuffled = new ArrayList<>(rows);
      Collections.shuffle(shuffled, new Random(seed));
      HashAggregate a = aggregate(mode, rows);
      HashAggregate b = aggregate(mode, shuffled);
      List<Integer> all = IntStream.range(0, Buckets.COUNT).boxed().toList();
      assertThat(b.hashes(all)).isEqualTo(a.hashes(all));
      assertThat(b.total()).isEqualTo(rows.size());
    }
  }

  @Property(tries = 100)
  void suaMotDongThiBucketCuaDongDoLech(@ForAll("rows") List<Row> rows) {
    if (rows.isEmpty()) {
      return;
    }
    for (HashAggregate.Mode mode : HashAggregate.Mode.values()) {
      HashAggregate a = aggregate(mode, rows);
      List<Row> changed = new ArrayList<>(rows);
      Row first = changed.getFirst();
      changed.set(0, new Row(first.id(), first.a(), first.v() + 1));
      HashAggregate b = aggregate(mode, changed);
      List<Integer> all = IntStream.range(0, Buckets.COUNT).boxed().toList();
      assertThat(Buckets.mismatched(a.hashes(all), b.hashes(all)))
          .containsExactly(Buckets.of(first.id()));
    }
  }

  @Test
  void sum64TrungCachTinhCuaOrgService() throws Exception {
    UUID id = UUID.fromString("0190a3c2-7a1e-7c00-8000-000000000001");
    String canonical = RowHash.canonical("ORG_UNIT", id, java.time.LocalDate.of(2026, 1, 1), 5L);
    assertThat(canonical).isEqualTo("ORG_UNIT|" + id + "|2026-01-01|5");
    byte[] h =
        MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
    long v = 0;
    for (int i = 0; i < 8; i++) {
      v = (v << 8) | (h[i] & 0xffL);
    }
    HashAggregate agg = new HashAggregate(HashAggregate.Mode.SUM64);
    int bucket = agg.add(id, canonical);
    byte[] key =
        MessageDigest.getInstance("SHA-256").digest(id.toString().getBytes(StandardCharsets.UTF_8));
    assertThat(bucket).isEqualTo(key[0] & 0xff);
    assertThat(agg.hex(bucket)).isEqualTo(HexFormat.of().toHexDigits(v));
    assertThat(RowHash.canonical("PERSON", id, null, 3L)).isEqualTo("PERSON|" + id + "|-|3");
  }

  @Test
  void thoatKyTuPhanCachKhongGayVaCham() {
    assertThat(RowHash.canonical("a|b", "c")).isNotEqualTo(RowHash.canonical("a", "b|c"));
    assertThat(RowHash.canonical("-")).isNotEqualTo(RowHash.canonical((Object) null));
    assertThat(RowHash.canonical("a\\", "b")).isNotEqualTo(RowHash.canonical("a", "\\b"));
  }

  @Test
  void bucketThieuOPhiaDichLaLech() {
    assertThat(
            Buckets.mismatched(
                java.util.Map.of(1, "AB", 2, "cd", 3, "00"), java.util.Map.of(1, "ab", 2, "ce")))
        .containsExactly(2, 3);
  }

  private static HashAggregate aggregate(HashAggregate.Mode mode, List<Row> rows) {
    HashAggregate agg = new HashAggregate(mode);
    rows.forEach(r -> agg.add(r.id(), RowHash.canonical("X", r.id(), r.a(), r.v())));
    return agg;
  }
}
