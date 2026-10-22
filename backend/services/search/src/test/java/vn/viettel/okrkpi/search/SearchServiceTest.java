package vn.viettel.okrkpi.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.search.application.AccessPort;
import vn.viettel.okrkpi.search.application.CursorPort;
import vn.viettel.okrkpi.search.application.InvalidCursorException;
import vn.viettel.okrkpi.search.application.SearchHit;
import vn.viettel.okrkpi.search.application.SearchPage;
import vn.viettel.okrkpi.search.application.SearchQuery;
import vn.viettel.okrkpi.search.application.SearchReadPort;
import vn.viettel.okrkpi.search.application.SearchService;
import vn.viettel.okrkpi.search.domain.DocType;
import vn.viettel.okrkpi.search.domain.Snippet;

/**
 * Vòng lấy thêm ứng viên, con trỏ, dựng kết quả và đoạn trích của {@code searchFullText} (không cần
 * Docker).
 */
class SearchServiceTest {

  private static final String T = "0192f000-0000-7000-8000-0000000000a1";

  /** Chỉ mục giả: {@code n} ứng viên, mục thứ i có quyền khi {@code visible[i]}. */
  private static final class Fake implements SearchReadPort, AccessPort, CursorPort {
    final List<SearchHit> all = new ArrayList<>();
    final Map<String, Boolean> visible = new HashMap<>();
    boolean maySearch = true;
    int finds;

    Fake(int n, java.util.function.IntPredicate allowed) {
      for (int i = 0; i < n; i++) {
        DocType type =
            i % 7 == 6 ? DocType.SOURCE_DOC : i % 11 == 10 ? DocType.CATALOG_ITEM : DocType.TASK;
        String id = String.format("0192f000-0000-7000-8000-%012d", i);
        Map<String, Object> src =
            Map.of("title", "Kiểm điểm " + i, "status", "DA_GIAO", "doc_number", "12/CT");
        all.add(
            new SearchHit(
                type, id, 100 - i, src, i % 2 == 0 ? "Kiểm điểm " + i : null, List.of(i)));
        visible.put(id, allowed.test(i));
      }
    }

    @Override
    public List<SearchHit> find(SearchQuery q, List<Object> after, int size) {
      finds++;
      int from = after == null ? 0 : ((Number) after.get(0)).intValue() + 1;
      return all.subList(Math.min(from, all.size()), Math.min(from + size, all.size()));
    }

    @Override
    public boolean maySearch(String tenantId) {
      return maySearch;
    }

    @Override
    public List<Boolean> mayView(String tenantId, List<SearchHit> hits) {
      return hits.stream().map(h -> visible.get(h.entityId())).toList();
    }

    @Override
    public String seal(String binding, List<Object> sort) {
      return binding + "#" + sort.get(0);
    }

    @Override
    public Optional<List<Object>> open(String binding, String cursor) {
      return cursor.startsWith(binding + "#")
          ? Optional.of(List.of(Integer.parseInt(cursor.substring(binding.length() + 1))))
          : Optional.empty();
    }
  }

  private static SearchQuery q(int limit) {
    return new SearchQuery(T, "kiem diem", null, null, limit);
  }

  @Test
  void phanTrangHetDuLieuVaKhongTrung() {
    Fake f = new Fake(50, i -> i % 3 != 0);
    SearchService s = new SearchService(f, f, f);
    List<String> seen = new ArrayList<>();
    String cursor = null;
    do {
      SearchPage p = s.search(q(10), cursor, "b");
      p.items().forEach(i -> seen.add(i.id()));
      cursor = p.nextCursor();
    } while (cursor != null);
    assertThat(seen)
        .doesNotHaveDuplicates()
        .hasSize((int) f.visible.values().stream().filter(v -> v).count());
  }

  @Test
  void layThemUngVienToiDaBaVong() {
    Fake f = new Fake(200, i -> i == 199);
    SearchPage p = new SearchService(f, f, f).search(q(10), null, "b");
    assertThat(p.items()).isEmpty();
    assertThat(f.finds).isEqualTo(SearchService.MAX_ROUNDS);
    assertThat(p.nextCursor()).isNotNull();
  }

  @Test
  void dungKhiHetUngVien() {
    Fake f = new Fake(4, i -> true);
    SearchPage p = new SearchService(f, f, f).search(q(10), null, "b");
    assertThat(p.items()).hasSize(4);
    assertThat(p.nextCursor()).isNull();
    Fake empty = new Fake(0, i -> true);
    assertThat(new SearchService(empty, empty, empty).search(q(5), null, "b").items()).isEmpty();
  }

  @Test
  void dungKetQuaTheoLoai() {
    Fake f = new Fake(12, i -> true);
    List<SearchPage.Item> items = new SearchService(f, f, f).search(q(12), null, "b").items();
    SearchPage.Item task = items.get(0);
    assertThat(task.type()).isEqualTo(DocType.TASK);
    assertThat(task.deepLink()).isEqualTo("/nhiem-vu/" + task.id());
    assertThat(task.status()).isEqualTo("DA_GIAO");
    assertThat(task.snippet().text()).isEqualTo("Kiểm điểm 0");
    assertThat(task.snippet().highlights()).containsExactly(new Snippet.Highlight(5, 4));
    SearchPage.Item doc = items.get(6);
    assertThat(doc.type()).isEqualTo(DocType.SOURCE_DOC);
    assertThat(doc.code()).isEqualTo("12/CT");
    assertThat(doc.deepLink()).startsWith("/nhiem-vu/van-ban-den/");
    SearchPage.Item cat = items.get(10);
    assertThat(cat.type()).isEqualTo(DocType.CATALOG_ITEM);
    assertThat(cat.deepLink()).isNull();
    assertThat(items.get(1).snippet()).isNull();
  }

  @Test
  void tuChoiKhiKhongCoQuyenHoacConTroSai() {
    Fake f = new Fake(3, i -> true);
    f.maySearch = false;
    assertThatThrownBy(() -> new SearchService(f, f, f).search(q(5), null, "b"))
        .isInstanceOf(SecurityException.class);
    f.maySearch = true;
    assertThatThrownBy(() -> new SearchService(f, f, f).search(q(5), "khac#1", "b"))
        .isInstanceOf(InvalidCursorException.class);
    assertThat(new SearchService(f, f, f).search(q(5), " ", "b").items()).hasSize(3);
    assertThatThrownBy(() -> q(201)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> q(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void doanTrichGopTuLienNhauVaBoDauLe() {
    Snippet s = Snippet.parse("Kế hoạch giáo dục và chính trị ");
    assertThat(s.text()).isEqualTo("Kế hoạch giáo dục và chính trị ");
    assertThat(s.highlights())
        .containsExactly(new Snippet.Highlight(9, 8), new Snippet.Highlight(21, 5));
    assertThat(Snippet.parse("abc").highlights()).isEmpty();
    SearchHit h =
        new SearchHit(
            DocType.TASK, "x", 1, Map.of("assignee_ids", List.of("a"), "n", 1), null, List.of());
    assertThat(h.list("assignee_ids")).containsExactly("a");
    assertThat(h.list("n")).isEmpty();
    assertThat(h.text("n")).isEqualTo("1");
    assertThat(h.text("khong")).isNull();
  }
}
