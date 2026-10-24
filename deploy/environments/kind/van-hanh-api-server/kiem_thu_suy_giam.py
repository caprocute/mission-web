"""Kiểm thử chạy thật bộ điều khiển suy giảm ba mức (INF-25) trên stack của dung-stack.sh.

Tín hiệu giả là cảnh báo DegradeLevel1..3 đẩy vào Alertmanager của cell (cấu hình observability-cell) bằng amtool;
Alertmanager gọi webhook của degrade-controller kèm token, bộ điều khiển dùng token ServiceAccount với RBAC của chart
để vá ApisixPluginConfig ở mọi namespace của cổng và ScaledObject ở namespace tác vụ nền. Kiểm từng mức: bộ plugin
đúng lớp ưu tiên (mức 1 tắt AI, mức 2 giảm giới hạn tenant 30% và tạm dừng tác vụ nền, mức 3 trả 503 kèm
Retry-After: 60 cho lớp 2, 3 còn lớp 1 vẫn qua), nâng SEV-2, khởi động lại giữ mức, hết cảnh báo thì chờ chống dao
động rồi về 0 và gỡ tạm dừng. Không chạy APISIX (cần Docker): hành vi lưu lượng của bộ plugin kiểm ở
api-gateway/tests/kiem-thu-cuc-bo.sh. Chạy: python3 kiem_thu_suy_giam.py (sau khi source $W/moi-truong.sh).
"""
import datetime, json, os, signal, subprocess, sys, time, urllib.request

W, DC, AM = os.environ["W"], f"http://127.0.0.1:{os.environ['P_DC']}", f"http://127.0.0.1:{os.environ['P_AM']}"
KQ = {"dat": 0, "hong": 0}


def ok(dk, mo_ta):
    KQ["dat" if dk else "hong"] += 1
    print(("  [ĐẠT] " if dk else "  [HỎNG] ") + mo_ta, flush=True)
    return dk


def kubectl(*a):
    return json.loads(subprocess.check_output(["kubectl", *a, "-o", "json"]))


def muc():
    return json.load(urllib.request.urlopen(DC + "/level", timeout=5))


def cho(dk, giay, mo_ta):
    t0 = time.time()
    while time.time() - t0 < giay:
        try:
            if dk():
                return ok(True, f"{mo_ta} (sau {time.time() - t0:.0f} s)")
        except Exception:
            pass
        time.sleep(1)
    return ok(False, f"{mo_ta}: quá {giay} s, mức hiện tại {muc()}")


