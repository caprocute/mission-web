package vn.viettel.okrkpi.search.application;

import java.util.Set;

/**
 * Chỉ mục mới đang được nạp khi dựng lại.
 *
 * @param oldIndex chỉ mục hiện hành mà bí danh đang trỏ tới
 * @param newIndex chỉ mục {@code v<n+1>} vừa tạo
 * @param tenants các tenant có bí danh trỏ vào {@code oldIndex} (một tenant với silo; mọi tenant
 *     pool của cell)
 */
public record RebuildTarget(
    RebuildScope scope, String oldIndex, String newIndex, Set<String> tenants) {}
