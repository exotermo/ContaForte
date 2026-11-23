# Wrapper fino: a lógica fica no CLI `cf` (tools/cf). Enquanto ele não existe, usa docker compose direto.
COMPOSE ?= docker compose

.PHONY: up down ps logs config
up:
	$(COMPOSE) up -d --wait
down:
	$(COMPOSE) down
ps:
	$(COMPOSE) ps
logs:
	$(COMPOSE) logs -f --tail=100
config:
	$(COMPOSE) config -q
