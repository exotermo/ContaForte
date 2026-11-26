package dev.contaforte.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

class ConcurrencyGateTest {

    /** Encoder falso que registra quantas chamadas estiveram dentro dele ao mesmo tempo. */
    private static final class SlowEncoder implements PasswordEncoder {
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        private void work() {
            peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            } finally {
                inside.decrementAndGet();
            }
        }

        @Override
        public String encode(CharSequence raw) {
            work();
            return "h:" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            work();
            return encoded.equals("h:" + raw);
        }
    }

    @Test
    void rajadaDeHashesNuncaPassaDoLimiteETodosTerminam() throws Exception {
        SlowEncoder slow = new SlowEncoder();
        PasswordEncoder bounded = new BoundedPasswordEncoder(slow, 2, Duration.ofSeconds(30));
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int n = i;
                results.add(pool.submit(() -> n % 2 == 0 ? bounded.matches("s", "h:s") : bounded.encode("s").equals("h:s")));
            }
            for (Future<Boolean> r : results) assertThat(r.get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(slow.peak.get()).isBetween(1, 2);
    }

    @Test
    void esperaEstouradaVira503ESemVagaNadaEExecutado() throws Exception {
        ConcurrencyGate gate = new ConcurrencyGate(1, Duration.ofMillis(50), "ocupado");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger secondRan = new AtomicInteger();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> first = pool.submit(() -> gate.run(() -> {
                entered.countDown();
                try {
                    return release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> gate.run(secondRan::incrementAndGet))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                    .hasMessage("ocupado");
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(secondRan.get()).isZero();
    }

    @Test
    void erroNoTrabalhoDevolveAVaga() {
        ConcurrencyGate gate = new ConcurrencyGate(1, Duration.ofMillis(50), "ocupado");
        assertThatThrownBy(() -> gate.run(() -> {
            throw new IllegalStateException("falhou");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(gate.run(() -> "ok")).isEqualTo("ok");
    }
}
