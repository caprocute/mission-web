package vn.viettel.okrkpi.search.application;

import java.util.ArrayList;
import java.util.List;
import vn.viettel.okrkpi.search.domain.DocType;
import vn.viettel.okrkpi.search.domain.Snippet;

/**
 * {@code searchFullText} (DV-16-02): lấy ứng viên từ chỉ mục của tenant người gọi, kiểm quyền lại
 * từng ứng viên, loại mục ngoài phạm vi trước khi trả. Chỉ mục có thể chậm hơn quyền thực tế (người
 * vừa bị thu hồi phân công vẫn còn trong {@code assignee_ids}), nên quyết định lấy theo chính sách
 * hiện hành, không tin chỉ mục. Khi loại bớt làm trang thiếu, lấy thêm ứng viên tối đa {@link
 * #MAX_ROUNDS} vòng (suy luận 3).
 */
public class SearchService {

  public static final int MAX_ROUNDS = 3;

  private final SearchReadPort index;
  private final AccessPort access;
  private final CursorPort cursors;

  public SearchService(SearchReadPort index, AccessPort access, CursorPort cursors) {
    this.index = index;
    this.access = access;
    this.cursors = cursors;
  }

  /**
   * @param binding chuỗi gắn con trỏ với người gọi và truy vấn (người gọi, tenant, từ khoá, bộ lọc)
   * @throws SecurityException khi người gọi không được dùng API tìm kiếm
   * @throws InvalidCursorException khi con trỏ không mở được
   */
  public SearchPage search(SearchQuery query, String cursor, String binding) {
    if (!access.maySearch(query.tenantId())) {
      throw new SecurityException("Không có quyền tìm kiếm");
    }
    List<Object> after = null;
    if (cursor != null && !cursor.isBlank()) {
      after = cursors.open(binding, cursor).orElseThrow(InvalidCursorException::new);
    }
    List<SearchPage.Item> items = new ArrayList<>(query.limit());
    List<Object> lastReturned = null;
    List<Object> lastExamined = after;
    boolean more = false;
    int size = Math.min(SearchQuery.MAX_LIMIT, query.limit() + Math.max(5, query.limit() / 2));
    for (int round = 0; round < MAX_ROUNDS && items.size() < query.limit(); round++) {
      List<SearchHit> hits = index.find(query, lastExamined, size);
      if (hits.isEmpty()) {
        more = false;
        break;
      }
      List<Boolean> allowed = access.mayView(query.tenantId(), hits);
      int i = 0;
      for (; i < hits.size() && items.size() < query.limit(); i++) {
        SearchHit h = hits.get(i);
        lastExamined = h.sort();
        if (Boolean.TRUE.equals(allowed.get(i))) {
          items.add(item(h));
          lastReturned = h.sort();
        }
      }
      // Còn ứng viên chưa xét trong lô, hoặc lô đầy thì có thể còn trang sau.
      more = i < hits.size() || hits.size() == size;
    }
    List<Object> next = items.size() >= query.limit() ? lastReturned : lastExamined;
    String nextCursor = more && next != null ? cursors.seal(binding, next) : null;
    return new SearchPage(List.copyOf(items), nextCursor);
  }

  static SearchPage.Item item(SearchHit h) {
    String title = h.text("title");
    Snippet snippet = h.marked() == null ? null : Snippet.parse(h.marked());
    return switch (h.type()) {
      case TASK ->
          new SearchPage.Item(
              DocType.TASK,
              h.entityId(),
              null,
              title,
              snippet,
              h.text("status"),
              "/nhiem-vu/" + h.entityId(),
              h.score());
      case SOURCE_DOC ->
          new SearchPage.Item(
              DocType.SOURCE_DOC,
              h.entityId(),
              h.text("doc_number"),
              title,
              snippet,
              null,
              "/nhiem-vu/van-ban-den/" + h.entityId(),
              h.score());
      case CATALOG_ITEM ->
          new SearchPage.Item(
              DocType.CATALOG_ITEM, h.entityId(), null, title, snippet, null, null, h.score());
    };
  }
}
