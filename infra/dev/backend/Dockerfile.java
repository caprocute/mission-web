# syntax=docker/dockerfile:1
# Ảnh dev dùng chung cho service Java của profile `backend` (INF-22). Ngữ cảnh dựng là thư mục mô-đun Maven
# (ví dụ backend/mission-core) đã có jar Spring Boot trong target/ (scripts/deploy-backend.sh chạy `mvnw package`).
# Khác ảnh phát hành (Dockerfile của từng service, distroless): nền eclipse-temurin JRE có curl để compose kiểm
# /actuator/health/readiness trên cổng quản trị; vẫn chạy không root và tách lớp jar như ảnh phát hành.
FROM eclipse-temurin:21-jre-jammy AS extract
WORKDIR /workspace
COPY target/*.jar ./jars/
# Chọn jar Spring Boot đã đóng gói lại (mission-core gắn classifier exec, jar thường là thư viện mỏng): thử lần lượt,
# jar nào layertools đọc được thì dùng; bỏ *-plain, *-sources, *-javadoc, *-tests. Tách lớp để dựng lại nhanh.
RUN set -eu; \
    for jar in $(ls jars/*-exec.jar 2>/dev/null) $(ls jars/*.jar | grep -Ev -- '-(exec|plain|sources|javadoc|tests)\.jar$'); do \
      if java -Djarmode=layertools -jar "${jar}" list >/dev/null 2>&1; then \
        java -Djarmode=layertools -jar "${jar}" extract --destination layers; exit 0; \
      fi; \
    done; \
    echo "không thấy jar Spring Boot trong target/" >&2; exit 1

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=extract /workspace/layers/dependencies/ ./
COPY --from=extract /workspace/layers/spring-boot-loader/ ./
COPY --from=extract /workspace/layers/snapshot-dependencies/ ./
COPY --from=extract /workspace/layers/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-XX:+UseZGC", "-Djava.io.tmpdir=/tmp", "org.springframework.boot.loader.launch.JarLauncher"]
