package ai.javaclaw;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    ApplicationModules modules = ApplicationModules.of(JavaClawApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }
}