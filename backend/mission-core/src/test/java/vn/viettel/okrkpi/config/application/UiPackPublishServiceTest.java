package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.config.application.port.UiPackBundleStore;
import vn.viettel.okrkpi.config.application.port.UiPackEvents;
import vn.viettel.okrkpi.config.application.port.UiPackNotices;
import vn.viettel.okrkpi.config.application.port.UiPackSigner;
import vn.viettel.okrkpi.config.domain.UiPackContent;
import vn.viettel.okrkpi.config.domain.UiPackVersion;
import vn.viettel.okrkpi.config.domain.UiPackVersionStatus;
import vn.viettel.okrkpi.shared.authz.StepUpRequiredException;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** CORE-08-11: phát hành UI Pack, gói ký, con trỏ hiện hành, bố cục trang chủ theo vai trò. */
class UiPackPublishServiceTest {

  private static final Instant NOW = Instant.parse("2027-01-04T01:00:00.123456789Z");
  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final UUID AUTHOR = UUID.randomUUID();
  private static final UUID REVIEWER = UUID.randomUUID();
  private static final ObjectMapper JSON = new ObjectMapper();

  final UiPackServiceTest.Repo repo = new UiPackServiceTest.Repo();
  final UiPackServiceTest.Jobs jobs = new UiPackServiceTest.Jobs();
  final List<String> audits = new ArrayList<>();
  final List<String> events = new ArrayList<>();
  final List<List<String>> invalidated = new ArrayList<>();
  final Map<String, byte[]> objects = new HashMap<>();
  final Map<String, String> cache = new HashMap<>();
  final KeyPair keys = keys();

  final UiPackService packs =
      new UiPackService(
          repo,
          new UiPackNotices() {
            @Override
            public void reviewRequested(TenantId t, String n, UiPackVersion v, List<UUID> to) {}

            @Override
            public void rejected(TenantId t, String n, UiPackVersion v, String c, List<UUID> to) {}
          },
          (t, doc) -> List.of(),
          jobs,
          (t, role, at) -> List.of(REVIEWER),
          (t, a, action, type, id, agg, v) -> audits.add(action + ":" + id),
          JSON,
          Clock.fixed(NOW),
          Runnable::run,
          TransactionOperations.withoutTransaction());

  final UiPackSigner signer =
      new UiPackSigner() {
        @Override
        public String keyId() {
          return "k-test";
        }

        @Override
        public byte[] sign(byte[] data) {
          try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(keys.getPrivate());
            s.update(data);
            return s.sign();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        }

        @Override
        public byte[] publicKey() {
          return keys.getPublic().getEncoded();
        }
      };

  final UiPackBundleStore store =
      new UiPackBundleStore() {
        @Override
        public void put(String key, byte[] content, String type, String cacheControl) {
          objects.put(key, content);
          cache.put(key, cacheControl);
        }

        @Override
        public Optional<byte[]> get(String key) {
          return Optional.ofNullable(objects.get(key));
        }
      };

  final UiPackEvents eventPort =
      (t, v, paths) -> events.add(v.version() + ":" + v.bundleSha256() + ":" + paths);

  final UiPackPublishService service =
      new UiPackPublishService(
          packs,
          repo,
          new UiPackCompiler(JSON),
          signer,
          store,
          eventPort,
          invalidated::add,
          jobs,
          (t, a, action, type, id, agg, v) -> audits.add(action + ":" + id),
          JSON,
          Clock.fixed(NOW),
          Runnable::run,
          TransactionOperations.withoutTransaction());

  private static KeyPair keys() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Actor mfa(UUID id) {
    return new Actor(id, "aal2", NOW.getEpochSecond() - 60);
  }

  private UUID packId() {
    return packs.listPacks(TENANT, AUTHOR, null, 20).get(0).pack().id();
  }

  /** Tạo phiên bản, gắn bố cục có khối chỉ lãnh đạo thấy, gửi duyệt. */
  private void waiting(UUID pack, String version) throws Exception {
    waiting(pack, version, null);
  }

  private void waiting(UUID pack, String version, UUID policyPack) throws Exception {
    UiPackVersion v = packs.create(TENANT, AUTHOR, pack, version, null);
    UiPackContent c = v.content();
    JsonNode layouts =
        JSON.readTree(
            """
            {"home":{"component":"vt.layout.grid","children":[
              {"component":"vt.widget.myTasksToday","props":{"limit":7}},
              {"component":"vt.widget.okrProgress","visibleWhen":"'leader' in user.roles"}]}}
            """);
    UiPackContent next =
        packs.content(
            JSON.readTree(c.tokensJson()),
            layouts,
            (ArrayNode) JSON.readTree(c.assetsJson()),
            c.componentRegistryVersion(),
            policyPack,
            true,
            true);
    UiPackVersion updated = packs.update(TENANT, AUTHOR, pack, version, v.rowVersion(), next);
    packs.submit(TENANT, AUTHOR, pack, version, updated.rowVersion());
    assertThat(packs.get(TENANT, pack, version).status()).isEqualTo(UiPackVersionStatus.CHO_DUYET);
  }

