package vn.viettel.okrkpi.oversight.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.oversight.application.AccessLogSearchService.Searcher;
import vn.viettel.okrkpi.oversight.application.AuditIndexingService.AuditRecord;
import vn.viettel.okrkpi.oversight.application.AuditIndexingService.Target;
import vn.viettel.okrkpi.oversight.application.port.AuditIndexReader;
import vn.viettel.okrkpi.oversight.domain.AccessLogQuery;
import vn.viettel.okrkpi.oversight.domain.AuditDocument;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-07-12: kiểm vai trò, AAL2, dạng tham số, ghi kiểm toán và dựng tài liệu chỉ mục. */
class AccessLogSearchServiceTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final Instant AT = Instant.parse("2026-10-22T03:00:00Z");

  private final List<String> audited = new ArrayList<>();
  private final List<AuditDocument> stored = new ArrayList<>();
  private final AuditIndexReader reader = (t, q, after, limit) -> List.copyOf(stored);
  private final AccessLogSearchService service =
      new AccessLogSearchService(
          reader,
          (tenant, actor, action, objectType, objectId, purpose, role) ->
              audited.add(action + "|" + objectType + "|" + objectId + "|" + role));

  private final UUID user = UUID.randomUUID();

  @Test
  void inspectorWithAal2SearchesAndEachSearchIsAuditedOnce() {
    UUID actor = UUID.randomUUID();
    stored.add(doc());

    List<AuditDocument> found =
        service.search(
            TENANT,
            new Searcher(user, List.of("inspector"), "aal2"),
            new AccessLogQuery(actor, "appraisal.ho_so", "abc", true, AT, AT.plusSeconds(60)),
            null,
            21);

    assertThat(found).hasSize(1);
    assertThat(audited)
        .containsExactly(
            "XEM_NHAT_KY_KIEM_TOAN|oversight.access_log|nguoiXem="
                + actor
                + ";loai=appraisal.ho_so;doiTuong=abc;nguoiKhac=1;tu="
                + AT
                + ";den="
                + AT.plusSeconds(60)
                + "|inspector");
    assertThat(
            AccessLogSearchService.summary(new AccessLogQuery(null, null, null, false, null, null)))
        .isEqualTo("*");
  }

  @Test
  void rejectsOtherRolesAal1AndMalformedFilters() {
    AccessLogQuery all = new AccessLogQuery(null, null, null, false, null, null);
    assertThatThrownBy(
            () ->
                service.search(
                    TENANT, new Searcher(user, List.of("manager"), "aal2"), all, null, 21))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-403-OUT-OF-SCOPE");
    assertThatThrownBy(
            () ->
                service.search(
                    TENANT, new Searcher(user, List.of("inspector"), "aal1"), all, null, 21))
        .isInstanceOf(StepUpRequiredException.class);
    Searcher ok = new Searcher(user, List.of("inspector"), "aal2");
    assertThatThrownBy(
            () ->
                service.search(
                    TENANT,
                    ok,
                    new AccessLogQuery(null, "HoSo", null, false, null, null),
                    null,
                    21))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(
            () ->
                service.search(
                    TENANT, ok, new AccessLogQuery(null, null, "a b", false, null, null), null, 21))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-422-VALIDATION");
    assertThatThrownBy(() -> new AccessLogQuery(null, null, null, false, AT, AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(audited).isEmpty();
  }

  @Test
  void indexingBuildsOneDocumentPerTargetWithStableIds() {
    List<List<AuditDocument>> written = new ArrayList<>();
    AuditIndexingService indexing = new AuditIndexingService(written::add);
    UUID eventId = UUID.randomUUID();
    AuditRecord withTargets =
        new AuditRecord(
            eventId,
            TENANT,
            AT,
            user,
            null,
            "manager",
            AuditDocument.ACTION_C2_ACCESS,
            "THANH_CONG",
            "appraisal.ho_so",
            "x",
            null,
            false,
            null,
            List.of(
                new Target("appraisal.ho_so", "a", "diem", "HO_SO", false),
                new Target("appraisal.ho_so", "b", "nhan_xet", "HO_SO", true)));

    List<AuditDocument> docs = indexing.index(withTargets);

    assertThat(docs)
        .extracting(AuditDocument::docId)
        .containsExactly(eventId + ":0", eventId + ":1");
    assertThat(docs).extracting(AuditDocument::purpose).containsOnly("HO_SO");
    assertThat(docs.get(1).self()).isTrue();
    assertThat(indexing.index(withTargets)).isEqualTo(docs);
    assertThat(written).hasSize(2);

    AuditRecord bare =
        new AuditRecord(
            eventId,
            TENANT,
            AT,
            null,
            null,
            null,
            "DANG_NHAP",
            "THANH_CONG",
            "iam.session",
            null,
            "DANG_NHAP",
            true,
            null,
            null);
    List<AuditDocument> one = AuditIndexingService.documents(bare);
    assertThat(one).hasSize(1);
    assertThat(one.get(0).column()).isNull();
    assertThat(one.get(0).system()).isTrue();
  }

  private AuditDocument doc() {
    return new AuditDocument(
        "e:0",
        UUID.randomUUID(),
        TENANT,
        AT,
        UUID.randomUUID(),
        null,
        "manager",
        AuditDocument.ACTION_C2_ACCESS,
        "THANH_CONG",
        "appraisal.ho_so",
        "abc",
        "diem",
        "HO_SO",
        false,
        false,
        null);
  }
}
