package dev.contaforte.users;

import dev.contaforte.audit.AuditService;
import dev.contaforte.common.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    UserService(UserRepository users, PasswordEncoder encoder, AuditService audit, ApplicationEventPublisher events) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.events = events;
    }

    User get(UUID id) {
        return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Usuário não encontrado."));
    }

    @Transactional
    User update(UUID id, String email, String currentPassword, String newPassword) {
        User user = get(id);
        if (email != null && !email.isBlank() && !email.trim().equalsIgnoreCase(user.getEmail())) {
            if (users.existsByEmailIgnoreCase(email.trim())) {
                throw new ApiException(HttpStatus.CONFLICT, "E-mail já em uso.");
            }
            user.setEmail(email.trim());
            audit.record(id, "EMAIL_CHANGED", "user:" + id, Map.of());
        }
        if (newPassword != null) {
            if (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Senha atual incorreta.");
            }
            user.setPasswordHash(encoder.encode(newPassword));
            audit.record(id, "PASSWORD_CHANGED", "user:" + id, Map.of());
            events.publishEvent(new PasswordChanged(id));
        }
        return users.save(user);
    }
}