  @Test
  void publishSignsStoresAndSwitchesCurrentVersion() throws Exception {
    UUID pack = packId();
    waiting(pack, "1.0.0");
    UiPackVersion before = packs.get(TENANT, pack, "1.0.0");

    String job = service.publish(TENANT, mfa(REVIEWER), pack, "1.0.0", before.rowVersion());

    assertThat(jobs.state.get(job)).startsWith("HOAN_TAT ");
    assertThat(jobs.messages.get(job))
        .isEqualTo(
            "UI Pack v1.0.0 đã phát hành. Người dùng sẽ nhận giao diện mới ở lần tải trang kế"
                + " tiếp.");
    UiPackVersion published = packs.get(TENANT, pack, "1.0.0");
    assertThat(published.status()).isEqualTo(UiPackVersionStatus.DA_PHAT_HANH);
    assertThat(published.publishedAt()).isEqualTo(Instant.parse("2027-01-04T01:00:00.123456Z"));
    String prefix = "ui-packs/" + TENANT.value() + "/1.0.0/";
    assertThat(published.bundleObjectKey()).isEqualTo(prefix + "pack.json");
    assertThat(objects)
        .containsKeys(prefix + "pack.json", prefix + "theme.css", prefix + "preset.json");
    assertThat(cache.get(prefix + "pack.json")).isEqualTo(UiPackBundleStore.IMMUTABLE);
    assertThat(repo.packs.get(pack).currentVersionId()).isEqualTo(published.id());
    assertThat(events)
        .containsExactly(
            "1.0.0:"
                + published.bundleSha256()
                + ":[/ui-packs/"
                + TENANT.value()
                + "/current.json]");
    assertThat(audits).contains("PHAT_HANH_UI_PACK:1.0.0");

    JsonNode packJson = JSON.readTree(objects.get(prefix + "pack.json"));
    assertThat(packJson.path("contentHash").asText()).isEqualTo(published.bundleSha256());
    assertThat(packJson.has("assets")).isFalse();
    byte[] signed = service.signedContent(packJson);
    assertThat(java.util.HexFormat.of().formatHex(UiPackPublishService.sha256(signed)))
        .isEqualTo(published.bundleSha256());
    Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
    verify.initVerify(keys.getPublic());
    verify.update(signed);
    assertThat(verify.verify(Base64.getDecoder().decode(packJson.path("signature").asText())))
        .isTrue();
    assertThat(new String(objects.get(prefix + "theme.css")))
        .startsWith(":root[data-tenant=\"" + TENANT.value() + "\"] {");

    UiPackPublishService.Current current = service.current(TENANT).orElseThrow();
    assertThat(current.etag()).isEqualTo(published.bundleSha256());
    assertThat(current.body()).isEqualTo(packJson);

    service.onPublished(TENANT, published.id(), List.of("/ui-packs/x/current.json"));
    String pointer = "ui-packs/" + TENANT.value() + "/current.json";
    assertThat(JSON.readTree(objects.get(pointer)).path("pack").asText())
        .isEqualTo("/" + prefix + "pack.json");
    assertThat(cache.get(pointer)).isEqualTo(UiPackBundleStore.POINTER);
    assertThat(invalidated).containsExactly(List.of("/ui-packs/x/current.json"));

    waiting(pack, "1.1.0");
    UiPackVersion second = packs.get(TENANT, pack, "1.1.0");
    service.publish(TENANT, mfa(REVIEWER), pack, "1.1.0", second.rowVersion());
    assertThat(packs.get(TENANT, pack, "1.0.0").status()).isEqualTo(UiPackVersionStatus.THAY_THE);
    assertThat(repo.packs.get(pack).currentVersionId())
        .isEqualTo(packs.get(TENANT, pack, "1.1.0").id());

    objects.remove(pointer);
    service.onPublished(TENANT, published.id(), List.of());
    assertThat(objects).doesNotContainKey(pointer);
  }

  @Test
  void publishRequiresOtherPersonMfaAndWaitingVersion() throws Exception {
    UUID pack = packId();
    waiting(pack, "1.0.0");
    int rv = packs.get(TENANT, pack, "1.0.0").rowVersion();
    assertThatThrownBy(() -> service.publish(TENANT, mfa(AUTHOR), pack, "1.0.0", rv))
        .isInstanceOf(BusinessException.class)
        .hasMessage("UI-403-SAME-PERSON");
    assertThatThrownBy(
            () -> service.publish(TENANT, new Actor(REVIEWER, null, null), pack, "1.0.0", rv))
        .isInstanceOf(StepUpRequiredException.class);
    packs.create(TENANT, AUTHOR, pack, "2.0.0", null);
    assertThat(service.current(TENANT)).isEmpty();
    assertThatThrownBy(() -> service.publish(TENANT, mfa(REVIEWER), pack, "2.0.0", 0))
        .isInstanceOf(BusinessException.class)
        .hasMessage("UI-409-NOT-DRAFT");
  }

