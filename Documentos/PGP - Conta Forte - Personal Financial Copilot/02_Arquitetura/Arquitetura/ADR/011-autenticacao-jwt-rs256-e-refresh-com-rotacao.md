# ADR-011 — Autenticação com JWT RS256 e refresh tokens com rotação

**Status:** Aceito · **Data:** 2026-11-23

## Contexto
A API é consumida por um front web/PWA e, no futuro, por dispositivos IoT. Precisa de autenticação stateless para a API, sessões revogáveis e proteção contra força bruta, sem depender de cookies de sessão.

## Decisão
- **Access token:** JWT assinado com **RS256**, 15 minutos, com `sub` (UUID), `role`, `iss` e `jti`. A chave privada vem do ambiente (`JWT_PRIVATE_KEY_B64`, gerada pelo `cf init`) e a pública é derivada dela. Dá para validar tokens em outro serviço só com a chave pública.
- **Refresh token:** opaco (256 bits aleatórios), guardado **apenas como hash SHA-256**, com validade de 14 dias. Cada uso **rotaciona** o token. Reapresentar um token já usado revoga **toda a cadeia** (detecção de roubo), e a revogação é atômica contra corridas.
- **Senhas:** Argon2id. O login faz uma verificação de hash mesmo quando o e-mail não existe, e a resposta é idêntica nos dois casos.
- **Rate limit de login:** contadores de **falhas** no Redis, por conta+IP (5 em 15 min) e por IP (30 em 15 min). Se o Redis cair, o login é **recusado (503)**: falha fechada.
- **Sessões e senha:** trocar a senha publica o evento `PasswordChanged`, e o módulo `auth` revoga todos os refresh tokens.
- **Auditoria:** `audit_logs` registra login, refresh, reuso, logout e trocas de credencial, sem senha nem e-mail em claro (o e-mail aparece só como prefixo do hash).
- **IP do cliente:** o gateway sobrescreve `X-Forwarded-For` com o IP que ele enxerga. O cliente não consegue forjar o IP para burlar o limite.

## Consequências
- Revogar um access token antes dos 15 minutos não é possível (limitação aceita do JWT). A janela é curta.
- Trocar a chave JWT invalida todos os access tokens. Rotação de chaves com `kid` e múltiplas chaves fica para depois.
- Com Cloudflare Tunnel na frente (Etapa 7), o IP real do cliente virá de `CF-Connecting-IP`, e o gateway precisará confiar nele só quando a requisição vier do `cloudflared`.
- Falha fechada no limitador troca disponibilidade por segurança: aceitável para dados financeiros.
