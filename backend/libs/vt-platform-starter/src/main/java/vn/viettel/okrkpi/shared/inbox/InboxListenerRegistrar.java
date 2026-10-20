package vn.viettel.okrkpi.shared.inbox;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * NEN-012 · Quét các phương thức đánh dấu {@link InboxListener}, dựng {@link InboxEndpoint} và quản
 * lý vòng đời các {@link ConcurrentMessageListenerContainer} của Spring Kafka với {@code
 * AckMode.MANUAL_IMMEDIATE}.
 */
public class InboxListenerRegistrar implements BeanPostProcessor, SmartLifecycle {

  private final InboxMessageProcessor processor;
  private final ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>>
      containerFactoryProvider;
  private final boolean autoStartup;
  private final List<InboxEndpoint> endpoints = new CopyOnWriteArrayList<>();
  private final Map<String, InboxEndpoint> byConsumerAndTopic = new ConcurrentHashMap<>();
  private final List<ConcurrentMessageListenerContainer<String, byte[]>> containers =
      new CopyOnWriteArrayList<>();
  private volatile boolean running;

  public InboxListenerRegistrar(
      InboxMessageProcessor processor,
      ObjectProvider<ConcurrentKafkaListenerContainerFactory<String, byte[]>>
          containerFactoryProvider,
      boolean autoStartup) {
    this.processor = Objects.requireNonNull(processor, "processor");
    this.containerFactoryProvider =
        Objects.requireNonNull(containerFactoryProvider, "containerFactoryProvider");
    this.autoStartup = autoStartup;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    Class<?> targetClass = AopUtils.getTargetClass(bean);
    Map<Method, InboxListener> annotatedMethods =
        MethodIntrospector.selectMethods(
            targetClass,
            (MethodIntrospector.MetadataLookup<InboxListener>)
                method -> AnnotatedElementUtils.findMergedAnnotation(method, InboxListener.class));
    if (annotatedMethods.isEmpty()) {
      return bean;
    }
    for (Map.Entry<Method, InboxListener> entry : annotatedMethods.entrySet()) {
      InboxEndpoint endpoint = InboxEndpoint.fromMethod(bean, entry.getKey(), entry.getValue());
      registerEndpoint(endpoint);
    }
    return bean;
  }

  public void registerEndpoint(InboxEndpoint endpoint) {
    Objects.requireNonNull(endpoint, "endpoint");
    String key = endpoint.consumer() + "@" + endpoint.topic();
    InboxEndpoint existing = byConsumerAndTopic.putIfAbsent(key, endpoint);
    if (existing != null) {
      throw new IllegalStateException("Trùng đăng ký @InboxListener cho consumer và topic: " + key);
    }
    endpoints.add(endpoint);
  }

  public List<InboxEndpoint> endpoints() {
    return List.copyOf(endpoints);
  }

  public List<ConcurrentMessageListenerContainer<String, byte[]>> containers() {
    return List.copyOf(containers);
  }

  @Override
  public boolean isAutoStartup() {
    return autoStartup;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
        containerFactoryProvider.getIfAvailable();
    if (factory != null && containers.isEmpty()) {
      for (InboxEndpoint endpoint : endpoints) {
        ConcurrentMessageListenerContainer<String, byte[]> container =
            createContainer(factory, endpoint);
        containers.add(container);
      }
    }
    for (ConcurrentMessageListenerContainer<String, byte[]> container : containers) {
      if (!container.isRunning()) {
        container.start();
      }
    }
    running = true;
  }

  @Override
  public synchronized void stop() {
    for (ConcurrentMessageListenerContainer<String, byte[]> container :
        new ArrayList<>(containers)) {
      if (container.isRunning()) {
        container.stop();
      }
    }
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private ConcurrentMessageListenerContainer<String, byte[]> createContainer(
      ConcurrentKafkaListenerContainerFactory<String, byte[]> factory, InboxEndpoint endpoint) {
    ConcurrentMessageListenerContainer<String, byte[]> container =
        factory.createContainer(endpoint.topic());
    ContainerProperties props = container.getContainerProperties();
    props.setGroupId(endpoint.consumer());
    props.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    props.setMessageListener(
        (AcknowledgingMessageListener<String, byte[]>)
            (record, acknowledgment) -> processor.process(endpoint, record, acknowledgment));
    container.setBeanName("inboxListenerContainer-" + endpoint.consumer() + "-" + endpoint.topic());
    return container;
  }
}
