package vn.viettel.okrkpi.config.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.config.application.AccessHistoryService;
import vn.viettel.okrkpi.config.application.CustomRoleService;
import vn.viettel.okrkpi.config.application.DelegationService;
import vn.viettel.okrkpi.config.application.RealmRoleSync;
import vn.viettel.okrkpi.config.application.RoleBindingService;
import vn.viettel.okrkpi.config.application.port.TenantRolePublisher;
import vn.viettel.okrkpi.config.infrastructure.authz.ClasspathPermissionCatalog;
import vn.viettel.okrkpi.config.infrastructure.client.KeycloakRealmRoles;
import vn.viettel.okrkpi.config.infrastructure.client.OpaTenantRolePublisher;
import vn.viettel.okrkpi.config.infrastructure.listener.RealmRoleSyncListener;
import vn.viettel.okrkpi.config.infrastructure.scheduling.AccessMaintenanceJob;
import vn.viettel.okrkpi.config.infrastructure.scheduling.CustomRolePublishJob;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEndpoint;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.inbox.InboxMetrics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.InboxProperties;
import vn.viettel.okrkpi.shared.inbox.InboxRetryDlqPublisher;
import vn.viettel.okrkpi.shared.infrastructure.identity.KeycloakAdminClient;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.outbox.ApicurioPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.routing.TenantDirectory;
import vn.vtokr.config.v1.VaiTroDaThayDoi;

/**
 * CORE-08-16: quản trị vai trò và uỷ quyền của MOD-08 trên {@code configDataSource}; EVT-27 và sự
 * kiện kiểm toán ghi {@code config.outbox_event} cùng giao dịch. Đồng bộ vai trò realm Keycloak chỉ
 * bật khi có Admin API ({@code mission.config.keycloak-admin.base-url}).
 */
@Configuration(proxyBeanMethods = false)
public class AccessConfiguration {

  @Bean
  JdbcRoleBindingRepository roleBindingRepository(
      @Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcRoleBindingRepository(JdbcClient.create(dataSource));
  }

  @Bean
  JdbcDelegationRepository delegationRepository(
      @Qualifier("configDataSource") DataSource dataSource) {
    return new JdbcDelegationRepository(JdbcClient.create(dataSource));
  }

