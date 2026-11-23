# Lições aprendidas — Milestone 1 (Foundation)

## O que funcionou
- **Tudo em container:** o host tinha Java 17 e o projeto exige 21. Fazer todo build e teste em Docker eliminou o "na minha máquina funciona" e é o que torna o `./cf init && ./cf up` possível.
- **Validar de ponta a ponta, não só por unidade:** o teste E2E achou um bug que nenhum teste unitário via (o nginx mandava `Host` sem a porta e a checagem de Origin recusava o login com 403).
- **Capturas de tela como teste:** o gerador de dashboards passava em todas as consultas, mas os painéis estavam sobrepostos. Só a imagem mostrou.
- **Fail-closed no rate limit:** se o Redis cai, o login responde 503 em vez de liberar tentativas ilimitadas.
- **Doctor como contrato de segurança:** `./cf doctor` transforma as promessas de isolamento (redes internas, portas só em loopback, segredos fortes) em verificações repetíveis.

## O que deu trabalho
| Problema | Causa | Solução |
|---|---|---|
| Mosquitto caía na partida | arquivo de senhas pertencia ao root | `chown` no entrypoint |
| Hibernate recusava o schema | `char(64)` na migração vs `String` na entidade | migração V2 usando `varchar(64)` |
| Origin 403 no login | nginx enviava `Host` sem porta | `$http_host` |
| cAdvisor só via o cgroup raiz | exigiria `docker.sock` e containerd montados (root no host) | exporter próprio em Go lendo um proxy GET-only |
| Alloy recebia 403 | proxy do Docker sem a rota de redes | `NETWORKS=1` |
| Loki "unhealthy" | imagem sem shell nem `wget` | healthcheck removido; o doctor confere pelo Prometheus |
| Alerta fantasma após remover um job | séries mortas do job antigo | expirou sozinho; reavaliar regras após mudanças |
| `compose config` falhava sem o token do túnel | `${VAR:?}` é avaliado mesmo com o serviço em perfil desligado | variável opcional no compose, checada no doctor |
| Prometheus ignorava o scrape novo | arquivo montado mudou, container não foi recriado | reiniciar o serviço |
| Rede instável (Maven, manifests Docker) | intermitência do ambiente | repetir o comando |

## Decisões que valem lembrar
- Promtail foi descontinuado: usamos o Alloy.
- O monitoramento nunca monta o `docker.sock` diretamente.
- O Grafana nunca sai pelo túnel Cloudflare.
- Majors de dependências são atualizados de propósito, não pelo Dependabot.

## Dívidas conhecidas (candidatas a milestones futuros)
- Usuário `pg_monitor` dedicado para o `postgres-exporter`.
- Alertmanager (hoje os alertas não notificam ninguém).
- CSP com nonce (hoje usa `'unsafe-inline'`).
- Corrida de refresh entre abas (rara; a rotação derruba a sessão).
- Porta 8081 do gateway alcançável por containers das redes internas (afeta só o rate limit; ver ADR-007).
- Trocar a senha do admin inicial pela interface.
