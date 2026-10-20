package vn.viettel.okrkpi.shared.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import vn.viettel.okrkpi.shared.datasource.MissionCoreDataSourceProperties.ModulePoolProperties;
import vn.viettel.okrkpi.shared.datasource.MissionCoreDataSourceProperties.ReadReplicaProperties;
import vn.viettel.okrkpi.shared.tenant.TenantDataSourceTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * Cấu hình tám {@link DataSource}, tám {@link LocalContainerEntityManagerFactoryBean}, tám {@link
 * ModuleTenantJpaTransactionManager} cho các mô-đun nghiệp vụ (MOD-01..MOD-08) và một {@link
 * DataSource} riêng cho {@code org_replica} (TL-01 L760–902, TL-03, TL-04).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  MissionCoreDataSourceProperties.class,
  TenantProperties.class,
  JpaProperties.class
})
public class ModuleDataSourceConfiguration {

  @Bean
  public RecentWriteTracker recentWriteTracker() {
    return new RecentWriteTracker();
  }

  // ==================== 1. DataSource cho 8 mô-đun nghiệp vụ ====================

  @Bean("okrDataSource")
  public ReadWriteRoutingDataSource okrDataSource(
      MissionCoreDataSourceProperties props, RecentWriteTracker tracker) {
    return buildModuleDataSource(ModuleName.OKR, props, tracker);
  }

  @Primary
  @Bean({"taskDataSource", "dataSource"})
  public ReadWriteRoutingDataSource taskDataSource(
      MissionCoreDataSourceProperties props, RecentWriteTracker tracker) {
    validateBudget(props);
    return buildModuleDataSource(ModuleName.TASK, props, tracker);
  }

  @Bean("productDataSource")
  public ReadWriteRoutingDataSource productDataSource(
      MissionCoreDataSourceProperties props, RecentWriteTracker tracker) {
    return buildModuleDataSource(ModuleName.PRODUCT, props, tracker);
  }

  @Bean("appraisalDataSource")
  public ReadWriteRoutingDataSource appraisalDataSource(
      MissionCoreDataSourceProperties props, RecentWriteTracker tracker) {
    return buildModuleDataSource(ModuleName.APPRAISAL, props, tracker);
  }

  @Bean("policyDataSource")
  public ReadWriteRoutingDataSource policyDataSource(
      MissionCoreDataSourceProperties props, RecentWriteTracker tracker) {
    return buildModuleDataSource(ModuleName.POLICY, props, tracker);
  }

  @Bean("dossierDataSource")
  public ReadWriteRoutingDataSource dossierDataSource(
      MissionCoreDataSourceProperties props, RecentWriteTracker tracker) {
    return buildModuleDataSource(ModuleName.DOSSIER, props, tracker);
  }

  @Bean("oversightDataSource")
  public ReadWriteRoutingDataSource oversightDataSource(
      MissionCoreDataSourceProperties props, RecentWriteTracker tracker) {
    return buildModuleDataSource(ModuleName.OVERSIGHT, props, tracker);
  }

  @Bean("configDataSource")
  public ReadWriteRoutingDataSource configDataSource(
      MissionCoreDataSourceProperties props, RecentWriteTracker tracker) {
    return buildModuleDataSource(ModuleName.CONFIG, props, tracker);
  }

  @Bean("orgReplicaDataSource")
  public HikariDataSource orgReplicaDataSource(MissionCoreDataSourceProperties props) {
    ModulePoolProperties configured = props.getDatasources().get("org_replica");
    if (configured == null) {
      configured = props.getDatasources().get("org-replica");
    }
    if (configured == null) {
      ModulePoolProperties taskPool = props.poolFor(ModuleName.TASK);
      configured = new ModulePoolProperties();
      configured.setUrl(taskPool.getUrl());
      configured.setUsername("svc_org_replica");
      configured.setPassword(taskPool.getPassword());
      configured.setMaxPoolSize(4);
      configured.setMinIdle(1);
    }
    int maxPool = configured.getMaxPoolSize() != null ? configured.getMaxPoolSize() : 4;
    int minIdle = configured.getMinIdle() != null ? configured.getMinIdle() : Math.min(1, maxPool);
    return createHikariPool(
        "mission-core-org_replica-rw",
        "mission-core-org_replica",
        configured.getUrl(),
        configured.getUsername(),
        configured.getPassword(),
        maxPool,
        minIdle,
        configured);
  }

