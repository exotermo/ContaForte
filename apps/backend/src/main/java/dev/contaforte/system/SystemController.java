package dev.contaforte.system;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
class SystemController {

    private final ObjectProvider<BuildProperties> build;

    SystemController(ObjectProvider<BuildProperties> build) {
        this.build = build;
    }

    @GetMapping("/info")
    Map<String, String> info() {
        BuildProperties props = build.getIfAvailable();
        return Map.of(
                "name", "contaforte-backend",
                "version", props != null ? props.getVersion() : "dev");
    }
}
