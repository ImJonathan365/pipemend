# ADR-0002 — Dos servicios (Spring Boot + FastAPI) comunicados por HTTP síncrono

- **Estado:** Accepted
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-08, FR-12, NFR-07, NFR-17

## Contexto
El stack exige Spring Boot/Spring Batch para el pipeline y FastAPI para la IA. El ecosistema de SDKs de LLM y validación de salida estructurada (Pydantic) es más maduro en Python.

## Decisión
- `pipeline-service` (Spring) orquesta y es dueño de los datos; `ai-service` (FastAPI) encapsula prompts, proveedores y validación de salida.
- Comunicación **HTTP/JSON síncrona**, unidireccional (`pipeline → ai`), con timeouts, reintentos y circuit breaker en el cliente.
- Contrato versionado en ruta (`/v1`) y documentado en `06-contrato-api.md` + `contracts/`.

## Alternativas consideradas
| Alternativa | Motivo de descarte |
|---|---|
| Todo en Java (Spring AI / SDK Java) | Contradice el stack obligatorio; se pierde la demostración de integración políglota |
| Mensajería (Kafka/RabbitMQ) | Complejidad operativa desproporcionada para un batch de portafolio; dificulta el "un comando" |
| gRPC | Menos legible para evaluadores; Swagger es parte del entregable |

## Consecuencias
- Latencia de red por registro inválido (mitigada con caché y tope).
- Punto de falla adicional → degradación segura obligatoria (FR-12).
- Evolución independiente de cada servicio mientras se respete el contrato.
