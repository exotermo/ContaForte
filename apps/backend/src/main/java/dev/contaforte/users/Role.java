package dev.contaforte.users;

/** Papéis do RBAC inicial (SDD §7.1). DEVICE e SERVICE são identidades não humanas. */
public enum Role {
    USER,
    ADMIN,
    SERVICE,
    DEVICE
}
