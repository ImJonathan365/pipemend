# ADR-0006 — Versiones fijadas y herramienta de build

- **Estado:** Accepted
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** NFR-17, NFR-18
- **Reemplaza a:** la versión `Proposed` de este mismo ADR (Java 21 / "última estable ≥ 3.5")

## Contexto

1. **Spring Boot 3.5 llegó a fin de soporte open source el 30/06/2026.** Iniciar un proyecto nuevo sobre la rama 3.x significa arrancar sin parches de seguridad upstream. Las ramas con soporte OSS son **4.0** (hasta 31/12/2026) y **4.1** (hasta ~31/07/2027).
2. Spring Framework 7 / Spring Boot 4.x soportan **JDK 17 a 25/26** y recomiendan JDK 25 para producción. **Java 25 es la LTS vigente**.
3. Spring Boot 4.x trae **Spring Batch 6**, con cambios de API y de paquetes respecto a Batch 5.
4. El proyecto se desarrolla con asistencia de modelos de IA, cuyo material de entrenamiento está dominado por ejemplos de Spring Batch 5 y Spring Boot 3.x.

El criterio no es "la última LTS por ser la última", sino: **la última LTS que el framework soporta oficialmente y para la que existen imágenes base y herramientas estables**. En este caso ambos coinciden.

## Decisión

| Componente | Versión fijada | Nota |
|---|---|---|
| Java | **25 (LTS)** | `toolchain { languageVersion = JavaLanguageVersion.of(25) }` |
| Spring Boot | **4.1.x** (la última parche al iniciar) | No usar 3.x (EOL) |
| Spring Batch | **6.x** (la gestionada por Boot 4.1) | Ver §"Batch 5 vs 6" |
| Build Java | **Gradle con Kotlin DSL** | Un solo `build.gradle.kts`; wrapper commiteado |
| Imagen base Java | `eclipse-temurin:25-jdk` (build) → `eclipse-temurin:25-jre` (runtime) | Dockerfile multi-stage |
| Python | **3.14.x** | Imagen `python:3.14-slim` |
| FastAPI / Pydantic | Últimas estables / Pydantic **v2** | |
| PostgreSQL | **18.x** | Imagen `postgres:18-alpine` |
| Resilience4j, springdoc-openapi, Flyway, Testcontainers, WireMock | Las versiones compatibles con Spring Boot 4.1 (preferir las gestionadas por el BOM) | Verificar compatibilidad con Boot 4 antes de agregar |

Reglas derivadas:

- Las versiones exactas (parche) se fijan en `build.gradle.kts`, `pyproject.toml` y `docker-compose.yml` durante el Sprint 1, y esos archivos son la referencia operativa; esta tabla fija las **mayores**.
- Subir una versión mayor de cualquier fila requiere un ADR nuevo.
- Antes de agregar una dependencia, verificar que tenga una versión compatible con Spring Boot 4 / Java 25. Si no la tiene, **no** se baja la versión de Boot: se busca una alternativa o se consulta al owner.

## Batch 5 vs Batch 6 (diferencias que importan aquí)

| Tema | Spring Batch 5 (NO usar) | Spring Batch 6 (usar) |
|---|---|---|
| Lanzar un job | `JobLauncher` | `JobOperator` (extiende `JobLauncher`, que queda deprecado; el framework ya no expone un bean `JobLauncher` dedicado) |
| Consultar ejecuciones | `JobExplorer` | `JobRepository` (extiende `JobExplorer`) |
| Configuración del repositorio | `@EnableBatchProcessing` ligado a JDBC | `@EnableBatchProcessing` desacoplado + `@EnableJdbcJobRepository` |
| Configuración programática | `DefaultBatchConfiguration` | `JdbcDefaultBatchConfiguration` (la base ahora es "resourceless") |
| Paquetes | `org.springframework.batch.item.*`, `org.springframework.batch.core.*` | Infraestructura movida a `org.springframework.batch.infrastructure.*`; listeners a `org.springframework.batch.core.listener`; job y parámetros a `org.springframework.batch.core.job[.parameters]` |
| Chunk | `.chunk(size, transactionManager)` | `.chunk(size).transactionManager(transactionManager)` |
| Modelo de dominio | IDs `Long`, constructores vacíos + setters | IDs `long`, `JobParameter` como `record`, objetos inmutables con dependencias en el constructor |

Referencia primaria: guía de migración oficial de Spring Batch 5 → 6. Ante la duda, la documentación oficial de la versión fijada **manda sobre cualquier ejemplo encontrado en internet o generado por un modelo**.

## Alternativas consideradas

| Alternativa | Pros | Contras | Motivo de descarte |
|---|---|---|---|
| Java 21 + Spring Boot 3.5 | Muchísimo material y ejemplos; APIs conocidas por los modelos | Rama EOL desde 06/2026; se ve desactualizado en un portafolio de 2026 | Un proyecto de portafolio nuevo no debe nacer sin soporte |
| Java 21 + Spring Boot 4.1 | Soportado (Boot 4 acepta 17+) | Renuncia a la LTS vigente sin ganar estabilidad real | Sin beneficio |
| Java 26 (no LTS, si estuviera disponible) | Lo más nuevo | Ciclo de soporte de 6 meses; no aporta al proyecto | Innecesario |
| Maven en lugar de Gradle | Más común en entornos Spring corporativos; XML predecible para modelos | Build más verboso | Preferencia del owner; cambiar de build tool implica solo editar esta fila y el Sprint 1 |

## Consecuencias

- **Positiva:** el portafolio muestra el stack vigente (Java 25 LTS, Spring Boot 4.1, Batch 6, PostgreSQL 18), lo cual es un punto a favor ante evaluadores técnicos.
- **Negativa (real):** hay menos ejemplos públicos de Batch 6 y los asistentes de IA tienden a producir código de Batch 5. Mitigación: tabla de diferencias anterior, regla en `09` §7 bis, y un esqueleto de job funcionando desde el Sprint 2 que sirva de plantilla interna.
- **Negativa menor:** alguna librería de terceros puede tardar en soportar Boot 4 / Java 25. Mitigación: preferir lo gestionado por el BOM de Spring Boot.

## Criterio de revisión

- Publicación de una nueva LTS de Java soportada por la rama de Boot en uso.
- Fin de soporte OSS de Spring Boot 4.1.
- Una dependencia imprescindible sin soporte para Boot 4 / Java 25.
