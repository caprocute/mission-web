package vn.viettel.okrkpi.integ.infrastructure.anchor;

import java.util.Map;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import vn.viettel.okrkpi.integ.application.anchor.AnchorService;
import vn.viettel.okrkpi.integ.application.anchor.AnchorTarget;
import vn.viettel.okrkpi.integ.infrastructure.cloudevents.CloudEventsHttpSender;
import vn.viettel.okrkpi.integ.infrastructure.cloudevents.CloudEventsSenderFactory;

/**
 * Đăng ký adapter CloudEvents chiều ra cho từng đích neo theo cấu hình (số đích thay đổi theo
 * cell), với bộ xử lý xác nhận {@link AnchorReceipts} giữ biên nhận.
 */
public class AnchorSenderRegistrar
    implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    Map<AnchorTarget, String> targets =
        Binder.get(environment)
            .bind("okrkpi.integ.anchor.targets", Bindable.mapOf(AnchorTarget.class, String.class))
            .orElse(Map.of());
    ConfigurableListableBeanFactory beans = (ConfigurableListableBeanFactory) registry;
    targets.forEach(
        (target, code) -> {
          RootBeanDefinition def =
              new RootBeanDefinition(
                  CloudEventsHttpSender.class,
                  () ->
                      beans
                          .getBean(CloudEventsSenderFactory.class)
                          .sender(
                              code, new AnchorReceipts(() -> beans.getBean(AnchorService.class))));
          registry.registerBeanDefinition("integAnchorSender" + target.name(), def);
        });
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
}
