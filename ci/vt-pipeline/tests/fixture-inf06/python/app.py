"""Dịch vụ Python mẫu tối thiểu để kiểm thử vtPythonPipeline (INF-06)."""


def xep_loai(diem: float) -> str:
    if diem >= 90:
        return "HTXSNV"
    if diem >= 70:
        return "HTTNV"
    if diem >= 50:
        return "HTNV"
    return "KHTNV"
