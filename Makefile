# Atalhos finos: a lógica fica no CLI `cf` (tools/cf).
.PHONY: init up down status doctor test
init:
	./cf init
up:
	./cf up
down:
	./cf down
status:
	./cf status
doctor:
	./cf doctor
test:
	./cf test
