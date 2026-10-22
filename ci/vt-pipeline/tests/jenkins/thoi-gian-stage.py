#!/usr/bin/env python3
"""In thời gian và kết quả từng stage của một lần chạy Jenkinsfile Runner (INF-05).

    thoi-gian-stage.py <JENKINS_HOME của lần chạy>

Đọc dữ liệu flow node của bản dựng (jobs/*/builds/1/workflow/*.xml hoặc workflow-fallback). Mỗi stage
(kể cả nhánh song song) là một StepStartNode có LabelAction; thời gian là hiệu TimingAction của
StepEndNode tương ứng và của chính nó. Kết quả: stage<TAB>kết quả<TAB>giây.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def doc_nut(thu_muc):
    nut = {}
    for p in glob.glob(os.path.join(thu_muc, '*.xml')):
        try:
            goc = ET.parse(p).getroot()
        except ET.ParseError:
            continue
        for n in goc.iter():
            if n.tag != 'node' and not n.tag.endswith('Node'):
                continue
            id_ = n.findtext('id')
            if not id_:
                continue
            nut[id_] = (n, goc)
    return nut


def main():
    home = sys.argv[1]
    tm = glob.glob(os.path.join(home, 'jobs', '*', 'builds', '*', 'workflow'))
    if not tm:
        tm = glob.glob(os.path.join(home, 'jobs', '*', 'builds', '*', 'workflow-fallback'))
    if not tm:
        print('không tìm thấy dữ liệu flow node', file=sys.stderr)
        return 1
    hang = []
    tep = sorted(glob.glob(os.path.join(tm[0], '*.xml')), key=lambda p: int(re.sub(r'\D', '', os.path.basename(p)) or 0))
    nut = {}
    for p in tep:
        try:
            goc = ET.parse(p).getroot()
        except ET.ParseError:
            continue
        node = goc.find('node')
        if node is None:
            continue
        nut[node.findtext('id')] = (node, goc)

    def thoi_diem(goc):
        for a in goc.iter():
            if a.tag.endswith('TimingAction'):
                t = a.findtext('startTime')
                if t:
                    return int(t)
        return None

    ket_thuc = {}
    for id_, (node, goc) in nut.items():
        s = node.findtext('startId')
        if s:
            ket_thuc[s] = (id_, goc)
    for id_, (node, goc) in sorted(nut.items(), key=lambda kv: int(kv[0])):
        nhan = None
        for a in goc.iter():
            if a.tag.endswith('LabelAction') and a.findtext('displayName'):
                nhan = a.findtext('displayName')
        if not nhan or nhan.startswith('Branch: '):
            # Nhánh song song có cả "Branch: X" và stage X; giữ stage
            if nhan and nhan.startswith('Branch: '):
                continue
            if not nhan:
                continue
        bd = thoi_diem(goc)
        kt_id = ket_thuc.get(id_)
        kt = thoi_diem(kt_id[1]) if kt_id else None
        loi = 'SUCCESS'
        g_kt = kt_id[1] if kt_id else goc
        for a in g_kt.iter():
            if a.tag.endswith('ErrorAction'):
                loi = 'FAILURE'
        # Stage bị bỏ qua có TagsAction STAGE_STATUS = SKIPPED_FOR_CONDITIONAL hoặc SKIPPED_FOR_FAILURE
        for a in goc.iter():
            if a.tag.endswith('TagsAction'):
                txt = ET.tostring(a, encoding='unicode')
                if 'SKIPPED_FOR_CONDITIONAL' in txt:
                    loi = 'BỎ QUA (điều kiện)'
                elif 'SKIPPED_FOR_FAILURE' in txt:
                    loi = 'BỎ QUA (lỗi trước đó)'
        giay = '' if bd is None or kt is None else f'{(kt - bd) / 1000:.1f}'
        hang.append((nhan, loi, giay))
    for h in hang:
        print('\t'.join(h))
    return 0


if __name__ == '__main__':
    sys.exit(main())
