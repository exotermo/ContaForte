# ADR-022 — Instalação por pacote, sem construir no servidor

**Status:** Aceito · **Data:** 2026-11-26

## Contexto
O servidor de destino tem um processador antigo, pouca memória e outros serviços rodando. Construir lá (Gradle, Next.js e a subida de treino da AppCDS) pediria bem mais que o 1 GB da stack e demoraria muito. Publicar as imagens num registro público ou privado exporia algo fora do repositório e exigiria credenciais no servidor.

## Decisão
- **`./cf release`** gera o pacote na máquina de desenvolvimento: as três imagens do projeto (`docker save`), os arquivos do repositório, o `cf` compilado para linux/amd64 e um `SHA256SUMS`. **Tudo sai do commit atual** (`git archive`): o que não foi commitado não entra, e a versão anunciada descreve o conteúdo.
- **A cópia é manual** (`scp`) e as imagens entram com `docker load`. Nada é publicado.
- **O arquivo `.release`**, na raiz do pacote, liga o modo "imagens prontas": o `cf` usa a versão dele, sobe com `--no-build`, avisa se falta carregar uma imagem e recusa `build` e `release`. O wrapper `./cf` não compila nada nesse modo.
- **`pull_policy: never`** nas três imagens do projeto: o Docker nunca procura na Internet uma imagem com o nome delas. Imagens de terceiros (Postgres, Redis, nginx, cloudflared e o monitoramento) continuam vindo dos registros oficiais.
- **`./cf check-images`** executa um programa de cada imagem sempre ligada antes de subir: se o processador não suportar alguma, o erro aparece sem stack nem dados no caminho.

## Alternativas descartadas
- **Construir no servidor:** pesado para a máquina e deixa lá ferramentas de build e código intermediário.
- **GitHub Container Registry:** mais cômodo para atualizar, mas publica imagens. Pode ser adotado depois, com decisão explícita.

## Consequências
- Atualizar é gerar outro pacote, copiar, carregar e extrair por cima; o `.env` e os backups não vêm no pacote e são preservados. Cada transferência leva cerca de 250 MB.
- Voltar a uma versão anterior é extrair o pacote antigo, **desde que o banco não tenha recebido migrações novas**: as migrações não são desfeitas. Faça backup antes de atualizar.
- Arquivos removidos numa versão nova permanecem na pasta depois da extração por cima; o Compose só usa o que o `docker-compose.yml` cita.
- Imagens antigas se acumulam no destino até serem apagadas à mão.
- O `check-images` é um teste curto: não garante que todo caminho de código funcione naquele processador. A prova é a stack rodando lá.
- O CI gera o pacote, apaga as imagens, instala a partir dele e falha se algo for construído no destino.
