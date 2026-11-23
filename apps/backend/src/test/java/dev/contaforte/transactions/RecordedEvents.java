package dev.contaforte.transactions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Coletor de eventos para os testes: a requisição roda em outra thread, então um listener real é o caminho. */
@Component
class RecordedEvents {

    private final List<TransactionRecorded> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(TransactionRecorded event) {
        events.add(event);
    }

    List<TransactionRecorded> all() {
        return List.copyOf(events);
    }
}
