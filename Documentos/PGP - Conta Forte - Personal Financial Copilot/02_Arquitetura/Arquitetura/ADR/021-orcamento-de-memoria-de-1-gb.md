# ADR-021 — Orçamento de memória de 1 GB e limites de trabalho simultâneo

**Status:** Aceito · **Data:** 2026-11-26

## Contexto
O núcleo e o túnel precisam caber em **1 GB**, contado como a soma dos limites dos contêineres: cada um pode chegar ao próprio teto sem estourar o total. O servidor de destino tem um processador de 2 núcleos sem SSE4.2 nem AVX. Os testes rodaram com todos os contêineres dividindo 2 núcleos.

## Limites

| Serviço | Antes (MiB) | Agora (MiB) | Como cabe |
|---|---|---|---|
| backend | 768 | 448 | heap de até 224 MiB, coletor serial, pilhas de 512 KiB, só o primeiro nível do JIT, metaspace e código compilado com teto, AppCDS, 20 threads de atendimento e 5 conexões com o banco |
| postgres | 512 | 192 | `shared_buffers` 48 MB, 20 conexões, `work_mem` 4 MB, sem consultas paralelas nem JIT |
| web | 256 | 160 | heap do Node com teto de 80 MiB, um upload por vez e sem o otimizador de imagens |
| redis | 256 | 32 | `maxmemory` 16 MB, sem despejo de chaves |
| gateway | 64 | 48 | 1 processo; o espaço é para os buffers de rede de uploads em paralelo |
| cloudflared | 128 | 64 | `GOMEMLIMIT` de 48 MiB |
| **Soma** | **1984** | **944** | um teste reprova a soma acima de 1024 e qualquer serviço sem limite |

## Decisões
- **Limitar o trabalho simultâneo, e não só a memória.** Limite menor sem isso troca uso de memória por queda do serviço. No máximo **2 hashes de senha**, **1 operação de importação** (sugestão, prévia, confirmação ou desfazer) e **1 upload no BFF** ao mesmo tempo. Quem chega depois espera; se a espera estourar, recebe 503.
- **O BFF confere o token antes de ler um arquivo.** Um cookie qualquer não faz o corpo ir para a memória.
- **Sem buffers do tamanho do arquivo por thread** (`jdk.nio.maxCachedBufferSize`): a JVM os guardava depois de cada leitura grande e chegaram a 93 MiB.
- **AppCDS:** o build faz uma subida de treino, sem banco, e grava as classes carregadas num arquivo que a JVM mapeia. A chave RSA do treino é descartável e não sai do estágio de build.
- **Só o primeiro nível do JIT (`TieredStopAtLevel=1`):** na comparação, o backend ocupou cerca de 80 MiB a menos que com os dois níveis, com os mesmos tempos de importação.

## Falhas que os testes de estresse revelaram
- **Rajada de logins derrubava o backend, também com os limites antigos.** O limitador conta a falha depois do hash, então tentativas em paralelo passavam todas e cada uma ocupava cerca de 16 MiB do heap. Não exigia conta.
- **Uploads em paralelo derrubavam o web.** O BFF lia o arquivo antes de o backend validar o token: bastava um cookie inventado. Os arquivos ficam fora do heap do Node, então o teto do heap não ajuda.
- **Gateway a 24 MiB** (alvo inicial) morria com uploads em paralelo: o nginx usava menos de 1 MiB e cerca de 22 MiB eram buffers de rede, que contam no limite do contêiner.
- **Índice que faltava:** `import_items.duplicate_of` (migração V10). Cada transação apagada lia a tabela inteira. Com memória sobrando o custo passava despercebido; com o Postgres menor, apagar um usuário de teste passou de 9 minutos. Com o índice: 0,85 s.
- **`/_next/image` ficava aberto sem login** e carregava o `sharp`, que o processador do servidor não executa. Foi desligado e removido da imagem.

## Medição final
Em 2 núcleos, stack nova. "Não descartável" é o pico de memória anônima, do kernel e de rede, aquela que o kernel não consegue liberar antes de matar o processo; a medição cobre o bench, os E2E, 100 logins errados em paralelo, 6 importações de 20 mil linhas ao mesmo tempo, 900 páginas e uploads de 5 MB com e sem login.

| Serviço | Não descartável (MiB) | Folga (MiB) |
|---|---|---|
| backend | 361 de 448 | 87 |
| postgres | 89 de 192 | 103 |
| web | 105 de 160 | 55 |
| redis | 6 de 32 | 26 |
| gateway | 19 de 48 | 29 |

Nenhum reinício e nenhuma morte por falta de memória. O total simultâneo, contando cache em uso e swap, ficou em cerca de 430 MiB parado, 410 a 435 na importação e na carga do bench (maior total simultâneo) e 590 com os uploads. Importação de 20 mil linhas: prévia 4,8 s e confirmação 5,3 s, como antes; p95 das telas abaixo de 100 ms; o backend sobe em cerca de metade do tempo.

## Alternativas descartadas
- **GraalVM native image:** gasta menos memória, mas o build é pesado e exige revisar reflexão e dependências. Fica para depois.
- **Reduzir o limite de 20 mil linhas:** não foi necessário.
- **Diminuir o custo do Argon2:** economizaria memória enfraquecendo as senhas.

## Consequências
- Importações são atendidas uma de cada vez, inclusive entre usuários diferentes. Para uso pessoal é aceitável. Um upload recusado pode chegar ao navegador como 503 em vez de 401, porque o front responde sem ler o arquivo.
- Sem o segundo nível do JIT, cálculos longos podem render menos; nos cenários medidos não houve diferença.
- Mudar limites, opções da JVM ou os portões exige rodar `./cf bench`. A medição só vale com Linux e cgroup v2.
- A memória do cloudflared ainda não foi medida: depende de um túnel real e será conferida no deploy.