  @Bean("moduleDataSources")
  public Map<ModuleName, ReadWriteRoutingDataSource> moduleDataSources(
      @Qualifier("okrDataSource") ReadWriteRoutingDataSource okr,
      @Qualifier("taskDataSource") ReadWriteRoutingDataSource task,
      @Qualifier("productDataSource") ReadWriteRoutingDataSource product,
      @Qualifier("appraisalDataSource") ReadWriteRoutingDataSource appraisal,
      @Qualifier("policyDataSource") ReadWriteRoutingDataSource policy,
      @Qualifier("dossierDataSource") ReadWriteRoutingDataSource dossier,
      @Qualifier("oversightDataSource") ReadWriteRoutingDataSource oversight,
      @Qualifier("configDataSource") ReadWriteRoutingDataSource config) {
    Map<ModuleName, ReadWriteRoutingDataSource> map = new EnumMap<>(ModuleName.class);
    map.put(ModuleName.OKR, okr);
    map.put(ModuleName.TASK, task);
    map.put(ModuleName.PRODUCT, product);
    map.put(ModuleName.APPRAISAL, appraisal);
    map.put(ModuleName.POLICY, policy);
    map.put(ModuleName.DOSSIER, dossier);
    map.put(ModuleName.OVERSIGHT, oversight);
    map.put(ModuleName.CONFIG, config);
    return Map.copyOf(map);
  }

  // ==================== 2. EntityManagerFactory cho 8 mô-đun ====================

  @Bean("okrEntityManagerFactory")
  public LocalContainerEntityManagerFactoryBean okrEntityManagerFactory(
      @Qualifier("okrDataSource") DataSource dataSource, JpaProperties jpaProperties) {
    return buildEntityManagerFactory(ModuleName.OKR, dataSource, jpaProperties);
  }

  @Primary
  @Bean({"taskEntityManagerFactory", "entityManagerFactory"})
  public LocalContainerEntityManagerFactoryBean taskEntityManagerFactory(
      @Qualifier("taskDataSource") DataSource dataSource, JpaProperties jpaProperties) {
    return buildEntityManagerFactory(ModuleName.TASK, dataSource, jpaProperties);
  }

  @Bean("productEntityManagerFactory")
  public LocalContainerEntityManagerFactoryBean productEntityManagerFactory(
      @Qualifier("productDataSource") DataSource dataSource, JpaProperties jpaProperties) {
    return buildEntityManagerFactory(ModuleName.PRODUCT, dataSource, jpaProperties);
  }

  @Bean("appraisalEntityManagerFactory")
  public LocalContainerEntityManagerFactoryBean appraisalEntityManagerFactory(
      @Qualifier("appraisalDataSource") DataSource dataSource, JpaProperties jpaProperties) {
    return buildEntityManagerFactory(ModuleName.APPRAISAL, dataSource, jpaProperties);
  }

  @Bean("policyEntityManagerFactory")
  public LocalContainerEntityManagerFactoryBean policyEntityManagerFactory(
      @Qualifier("policyDataSource") DataSource dataSource, JpaProperties jpaProperties) {
    return buildEntityManagerFactory(ModuleName.POLICY, dataSource, jpaProperties);
  }

  @Bean("dossierEntityManagerFactory")
  public LocalContainerEntityManagerFactoryBean dossierEntityManagerFactory(
      @Qualifier("dossierDataSource") DataSource dataSource, JpaProperties jpaProperties) {
    return buildEntityManagerFactory(ModuleName.DOSSIER, dataSource, jpaProperties);
  }

  @Bean("oversightEntityManagerFactory")
  public LocalContainerEntityManagerFactoryBean oversightEntityManagerFactory(
      @Qualifier("oversightDataSource") DataSource dataSource, JpaProperties jpaProperties) {
    return buildEntityManagerFactory(ModuleName.OVERSIGHT, dataSource, jpaProperties);
  }

