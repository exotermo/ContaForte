# Lições aprendidas — Milestone 3 (Imports)

## Entregue
Importação de extratos OFX e CSV em duas fases (prévia e confirmação), com deduplicação em camadas, desfazimento lógico, tela com assistente e histórico, `./cf seed --with-imports`, medição de carga e painel de observabilidade. Números ao fechar: **464 testes de backend**, **174 de frontend**, **14 E2E no navegador**, mais os de Go; prévia de 20 mil linhas em 1,4 s e pico de 434 MiB nos 768 MiB do backend.

## O que funcionou
- **Decidir antes de codar (ADR-015/016):** duas fases, "o arquivo nunca é guardado" e deduplicação em três passagens foram escritos primeiro. Todas as etapas seguintes só aplicaram essas decisões.
- **Parser próprio e linear para OFX:** em vez de um parser XML, um tokenizador simples lê o OFX em SGML e em XML sem expor a entidades externas (XXE) e sem custo quadrático. Testes de fuzz com sementes fixas acharam casos reais.
- **Testes escritos para quebrar o parser:** a M3.2 encontrou cinco defeitos antes de qualquer tela (ver abaixo).
- **Integridade no banco:** chave estrangeira composta `(import_id, conta)`, restrições de contagem e de ciclo de vida e índice único parcial do identificador externo tornaram os erros de importação impossíveis de gravar, mesmo por bug na API.
- **Prévia que não cria nada:** o usuário vê e decide; desfazer é só um passo de segurança extra. Isso mudou o desenho da tela (a comparação lado a lado de cada possível duplicata) e eliminou uma classe de reclamação.
- **Validar na stack real:** o `curl` com arquivo de verdade achou o limite de 1 MB do nginx; a captura de tela achou a tabela de revisão cortando justamente as colunas de decisão no celular.
- **Autoverificação em todo lugar:** o seed confere contagens e saldos; o bench confere que as 20 mil linhas viraram 20 mil novas. Foi essa conferência que pegou o erro do próprio gerador de dados do bench.

## O que deu trabalho
| Problema | Causa | Solução |
|---|---|---|
| `"--5"` era aceito como valor | tratamento do sinal aplicado duas vezes | `AmountParser` com um só sinal e teste dedicado |
| `"0.001"` lido como R$ 1,00 | o primeiro grupo de milhares podia começar com zero | regra explícita e caso de borda no teste |
| Corte de texto partia um emoji ao meio | corte por unidade UTF-16, não por ponto de código | corte por ponto de código |
| Arquivo UTF-16 recusado como binário | verificação de binário antes da decodificação | removida; o decodificador trata a marca de ordem de bytes |
| Separador TAB perdido ao salvar o mapeamento | `"\t"` tratado como espaço em branco | gravar `TAB` e tratar `\t` antes de `blank()` |
| Decodificação de entidades quadrática | busca do `;` até o fim do texto | janela de 10 caracteres |
| Uma correspondência fraca "roubava" a forte | classificação numa só passagem | três passagens: identificadores, texto exato no mesmo dia, depois ±1 dia |
| Identificador PIX com maiúsculas normalizadas | a caixa faz parte do identificador | só o prefixo `E`/`D` é maiúsculo; o resto é preservado |
| Upload de 5 MB recusado com 413 inesperado | nginx tem limite padrão de 1 MB | `client_max_body_size 6m` no gateway |
| Resposta de prévia dentro de um envelope | duas formas de resposta a manter | campos opcionais na mesma `ImportResponse` |
| Tabela de revisão sem Valor e sem "Comparada com" no celular | rolagem horizontal escondia as colunas | cartões empilhados com `stackedCell` (também em Transações) |
| E2E intermitente ao crescer | testes em paralelo com o mesmo usuário somavam no mesmo resumo (diferença de exatamente 4.179,53) | `workers: 1` |
| Bench falhou com 196 linhas inválidas | meu gerador criava datas futuras; o parser recusa (certo) | datas só no passado, com teste |
| Limite de 20 prévias/h quebrava rodadas seguidas do E2E | contador do admin no Redis | `cf e2e` zera `rl:import:*` antes e depois |
| Primeiro incremento de uma série nova invisível no Prometheus | mesma limitação do M2 | contadores de importação registrados em 0 na partida |

## Decisões que valem lembrar
- O arquivo nunca é guardado: só hash, nome e linhas normalizadas por 24 h; depois, as transações confirmadas.
- Identificador do extrato igual é sempre duplicata; identificadores diferentes só geram "possível duplicata".
- Possíveis duplicatas vêm desmarcadas: dinheiro duplicado é pior que dinheiro faltando que se corrige com um clique.
- Confirmar trava a prévia (`FOR UPDATE`) e confere os identificadores de novo; o que virou duplicata no intervalo é pulado e contado, não derruba a importação.
- Tipo do arquivo é decidido pelo conteúdo; o navegador só dá uma dica.
- Desfazer é lógico: a importação e as linhas ficam como histórico e o identificador é liberado para reimportar.
- Auditoria e métricas só com ids e contagens; nunca nome de arquivo, descrição ou valor.

## Dívidas conhecidas (candidatas a milestones futuros)
- Transferência entre contas próprias importada aparece como receita numa conta e despesa na outra; falta conciliação automática.
- Só BRL; só OFX 1.x/2.x e CSV com mapeamento (sem PDF e sem Open Finance).
- Delimitador, linhas de título e cabeçalho do CSV não são editáveis na tela (só a detecção).
- "Marcar todas as possíveis" só vale por página.
- Formato da série `EXPIRED` das métricas fica como `UNKNOWN`.
- O upload de 5 MB é lido inteiro em memória (limite de tamanho e de taxa mitigam; medido: 434 MiB de pico).
- Sem teste com leitor de tela.
