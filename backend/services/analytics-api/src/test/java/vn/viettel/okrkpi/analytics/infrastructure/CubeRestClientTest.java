package vn.viettel.okrkpi.analytics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.analytics.domain.SemanticCatalog;

/** Dựng lớp ngữ nghĩa từ siêu dữ liệu Cube: thành viên công khai, tiêu đề ngắn, đơn vị, quan hệ. */
class CubeRestClientTest {

  static final String META =
      """
      {"cubes": [
        {"name": "okr", "title": "Mục tiêu O", "meta": {"phien_ban": "2026.4"},
         "joins": [{"name": "org_unit"}, {"name": "period"}],
         "measures": [
           {"name": "okr.tien_do_o", "title": "Mục tiêu O Tiến độ mục tiêu O", "shortTitle": "Tiến độ mục tiêu O",
            "type": "number", "format": "percent", "isVisible": true, "public": true},
           {"name": "okr.tong_tien_do", "type": "sum", "isVisible": false, "public": false}],
         "dimensions": [
           {"name": "okr.tenant_id", "type": "string", "isVisible": false, "public": false},
           {"name": "okr.chu_the", "shortTitle": "Chủ thể mục tiêu", "type": "string"}]},
        {"name": "org_unit", "meta": {"phien_ban": "2026.4"}, "joins": [{"name": "subject_group"}],
         "measures": [], "dimensions": [{"name": "org_unit.ten", "shortTitle": "Tên đơn vị", "type": "string"}]},
        {"name": "subject_group", "meta": {"phien_ban": "2026.3"}, "measures": [],
         "dimensions": [{"name": "subject_group.ten", "shortTitle": "Khối", "type": "string"}]},
        {"name": "period", "meta": {"phien_ban": "2026.4"}, "measures": [],
         "dimensions": [{"name": "period.id", "shortTitle": "Mã định danh kỳ", "type": "string"}]},
        {"name": "task", "meta": {"phien_ban": "2026.4"}, "joins": [{"name": "org_unit"}],
         "measures": [{"name": "task.so_nhiem_vu", "shortTitle": "Số nhiệm vụ", "type": "count",
                       "drillMembers": ["task.ma"]}],
         "dimensions": [{"name": "task.han", "shortTitle": "Hạn", "type": "time"}]}
      ]}
      """;

  @Test
  void catalogFromMeta() throws Exception {
    SemanticCatalog c = CubeRestClient.catalog(new ObjectMapper().readTree(META));
    assertThat(c.version()).isEqualTo("2026.4");
    assertThat(c.measures()).containsOnlyKeys("okr.tien_do_o", "task.so_nhiem_vu");
    assertThat(c.measures().get("okr.tien_do_o"))
        .isEqualTo(
            new SemanticCatalog.Measure(
                "okr.tien_do_o", "Tiến độ mục tiêu O", "number", "%", false));
    assertThat(c.measures().get("task.so_nhiem_vu").drillable()).isTrue();
    assertThat(c.dimensions()).doesNotContainKey("okr.tenant_id");
    assertThat(c.dimensions().get("org_unit.ten").supportedMeasures())
        .containsExactlyInAnyOrder("okr.tien_do_o", "task.so_nhiem_vu");
    assertThat(c.dimensions().get("subject_group.ten").supportedMeasures())
        .containsExactlyInAnyOrder("okr.tien_do_o", "task.so_nhiem_vu");
    assertThat(c.dimensions().get("period.id").supportedMeasures())
        .containsExactly("okr.tien_do_o");
    assertThat(c.dimensions().get("okr.chu_the").supportedMeasures())
        .containsExactly("okr.tien_do_o");
    assertThat(c.dimensions().get("task.han").supportedMeasures())
        .containsExactly("task.so_nhiem_vu");
  }
}
