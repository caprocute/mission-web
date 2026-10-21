package vn.viettel.okrkpi.shared.datasource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.task.TaskDecorator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Cấu hình tích hợp Spring Modulith Event Publication Registry ({@code modulith.event_publication})
 * với cơ chế đa-tenant RLS ({@code FORCE ROW LEVEL SECURITY} theo {@code app.tenant_id}) và luồng
 * bất đồng bộ của {@code mission-core}.
 */
@Configuration(proxyBeanMethods = false)
public class ModulithTenantConfiguration {

  private static final String EVENT_PUBLICATION_REPO_INTERFACE =
      "org.springframework.modulith.events.core.EventPublicationRepository";

  /**
   * Lan truyền {@link TenantContext} từ luồng phát sự kiện sang luồng thực thi {@code @Async} của
   * {@code @ApplicationModuleListener}.
   */
  @Bean
  public TaskDecorator tenantPropagatingTaskDecorator() {
    return runnable -> {
      Optional<TenantId> captured = TenantContext.current();
      if (captured.isEmpty()) {
        return runnable;
      }
      TenantId tenantId = captured.get();
      return () -> TenantContext.run(tenantId, runnable);
    };
  }

  /**
   * Bọc {@code EventPublicationRepository} của Spring Modulith trong giao dịch gắn {@code SET LOCAL
   * app.tenant_id} trên {@code taskDataSource} để mọi thao tác {@code create}, {@code
   * markCompleted} và {@code findIncompletePublications*} đều thỏa mãn chính sách RLS của bảng
   * {@code modulith.event_publication}.
   */
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  public static BeanPostProcessor tenantAwareEventPublicationRepositoryPostProcessor(
      @Qualifier("transactionManager") ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName)
          throws BeansException {
        Class<?> repoInterface = resolveEventPublicationRepositoryInterface(bean.getClass());
        if (repoInterface == null) {
          return bean;
        }
        return Proxy.newProxyInstance(
            repoInterface.getClassLoader(),
            new Class<?>[] {repoInterface},
            new TenantAwareEventPublicationHandler(bean, transactionManagerProvider));
      }
    };
  }

  private static Class<?> resolveEventPublicationRepositoryInterface(Class<?> beanClass) {
    try {
      Class<?> iface =
          Class.forName(EVENT_PUBLICATION_REPO_INTERFACE, false, beanClass.getClassLoader());
      if (iface.isAssignableFrom(beanClass)) {
        return iface;
      }
    } catch (ClassNotFoundException ignored) {
      // Không có spring-modulith-events-core trên classpath
    }
    return null;
  }

  private static final class TenantAwareEventPublicationHandler
      implements java.lang.reflect.InvocationHandler {

    private final Object delegate;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;
    private final Set<TenantId> knownTenants = ConcurrentHashMap.newKeySet();
    private volatile TransactionTemplate transactionTemplate;

    private TenantAwareEventPublicationHandler(
        Object delegate, ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
      this.delegate = delegate;
      this.transactionManagerProvider = transactionManagerProvider;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return invokeRaw(method, args);
      }

      Optional<TenantId> resolvedTenant = TenantContext.current().or(() -> extractTenantId(args));
      resolvedTenant.ifPresent(knownTenants::add);

      if (resolvedTenant.isPresent()) {
        TenantId tenantId = resolvedTenant.get();
        return TenantContext.call(tenantId, () -> executeInTenantTx(() -> invokeRaw(method, args)));
      }

      if (method.getName().startsWith("findIncompletePublications")
          || method.getName().startsWith("findCompletedPublications")) {
        if (knownTenants.isEmpty()) {
          return List.of();
        }
        List<Object> aggregated = new ArrayList<>();
        for (TenantId tenantId : knownTenants) {
          Object result =
              TenantContext.call(tenantId, () -> executeInTenantTx(() -> invokeRaw(method, args)));
          if (result instanceof List<?> list) {
            aggregated.addAll(list);
          } else if (result instanceof Optional<?> opt && opt.isPresent()) {
            return opt;
          }
        }
        if (method.getReturnType() == Optional.class) {
          return Optional.empty();
        }
        return aggregated;
      }

      return invokeRaw(method, args);
    }

    private Object executeInTenantTx(SqlSupplier supplier) {
      TransactionTemplate tx = getTransactionTemplate();
      if (tx == null) {
        return supplier.get();
      }
      return tx.execute(status -> supplier.get());
    }

    private TransactionTemplate getTransactionTemplate() {
      TransactionTemplate local = this.transactionTemplate;
      if (local == null) {
        // Cùng trình quản lý giao dịch JPA mà JdbcEventPublicationRepository dùng (taskDataSource),
        // để giao dịch của repository tham gia giao dịch này thay vì mở giao dịch thứ hai.
        PlatformTransactionManager tm = transactionManagerProvider.getIfAvailable();
        if (tm != null) {
          local = new TransactionTemplate(tm);
          this.transactionTemplate = local;
        }
      }
      return local;
    }

    private Object invokeRaw(Method method, Object[] args) {
      try {
        return method.invoke(delegate, args);
      } catch (InvocationTargetException ex) {
        Throwable target = ex.getTargetException();
        if (target instanceof RuntimeException re) {
          throw re;
        }
        if (target instanceof Error err) {
          throw err;
        }
        throw new IllegalStateException(target);
      } catch (IllegalAccessException ex) {
        throw new IllegalStateException(ex);
      }
    }

    private static Optional<TenantId> extractTenantId(Object[] args) {
      if (args == null) {
        return Optional.empty();
      }
      for (Object arg : args) {
        Optional<TenantId> fromArg = extractTenantIdFromObject(arg);
        if (fromArg.isPresent()) {
          return fromArg;
        }
        try {
          Method getEvent = arg.getClass().getMethod("getEvent");
          Object event = getEvent.invoke(arg);
          Optional<TenantId> fromEvent = extractTenantIdFromObject(event);
          if (fromEvent.isPresent()) {
            return fromEvent;
          }
        } catch (ReflectiveOperationException ignored) {
          // Không phải TargetEventPublication
        }
      }
      return Optional.empty();
    }

    private static Optional<TenantId> extractTenantIdFromObject(Object candidate) {
      if (candidate == null) {
        return Optional.empty();
      }
      if (candidate instanceof TenantId tid) {
        return Optional.of(tid);
      }
      try {
        Method m = candidate.getClass().getMethod("tenantId");
        Object val = m.invoke(candidate);
        if (val instanceof TenantId tid) {
          return Optional.of(tid);
        }
      } catch (ReflectiveOperationException ignored) {
        // Sự kiện không có phương thức tenantId()
      }
      return Optional.empty();
    }
  }

  @FunctionalInterface
  private interface SqlSupplier {
    Object get();
  }
}
