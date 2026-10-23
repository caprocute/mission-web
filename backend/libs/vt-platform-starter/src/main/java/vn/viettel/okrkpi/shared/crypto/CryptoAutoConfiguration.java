package vn.viettel.okrkpi.shared.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.viettel.okrkpi.shared.crypto.bcy.BcyCryptoConfiguration;

/**
 * Auto-configuration cho mã hoá trường phong bì C2, {@link DekCache}, {@link DekService}, {@link
 * OpenBaoTransitClient}, {@link FieldEncryptor} và {@link BlindIndexer} (NEN-015, ADR-06, ADR-17,
 * ADR-30).
 */
@AutoConfiguration
@EnableConfigurationProperties(CryptoProperties.class)
@Import(BcyCryptoConfiguration.class)
@ConditionalOnProperty(
    prefix = "okrkpi.crypto",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CryptoAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public CryptoProvider cryptoProvider() {
    return new JcaAesGcmCryptoProvider();
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public DekCache dekCache(CryptoProperties properties, ObjectProvider<Clock> clockProvider) {
    Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
    return new DekCache(properties.getDekCacheMaxSize(), properties.getDekCacheTtl(), clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public OpenBaoTransitClient openBaoTransitClient(
      CryptoProperties properties,
      ObjectProvider<ObjectMapper> objectMapperProvider,
      ObjectProvider<Clock> clockProvider) {
    ObjectMapper mapper =
        objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
    Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
    return new OpenBaoTransitClient(properties, mapper, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public DekService dekService(
      ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
      OpenBaoTransitClient transitClient,
      CryptoProvider cryptoProvider,
      DekCache dekCache,
      ObjectProvider<Clock> clockProvider) {
    Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
    return new DekService(
        jdbcTemplateProvider.getIfAvailable(), transitClient, cryptoProvider, dekCache, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public FieldEncryptor fieldEncryptor(DekService dekService, CryptoProvider cryptoProvider) {
    FieldEncryptor encryptor = new FieldEncryptor(dekService, cryptoProvider);
    EncryptionContextHolder.registerFieldEncryptor(encryptor);
    return encryptor;
  }

  @Bean
  @ConditionalOnMissingBean
  public BlindIndexer blindIndexer(DekService dekService, CryptoProvider cryptoProvider) {
    return new BlindIndexer(dekService, cryptoProvider);
  }
}
