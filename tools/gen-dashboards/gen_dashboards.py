"""Gera os dashboards do Grafana (JSON provisionado) em infrastructure/monitoring/grafana/dashboards.

Uso (a partir da raiz do repositório):  python3 tools/gen-dashboards/gen_dashboards.py
Os JSON são o artefato versionado; edite aqui e regenere em vez de editar o JSON à mão.
"""
import json, os
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "infrastructure", "monitoring", "grafana", "dashboards")
PROM={"type":"prometheus","uid":"prometheus"}; LOKI={"type":"loki","uid":"loki"}

class D:
    def __init__(s, uid, title, tags):
        s.uid, s.title, s.tags, s.panels, s.id = uid, title, tags, [], 1
        s.x = s.y = s.lh = 0      # x: coluna atual, y: topo da linha atual, lh: altura da linha atual
    def row(s, title):
        if s.x > 0 or s.lh > 0:   # fecha a linha anterior antes de abrir a seção
            s.y += s.lh; s.x = 0; s.lh = 0
        s.panels.append({"type":"row","title":title,"collapsed":False,"id":s.id,"gridPos":{"h":1,"w":24,"x":0,"y":s.y}}); s.id+=1; s.y+=1
    def _place(s, w, h):
        if s.x + w > 24:
            s.y += s.lh; s.x = 0; s.lh = 0
        pos={"h":h,"w":w,"x":s.x,"y":s.y}; s.x += w; s.lh = max(s.lh, h); return pos
    def stat(s, title, expr, unit="short", w=4, thresholds=None, mappings=None, decimals=None):
        steps = thresholds or [{"color":"green","value":None}]
        d={"unit":unit,"thresholds":{"mode":"absolute","steps":steps}}
        if mappings: d["mappings"]=mappings
        if decimals is not None: d["decimals"]=decimals
        s.panels.append({"type":"stat","title":title,"id":s.id,"datasource":PROM,"gridPos":s._place(w,4),
            "targets":[{"expr":expr,"refId":"A","datasource":PROM}],
            "fieldConfig":{"defaults":d,"overrides":[]},
            "options":{"reduceOptions":{"calcs":["lastNotNull"],"fields":"","values":False},"colorMode":"background","graphMode":"none","textMode":"auto"}}); s.id+=1
    def ts(s, title, targets, unit="short", w=12, h=7, stack=False, minv=None, maxv=None):
        d={"unit":unit,"custom":{"lineWidth":1,"fillOpacity":15 if not stack else 40,"showPoints":"never","stacking":{"mode":"normal" if stack else "none"}}}
        if minv is not None: d["min"]=minv
        if maxv is not None: d["max"]=maxv
        s.panels.append({"type":"timeseries","title":title,"id":s.id,"datasource":PROM,"gridPos":s._place(w,h),
            "targets":[{"expr":e,"legendFormat":l,"refId":chr(65+i),"datasource":PROM} for i,(e,l) in enumerate(targets)],
            "fieldConfig":{"defaults":d,"overrides":[]},
            "options":{"legend":{"displayMode":"list","placement":"bottom"},"tooltip":{"mode":"multi","sort":"desc"}}}); s.id+=1
    def logs(s, title, expr, w=24, h=10):
        s.panels.append({"type":"logs","title":title,"id":s.id,"datasource":LOKI,"gridPos":s._place(w,h),
            "targets":[{"expr":expr,"refId":"A","datasource":LOKI,"queryType":"range"}],
            "options":{"showTime":True,"wrapLogMessage":True,"sortOrder":"Descending","enableLogDetails":True,"dedupStrategy":"none"}}); s.id+=1
    def lokits(s, title, expr, w=12, h=7):
        s.panels.append({"type":"timeseries","title":title,"id":s.id,"datasource":LOKI,"gridPos":s._place(w,h),
            "targets":[{"expr":expr,"legendFormat":"{{service}}","refId":"A","datasource":LOKI,"queryType":"range"}],
            "fieldConfig":{"defaults":{"unit":"short","custom":{"drawStyle":"bars","fillOpacity":60,"stacking":{"mode":"normal"}}},"overrides":[]},
            "options":{"legend":{"displayMode":"list","placement":"bottom"}}}); s.id+=1
    def save(s, fname, refresh="30s"):
        dash={"uid":s.uid,"title":s.title,"tags":s.tags,"timezone":"browser","schemaVersion":39,"version":1,
              "refresh":refresh,"time":{"from":"now-1h","to":"now"},"editable":False,"panels":s.panels,"templating":{"list":[]},
              "annotations":{"list":[]},"links":[]}
        json.dump(dash, open(os.path.join(OUT,fname),"w",encoding="utf-8"), ensure_ascii=False, indent=2)

