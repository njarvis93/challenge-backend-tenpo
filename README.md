# Tenpo — Backend Challenge 2026

API REST reactiva construida con **Spring Boot 4 / Spring WebFlux** y **Java 21**. Calcula la suma de dos números aplicando un porcentaje obtenido de un servicio externo, registra de forma asíncrona el historial de llamadas en PostgreSQL y limita el tráfico a 3 peticiones por minuto mediante un token bucket distribuido en Redis.

---

## Índice

- [Levantar el proyecto](#levantar-el-proyecto)
- [Endpoints](#endpoints)
- [Cómo probar cada funcionalidad](#cómo-probar-cada-funcionalidad)
- [Documentación de la API](#documentación-de-la-api)
- [Arquitectura](#arquitectura)
- [Tests](#tests)
- [Integración continua](#integración-continua)
- [Decisiones técnicas](#decisiones-técnicas)
- [Imagen en Docker Hub](#imagen-en-docker-hub)

---

## Levantar el proyecto

### Con Docker Compose (recomendado)

Único requisito: Docker. No hace falta compilar nada: la imagen se descarga de Docker Hub.

```bash
docker compose up -d
```

Levanta tres contenedores: la API (`:8080`), PostgreSQL (`:5432`) y Redis (`:6379`). La API espera a que ambos estén *healthy* antes de arrancar, y Flyway crea el esquema en el primer arranque.

La imagen publicada es **[`njarvis93/tenpo-challenge:1.0.0`](https://hub.docker.com/r/njarvis93/tenpo-challenge)**, construida para `linux/amd64` y `linux/arm64`.

Para **compilar desde el código fuente** en lugar de descargar la imagen:

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml up --build -d
```

Comprobar que está arriba:

```bash
curl http://localhost:8080/actuator/health
```

Ver los logs:

```bash
docker compose logs -f api
```

Detener todo (`-v` elimina también los datos de PostgreSQL):

```bash
docker compose down -v
```

### En local, sin contenerizar la API

Se requieren PostgreSQL y Redis en marcha. Lo más cómodo es levantar solo esas dos dependencias con Compose:

```bash
docker compose up -d postgres redis
```

Y luego arrancar la aplicación con el wrapper de Maven (no hace falta tener Maven instalado):

```bash
./mvnw spring-boot:run
```

Las variables de entorno soportadas están en [`.env.example`](.env.example); todas tienen valores por defecto que funcionan con el Compose de este repositorio.

---

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/v1/calculations` | Suma dos números y les aplica el porcentaje del servicio externo |
| `GET` | `/api/v1/history` | Historial paginado de llamadas a la API |
| `GET` | `/internal/mock/percentage` | Mock del servicio externo (fuera de `/api`, no consume cuota) |
| `GET` | `/actuator/health` | Estado del servicio |
| `GET` | `/swagger-ui.html` | Documentación interactiva |

### `POST /api/v1/calculations`

```bash
curl -X POST http://localhost:8080/api/v1/calculations \
  -H 'Content-Type: application/json' \
  -d '{"num1": 5, "num2": 5}'
```

```json
{
  "num1": 5,
  "num2": 5,
  "percentageApplied": 12.4,
  "result": 11.24
}
```

El porcentaje es **aleatorio en cada llamada** (ver [Decisiones técnicas](#decisiones-técnicas)), por eso la respuesta incluye `percentageApplied`: sin ese dato el resultado no sería verificable por el cliente.

### `GET /api/v1/history`

```bash
curl 'http://localhost:8080/api/v1/history?page=0&size=10'
```

```json
{
  "content": [
    {
      "id": 12,
      "calledAt": "2026-09-04T03:21:44.512Z",
      "endpoint": "/api/v1/calculations",
      "httpMethod": "POST",
      "parameters": "body={\"num1\": 5, \"num2\": 5}",
      "response": "{\"num1\":5,\"num2\":5,\"percentageApplied\":12.4,\"result\":11.24}",
      "error": null,
      "statusCode": 200,
      "durationMs": 37
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1
}
```

Parámetros: `page` (base 0, por defecto `0`) y `size` (por defecto `20`, máximo `100`). El orden es siempre por fecha descendente.

---

## Cómo probar cada funcionalidad

Los ejemplos usan `curl` por ser copiables tal cual, pero todos los endpoints pueden ejercitarse igual desde **[Swagger UI](http://localhost:8080/swagger-ui.html)** con el botón *Try it out*, incluidos el `429` del rate limit y los errores de validación.

### 1. Cálculo con porcentaje dinámico

```bash
curl -X POST http://localhost:8080/api/v1/calculations \
  -H 'Content-Type: application/json' -d '{"num1": 5, "num2": 5}'
```

Con un porcentaje del 10% el resultado sería `11.00`, tal como pide el enunciado. Como el mock devuelve un valor aleatorio entre 5% y 20%, el resultado real estará entre `10.50` y `12.00` y cambiará entre llamadas.

### 2. Reintentos ante fallos del servicio externo

Se levanta la API forzando que el servicio externo falle siempre:

```bash
MOCK_FAILURE_RATE=1.0 docker compose up -d --force-recreate api
```

```bash
curl -i -X POST http://localhost:8080/api/v1/calculations \
  -H 'Content-Type: application/json' -d '{"num1": 5, "num2": 5}'
```

Responde `503` y en los logs se ven los tres intentos:

```bash
docker compose logs api | grep "servicio de porcentaje"
```

Para volver al comportamiento normal: `docker compose up -d --force-recreate api`.

### 3. Historial de llamadas

Tras un par de llamadas al endpoint de cálculo, el historial se consulta así:

```bash
curl 'http://localhost:8080/api/v1/history?page=0&size=5'
```

El registro es asíncrono, así que la escritura no suma latencia a la respuesta. Se registran también las llamadas fallidas, incluidas las rechazadas por rate limit.

### 4. Rate limiting (3 RPM)

```bash
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "intento $i -> %{http_code}\n" http://localhost:8080/api/v1/history
done
```

```
intento 1 -> 200
intento 2 -> 200
intento 3 -> 200
intento 4 -> 429
```

La respuesta `429` incluye la cabecera `X-Rate-Limit-Retry-After-Seconds` y un cuerpo descriptivo.

### 5. Manejo de errores HTTP

```bash
# 400 — falta num2
curl -s -X POST http://localhost:8080/api/v1/calculations \
  -H 'Content-Type: application/json' -d '{"num1": 5}' | jq

# 404 — ruta inexistente
curl -s http://localhost:8080/api/v1/no-existe | jq
```

Todas las respuestas de error siguen el formato **RFC 7807** (`application/problem+json`):

```json
{
  "type": "https://tenpo.cl/errors/400",
  "title": "Petición inválida",
  "status": 400,
  "detail": "num2: num2 es obligatorio",
  "instance": "/api/v1/calculations",
  "timestamp": "2026-09-04T03:22:10.884Z"
}
```

---

## Documentación de la API

Con el servicio levantado:

- **Swagger UI** → http://localhost:8080/swagger-ui.html
- **OpenAPI JSON** → http://localhost:8080/v3/api-docs

Swagger UI no solo documenta los endpoints: permite ejecutarlos desde el navegador con el botón **Try it out**, que envía la petición real al servicio y muestra el código de estado, las cabeceras y el cuerpo de la respuesta. Es la vía más cómoda para recorrer la API sin escribir un solo `curl`.

---

## Arquitectura

```
cl.tenpo.challenge
├── config/     AppProperties, WebClientConfig, RedisConfig, OpenApiConfig
├── domain/     CalculationService, CallHistoryService, PercentageProvider (puerto)
├── infra/      RemotePercentageProvider (WebClient + reintentos),
│               MockPercentageController, CallHistory (entidad) + repositorio
└── web/        Controladores, DTOs, GlobalExceptionHandler
    └── filter/ CallHistoryWebFilter (orden 0), RateLimitWebFilter (orden 10)
```

Flujo de una petición a `/api/**`:

```
Cliente
  │
  ▼
CallHistoryWebFilter ─── registra la llamada (asíncrono, fuera de la respuesta) ──► PostgreSQL
  │
  ▼
RateLimitWebFilter ───── token bucket ──────────────────────────────────────────► Redis
  │                       └── sin cupo ─► 429
  ▼
Controlador ─► CalculationService ─► RemotePercentageProvider ──► Servicio externo
                                          └── 3 intentos, si fallan todos ─► 503
```

El filtro de historial es el **más externo** a propósito: así quedan registradas también las peticiones rechazadas con `429`, que nunca llegan al controlador.

---

## Tests

```bash
./mvnw verify
```

Requiere Docker: los tests de integración levantan PostgreSQL y Redis con Testcontainers.

| Test | Qué cubre |
|---|---|
| `CalculationServiceTest` | Fórmula, redondeo `HALF_UP` a 2 decimales y propagación de errores |
| `RemotePercentageProviderTest` | Reintentos contra un servidor HTTP real (WireMock): éxito, fallo‑luego‑éxito, 3 fallos → error, y timeout |
| `CallHistoryRepositoryTest` | Esquema de Flyway y paginación sobre PostgreSQL real |
| `ApiIntegrationTest` | Recorrido end‑to‑end: cálculo, validación, `503`, `429`, registro asíncrono en historial y `404` |

`verify` además ejecuta las puertas de calidad descritas en [Integración continua](#integración-continua): genera el reporte de cobertura en `target/site/jacoco/` y falla si la cobertura de líneas baja del **80 %**, y pasa el código por SpotBugs y PMD. Cobertura actual: **88 % de líneas**.

---

## Integración continua

Dos workflows de GitHub Actions se ejecutan en paralelo en cada push a `main` y en cada pull request. No dependen de ningún servicio externo ni requieren cuentas ni secretos: todo el análisis corre dentro del propio runner.

| Workflow | Qué hace |
|---|---|
| [`build.yml`](.github/workflows/build.yml) | Compila con JDK 21, ejecuta la suite completa de tests (Testcontainers usa el Docker del runner), publica el jar y los reportes, y valida que el `Dockerfile` construya |
| [`quality.yml`](.github/workflows/quality.yml) | Ejecuta las tres puertas de calidad y publica los reportes junto con un resumen de cobertura |

### Puertas de calidad

Están declaradas en el `pom.xml` y se aplican en la fase `verify`, así que `./mvnw verify` reproduce en local exactamente lo que valida el CI:

| Herramienta | Qué comprueba | Cuándo falla |
|---|---|---|
| **JaCoCo** | Cobertura de tests | Cobertura de líneas por debajo del **80 %** (propiedad `coverage.minimum`) |
| **SpotBugs** + **FindSecBugs** | Bugs y vulnerabilidades sobre el bytecode: NPEs, recursos sin cerrar, comparaciones sospechosas, patrones inseguros | Cualquier hallazgo de prioridad media o superior |
| **PMD** + **CPD** | Code smells: código muerto, complejidad ciclomática y cognitiva, malas prácticas, bloques duplicados | Cualquier violación de las reglas activas |

Las reglas de PMD viven en [`config/pmd-ruleset.xml`](config/pmd-ruleset.xml) y las exclusiones de SpotBugs en [`config/spotbugs-exclude.xml`](config/spotbugs-exclude.xml). Ambos archivos documentan el porqué de cada exclusión: solo se descartan reglas que en un proyecto Spring señalan como defecto algo que es correcto por diseño —por ejemplo, `EI_EXPOSE_REP` sobre beans singleton inyectados por el contenedor—, nunca hallazgos concretos sin justificación.

`build.yml` omite SpotBugs y PMD (`-Dspotbugs.skip -Dpmd.skip -Dcpd.skip`) para no repetir el mismo análisis que ya hace `quality.yml`.

---

## Decisiones técnicas

### Spring WebFlux para la capa web

El enunciado ofrecía punto extra por programación reactiva, pero la razón de fondo es que este servicio es **I/O bound**: cada petición espera por un servicio HTTP externo y por la base de datos. Con el modelo de un hilo por petición, la mayor parte del tiempo esos hilos están bloqueados sin hacer nada. WebFlux atiende con un puñado de hilos (uno por core) y libera el hilo mientras espera, lo que sostiene mucha más concurrencia con menos memoria.

### JPA sobre R2DBC para la capa de persistencia

El modelo de datos es una única tabla plana sin relaciones, así que el criterio de elección no fue el mapeo objeto‑relacional sino el ecosistema alrededor: con JPA vienen de serie Flyway para versionar el esquema, `Pageable` para la paginación del historial y `@DataJpaTest` para los tests de repositorio contra PostgreSQL real. R2DBC habría exigido escribir a mano el SQL de paginado y de conteo, y resolver la inicialización del esquema por fuera de Flyway, a cambio de una ganancia marginal en un endpoint de consulta que no está en la ruta crítica.

Como JPA bloquea el hilo mientras espera a la base de datos, *todo* el acceso a datos se concentra en un único punto, `CallHistoryService`, que publica las operaciones en `Schedulers.boundedElastic()`. El event loop de Netty nunca queda bloqueado: el bloqueo se confina a un pool elástico dimensionado junto al pool de conexiones de HikariCP. Al estar aislado en esa clase, migrar a R2DBC más adelante —si el volumen del historial lo justificara— es un cambio acotado que no toca el resto de la aplicación.

### El mock devuelve un porcentaje aleatorio, no un valor fijo

El servicio externo se simula con un **valor aleatorio** entre 5% y 20% (`app.mock.min-percentage` / `max-percentage`) en lugar de un porcentaje constante. Un mock que siempre devuelve el mismo número no ejercita la lógica: con un valor fijo, un error de redondeo o un porcentaje que no se propaga hasta la respuesta pasarían desapercibidos al probar la API a mano. Al variar en cada llamada, el porcentaje aplicado queda a la vista en `percentageApplied` y el cálculo es verificable de un vistazo.

La aleatoriedad no alcanza a los tests: éstos sustituyen el servicio externo por WireMock con un valor fijo, de modo que las aserciones son deterministas. Para reproducir una secuencia concreta en una demo o al depurar, `app.mock.seed` fija la semilla del generador.

### Reintentos con backoff exponencial

`Retry.backoff(2, 200ms)` sobre el `Mono` del `WebClient`: 1 intento original + 2 reintentos = **3 intentos totales**, con espera creciente y *jitter* para no golpear en sincronía a un servicio que se está recuperando. Agotados los intentos, el error se traduce a una excepción de dominio que el manejador global convierte en `503`.

### Rate limiting con Bucket4j sobre Redis

Un contador en memoria limitaría a 3 RPM **por instancia**: con dos réplicas el límite real sería 6. Alojar el token bucket en Redis mantiene el límite global independientemente de cuántas instancias corran. Las operaciones contra Redis son asíncronas (`CompletableFuture` envuelto en `Mono`), de modo que el filtro tampoco bloquea el event loop.

Se usa `refillGreedy`: los tokens se reponen de forma continua (uno cada 20 s) en vez de liberarse los 3 de golpe al cambiar de minuto, lo que evita ráfagas en el borde de la ventana.

### Historial asíncrono y a prueba de fallos

El registro se dispara desde un `WebFilter` **fuera de la cadena de respuesta**: el cliente recibe su respuesta sin esperar a la escritura en base de datos. Si la escritura falla, se registra el error en el log y la petición del cliente no se ve afectada — un historial caído no puede tumbar la funcionalidad principal.

Los cuerpos de request y response se truncan a 2.000 caracteres: el historial es una bitácora de auditoría, no un almacén de payloads.

### Errores en formato RFC 7807

Todas las respuestas de error usan `ProblemDetail` (`application/problem+json`), un estándar que los clientes pueden parsear de forma uniforme, con `type`, `title`, `status`, `detail`, `instance` y `timestamp`.

---

## Imagen en Docker Hub

Publicada en **[njarvis93/tenpo-challenge](https://hub.docker.com/r/njarvis93/tenpo-challenge)**, multi‑arquitectura (`linux/amd64` y `linux/arm64`), con los tags `1.0.0` y `latest`.

Es la que levanta `docker compose up -d` por defecto; para descargarla por separado:

```bash
docker pull njarvis93/tenpo-challenge:1.0.0
```
