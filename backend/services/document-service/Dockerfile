# NEN-019 · TL-04 WI-20 "Nguyên tắc cấu hình chung".
# Ảnh sản xuất chính thức đóng gói bằng Jib (gcr.io/distroless/java21-debian12:nonroot, UID 10001).
# Dockerfile đa tầng này dùng khi cần đóng gói bằng Docker CLI cục bộ, vẫn đảm bảo JRE 21 tối giản
# và chạy bằng UID/GID 10001:10001 không đặc quyền.

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
USER 10001:10001
COPY --from=builder /workspace/dependencies/ ./
COPY --from=builder /workspace/spring-boot-loader/ ./
COPY --from=builder /workspace/snapshot-dependencies/ ./
COPY --from=builder /workspace/application/ ./

EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-XX:+UseZGC", "-Djava.io.tmpdir=/tmp", "org.springframework.boot.loader.launch.JarLauncher"]
