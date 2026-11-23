# ADR-007 — Cloudflare Zero Trust no ingresso

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
Reduz superfície de ataque e permite hospedar em casa ou em VPS pequeno.

## Decisão
A exposição pública usa Cloudflare Tunnel, WAF, rate limit e Access, sem portas administrativas abertas.

## Implementação (Milestone 1, Etapa 7)
- **Opcional:** o serviço `cloudflared` está no perfil `tunnel` do Compose. Sem `COMPOSE_PROFILES=tunnel` no `.env`, nada muda e a stack segue acessível só em `127.0.0.1:8080`.
- **Só conexão de saída:** o `cloudflared` não publica porta. Fica apenas na rede `edge` (a única com saída à Internet) e não enxerga backend, bancos nem observabilidade.
- **Dois listeners no gateway:** a porta 80 (local, publicada em loopback) ignora cabeçalhos do cliente e usa `$remote_addr`. A porta 8081 (nunca publicada no host) é a que o túnel aponta e só ela confia em `CF-Connecting-IP` (validado por regex) e fixa `X-Forwarded-Proto: https`. Assim o IP real chega ao rate limit e o cookie de sessão sai com `Secure`, sem permitir spoof de quem acessa localmente.
- **Grafana nunca sai pelo túnel:** a rota `/grafana/` só existe no listener local.
- **Token:** `TUNNEL_TOKEN` fica no `.env` (permissão 600, fora do Git). O `./cf doctor` cobra o token quando o perfil está ativo.
- **Limitação conhecida:** a porta 8081 também é alcançável por containers das redes `app` e `obs` (o gateway está nelas). Um container comprometido poderia forjar o IP nas requisições que ele mesmo originar. O risco é baixo (só afeta o rate limit) e fica registrado.
- **WAF, rate limit de borda e Access** são configurados no painel do Cloudflare (ver o guia de operação).

## Consequências
Ver o [SDD](../../SDD/SDD_Personal_Financial_Copilot.md) para o detalhamento. Revisitar esta decisão exige um novo ADR que a substitua.
