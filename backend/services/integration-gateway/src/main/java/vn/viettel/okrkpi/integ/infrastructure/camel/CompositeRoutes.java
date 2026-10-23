package vn.viettel.okrkpi.integ.infrastructure.camel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.camel.CamelContext;
import org.apache.camel.RoutesBuilder;

/** Gộp nhiều route builder (một cho mỗi bộ kết nối) thành một bean cho Camel Spring Boot. */
public class CompositeRoutes implements RoutesBuilder {

  private final List<? extends RoutesBuilder> parts;

  public CompositeRoutes(List<? extends RoutesBuilder> parts) {
    this.parts = List.copyOf(parts);
  }

  @Override
  public void addRoutesToCamelContext(CamelContext context) throws Exception {
    for (RoutesBuilder p : parts) {
      p.addRoutesToCamelContext(context);
    }
  }

  @Override
  public void addTemplatedRoutesToCamelContext(CamelContext context) throws Exception {
    for (RoutesBuilder p : parts) {
      p.addTemplatedRoutesToCamelContext(context);
    }
  }

  @Override
  public Set<String> updateRoutesToCamelContext(CamelContext context) throws Exception {
    Set<String> ids = new HashSet<>();
    for (RoutesBuilder p : parts) {
      ids.addAll(p.updateRoutesToCamelContext(context));
    }
    return ids;
  }
}
