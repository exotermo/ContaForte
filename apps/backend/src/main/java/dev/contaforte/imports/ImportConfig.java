package dev.contaforte.imports;

import dev.contaforte.imports.parse.CsvParser;
import dev.contaforte.imports.parse.OfxParser;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ImportConfig {

    @Bean
    OfxParser ofxParser(Clock clock) {
        return new OfxParser(clock);
    }

    @Bean
    CsvParser csvParser(Clock clock) {
        return new CsvParser(clock);
    }
}