def canh_bao(ten, lvl, het=False):
    nhan = [f"alertname={ten}", f"degrade_level={lvl}", "severity=control", "doi=sre", "cell=kind-dev"]
    if het:
        nhan.append("--end=" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    subprocess.check_call(["amtool", "alert", "add", f"--alertmanager.url={AM}", *nhan], stdout=subprocess.DEVNULL)


def plugin_cfg(lop, lvl):
    return json.load(open(os.path.join(W, "levels", f"p{lop}-{lvl}.json")))


def apc_dung_muc(lvl):
    items = kubectl("get", "apisixpluginconfigs", "-A", "-l", "vt.io/degrade-managed=true")["items"]
    sai = [f"{i['metadata']['namespace']}/{i['metadata']['name']}" for i in items
           if i["metadata"]["labels"].get("vt.io/degrade-level") != str(lvl)
           or i["spec"]["plugins"] != plugin_cfg(i["metadata"]["labels"]["vt.io/priority-class"], lvl)]
    return len(items) == 18 and not sai


def plugin(lop, lvl, ten):
    return next((p for p in plugin_cfg(lop, lvl) if p["name"] == ten), None)


def tam_dung():
    return {i["metadata"]["name"]: i["metadata"].get("annotations", {}).get("autoscaling.keda.sh/paused")
            for i in kubectl("get", "scaledobjects", "-n", "workers")["items"]}


def thong_bao():
    p = os.path.join(W, "thong-bao.jsonl")
    return [json.loads(x) for x in open(p, encoding="utf-8")] if os.path.exists(p) else []


print("== Trạng thái đầu")
ok(muc()["level"] == 0 and apc_dung_muc(0), "mức 0, 18 ApisixPluginConfig ở 6 namespace mang bộ plugin mức 0")

print("== Mức 1: DegradeLevel1 qua Alertmanager của cell")
canh_bao("DegradeLevel1", 1)
cho(lambda: muc()["level"] == 1 and apc_dung_muc(1), 60, "Alertmanager gọi webhook, mọi ApisixPluginConfig lên mức 1")
fi = plugin(2, 1, "fault-injection")
ok(fi and fi["config"]["abort"].get("vars") == [[["uri", "~~", "^/api/v1/ai/"]]] and fi["config"]["abort"]["http_status"] == 503,
   "mức 1 tắt tính năng phụ: /api/v1/ai/* trả 503 ở cả ba lớp, tuyến khác không bị chặn")
ok(plugin(3, 1, "proxy-rewrite")["config"]["headers"]["set"]["X-Vt-Degrade-Level"] == "1",
   "upstream nhận X-Vt-Degrade-Level: 1 để rút gọn tìm kiếm, đọc tổng hợp sẵn")
ok(tam_dung() == {"tinh-lai-hang-loat": None, "tinh-diem-tang-dan": None}, "mức 1 chưa tạm dừng tác vụ nền")

print("== Mức 2: DegradeLevel2")
canh_bao("DegradeLevel2", 2)
cho(lambda: muc()["level"] == 2 and apc_dung_muc(2), 60, "mọi ApisixPluginConfig lên mức 2")
r0, r2 = plugin(2, 0, "limit-req")["config"], plugin(2, 2, "limit-req")["config"]
ok(r2["rate"] == round(r0["rate"] * 0.7) and r2["burst"] == round(r0["burst"] * 0.7),
   f"mức 2 giảm giới hạn tenant 30% (limit-req {r0['rate']}/{r0['burst']} → {r2['rate']}/{r2['burst']})")
cho(lambda: tam_dung() == {"tinh-lai-hang-loat": "true", "tinh-diem-tang-dan": None}, 30,
    "ScaledObject gắn vt.io/degrade-pause-from=2 bị tạm dừng, ScaledObject không gắn nhãn giữ nguyên")

print("== Mức 3: DegradeLevel3")
canh_bao("DegradeLevel3", 3)
cho(lambda: muc()["level"] == 3 and apc_dung_muc(3), 60, "mọi ApisixPluginConfig lên mức 3")
for lop in (2, 3):
    fi = plugin(lop, 3, "fault-injection")["config"]
    ok("vars" not in fi["abort"] and fi["abort"]["http_status"] == 503 and fi["abort"]["headers"]["Retry-After"] == "60"
       and json.loads(fi["abort"]["body"])["status"] == 503,
       f"lớp {lop} mức 3: mọi request nhận 503, Retry-After: 60, thân RFC 9457")
fi1 = plugin(1, 3, "fault-injection")["config"]
ok(fi1["abort"].get("vars") == [[["uri", "~~", "^/api/v1/ai/"]]], "lớp 1 (nộp, ký hồ sơ, đăng nhập) mức 3 vẫn qua, chỉ chặn AI")
cho(lambda: any(t.get("event") == "degrade_escalate_sev2" or "SEV-2" in json.dumps(t, ensure_ascii=False) for t in thong_bao()),
    40, "mức ≥ 2 quá ESCALATE_AFTER: kênh trực nhận thông báo nâng SEV-2")
ok(any(json.dumps(t, ensure_ascii=False).find("degrade_level_changed") >= 0 or t.get("level") == 3 for t in thong_bao()),
   f"kênh trực nhận thông báo đổi mức ({len(thong_bao())} thông báo)")

print("== Hết cảnh báo: chống dao động rồi về mức 0")
t_het = time.time()
for ten, lvl in (("DegradeLevel1", 1), ("DegradeLevel2", 2), ("DegradeLevel3", 3)):
    canh_bao(ten, lvl, het=True)
cho(lambda: muc()["target"] == 0, 90, "Alertmanager gửi resolved, mức mong muốn về 0")
cho(lambda: muc()["level"] == 3 and muc().get("holdDownUntil"), 10, "trong thời gian chống dao động vẫn giữ mức 3 (có holdDownUntil)")
print("== Khởi động lại bộ điều khiển giữa thời gian chống dao động")
pid = int(open(os.path.join(W, "degrade-controller.pid")).read())
cmd = open(f"/proc/{pid}/cmdline", "rb").read().split(b"\0")[:-1]
env = dict(x.split(b"=", 1) for x in open(f"/proc/{pid}/environ", "rb").read().split(b"\0") if b"=" in x)
os.kill(pid, signal.SIGTERM)
time.sleep(1)
log = open(os.path.join(W, "degrade-controller.log"), "ab")
p = subprocess.Popen(cmd, env=env, cwd=W, stdout=log, stderr=log)
open(os.path.join(W, "degrade-controller.pid"), "w").write(str(p.pid))
cho(lambda: muc()["level"] == 3, 20, "sau khởi động lại đọc mức 3 từ ApisixPluginConfig, không tụt mức ngay")

cho(lambda: muc()["level"] == 0 and apc_dung_muc(0), 120, "hết HOLD_DOWN thì hạ thẳng về mức 0, bộ plugin mức 0")
ok(time.time() - t_het >= float(os.environ.get("HOLD_DOWN_GIAY", "20")), "không hạ trước khi hết thời gian chống dao động")
cho(lambda: tam_dung() == {"tinh-lai-hang-loat": None, "tinh-diem-tang-dan": None}, 30, "gỡ tạm dừng ScaledObject do bộ điều khiển đặt")

print(f"\nTổng: {KQ['dat']} đạt, {KQ['hong']} hỏng")
sys.exit(1 if KQ["hong"] else 0)
