package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException;

/** Đọc sổ cái theo đối tượng: token dịch vụ, tenant đích trong header, đọc hết các trang. */
class HttpLedgerEntryLookupTest {

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final HttpLedgerEntryLookup lookup =
      new HttpLedgerEntryLookup(builder, "http://ledger", () -> "tok");
  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID agg = UUID.randomUUID();
  private final Instant from = Instant.parse("2026-10-22T00:00:00Z");
  private final Instant to = Instant.parse("2026-10-23T02:00:00Z");

  private static String item(UUID event, String hash) {
    return "{\"eventId\":\""
        + event
        + "\",\"eventType\":\"task.task.assigned.v1\",\"payloadHash\":\""
        + hash
        + "\",\"recordedAt\":\"2026-10-22T01:00:00Z\"}";
  }

  @Test
  void docHetCacTrangVoiTenantTrongHeader() {
    UUID e1 = UUID.randomUUID();
    UUID e2 = UUID.randomUUID();
    server
        .expect(requestTo(Matchers.startsWith("http://ledger/api/v1/ledger/entries?")))
        .andExpect(queryParam("aggregateId", agg.toString()))
        .andExpect(queryParam("limit", "200"))
        .andExpect(header("Authorization", "Bearer tok"))
        .andExpect(header("X-Tenant-Id", tenant.value().toString()))
        .andRespond(
            withSuccess(
                "{\"items\":[" + item(e1, "aa") + "],\"nextCursor\":\"c2\"}",
                MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(Matchers.containsString("cursor=c2")))
        .andRespond(
            withSuccess(
                "{\"items\":[" + item(e2, "bb") + "],\"nextCursor\":null}",
                MediaType.APPLICATION_JSON));

    assertThat(lookup.entries(tenant, agg, from, to))
        .extracting(LedgerEntryLookup.LedgerRef::eventId, LedgerEntryLookup.LedgerRef::payloadHash)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(e1, "aa"),
            org.assertj.core.groups.Tuple.tuple(e2, "bb"));
    server.verify();
  }

  @Test
  void loiCuaSoCaiLaPhuThuocKhongDungDuoc() {
    server
        .expect(requestTo(Matchers.startsWith("http://ledger/api/v1/ledger/entries")))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));
    assertThatThrownBy(() -> lookup.entries(tenant, agg, from, to))
        .isInstanceOf(DependencyUnavailableException.class);
  }
}