UPDOWN=[{"type":"value","options":{"1":{"text":"NO AR","color":"green"},"0":{"text":"FORA","color":"red"}}}]
RED=[{"color":"red","value":None},{"color":"green","value":1}]
CN='name=~"contaforte-.*"'

# ---------------- Visão geral ----------------
d=D("cf-overview","ContaForte — Visão geral",["contaforte"])
d.row("Saúde")
d.stat("Alvos no ar","sum(up)",w=4,thresholds=[{"color":"red","value":None},{"color":"green","value":8}])
d.stat("Backend","up{job=\"backend\"}",w=4,thresholds=RED,mappings=UPDOWN)
d.stat("Postgres","pg_up",w=4,thresholds=RED,mappings=UPDOWN)
d.stat("Redis","redis_up",w=4,thresholds=RED,mappings=UPDOWN)
d.stat("Alertas disparando","count(ALERTS{alertstate=\"firing\"}) or vector(0)",w=4,thresholds=[{"color":"green","value":None},{"color":"red","value":1}])
d.stat("Containers monitorados",f"count(container_last_seen{{{CN}}})",w=4)
d.row("Backend (HTTP)")
d.stat("Requisições/s","sum(rate(http_server_requests_seconds_count{job=\"backend\"}[5m]))",unit="reqps",w=6,decimals=2)
d.stat("Erros 5xx/s","sum(rate(http_server_requests_seconds_count{job=\"backend\",status=~\"5..\"}[5m])) or vector(0)",unit="reqps",w=6,decimals=3,thresholds=[{"color":"green","value":None},{"color":"red","value":0.1}])
d.stat("Latência p95","histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"backend\"}[5m]))) * 1000",unit="ms",w=6,decimals=0,thresholds=[{"color":"green","value":None},{"color":"orange","value":500},{"color":"red","value":1500}])
d.stat("Uptime do backend","time() - process_start_time_seconds{job=\"backend\"}",unit="s",w=6)
d.ts("Requisições por status",[("sum by (status) (rate(http_server_requests_seconds_count{job=\"backend\"}[5m]))","{{status}}")],unit="reqps",stack=True)
d.ts("Latência por rota (média)",[("sum by (uri) (rate(http_server_requests_seconds_sum{job=\"backend\"}[5m])) / sum by (uri) (rate(http_server_requests_seconds_count{job=\"backend\"}[5m]))","{{uri}}")],unit="s")
d.row("JVM do backend")
d.ts("Heap usado x máximo",[("sum(jvm_memory_used_bytes{job=\"backend\",area=\"heap\"})","usado"),("sum(jvm_memory_max_bytes{job=\"backend\",area=\"heap\"})","máximo")],unit="bytes")
d.ts("Threads e pausas de GC",[("jvm_threads_live_threads{job=\"backend\"}","threads vivas"),("rate(jvm_gc_pause_seconds_sum{job=\"backend\"}[5m]) * 1000","pausa GC (ms/s)")])
d.ts("Pool de conexões (Hikari)",[("hikaricp_connections_active{job=\"backend\"}","ativas"),("hikaricp_connections_idle{job=\"backend\"}","ociosas"),("hikaricp_connections_pending{job=\"backend\"}","pendentes")])
d.ts("CPU do processo",[("process_cpu_usage{job=\"backend\"}","backend")],unit="percentunit",minv=0)
d.row("Containers")
d.ts("CPU por container",[(f"sum by (name) (rate(container_cpu_usage_seconds_total{{{CN}}}[2m]))","{{name}}")],unit="percentunit")
d.ts("Memória por container",[(f"container_memory_working_set_bytes{{{CN}}}","{{name}}")],unit="bytes")
d.ts("Uso da memória sobre o limite",[(f"container_memory_working_set_bytes{{{CN}}} / (container_spec_memory_limit_bytes{{{CN}}} > 0)","{{name}}")],unit="percentunit",minv=0,maxv=1)
d.ts("Rede (recebido)",[(f"sum by (name) (rate(container_network_receive_bytes_total{{{CN}}}[2m]))","{{name}}")],unit="Bps")
d.row("Logs")
d.lokits("Volume de logs por serviço","sum by (service) (count_over_time({service=~\".+\"}[1m]))")
d.lokits("Erros e avisos por serviço","sum by (service) (count_over_time({service=~\".+\", level=~\"ERROR|WARN\"}[1m]))")
d.logs("Erros e avisos do backend","{service=\"backend\", level=~\"ERROR|WARN\"}")
d.logs("Todos os logs","{service=~\".+\"}",h=12)
d.save("visao-geral.json")