  @Bean("configEntityManagerFactory")
  public LocalContainerEntityManagerFactoryBean configEntityManagerFactory(
      @Qualifier("configDataSource") DataSource dataSource, JpaProperties jpaProperties) {
    return buildEntityManagerFactory(ModuleName.CONFIG, dataSource, jpaProperties);
  }

  // ==================== 3. TransactionManager cho 8 mô-đun + org_replica ====================

  @Bean("okrTransactionManager")
  public ModuleTenantJpaTransactionManager okrTransactionManager(
      @Qualifier("okrEntityManagerFactory") EntityManagerFactory emf,
      TenantProperties tenantProps,
      RecentWriteTracker tracker) {
    return new ModuleTenantJpaTransactionManager(ModuleName.OKR, emf, tenantProps, tracker);
  }

  @Primary
  @Bean({"taskTransactionManager", "transactionManager"})
  public ModuleTenantJpaTransactionManager taskTransactionManager(
      @Qualifier("taskEntityManagerFactory") EntityManagerFactory emf,
      TenantProperties tenantProps,
      RecentWriteTracker tracker) {
    return new ModuleTenantJpaTransactionManager(ModuleName.TASK, emf, tenantProps, tracker);
  }

  @Bean("productTransactionManager")
  public ModuleTenantJpaTransactionManager productTransactionManager(
      @Qualifier("productEntityManagerFactory") EntityManagerFactory emf,
      TenantProperties tenantProps,
      RecentWriteTracker tracker) {
    return new ModuleTenantJpaTransactionManager(ModuleName.PRODUCT, emf, tenantProps, tracker);
  }

  @Bean("appraisalTransactionManager")
  public ModuleTenantJpaTransactionManager appraisalTransactionManager(
      @Qualifier("appraisalEntityManagerFactory") EntityManagerFactory emf,
      TenantProperties tenantProps,
      RecentWriteTracker tracker) {
    return new ModuleTenantJpaTransactionManager(ModuleName.APPRAISAL, emf, tenantProps, tracker);
  }

  @Bean("policyTransactionManager")
  public ModuleTenantJpaTransactionManager policyTransactionManager(
      @Qualifier("policyEntityManagerFactory") EntityManagerFactory emf,
      TenantProperties tenantProps,
      RecentWriteTracker tracker) {
    return new ModuleTenantJpaTransactionManager(ModuleName.POLICY, emf, tenantProps, tracker);
  }

  @Bean("dossierTransactionManager")
  public ModuleTenantJpaTransactionManager dossierTransactionManager(
      @Qualifier("dossierEntityManagerFactory") EntityManagerFactory emf,
      TenantProperties tenantProps,
      RecentWriteTracker tracker) {
    return new ModuleTenantJpaTransactionManager(ModuleName.DOSSIER, emf, tenantProps, tracker);
  }

  @Bean("oversightTransactionManager")
  public ModuleTenantJpaTransactionManager oversightTransactionManager(
      @Qualifier("oversightEntityManagerFactory") EntityManagerFactory emf,
      TenantProperties tenantProps,
      RecentWriteTracker tracker) {
    return new ModuleTenantJpaTransactionManager(ModuleName.OVERSIGHT, emf, tenantProps, tracker);
  }

  @Bean("configTransactionManager")
  public ModuleTenantJpaTransactionManager configTransactionManager(
      @Qualifier("configEntityManagerFactory") EntityManagerFactory emf,
      TenantProperties tenantProps,
      RecentWriteTracker tracker) {
    return new ModuleTenantJpaTransactionManager(ModuleName.CONFIG, emf, tenantProps, tracker);
  }

  @Bean("orgReplicaTransactionManager")
  public TenantDataSourceTransactionManager orgReplicaTransactionManager(
      @Qualifier("orgReplicaDataSource") DataSource orgReplicaDataSource,
      TenantProperties tenantProps) {
    return new TenantDataSourceTransactionManager(orgReplicaDataSource, tenantProps);
  }

  // ==================== Helpers ====================

  private static void validateBudget(MissionCoreDataSourceProperties props) {
    Map<ModuleName, Integer> sizes = new EnumMap<>(ModuleName.class);
    for (ModuleName module : ModuleName.values()) {
      ModulePoolProperties pool = props.poolFor(module);
      sizes.put(module, pool.getMaxPoolSize());
    }
    PgBouncerJdbcUrlFinalizer.validateTotalPoolBudget(sizes);
  }

