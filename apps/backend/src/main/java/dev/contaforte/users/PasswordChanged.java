package dev.contaforte.users;

import java.util.UUID;

/** Evento de domínio: a senha do usuário mudou (o módulo auth revoga as sessões). */
public record PasswordChanged(UUID userId) {}