  @Bean
  OutboxAccessEvents accessEvents(
      @Qualifier("configDataSource") DataSource dataSource,
      @Value("${mission.config.outbox.registry-url:${okrkpi.inbox.registry.url:}}") String registry,
      ObjectProvider<OutboxPayloadSerializer> serializer,
      ObjectProvider<ObjectMapper> json) {
    return new OutboxAccessEvents(
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "config",
            serializer.getIfAvailable(() -> new ApicurioPayloadSerializer(registry, Map.of())),
            json.getIfAvailable(ObjectMapper::new),
            Clock.systemUtc()));
  }

  @Bean
  RoleBindingService roleBindingService(
      JdbcRoleBindingRepository roleBindingRepository,
      OutboxAccessEvents accessEvents,
      ObjectProvider<AccessDirectory> directory,
      JdbcCustomRoleRepository customRoleRepository) {
    return new RoleBindingService(
        roleBindingRepository,
        accessEvents,
        directory.getIfAvailable(),
        Clock.systemUtc(),
        customRoleRepository);
  }

  @Bean
  JdbcCustomRoleRepository customRoleRepository(
      @Qualifier("configDataSource") DataSource dataSource, ObjectProvider<ObjectMapper> json) {
    return new JdbcCustomRoleRepository(
        JdbcClient.create(dataSource), json.getIfAvailable(ObjectMapper::new));
  }

  @Bean
  ClasspathPermissionCatalog permissionCatalog(ObjectProvider<ObjectMapper> json) {
    return new ClasspathPermissionCatalog(json.getIfAvailable(ObjectMapper::new));
  }

  /**
   * CORE-08-18: định nghĩa vai trò tuỳ chỉnh ghi sang OPA khi OPA bật ({@code okrkpi.opa.enabled});
   * realm role Keycloak tạo khi có Admin API.
   */
  @Bean
  CustomRoleService customRoleService(
      JdbcCustomRoleRepository customRoleRepository,
      JdbcRoleBindingRepository roleBindingRepository,
      ClasspathPermissionCatalog permissionCatalog,
      OutboxAccessEvents accessEvents,
      ObjectProvider<ObjectMapper> json,
      ObjectProvider<KeycloakAdminClient> keycloak,
      @Value("${okrkpi.opa.enabled:false}") boolean opaEnabled,
      @Value("${okrkpi.opa.base-url:http://127.0.0.1:8181}") URI opaBaseUrl,
      @Value("${mission.config.keycloak-admin.base-url:}") String keycloakBaseUrl) {
    TenantRolePublisher publisher =
        opaEnabled
            ? new OpaTenantRolePublisher(
                opaBaseUrl, json.getIfAvailable(ObjectMapper::new), Duration.ofSeconds(5))
            : (tenant, roles) -> {};
    KeycloakAdminClient admin = keycloakBaseUrl.isBlank() ? null : keycloak.getIfAvailable();
    return new CustomRoleService(
        customRoleRepository,
        roleBindingRepository,
        permissionCatalog,
        publisher,
        admin == null ? null : new KeycloakRealmRoles(admin),
        accessEvents,
        Clock.systemUtc());
  }

  @Bean
  DelegationService delegationService(
      JdbcDelegationRepository delegationRepository,
      JdbcRoleBindingRepository roleBindingRepository,
      OutboxAccessEvents accessEvents,
      ObjectProvider<AccessDirectory> directory) {
    return new DelegationService(
        delegationRepository,
        roleBindingRepository,
        accessEvents,
        directory.getIfAvailable(),
        Clock.systemUtc());
  }

  @Bean
  AccessHistoryService accessHistoryService(
      JdbcRoleBindingRepository roleBindingRepository,
      JdbcDelegationRepository delegationRepository,
      ObjectProvider<AccessDirectory> directory) {
    return new AccessHistoryService(
        roleBindingRepository, delegationRepository, directory.getIfAvailable(), Clock.systemUtc());
  }

  /** Điểm nhận EVT-27 của {@link RealmRoleSyncListener}; nhận mọi loại tổng thể. */
  public static InboxEndpoint realmRoleSyncEndpoint(RealmRoleSyncListener listener) {
    try {
      return new InboxEndpoint(
          EventTopics.EVT_27_VAI_TRO_DA_THAY_DOI,
          RealmRoleSyncListener.CONSUMER,
          null,
          listener,
          RealmRoleSyncListener.class.getMethod("on", VaiTroDaThayDoi.class, InboxEnvelope.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Đồng bộ vai trò realm Keycloak theo EVT-27 và đối soát hằng đêm. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "mission.config.keycloak-admin", name = "base-url")
  static class RealmSync {

    @Bean
    RealmRoleSync realmRoleSync(
        JdbcRoleBindingRepository roleBindingRepository, KeycloakAdminClient keycloak) {
      return new RealmRoleSync(
          roleBindingRepository, new KeycloakRealmRoles(keycloak), Clock.systemUtc());
    }

    @Bean
    RealmRoleSyncListener realmRoleSyncListener(RealmRoleSync realmRoleSync) {
      return new RealmRoleSyncListener(realmRoleSync);
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "mission.config.access.realm-sync",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
    ConcurrentMessageListenerContainer<String, byte[]> realmRoleSyncContainer(
        RealmRoleSyncListener listener,
        @Qualifier("configDataSource") DataSource dataSource,
        @Qualifier("configTransactionManager") PlatformTransactionManager tm,
        InboxProperties properties,
        InboxPayloadDeserializer deserializer,
        InboxRetryDlqPublisher retryDlqPublisher,
        InboxMetrics metrics,
        @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
        @Value("${okrkpi.inbox.listener-auto-startup:true}") boolean autoStartup) {
      InboxMessageProcessor processor =
          PeriodConfiguration.configInboxProcessor(
              dataSource, tm, properties, deserializer, retryDlqPublisher, metrics);
      InboxEndpoint endpoint = realmRoleSyncEndpoint(listener);
      Map<String, Object> props = new HashMap<>();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "mission-core-" + RealmRoleSyncListener.CONSUMER);
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      ContainerProperties container =
          new ContainerProperties(EventTopics.EVT_27_VAI_TRO_DA_THAY_DOI);
      container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
      container.setMessageListener(
          (AcknowledgingMessageListener<String, byte[]>)
              (record, ack) -> processor.process(endpoint, record, ack));
      ConcurrentMessageListenerContainer<String, byte[]> c =
          new ConcurrentMessageListenerContainer<>(
              new DefaultKafkaConsumerFactory<>(props), container);
      c.setAutoStartup(autoStartup);
      return c;
    }
  }

  /**
   * Phát EVT-27 hết hạn và đối soát Keycloak; tắt bằng {@code
   * mission.config.access.jobs.enabled=false}.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "mission.config.access.jobs",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Maintenance {

    @Bean
    AccessMaintenanceJob accessMaintenanceJob(
        RoleBindingService roleBindingService,
        DelegationService delegationService,
        ObjectProvider<RealmRoleSync> realmRoleSync,
        ObjectProvider<TenantDirectory> directory,
        @Qualifier("configTransactionManager") PlatformTransactionManager tm,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new AccessMaintenanceJob(
          roleBindingService, delegationService, realmRoleSync, directory, tm, cell);
    }

    @Bean
    CustomRolePublishJob customRolePublishJob(
        CustomRoleService customRoleService,
        ObjectProvider<TenantDirectory> directory,
        @Value("${mission.config.period-opening.cell:${CELL_ID:}}") String cell) {
      return new CustomRolePublishJob(customRoleService, directory, cell);
    }
  }
}
