# Bảng đối chiếu values hạng L với TL-04 mục 5.2 (sinh bởi ci/doi-chieu-tl04.py, INF-02)

| Service | Workload | min | max | cpu | mem | startup | readiness | pdb | phatHanh |
|---|---|---|---|---|---|---|---|---|---|
| SVC-01 | apisix | 3 | 8 | ['2', '4'] | 2Gi | TCP 9080 | **TCP 9080** (TL: /apisix/status; đã ghi) | minAvailable 2 | RollingUpdate |
| SVC-02 | keycloak | 3 | 8 | ['2', '3'] | 3Gi | /health/started | /health/ready | minAvailable 2 | RollingUpdate |
| SVC-04 | org-service | 3 | **3** (TL: 6; đã ghi) | ['1', '2'] | 3Gi | 150 | actuator | minAvailable 2 | RollingUpdate |
| SVC-05 | mission-core | 6 | 24 | ['3', '4'] | 8Gi | 150 | actuator | maxUnavailable 1 | Rollout |
| SVC-06 | workflow-api | 3 | 8 | ['2', '3'] | 6Gi | **150** (TL: 180; đã ghi) | actuator | minAvailable 2 | **RollingUpdate** (TL: Rollout; đã ghi) |
| SVC-06 | workflow-executor | 2 | 10 | ['2', '3'] | **6Gi** (TL: 4Gi; đã ghi) | **150** (TL: 180; đã ghi) | actuator | **None** (TL: maxUnavailable 1; đã ghi) | RollingUpdate |
| SVC-07 | scoring-service | 2 | 24 | ['2', '3'] | 4Gi | 120 | actuator | maxUnavailable 25% | RollingUpdate |
| SVC-08 | evidence-service | 3 | 8 | ['2', '3'] | 3Gi | 120 | actuator | minAvailable 2 | RollingUpdate |
| SVC-08 | clamav-scanner | 2 | 12 | ['1', '2'] | 3Gi | 300 | TCP 3310 | maxUnavailable 25% | RollingUpdate |
| SVC-09 | ledger-service | 6 | 12 | ['1', '2'] | 1Gi | /healthz (TL không ghi) | /readyz | maxUnavailable 1 | RollingUpdate |
| SVC-10 | esign-service | 2 | 6 | ['1', '2'] | 2Gi | 120 | actuator | minAvailable 1 | RollingUpdate |
| SVC-11 | document-service | 2 | 20 | ['1', '2'] | 3Gi | 120 | actuator | maxUnavailable 25% | RollingUpdate |
| SVC-11 | gotenberg | 2 | 24 | ['2', '2'] | 3Gi | /health | /health | maxUnavailable 25% | RollingUpdate |
| SVC-12 | notification-service | 4 | 10 | ['2', '2'] | 2Gi | /healthz (TL không ghi) | /readyz | maxUnavailable 1 | RollingUpdate |
| SVC-13 | integration-gateway | 3 | 6 | ['1', '2'] | 3Gi | 150 | actuator | minAvailable 2 | RollingUpdate |
| SVC-14 | ai-gateway | 2 | 6 | ['1', '2'] | 2Gi | /healthz (TL không ghi) | /readyz | minAvailable 1 | RollingUpdate |
| SVC-14 | vllm | 2 | 4 | ['8', '16'] | 64Gi+1GPU | 600 | /health | maxUnavailable 1 | Recreate |
| SVC-15 | analytics-api | 2 | 6 | ['1', '2'] | 2Gi | 90 | actuator | minAvailable 1 | RollingUpdate |
| SVC-15 | cube | 3 | 8 | ['2', '4'] | 4Gi | /readyz | /readyz | minAvailable 2 | RollingUpdate |
| SVC-15 | superset | 2 | 4 | ['1', '2'] | 3Gi | /health | /health | minAvailable 1 | RollingUpdate |
| SVC-16 | search-indexer | 2 | 6 | ['1', '2'] | 2Gi | 90 | actuator | maxUnavailable 1 | RollingUpdate |
| SVC-17 | web-frontend | 2 | 4 | ['250m', '500m'] | 256Mi | /healthz | /healthz | minAvailable 1 | RollingUpdate |

Tổng: 22 workload, 7 ô lệch đã ghi lý do, 0 ô lệch chưa ghi.