# ---------------- Postgres ----------------
p=D("cf-postgres","ContaForte — PostgreSQL",["contaforte","postgres"])
DB='datname="contaforte"'
p.row("Estado")
p.stat("Postgres","pg_up",w=4,thresholds=RED,mappings=UPDOWN)
p.stat("Conexões",f"pg_stat_database_numbackends{{{DB}}}",w=4)
p.stat("Tamanho do banco",f"pg_database_size_bytes{{{DB}}}",unit="bytes",w=4)
p.stat("Hit ratio do cache",f"sum(rate(pg_stat_database_blks_hit{{{DB}}}[5m])) / (sum(rate(pg_stat_database_blks_hit{{{DB}}}[5m])) + sum(rate(pg_stat_database_blks_read{{{DB}}}[5m])))",unit="percentunit",w=4,decimals=1)
p.stat("Deadlocks (1h)",f"increase(pg_stat_database_deadlocks{{{DB}}}[1h])",w=4,decimals=0,thresholds=[{"color":"green","value":None},{"color":"red","value":1}])
p.stat("Linhas lidas/s",f"sum(rate(pg_stat_database_tup_returned{{{DB}}}[5m]))",unit="ops",w=4,decimals=1)
p.row("Atividade")
p.ts("Transações por segundo",[(f"rate(pg_stat_database_xact_commit{{{DB}}}[5m])","commit"),(f"rate(pg_stat_database_xact_rollback{{{DB}}}[5m])","rollback")],unit="ops")
p.ts("Linhas por segundo",[(f"rate(pg_stat_database_tup_inserted{{{DB}}}[5m])","inseridas"),(f"rate(pg_stat_database_tup_updated{{{DB}}}[5m])","atualizadas"),(f"rate(pg_stat_database_tup_deleted{{{DB}}}[5m])","removidas"),(f"rate(pg_stat_database_tup_returned{{{DB}}}[5m])","lidas")],unit="ops")
p.ts("Conexões por banco",[("pg_stat_database_numbackends","{{datname}}")])
p.ts("Locks por modo",[("sum by (mode) (pg_locks_count)","{{mode}}")])
p.ts("Cache: leituras de disco x memória",[(f"rate(pg_stat_database_blks_hit{{{DB}}}[5m])","memória (hit)"),(f"rate(pg_stat_database_blks_read{{{DB}}}[5m])","disco (read)")],unit="ops")
p.ts("Tamanho do banco ao longo do tempo",[(f"pg_database_size_bytes{{{DB}}}","contaforte")],unit="bytes")
p.save("postgres.json")