  private static ReadWriteRoutingDataSource buildModuleDataSource(
      ModuleName module, MissionCoreDataSourceProperties props, RecentWriteTracker tracker) {
    ModulePoolProperties pool = props.poolFor(module);
    int maxPool = pool.getMaxPoolSize();
    int minIdle = Math.min(pool.getMinIdle(), maxPool);
    HikariDataSource primary =
        createHikariPool(
            module.applicationName() + "-rw",
            module.applicationName(),
            pool.getUrl(),
            pool.getUsername(),
            pool.getPassword(),
            maxPool,
            minIdle,
            pool);

    ReadReplicaProperties replicaProps = pool.getReadReplica();
    HikariDataSource replica = null;
    if (replicaProps != null
        && replicaProps.isEnabled()
        && replicaProps.getUrl() != null
        && !replicaProps.getUrl().isBlank()) {
      int roMax = replicaProps.getMaxPoolSize() != null ? replicaProps.getMaxPoolSize() : maxPool;
      int roMin =
          replicaProps.getMinIdle() != null
              ? Math.min(replicaProps.getMinIdle(), roMax)
              : Math.min(minIdle, roMax);
      String roUser =
          (replicaProps.getUsername() != null && !replicaProps.getUsername().isBlank())
              ? replicaProps.getUsername()
              : "ro_" + module.schema();
      String roPwd =
          replicaProps.getPassword() != null ? replicaProps.getPassword() : pool.getPassword();
      replica =
          createHikariPool(
              module.applicationName() + "-ro",
              module.applicationName(),
              replicaProps.getUrl(),
              roUser,
              roPwd,
              roMax,
              roMin,
              pool);
    }

    long maxLag =
        replicaProps != null
            ? replicaProps.getMaxLagMs()
            : RecentWriteTracker.DEFAULT_MAX_REPLICA_LAG_MS;
    boolean replicaEnabled = replica != null;
    return new ReadWriteRoutingDataSource(
        module, primary, replica, replicaEnabled, maxLag, tracker);
  }

  private static HikariDataSource createHikariPool(
      String poolName,
      String appName,
      String rawUrl,
      String username,
      String password,
      int maxPoolSize,
      int minIdle,
      ModulePoolProperties timeouts) {
    String finalUrl = PgBouncerJdbcUrlFinalizer.ensurePgBouncerParams(rawUrl, appName);
    HikariConfig cfg = new HikariConfig();
    cfg.setPoolName(poolName);
    cfg.setJdbcUrl(finalUrl);
    cfg.setUsername(username);
    cfg.setPassword(password != null ? password : "");
    cfg.setMaximumPoolSize(maxPoolSize);
    cfg.setMinimumIdle(minIdle);
    cfg.setConnectionTimeout(timeouts.getConnectionTimeout());
    cfg.setMaxLifetime(timeouts.getMaxLifetime());
    cfg.setIdleTimeout(timeouts.getIdleTimeout());
    cfg.setLeakDetectionThreshold(timeouts.getLeakDetectionThreshold());
    return new HikariDataSource(cfg);
  }

  private static LocalContainerEntityManagerFactoryBean buildEntityManagerFactory(
      ModuleName module, DataSource dataSource, JpaProperties jpaProperties) {
    LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
    emf.setDataSource(dataSource);
    emf.setPersistenceUnitName(module.schema() + "-pu");
    emf.setPackagesToScan(module.entityPackage());
    HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
    vendorAdapter.setShowSql(jpaProperties.isShowSql());
    emf.setJpaVendorAdapter(vendorAdapter);

    Map<String, Object> props = new LinkedHashMap<>(jpaProperties.getProperties());
    props.putIfAbsent("hibernate.hbm2ddl.auto", "validate");
    props.putIfAbsent("hibernate.jdbc.batch_size", "50");
    props.putIfAbsent("hibernate.order_updates", "true");
    props.putIfAbsent("hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS", "500");
    props.putIfAbsent("hibernate.default_schema", module.schema());
    emf.setJpaPropertyMap(props);
    return emf;
  }
}
