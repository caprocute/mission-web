package vn.viettel.okrkpi.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.search.application.EventMapper;
import vn.viettel.okrkpi.search.application.InboundEvent;
import vn.viettel.okrkpi.search.domain.IndexCommand;
import vn.viettel.okrkpi.search.domain.IndexNames;
import vn.viettel.okrkpi.search.domain.VietnameseText;

/** Kiểm thử không cần Docker: dựng thao tác từ sự kiện, chuẩn hoá văn bản, tên chỉ mục. */
class EventMapperTest {

  private static final String TENANT = "0192F000-0000-7000-8000-0000000000A1";

  @Test
  void giaoViecThanhNhomAssignCoPhienBan() {
    UUID task = UUID.randomUUID();
    IndexCommand c =
        EventMapper.map(
                new InboundEvent(
                    3,
                    "t",
                    TENANT,
                    Events.assigned(task, "  Giáóo  dục\n chính trị ", 9, false),
                    Instant.EPOCH))
            .orElseThrow();
    assertThat(c.seq()).isEqualTo(3);
    assertThat(c.tenantId()).isEqualTo(TENANT.toLowerCase());
    assertThat(c.docId()).isEqualTo("TASK:" + task);
    assertThat(c.mode()).isEqualTo(IndexCommand.Mode.MERGE);
    assertThat(c.version()).isEqualTo(9);
    assertThat(c.fields())
        .containsEntry("due_date", "2026-12-31")
        .containsEntry("is_key_task", true)
        .doesNotContainKey("weight");
  }

  @Test
  void nhiemVuMatBoTieuDe() {
    IndexCommand c =
        EventMapper.map(
                new InboundEvent(
                    0,
                    "t",
                    TENANT,
                    Events.assigned(UUID.randomUUID(), "Nhiệm vụ hạn chế", 1, true),
                    null))
            .orElseThrow();
    assertThat(c.fields()).containsEntry("title", null).containsEntry("is_restricted", true);
  }

  @Test
  void trangThaiVaVanBanChiGiuTruongDuocPhep() {
    IndexCommand s =
        EventMapper.map(
                new InboundEvent(0, "t", TENANT, Events.status(UUID.randomUUID(), "HUY", 2), null))
            .orElseThrow();
    assertThat(s.fields().keySet()).containsExactlyInAnyOrder("status", "is_overdue");
    IndexCommand d =
        EventMapper.map(
                new InboundEvent(
                    0, "t", TENANT, Events.document(UUID.randomUUID(), "Trích yếu", 4), null))
            .orElseThrow();
    assertThat(d.mode()).isEqualTo(IndexCommand.Mode.REPLACE);
    assertThat(d.fields().keySet())
        .containsExactlyInAnyOrder("title", "summary", "doc_number", "issuer", "issued_date");
  }

  @Test
  void chuanHoaVanBanVaTenChiMuc() {
    assertThat(VietnameseText.normalize("giáo dục")).isEqualTo("giáo dục");
    assertThat(VietnameseText.normalize("  ​ ")).isNull();
    assertThat(IndexNames.next("pool_task_v9")).isEqualTo("pool_task_v10");
    assertThat(IndexNames.tenantIndex(TENANT, 1))
        .isEqualTo("t_" + TENANT.toLowerCase() + "_task_v1");
    assertThatThrownBy(() -> IndexNames.tenantAlias("x/../_all"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
