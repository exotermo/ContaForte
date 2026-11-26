package dev.contaforte.common;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * Limita quantas execuções de um trabalho que consome muita memória acontecem ao mesmo tempo (ADR-021). Quem chega
 * depois espera na fila; se a espera estourar, recebe 503 em vez de o processo morrer por falta de memória.
 */
public final class ConcurrencyGate {

    private final Semaphore permits;
    private final Duration maxWait;
    private final String busyMessage;

    public ConcurrencyGate(int concurrency, Duration maxWait, String busyMessage) {
        this.permits = new Semaphore(concurrency, true);
        this.maxWait = maxWait;
        this.busyMessage = busyMessage;
    }

    public <T> T run(Supplier<T> work) {
        try {
            if (!permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) throw busy();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    private ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, busyMessage);
    }
}
