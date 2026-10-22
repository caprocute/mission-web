package vn.viettel.okrkpi.search.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vn.viettel.okrkpi.search.application.SearchHit;
import vn.viettel.okrkpi.search.application.SearchQuery;
import vn.viettel.okrkpi.search.application.SearchReadPort;
import vn.viettel.okrkpi.search.application.TransientIndexException;
import vn.viettel.okrkpi.search.domain.DocType;
import vn.viettel.okrkpi.search.domain.FieldPolicy;
import vn.viettel.okrkpi.search.domain.IndexNames;
import vn.viettel.okrkpi.search.domain.Snippet;

/**
 * Truy vấn OpenSearch qua bí danh {@code t_<tenant>_task} của tenant người gọi. Highlighter dùng
 * hai ký tự vùng dùng riêng làm thẻ ({@link Snippet}), không bao giờ sinh HTML. Sắp xếp theo độ
 * khớp rồi theo loại và mã thực thể để {@code search_after} ổn định.
 */
public final class OpenSearchReader implements SearchReadPort {

  private static final List<String> SOURCE =
      List.of(
          FieldPolicy.DOC_TYPE,
          FieldPolicy.ENTITY_ID,
          "title",
          "status",
          "doc_number",
          "lead_assignee_id",
          "assignee_ids");

  private final OpenSearchHttp http;
  private final ObjectMapper json;

  public OpenSearchReader(OpenSearchHttp http, ObjectMapper json) {
    this.http = http;
    this.json = json;
  }

  @Override
  public List<SearchHit> find(SearchQuery q, List<Object> after, int size) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("size", size);
    body.put("track_total_hits", false);
    body.put("_source", SOURCE);
    body.put("query", query(q));
    body.put(
        "sort",
        List.of(
            Map.of("_score", "desc"),
            Map.of(FieldPolicy.DOC_TYPE, "asc"),
            Map.of(FieldPolicy.ENTITY_ID, "asc")));
    if (after != null) {
      body.put("search_after", after);
    }
    Map<String, Object> field =
        Map.of("number_of_fragments", 1, "fragment_size", 160, "no_match_size", 160);
    body.put(
        "highlight",
        Map.of(
            "type",
            "unified",
            "pre_tags",
            List.of(Snippet.PRE),
            "post_tags",
            List.of(Snippet.POST),
            "encoder",
            "default",
            "fields",
            Map.of("summary", field, "title", field)));
    OpenSearchHttp.Response r =
        http.send("POST", "/" + IndexNames.tenantAlias(q.tenantId()) + "/_search", body);
    if (r.status() == 404) {
      return List.of(); // tenant chưa có bí danh: chưa có dữ liệu để tìm.
    }
    if (!r.ok()) {
      throw new TransientIndexException("OpenSearch lỗi khi tìm, mã " + r.status());
    }
    List<SearchHit> out = new ArrayList<>();
    for (JsonNode h : r.body().path("hits").path("hits")) {
      JsonNode src = h.path("_source");
      DocType type;
      try {
        type = DocType.valueOf(src.path(FieldPolicy.DOC_TYPE).asText());
      } catch (IllegalArgumentException e) {
        continue;
      }
      JsonNode hl = h.path("highlight");
      JsonNode frag = hl.path(type == DocType.SOURCE_DOC ? "summary" : "title").path(0);
      if (frag.isMissingNode()) {
        frag = hl.path("title").path(0);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> source = json.convertValue(src, Map.class);
      List<Object> sort = new ArrayList<>();
      h.path("sort").forEach(s -> sort.add(s.isNumber() ? s.numberValue() : s.asText()));
      out.add(
          new SearchHit(
              type,
              src.path(FieldPolicy.ENTITY_ID).asText(),
              h.path("_score").asDouble(0),
              source,
              frag.isMissingNode() ? null : frag.asText(),
              sort));
    }
    return out;
  }

  private static Map<String, Object> query(SearchQuery q) {
    List<Object> filter = new ArrayList<>();
    filter.add(Map.of("exists", Map.of("field", "title")));
    if (q.type() != null) {
      filter.add(Map.of("term", Map.of(FieldPolicy.DOC_TYPE, q.type().name())));
    }
    if (q.unitId() != null) {
      filter.add(
          Map.of(
              "bool",
              Map.of(
                  "should",
                  List.of(
                      Map.of("term", Map.of("lead_assignee_id", q.unitId())),
                      Map.of("term", Map.of("assignee_ids", q.unitId()))),
                  "minimum_should_match",
                  1)));
    }
    Map<String, Object> match =
        Map.of(
            "multi_match",
            Map.of(
                "query",
                q.text(),
                "fields",
                List.of("title^3", "doc_number^2", "summary", "issuer"),
                "operator",
                "and"));
    return Map.of(
        "bool",
        Map.of(
            "must",
            List.of(match),
            "filter",
            filter,
            "must_not",
            List.of(Map.of("term", Map.of("is_restricted", true)))));
  }
}
