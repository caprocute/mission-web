// Phần dùng chung cho bốn nhóm dashboard của TL-04 mục 10.6 (INF-15): nguồn dữ liệu theo uid đã khai trong
// values Grafana (thanos, loki, tempo), biến cell, hàm dựng panel ngắn gọn.
local g = import 'github.com/grafana/grafonnet/gen/grafonnet-v11.4.0/main.libsonnet';
local prom = { type: 'prometheus', uid: 'thanos' };
local loki = { type: 'loki', uid: 'loki' };

{
  g: g,
  prom: prom,
  loki: loki,

  bienCell: self.bienNhan('cell', 'cell', 'up', 'Cell'),

  bienNhan(ten, nhan, chuoi, nhanHienThi):: g.dashboard.variable.query.new(ten) { datasource: prom }
                                           + g.dashboard.variable.query.queryTypes.withLabelValues(nhan, chuoi)
                                           + g.dashboard.variable.query.generalOptions.withLabel(nhanHienThi)
                                           + g.dashboard.variable.query.withRefresh('time')
                                           // "Tất cả" khớp cả chuỗi không có nhãn cell (Prometheus đọc thẳng, chưa qua Thanos).
                                           + g.dashboard.variable.query.selectionOptions.withIncludeAll(true, '.*')
                                           + { current: { text: 'All', value: '$__all' } },

  q(expr, legend='', exemplar=false):: g.query.prometheus.new(prom.uid, expr)
                                      + g.query.prometheus.withDatasource(prom)
                                      + g.query.prometheus.withLegendFormat(legend)
                                      + g.query.prometheus.withExemplar(exemplar),

  dashboard(uid, tieuDe, bien, panels):: g.dashboard.new(tieuDe)
                                       + g.dashboard.withUid(uid)
                                       + g.dashboard.withTags(['vt', 'inf-15'])
                                       + g.dashboard.withTimezone('Asia/Ho_Chi_Minh')
                                       + g.dashboard.withRefresh('1m')
                                       + g.dashboard.time.withFrom('now-6h')
                                       + g.dashboard.withVariables(bien)
                                       + g.dashboard.withPanels(g.util.grid.wrapPanels(panels, panelWidth=12, panelHeight=8)),

  bieuDo(tieuDe, truyVan, donVi='short'):: g.panel.timeSeries.new(tieuDe)
                                          + g.panel.timeSeries.queryOptions.withDatasource(prom.type, prom.uid)
                                          + g.panel.timeSeries.queryOptions.withTargets(truyVan)
                                          + g.panel.timeSeries.standardOptions.withUnit(donVi),

  so(tieuDe, truyVan, donVi='short', nguong=[]):: g.panel.stat.new(tieuDe)
                                                 + g.panel.stat.queryOptions.withDatasource(prom.type, prom.uid)
                                                 + g.panel.stat.queryOptions.withTargets(truyVan)
                                                 + g.panel.stat.standardOptions.withUnit(donVi)
                                                 + (if nguong == [] then {} else
                                                      g.panel.stat.standardOptions.thresholds.withMode('absolute')
                                                      + g.panel.stat.standardOptions.thresholds.withSteps(nguong))
                                                 + { gridPos+: { w: 6, h: 5 } },

  // Panel log: dòng có trace_id mở thẳng trace trên Tempo qua derived field của nguồn Loki (B5).
  nhatKy(tieuDe, logql):: g.panel.logs.new(tieuDe)
                         + g.panel.logs.queryOptions.withDatasource(loki.type, loki.uid)
                         + g.panel.logs.queryOptions.withTargets([g.query.loki.new(loki.uid, logql)])
                         + g.panel.logs.options.withShowTime(true)
                         + g.panel.logs.options.withWrapLogMessage(false)
                         + g.panel.logs.options.withPrettifyLogMessage(false)
                         + g.panel.logs.options.withEnableLogDetails(true)
                         + { gridPos+: { w: 24, h: 10 } },
}
