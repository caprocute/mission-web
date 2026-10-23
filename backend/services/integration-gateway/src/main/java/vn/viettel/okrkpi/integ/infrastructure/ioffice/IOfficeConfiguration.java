package vn.viettel.okrkpi.integ.infrastructure.ioffice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.port.ConnectorSecrets;

/**
 * Bộ kết nối iOffice và hệ cùng mô hình theo danh sách mã {@code okrkpi.integ.ioffice.codes}: mỗi
 * mã là một bean {@link InboundConnector} có route kéo riêng. REST hay SOAP, địa chỉ, bí mật và
 * lịch bật tắt theo tenant nằm ở {@code integ.connector}, không ở cấu hình này. Tắt toàn bộ bằng
 * {@code okrkpi.integ.ioffice.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
public class IOfficeConfiguration {

  @Bean
  static Registrar integIOfficeRegistrar() {
    return new Registrar();
  }

  /** Đăng ký một máy khách dùng chung và một bộ kết nối cho mỗi mã cấu hình. */
  static class Registrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
      this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
      Binder binder = Binder.get(environment);
      if (!binder.bind("okrkpi.integ.ioffice.enabled", Boolean.class).orElse(true)) {
        return;
      }
      List<String> codes =
          binder
              .bind("okrkpi.integ.ioffice.codes", Bindable.listOf(String.class))
              .orElse(List.of("IOFFICE"));
      Duration period =
          binder.bind("okrkpi.integ.ioffice.period", Duration.class).orElse(Duration.ofSeconds(60));
      Duration timeout =
          binder
              .bind("okrkpi.integ.ioffice.request-timeout", Duration.class)
              .orElse(Duration.ofSeconds(5));
      ConfigurableListableBeanFactory beans = (ConfigurableListableBeanFactory) registry;
      registry.registerBeanDefinition(
          "integIOfficeClient",
          new RootBeanDefinition(
              IOfficeClient.class,
              () ->
                  new IOfficeClient(
                      HttpClient.newBuilder()
                          .connectTimeout(timeout)
                          .followRedirects(HttpClient.Redirect.NEVER)
                          .build(),
                      beans.getBeanProvider(ObjectMapper.class).getIfAvailable(ObjectMapper::new),
                      beans.getBean(ConnectorSecrets.class),
                      timeout)));
      for (String code : codes) {
        registry.registerBeanDefinition(
            "integIOfficeConnector" + code,
            new RootBeanDefinition(
                IOfficeConnector.class,
                () -> {
                  IOfficeClient client = beans.getBean(IOfficeClient.class);
                  return new IOfficeConnector(
                      code, client, period, new IOfficeTranslator(client.json()));
                }));
      }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
  }
}
