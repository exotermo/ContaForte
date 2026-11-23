package dev.contaforte.common;

import java.time.ZoneId;

/**
 * Fuso de referência do produto. Instantes são guardados em UTC (timestamptz); "o dia" e "o mês" do usuário
 * são sempre calculados neste fuso (o Brasil não tem horário de verão desde 2019).
 */
public final class AppTime {

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private AppTime() {
    }
}
