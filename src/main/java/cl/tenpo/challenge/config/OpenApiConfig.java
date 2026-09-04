package cl.tenpo.challenge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI apiInfo() {
        return new OpenAPI().info(new Info()
                .title("Tenpo Backend Challenge API")
                .version("1.0.0")
                .description("""
                        API REST reactiva (Spring WebFlux) que calcula la suma de dos numeros
                        aplicando un porcentaje obtenido de un servicio externo, registra el
                        historial de llamadas de forma asincrona y limita el trafico a 3 RPM.
                        """));
    }
}