# ---------------- Redis ----------------
r=D("cf-redis","ContaForte — Redis",["contaforte","redis"])
r.row("Estado")
r.stat("Redis","redis_up",w=4,thresholds=RED,mappings=UPDOWN)
r.stat("Clientes conectados","redis_connected_clients",w=4)
r.stat("Memória usada","redis_memory_used_bytes",unit="bytes",w=4)
r.stat("Hit ratio","rate(redis_keyspace_hits_total[5m]) / (rate(redis_keyspace_hits_total[5m]) + rate(redis_keyspace_misses_total[5m]))",unit="percentunit",w=4,decimals=1)
r.stat("Chaves","sum(redis_db_keys)",w=4)
r.stat("Uptime","redis_uptime_in_seconds",unit="s",w=4)
r.row("Atividade")
r.ts("Comandos por segundo",[("rate(redis_commands_processed_total[5m])","comandos")],unit="ops")
r.ts("Memória",[("redis_memory_used_bytes","usada"),("redis_memory_used_rss_bytes","RSS")],unit="bytes")
r.ts("Acertos e erros de chave",[("rate(redis_keyspace_hits_total[5m])","hits"),("rate(redis_keyspace_misses_total[5m])","misses")],unit="ops")
r.ts("Clientes",[("redis_connected_clients","conectados"),("redis_blocked_clients","bloqueados")])
r.ts("Chaves expiradas e removidas",[("rate(redis_expired_keys_total[5m])","expiradas"),("rate(redis_evicted_keys_total[5m])","removidas (evicted)")],unit="ops")
r.ts("Rede",[("rate(redis_net_input_bytes_total[5m])","entrada"),("rate(redis_net_output_bytes_total[5m])","saída")],unit="Bps")
r.save("redis.json")
# ---------------- Negócio ----------------
# Métricas de uso do produto (transações registradas) e a experiência nas telas financeiras. Nenhuma métrica aqui
# carrega valor, descrição ou qualquer dado pessoal: só tipo da transação, rota e status HTTP.
FIN='uri=~"/api/v1/(accounts|transactions|summary|categories|contexts|imports).*"'
n=D("cf-negocio","ContaForte — Negócio",["contaforte","negocio"])
n.row("Uso")
n.stat("Transações registradas (desde o início do backend)","sum(contaforte_transactions_recorded_total) or vector(0)",w=6,decimals=0)
n.stat("Transações na última hora","sum(increase(contaforte_transactions_recorded_total[1h])) or vector(0)",w=6,decimals=0)
n.stat("Contas criadas (24h)","sum(increase(http_server_requests_seconds_count{job=\"backend\",method=\"POST\",uri=\"/api/v1/accounts\",status=\"201\"}[24h])) or vector(0)",w=6,decimals=0)
n.stat("Logins (24h)","sum(increase(http_server_requests_seconds_count{job=\"backend\",uri=\"/api/v1/auth/login\",status=\"200\"}[24h])) or vector(0)",w=6,decimals=0)
n.ts("Transações registradas por tipo (5 min)",[("sum by (type) (increase(contaforte_transactions_recorded_total[5m]))","{{type}}")],stack=True)
n.ts("Requisições por área",[
    ("sum(rate(http_server_requests_seconds_count{job=\"backend\",uri=~\"/api/v1/transactions.*\"}[5m]))","transações"),
    ("sum(rate(http_server_requests_seconds_count{job=\"backend\",uri=~\"/api/v1/accounts.*\"}[5m]))","contas"),
    ("sum(rate(http_server_requests_seconds_count{job=\"backend\",uri=~\"/api/v1/summary.*\"}[5m]))","resumo"),
    ("sum(rate(http_server_requests_seconds_count{job=\"backend\",uri=~\"/api/v1/(categories|contexts).*\"}[5m]))","categorias e contextos")],unit="reqps")
