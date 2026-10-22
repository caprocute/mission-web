"""Thư viện dùng chung của bộ công cụ định cỡ (INF-03): nạp giả định GD-xx, dựng values đã chồng của một cell
theo thứ tự TL-05 Phụ lục A (mặc định chart con → vt-platform → mô hình → hồ sơ → cell) và danh mục workload với
đường dẫn values, CPU mỗi pod (TL-04 mục 5.2) và node pool (TL-05 "Số node của tầng ứng dụng")."""
import copy
import os
import re

import yaml

GOC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEPLOY = os.path.join(GOC, "deploy")
PLATFORM = os.path.join(DEPLOY, "charts", "vt-platform")
HO_SO = ["compact", "S", "M", "L"]
TEN_TEP = {"compact": "compact", "S": "s", "M": "m", "L": "l"}

# Workload: (tên, node pool, CPU yêu cầu mỗi pod, đường dẫn minReplicas, đường dẫn maxReplicas, khoá GD-07/GD-08).
# org-service (chart DV-04-01) chưa có HPA nên min và max cùng đọc replicaCount; search API và Kafka Connect chưa có
# workload riêng, số replica giữ ở search.api và kafka.connect của tệp hồ sơ.
WORKLOAD = [
    ("api-gateway", "ingress", 2, "api-gateway.apisix.autoscaling.minReplicas",
     "api-gateway.apisix.autoscaling.maxReplicas", "api-gateway"),
    ("mission-core", "app-general", 3, "mission-core.autoscaling.keda.minReplicas",
     "mission-core.autoscaling.keda.maxReplicas", "mission-core"),
    ("org-service", "app-general", 1, "org-service.replicaCount", "org-service.replicaCount", "org-service"),
    ("iam", "app-general", 2, "iam.mayChu.autoscaling.hpa.minReplicas", "iam.mayChu.autoscaling.hpa.maxReplicas", None),
    ("workflow-api", "app-general", 2, "workflow-service.api.hpa.minReplicas", "workflow-service.api.hpa.maxReplicas",
     "workflow-api"),
    ("evidence-api", "app-general", 2, "evidence-service.thanhPhan.evidence-service.autoscaling.hpa.minReplicas",
     "evidence-service.thanhPhan.evidence-service.autoscaling.hpa.maxReplicas", "evidence-api"),
    ("analytics-api", "app-general", 1, "analytics.thanhPhan.analytics-api.autoscaling.hpa.minReplicas",
     "analytics.thanhPhan.analytics-api.autoscaling.hpa.maxReplicas", "analytics-api"),
    ("cube", "app-general", 2, "analytics.thanhPhan.cube.autoscaling.hpa.minReplicas",
     "analytics.thanhPhan.cube.autoscaling.hpa.maxReplicas", None),
    ("superset", "app-general", 1, "analytics.thanhPhan.superset.autoscaling.hpa.minReplicas",
     "analytics.thanhPhan.superset.autoscaling.hpa.maxReplicas", None),
    ("search-api", "app-general", 1, "search.api.replicas.min", "search.api.replicas.max", "search-api"),
    ("notification", "app-general", 2, "notification-service.autoscaling.hpa.minReplicas",
     "notification-service.autoscaling.hpa.maxReplicas", None),
    ("integration-gateway", "app-general", 1, "integration-gateway.autoscaling.hpa.minReplicas",
     "integration-gateway.autoscaling.hpa.maxReplicas", "integration-gateway"),
    ("web-frontend", "app-general", 0.25, "web-frontend.autoscaling.hpa.minReplicas",
     "web-frontend.autoscaling.hpa.maxReplicas", None),
    ("scoring-service", "app-worker", 2, "scoring-service.autoscaling.keda.minReplicas",
     "scoring-service.autoscaling.keda.maxReplicas", None),
    ("document-service", "app-worker", 1, "document-service.thanhPhan.document-service.autoscaling.hpa.minReplicas",
     "document-service.thanhPhan.document-service.autoscaling.hpa.maxReplicas", None),
    ("gotenberg", "app-worker", 2, "document-service.thanhPhan.gotenberg.autoscaling.hpa.minReplicas",
     "document-service.thanhPhan.gotenberg.autoscaling.hpa.maxReplicas", None),
    ("clamav", "app-worker", 1, "evidence-service.thanhPhan.clamav.autoscaling.hpa.minReplicas",
     "evidence-service.thanhPhan.clamav.autoscaling.hpa.maxReplicas", None),
    ("workflow-executor", "app-worker", 2, "workflow-service.executor.keda.minReplicaCount",
     "workflow-service.executor.keda.maxReplicaCount", None),
    ("search-indexer", "app-worker", 1, "search.autoscaling.hpa.minReplicas", "search.autoscaling.hpa.maxReplicas", None),
    ("kafka-connect", "app-worker", 2, "kafka.connect.replicas.min", "kafka.connect.replicas.max", None),
    ("ledger-service", "security-zone", 1, "ledger-service.autoscaling.keda.minReplicas",
     "ledger-service.autoscaling.keda.maxReplicas", None),
    ("esign-service", "security-zone", 1, "esign-service.autoscaling.hpa.minReplicas",
     "esign-service.autoscaling.hpa.maxReplicas", None),
    ("ai-gateway", "security-zone", 1, "ai-gateway.thanhPhan.ai-gateway.autoscaling.hpa.minReplicas",
     "ai-gateway.thanhPhan.ai-gateway.autoscaling.hpa.maxReplicas", None),
]


