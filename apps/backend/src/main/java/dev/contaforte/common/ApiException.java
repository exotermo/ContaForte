package dev.contaforte.common;

import org.springframework.http.HttpStatus;

/** Erro de negócio com status HTTP; vira ProblemDetail (RFC 7807) no handler global. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