n.row("Latência das telas financeiras (p95, última hora)")
n.stat("Lista de transações","histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"backend\",uri=\"/api/v1/transactions\",method=\"GET\"}[1h]))) * 1000",unit="ms",w=6,decimals=0,thresholds=[{"color":"green","value":None},{"color":"orange","value":300},{"color":"red","value":1000}])
n.stat("Resumo","histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"backend\",uri=\"/api/v1/summary\"}[1h]))) * 1000",unit="ms",w=6,decimals=0,thresholds=[{"color":"green","value":None},{"color":"orange","value":300},{"color":"red","value":1000}])
n.stat("Resumo mensal","histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"backend\",uri=\"/api/v1/summary/monthly\"}[1h]))) * 1000",unit="ms",w=6,decimals=0,thresholds=[{"color":"green","value":None},{"color":"orange","value":300},{"color":"red","value":1000}])
n.stat("Criar transação","histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job=\"backend\",uri=\"/api/v1/transactions\",method=\"POST\"}[1h]))) * 1000",unit="ms",w=6,decimals=0,thresholds=[{"color":"green","value":None},{"color":"orange","value":300},{"color":"red","value":1000}])
n.ts("p95 por rota",[(f"histogram_quantile(0.95, sum by (le, uri, method) (rate(http_server_requests_seconds_bucket{{job=\"backend\",{FIN}}}[5m])))","{{method}} {{uri}}")],unit="s",w=24)
n.row("Importações de extrato")
# só contagens por formato, status e resultado: nunca nome de arquivo, descrição ou valor
n.stat("Importações confirmadas (24h)","sum(increase(contaforte_imports_total{status=\"CONFIRMED\"}[24h])) or vector(0)",w=6,decimals=0)
n.stat("Importações desfeitas (24h)","sum(increase(contaforte_imports_total{status=\"ROLLED_BACK\"}[24h])) or vector(0)",w=6,decimals=0)
n.stat("Extratos recusados (24h)","sum(increase(contaforte_imports_total{status=\"REJECTED\"}[24h])) or vector(0)",w=6,decimals=0)
n.stat("Prévia: p95 (última hora)","histogram_quantile(0.95, sum by (le) (rate(contaforte_import_preview_seconds_bucket[1h]))) * 1000",unit="ms",w=6,decimals=0,thresholds=[{"color":"green","value":None},{"color":"orange","value":3000},{"color":"red","value":10000}])
n.ts("Importações por formato e status (5 min)",[("sum by (format, status) (increase(contaforte_imports_total[5m])) > 0","{{format}} {{status}}")],stack=True)
n.ts("Linhas por resultado (5 min)",[("sum by (result) (increase(contaforte_import_rows_total[5m]))","{{result}}")],stack=True)
n.ts("Duração da prévia (p50 e p95)",[("histogram_quantile(0.50, sum by (le, format) (rate(contaforte_import_preview_seconds_bucket[5m])))","p50 {{format}}"),("histogram_quantile(0.95, sum by (le, format) (rate(contaforte_import_preview_seconds_bucket[5m])))","p95 {{format}}")],unit="s")
n.ts("Requisições de importação por status HTTP",[("sum by (status, method) (rate(http_server_requests_seconds_count{job=\"backend\",uri=~\"/api/v1/imports.*\"}[5m]))","{{method}} {{status}}")],unit="reqps")
n.row("Rejeições e erros")
n.ts("Respostas 4xx por rota (validação, conflito, não encontrado)",[(f"sum by (status, uri) (rate(http_server_requests_seconds_count{{job=\"backend\",{FIN},status=~\"4..\"}}[5m]))","{{status}} {{uri}}")],unit="reqps")
n.ts("Respostas 5xx por rota",[(f"sum by (status, uri) (rate(http_server_requests_seconds_count{{job=\"backend\",{FIN},status=~\"5..\"}}[5m]))","{{status}} {{uri}}")],unit="reqps")
n.save("negocio.json")

print("dashboards gerados:", os.listdir(OUT))
