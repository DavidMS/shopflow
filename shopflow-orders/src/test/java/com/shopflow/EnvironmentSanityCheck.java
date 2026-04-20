package com.shopflow;

import com.shopflow.orders.ShopflowApplication;
import com.shopflow.orders.demo.CompletionAntipatterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verificación básica del entorno de desarrollo.
 * Si estos tests pasan, el entorno está correctamente configurado.
 *
 * Ejecutar desde IntelliJ: clic derecho > Run 'EnvironmentSanityCheck'
 * Ejecutar desde terminal: mvn test -pl shopflow-orders -Dtest=EnvironmentSanityCheck
 */
@SpringBootTest(classes = ShopflowApplication.class)
class EnvironmentSanityCheck {

    @MockBean
    KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    CompletionAntipatterns completionAntipatterns;

    @Test
    @DisplayName("El contexto de Spring arranca correctamente")
    void contextLoads() {
        // Si Spring no arranca, este test falla con un error descriptivo
    }

    @Test
    @DisplayName("Java 21+ está configurado")
    void javaVersionIsCorrect() {
        assertThat(Runtime.version().feature())
                .as("Se requiere Java 21 o superior. Versión actual: %d", Runtime.version().feature())
                .isGreaterThanOrEqualTo(21);
    }

    @Test
    @DisplayName("La JVM soporta Records (Java 16+)")
    void recordsAreSupported() {
        record TestRecord(String name, int value) {}
        var record = new TestRecord("test", 42);
        assertThat(record.name()).isEqualTo("test");
        assertThat(record.value()).isEqualTo(42);
    }

    @Test
    @DisplayName("La JVM soporta Pattern Matching en switch (Java 21)")
    void patternMatchingSwitchIsSupported() {
        Object obj = "hello";
        String result = switch (obj) {
            case String s -> "string: " + s;
            case Integer i -> "int: " + i;
            default -> "other";
        };
        assertThat(result).isEqualTo("string: hello");
    }
}
