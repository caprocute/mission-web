package vn.viettel.okrkpi.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.kernel.Clock;

class CursorCodecTest {

  private static final byte[] KEY =
      "khoa-ky-con-tro-thu-nghiem-32-byte!!".getBytes(StandardCharsets.UTF_8);
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-20T01:00:00Z"));
  private final Clock clock = () -> now.get();
  private final CursorCodec codec = new CursorCodec(KEY, Duration.ofHours(24), clock);

  @Test
  void maHoaRoiDocLaiKeyset() {
    UUID id = UUID.randomUUID();
    Instant at = Instant.parse("2026-10-19T08:30:00.123456Z");
    String cursor =
        codec.encode("ds", CursorKey.of(at, id, 7L, "Quý IV", LocalDate.of(2026, 12, 31)));
    assertThat(cursor).matches("[A-Za-z0-9_-]+").hasSizeLessThanOrEqualTo(CursorCodec.MAX_LENGTH);
    CursorKey k = codec.decode("ds", cursor);
    assertThat(k.size()).isEqualTo(5);
    assertThat(k.getInstant(0)).isEqualTo(at);
    assertThat(k.getUuid(1)).isEqualTo(id);
    assertThat(k.getLong(2)).isEqualTo(7);
    assertThat(k.getString(3)).isEqualTo("Quý IV");
    assertThat(k.getDate(4)).isEqualTo(LocalDate.of(2026, 12, 31));
    assertThat(k).isEqualTo(CursorKey.of(at, id, 7L, "Quý IV", LocalDate.of(2026, 12, 31)));
    assertThat(k.hashCode())
        .isEqualTo(CursorKey.of(at, id, 7L, "Quý IV", LocalDate.of(2026, 12, 31)).hashCode());
    assertThat(k.toString()).startsWith("CursorKey[");
  }

  @Test
  void conTroBiSuaSaiPhamViHetHanDeuBiTuChoi() {
    String cursor = codec.encode("ds-a", CursorKey.of("x", 1));
    byte[] raw = Base64.getUrlDecoder().decode(cursor);
    raw[3] ^= 1;
    String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    assertInvalid("ds-a", tampered);
    assertInvalid("ds-b", cursor);
    assertInvalid("ds-a", "!!!");
    assertInvalid("ds-a", "");
    assertInvalid("ds-a", "AAAA");
    assertInvalid("ds-a", "A".repeat(513));
    // Con trỏ do client tự dựng (không có MAC đúng).
    String forged =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"v\":1,\"t\":1792459200,\"k\":[\"x\"]}" + "0123456789abcdef")
                    .getBytes(StandardCharsets.UTF_8));
    assertInvalid("ds-a", forged);

    now.set(now.get().plus(Duration.ofHours(25)));
    assertInvalid("ds-a", cursor);
    // Phát hành ở "tương lai" quá độ lệch đồng hồ cho phép.
    CursorCodec future =
        new CursorCodec(KEY, Duration.ofHours(24), () -> now.get().plus(Duration.ofMinutes(5)));
    assertInvalid("ds-a", future.encode("ds-a", CursorKey.of("x")));
  }

  @Test
  void kiemLimitMacDinh20ToiDa200() {
    assertThat(codec.pageRequest(null, null, "ds").limit()).isEqualTo(20);
    assertThat(codec.pageRequest(200, "", "ds").firstPage()).isTrue();
    assertThat(codec.pageRequest(1, null, "ds").fetchSize()).isEqualTo(2);
    for (int bad : new int[] {0, -1, 201}) {
      assertThatThrownBy(() -> codec.pageRequest(bad, null, "ds"))
          .isInstanceOfSatisfying(
              InvalidPageRequestException.class,
              e -> {
                assertThat(e.parameter()).isEqualTo("limit");
                assertThat(e.userMessage()).contains("1 đến 200");
              });
    }
    assertThatThrownBy(() -> new PageRequest(0, null, "ds"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dungTrangTuLimitCongMot() {
    List<Integer> rows = new ArrayList<>(IntStream.rangeClosed(1, 450).boxed().toList());
    List<Integer> all = new ArrayList<>();
    PageRequest req = codec.pageRequest(200, null, "so");
    int pages = 0;
    while (true) {
      int from = req.firstPage() ? 0 : (int) req.after().getLong(0);
      List<Integer> fetched = rows.subList(from, Math.min(rows.size(), from + req.fetchSize()));
      CursorPage<Integer> page = codec.page(fetched, req, r -> CursorKey.of(r));
      pages++;
      all.addAll(page.items());
      if (!page.hasNext()) {
        break;
      }
      req = codec.pageRequest(200, page.nextCursor(), "so");
      assertThat(req.afterKey()).isPresent();
    }
    assertThat(pages).isEqualTo(3);
    assertThat(all).isEqualTo(rows);
    assertThat(CursorPage.last(List.of(1)).nextCursor()).isNull();
  }

  @Test
  void dauVaoSai() {
    assertThatThrownBy(() -> new CursorCodec(new byte[31], Duration.ofHours(1), clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CursorCodec(KEY, Duration.ZERO, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(CursorKey::of).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CursorKey.of(new Object()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CursorKey.of((Object) null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(CursorKey.of(Thread.State.NEW, true).getString(0)).isEqualTo("NEW");
    assertThatThrownBy(() -> codec.encode("ds", CursorKey.of("x".repeat(600))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void assertInvalid(String scope, String cursor) {
    assertThatThrownBy(() -> codec.decode(scope, cursor))
        .isInstanceOfSatisfying(
            InvalidPageRequestException.class, e -> assertThat(e.parameter()).isEqualTo("cursor"));
  }
}