def doc(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def gia_dinh(path=None):
    return doc(path or os.path.join(DEPLOY, "capacity", "assumptions.yaml"))


def gop(a, b):
    """Gộp sâu như Helm: map gộp theo khoá, giá trị khác (kể cả danh sách) bị thay."""
    kq = copy.deepcopy(a)
    for k, v in (b or {}).items():
        if isinstance(v, dict) and isinstance(kq.get(k), dict):
            kq[k] = gop(kq[k], v)
        else:
            kq[k] = copy.deepcopy(v)
    return kq


def lay(cay, duong, mac_dinh=None):
    for k in duong.split("."):
        if not isinstance(cay, dict) or k not in cay:
            return mac_dinh
        cay = cay[k]
    return cay


def dat(cay, duong, gia_tri):
    ks = duong.split(".")
    for k in ks[:-1]:
        cay = cay.setdefault(k, {})
    cay[ks[-1]] = gia_tri


def mac_dinh_chart_con():
    """values.yaml của các chart con khai trong Chart.yaml của vt-platform, đặt dưới khoá tên chart."""
    kq = {}
    for d in doc(os.path.join(PLATFORM, "Chart.yaml"))["dependencies"]:
        m = re.match(r"file://(.+)", d["repository"])
        tep = os.path.join(PLATFORM, m.group(1), "values.yaml")
        if os.path.exists(tep):
            v = doc(tep)
            v.pop("global", None)
            kq[d["name"]] = v
    return kq


def tep_ho_so(ho_so):
    return os.path.join(PLATFORM, "sizing", f"values-sizing-{TEN_TEP[ho_so]}.yaml")


def values(ho_so, mo_hinh="saas", them=()):
    """Values đã chồng theo TL-05 Phụ lục A cho một hồ sơ; `them` là các tệp chồng sau (values của cell, ca thử)."""
    lop = [os.path.join(PLATFORM, "values.yaml"), os.path.join(PLATFORM, f"values-{mo_hinh}.yaml"), tep_ho_so(ho_so)]
    kq = mac_dinh_chart_con()
    for tep in lop + [t for t in them if os.path.exists(t)]:
        kq = gop(kq, doc(tep))
    return kq


def cell(ten):
    return doc(os.path.join(DEPLOY, "cells", ten, "cell.yaml"))["cell"]


def values_cell(ten, mo_hinh="saas", them=()):
    c = cell(ten)
    return c, values(c["sizing"], mo_hinh, [os.path.join(DEPLOY, "cells", ten, "values.yaml"), *them])


def cac_cell():
    goc = os.path.join(DEPLOY, "cells")
    return sorted(t for t in os.listdir(goc) if os.path.exists(os.path.join(goc, t, "cell.yaml")))


def so_replica(v):
    """{tên workload: (min, max)} từ values đã chồng."""
    kq = {}
    for ten, _, _, pmin, pmax, _ in WORKLOAD:
        kq[ten] = (lay(v, pmin), lay(v, pmax))
    return kq
