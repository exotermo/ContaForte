package dev.contaforte.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Trilha de auditoria (tabela audit_logs). Nunca receba senhas, tokens ou e-mails em claro aqui. */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(UUID actorId, String action, String resource, Map<String, ?> detail) {
        String json;
        try {
            json = mapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.update("insert into audit_logs (actor_id, action, resource, detail) values (?, ?, ?, ?::jsonb)",
                actorId, action, resource, json);
    }
}
