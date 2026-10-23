package vn.viettel.okrkpi.integ.infrastructure.camel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;

/**
 * Bộ kết nối mẫu giả lập cho kiểm thử khung: hàng đợi trang theo kết nối thay cho đối tác, con trỏ
 * là số thứ tự trang; translator sinh EVT-24 và có thể bị cho lỗi theo mã thông điệp.
 */
class SampleConnector implements InboundConnector {

  static final String CODE = "MAU";

  final List<List<InboundMessage>> pages = new CopyOnWriteArrayList<>();
  final Set<String> failing = ConcurrentHashMap.newKeySet();
  final Map<String, AtomicInteger> translations = new ConcurrentHashMap<>();
  final AtomicInteger fetches = new AtomicInteger();

  @Override
  public String code() {
    return CODE;
  }

  @Override
  public String stream() {
    return "van-ban";
  }

  @Override
  public Duration period() {
    return Duration.ofMillis(200);
  }

  @Override
  public Page fetch(Connector connector, String cursor) throws IOException {
    fetches.incrementAndGet();
    int next = Integer.parseInt(cursor);
    if (next >= pages.size()) {
      return new Page(List.of(), cursor, null);
    }
    return new Page(new ArrayList<>(pages.get(next)), String.valueOf(next + 1), Instant.now());
  }

  @Override
  public Translator translator() {
    return (ctx, m) -> {
      translations.computeIfAbsent(m.externalMsgId(), k -> new AtomicInteger()).incrementAndGet();
      if (failing.contains(m.externalMsgId())) {
        throw new IllegalArgumentException("Không đọc được văn bản");
      }
      UUID doc = ctx.internalId(EntityType.VAN_BAN, m.externalMsgId());
      VanBanChiDaoDaNhan payload =
          VanBanChiDaoDaNhan.newBuilder()
              .setDocumentId(doc)
              .setConnectorCode(CODE)
              .setExternalId(m.externalMsgId())
              .setDocNumber("01/CT")
              .setIssuedDate(LocalDate.of(2026, 10, 1))
              .setIssuer("Văn phòng")
              .setSummary(new String(m.raw(), StandardCharsets.UTF_8).length() + " ký tự")
              .setAggregateVersion(1)
              .build();
      return List.of(
          new CanonicalEvent(
              "integ.document.received.v1", "VAN_BAN", doc, 1, payload, Classification.C1));
    };
  }

  static InboundMessage message(String id, String content) {
    return new InboundMessage(id, "VAN_BAN_CHI_DAO", content.getBytes(StandardCharsets.UTF_8));
  }
}
