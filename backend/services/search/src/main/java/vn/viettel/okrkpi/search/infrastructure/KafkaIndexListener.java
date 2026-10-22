package vn.viettel.okrkpi.search.infrastructure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.search.application.InboundEvent;
import vn.viettel.okrkpi.search.application.IndexFailure;
import vn.viettel.okrkpi.search.application.IndexingService;
import vn.viettel.okrkpi.search.domain.TenancyModel;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.vtokr.sys.v1.TenantProvisioned;

/**
 * Consumer Kafka của indexer. Sự kiện nghiệp vụ đọc theo lô để mỗi lô thành một lệnh {@code _bulk};
 * lỗi tạm thời của OpenSearch làm cả lô được thử lại với backoff (ghi lặp an toàn nhờ kiểm phiên
 * bản, nên không cần bảng inbox), lỗi vĩnh viễn của từng thông điệp chuyển sang {@code
 * <topic>.dlq.search-indexer} theo quy ước NEN-012.
 */
public class KafkaIndexListener {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaIndexListener.class);

  private final IndexingService service;
  private final EventDecoder decoder;
  private final InboxRetryDlqPublisher dlq;
  private final String consumer;

  public KafkaIndexListener(
      IndexingService service, EventDecoder decoder, InboxRetryDlqPublisher dlq, String consumer) {
    this.service = service;
    this.decoder = decoder;
    this.dlq = dlq;
    this.consumer = consumer;
  }

  @KafkaListener(
      id = "search-indexer",
      groupId = "${mission.search.consumer-group:search-indexer}",
      topics = {
        EventTopics.EVT_04_NHIEM_VU_DA_GIAO,
        EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI,
        EventTopics.EVT_24_VAN_BAN_CHI_DAO_DA_NHAN
      },
      containerFactory = "searchBatchFactory",
      autoStartup = "${mission.search.listen:true}")
  public void onBatch(List<ConsumerRecord<String, byte[]>> records) {
    List<InboundEvent> events = new ArrayList<>(records.size());
    Map<Integer, ConsumerRecord<String, byte[]>> bySeq = new HashMap<>();
    for (int i = 0; i < records.size(); i++) {
      ConsumerRecord<String, byte[]> r = records.get(i);
      try {
        events.add(decoder.decode(i, r));
        bySeq.put(i, r);
      } catch (PermanentInboxException e) {
        dead(r, e.errorCode(), e);
      }
    }
    for (IndexFailure f : service.index(events)) {
      dead(bySeq.get(f.seq()), f.code(), new PermanentInboxException(f.code(), f.reason()));
    }
  }

  @KafkaListener(
      id = "search-tenants",
      groupId = "${mission.search.consumer-group:search-indexer}",
      topics = EventTopics.SYS_TENANT_PROVISIONED_V1,
      containerFactory = "searchRecordFactory",
      autoStartup = "${mission.search.listen:true}")
  public void onTenant(ConsumerRecord<String, byte[]> record) {
    InboundEvent e;
    try {
      e = decoder.decode(0, record);
    } catch (PermanentInboxException ex) {
      dead(record, ex.errorCode(), ex);
      return;
    }
    TenantProvisioned t = (TenantProvisioned) e.payload();
    TenancyModel model = TenancyModel.parse(t.getTenancyModel()).orElse(null);
    if (model == null) {
      dead(
          record,
          "SEARCH_TENANCY_MODEL",
          new PermanentInboxException("Mô hình thuê bao không hợp lệ"));
      return;
    }
    service.provision(e.tenantId(), model);
  }

  private void dead(ConsumerRecord<String, byte[]> r, String code, Exception e) {
    LOG.warn("Chuyển thư chết thông điệp {}-{}@{}: {}", r.topic(), r.partition(), r.offset(), code);
    dlq.publishToDlq(r, r.topic(), consumer, code, e, Instant.now());
  }
}
