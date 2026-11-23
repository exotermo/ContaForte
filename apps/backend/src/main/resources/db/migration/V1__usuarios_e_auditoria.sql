-- V1: usuários e trilha de auditoria (Milestone 1)

CREATE TABLE users (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         varchar(320) NOT NULL,
    password_hash varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL DEFAULT 'USER'
                  CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN', 'SERVICE', 'DEVICE')),
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- e-mail único sem diferenciar maiúsculas/minúsculas
CREATE UNIQUE INDEX uk_users_email_lower ON users (lower(email));

CREATE TABLE audit_logs (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at timestamptz  NOT NULL DEFAULT now(),
    actor_id    uuid         REFERENCES users (id) ON DELETE SET NULL,
    action      varchar(100) NOT NULL,
    resource    varchar(200),
    detail      jsonb
);

CREATE INDEX ix_audit_logs_occurred_at ON audit_logs (occurred_at);
CREATE INDEX ix_audit_logs_actor_id ON audit_logs (actor_id);
