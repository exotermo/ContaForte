// Carga leve das telas principais (só para ter número). Uso: ./cf bench (veja tools/cf/bench.go).
// Cada rota tem um limiar de p95; o k6 sai com erro se algum for estourado.
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE_URL;
const TOKEN = __ENV.TOKEN;
const day = (ago) => new Date(Date.now() - ago * 864e5).toISOString().slice(0, 10);

const ROUTES = {
  lista: "/api/v1/transactions?size=20",
  pagina_profunda: "/api/v1/transactions?size=20&page=400",
  filtros: `/api/v1/transactions?size=20&type=EXPENSE&from=${day(120)}&to=${day(0)}&q=loja`,
  resumo: "/api/v1/summary",
  mensal: "/api/v1/summary/monthly",
  historico: "/api/v1/summary/history?months=24",
};

// declarar o limiar é o que faz o k6 publicar o p95 de cada rota (sub-métrica por tag)
const thresholds = { http_req_failed: ["rate<0.01"] };
for (const name of Object.keys(ROUTES)) thresholds[`http_req_duration{endpoint:${name}}`] = ["p(95)<1000"];

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || "20s",
  thresholds,
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "max"],
};

export default function () {
  for (const [name, path] of Object.entries(ROUTES)) {
    const res = http.get(`${BASE}${path}`, { headers: { Authorization: `Bearer ${TOKEN}` }, tags: { endpoint: name } });
    check(res, { [`${name}: 200`]: (r) => r.status === 200 });
  }
}

export function handleSummary(data) {
  return { "/out/summary.json": JSON.stringify(data) };
}