  @Test
  void homeLayoutFiltersVisibleWhenByRole() throws Exception {
    UUID pack = packId();
    waiting(pack, "1.0.0");
    service.publish(
        TENANT, mfa(REVIEWER), pack, "1.0.0", packs.get(TENANT, pack, "1.0.0").rowVersion());

    UiPackPublishService.Home member =
        service
            .home(TENANT, new UiPackPublishService.Viewer(AUTHOR, List.of("member"), "u1", false))
            .orElseThrow();
    assertThat(member.layout().path("children")).hasSize(1);
    assertThat(member.mode()).isEqualTo("default");
    assertThat(member.reducedSteps()).isFalse();
    assertThat(member.registryVersion()).isNotBlank();

    UiPackPublishService.Home leader =
        service
            .home(TENANT, new UiPackPublishService.Viewer(AUTHOR, List.of("leader"), "u1", true))
            .orElseThrow();
    assertThat(leader.layout().path("children")).hasSize(2);
    assertThat(leader.mode()).isEqualTo("elderly");
    assertThat(leader.reducedSteps()).isTrue();
  }

  private void publish(UUID pack, String version, UUID policyPack) throws Exception {
    waiting(pack, version, policyPack);
    service.publish(
        TENANT, mfa(REVIEWER), pack, version, packs.get(TENANT, pack, version).rowVersion());
  }

  @Test
  void activateRollsBackToSupersededBundleWithoutRecompiling() throws Exception {
    UUID pack = packId();
    publish(pack, "1.0.0", null);
    UiPackVersion first = packs.get(TENANT, pack, "1.0.0");
    publish(pack, "1.1.0", null);
    String etagBefore = service.current(TENANT).orElseThrow().etag();
    int stored = objects.size();
    events.clear();

    UiPackVersion back = service.activate(TENANT, REVIEWER, pack, "1.0.0", first.rowVersion() + 1);

    assertThat(back.status()).isEqualTo(UiPackVersionStatus.DA_PHAT_HANH);
    assertThat(back.bundleSha256()).isEqualTo(first.bundleSha256());
    assertThat(back.publishedAt()).isEqualTo(first.publishedAt());
    assertThat(packs.get(TENANT, pack, "1.1.0").status()).isEqualTo(UiPackVersionStatus.THAY_THE);
    assertThat(repo.packs.get(pack).currentVersionId()).isEqualTo(first.id());
    assertThat(service.current(TENANT).orElseThrow().etag())
        .isEqualTo(first.bundleSha256())
        .isNotEqualTo(etagBefore);
    assertThat(objects).hasSize(stored);
    assertThat(events).hasSize(1).first().asString().startsWith("1.0.0:");
    assertThat(audits).contains("QUAY_LUI_UI_PACK:1.0.0");
    service.onPublished(TENANT, back.id(), List.of());
    assertThat(
            JSON.readTree(objects.get("ui-packs/" + TENANT.value() + "/current.json"))
                .path("version")
                .asText())
        .isEqualTo("1.0.0");

    assertThatThrownBy(() -> service.activate(TENANT, REVIEWER, pack, "1.0.0", back.rowVersion()))
        .isInstanceOf(BusinessException.class)
        .hasMessage("UI-409-NOT-DRAFT");
    packs.create(TENANT, AUTHOR, pack, "2.0.0", null);
    assertThatThrownBy(() -> service.activate(TENANT, REVIEWER, pack, "2.0.0", 0))
        .isInstanceOf(BusinessException.class)
        .hasMessage("UI-409-NOT-DRAFT");
  }

  @Test
  void activateRefusesVersionBoundToRetiredPolicyPack() throws Exception {
    UUID retired = UUID.randomUUID();
    service.setPolicySchemas(
        new vn.viettel.okrkpi.config.api.PolicySchemaPort() {
          @Override
          public Optional<java.util.Set<String>> fields(TenantId t, String ref, String range) {
            return Optional.empty();
          }

          @Override
          public boolean retired(TenantId t, String ref) {
            return retired.toString().equals(ref);
          }
        });
    UUID pack = packId();
    publish(pack, "1.0.0", retired);
    publish(pack, "1.1.0", null);
    int rv = packs.get(TENANT, pack, "1.0.0").rowVersion();

    assertThatThrownBy(() -> service.activate(TENANT, REVIEWER, pack, "1.0.0", rv))
        .isInstanceOf(BusinessException.class)
        .hasMessage("UI-409-ROLLBACK-INCOMPATIBLE")
        .satisfies(
            e ->
                assertThat(((BusinessException) e).detail())
                    .isEqualTo(
                        "Không thể quay lui về v1.0.0 vì biểu mẫu của phiên bản này tham chiếu"
                            + " Policy Pack đã ngừng."));
    assertThat(packs.get(TENANT, pack, "1.1.0").status())
        .isEqualTo(UiPackVersionStatus.DA_PHAT_HANH);
    assertThat(service.isCompatible(TENANT, UUID.randomUUID().toString())).isTrue();
    assertThat(service.isCompatible(TENANT, retired.toString())).isFalse();
  }
}
