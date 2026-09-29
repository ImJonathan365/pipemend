# 08 — Definition of Done

Tres niveles: por historia/PR, por sprint y por proyecto completo. Un ítem no marcado significa **no hecho**.

## 1. DoD por historia / Pull Request

- [ ] Referencia a los IDs afectados (`FR-xx`, `NFR-xx`, `ADR-xxxx`) en el título o descripción del PR.
- [ ] Cada criterio de aceptación (`AC-xx.y`) implementado tiene al menos un test automatizado que lo cubre; el nombre o `@DisplayName` del test menciona el AC.
- [ ] Tests pasan localmente y en CI (`./gradlew test`; `pytest`). La descripción del PR incluye los comandos ejecutados y su resultado resumido (un agente no declara "tests pasan" sin haberlos ejecutado). <!-- rev: R-33 -->
- [ ] Ningún test, aserción, umbral de cobertura, fixture, muestra o `labels.csv` fue debilitado, deshabilitado o regenerado para que un test pase (`09` §2.3).
- [ ] Sin llamadas a LLM reales en tests.
- [ ] Lint y formato sin errores (Spotless; `ruff check`, `ruff format --check`, `mypy`).
- [ ] Si cambia un contrato: modelos Pydantic, `contracts/ai-service.openapi.json`, fixtures, DTOs Java y `06-contrato-api.md` actualizados en el **mismo** PR.
- [ ] Si cambia la BD: nueva migración Flyway (nunca editar una existente) y `05-modelo-de-datos.md` actualizado.
- [ ] Si cambia comportamiento documentado: el documento de `/docs` correspondiente está actualizado.
- [ ] Si introduce una decisión de arquitectura: ADR nuevo con estado `Accepted` (aprobado por el owner) **fusionado antes** que el código que la implementa (`09` §5).
- [ ] Solo toca archivos relacionados con los FR/NFR declarados; si toca un archivo protegido (`09` §2.1), el PR lo declara en una sección "Archivos protegidos modificados" con la aprobación del owner.
- [ ] Logs: eventos relevantes a nivel correcto, sin secretos ni datos crudos en `INFO`.
- [ ] Errores HTTP nuevos en formato problem+json con `code` estable.
- [ ] Endpoints nuevos documentados en OpenAPI con ejemplos.
- [ ] `docker compose up --build` sigue funcionando.

## 2. DoD por sprint

- [ ] Entregables del sprint (ver `07-plan-de-sprints.md`) funcionando desde `docker compose up`.
- [ ] La "Demo" del sprint se ejecutó de principio a fin.
- [ ] Tag `v0.{n}` creado.
- [ ] `docs/CHANGELOG.md` actualizado: hecho, recortado, pendiente, decisiones.
- [ ] Ningún test deshabilitado sin issue que lo justifique.

## 3. DoD del proyecto completo (v1.0)

### 3.1 Funcional

- [ ] Todos los FR **Must** implementados y sus AC cubiertos por tests.
- [ ] FR **Should** implementados o documentados como recortados en el README (sección "Limitaciones y siguientes pasos"), con su motivo.
- [ ] Muestras de FR-03 disponibles y funcionando (las que no se hayan recortado).
- [ ] Con el servicio de IA apagado, un lote con registros sucios termina `COMPLETED` y los inválidos quedan en cuarentena con `AI_UNAVAILABLE` (verificado por test).

### 3.2 Calidad de datos y de IA

- [ ] Invariante `read = direct + autoCorrected + quarantined` verificado por test para cada muestra.
- [ ] Ninguna fila aparece en `sales_transaction` y `quarantine_record` a la vez (test).
- [ ] Reenviar el mismo archivo → `409`; reenviar un archivo `REJECTED` → lote nuevo; reinicio tras falla → sin duplicados; lote zombi reconciliado a `FAILED`/`INTERRUPTED` al arrancar (tests). <!-- rev: R-12 -->
- [ ] Test que verifica que la metadata de Spring Batch se persiste en PostgreSQL (`BATCH_JOB_EXECUTION` con filas tras un job). <!-- rev: R-16 -->
- [ ] Evaluación con `mock` sobre `dirty-1k` (AC-20.3): **cada fila** termina con su `expected_outcome`, **0** auto-correcciones incorrectas, **0** defectos no corregibles en la tabla limpia (test en CI). Con mock el *recall* esperado es 100 %: el 80 % de OBJ-3 es la meta para el LLM real, no el gate del mock. <!-- rev: R-26 -->
- [ ] Suite adversarial (AC-10.6) en verde: ninguna corrección plausible pero incorrecta llega a `sales_transaction`. <!-- rev: R-25 -->
- [ ] Test de IA colgada (AC-12.5) en verde dentro de la cota de NFR-07. <!-- rev: R-11 -->
- [ ] Al menos un reporte de evaluación con un LLM real publicado en `reports/` y enlazado desde el README, con números honestos (incluidos errores).

### 3.3 No funcional

- [ ] Clon limpio → `cp .env.example .env && docker compose up --build` → los 3 servicios `healthy` sin pasos adicionales (probado en una máquina/directorio distinto del de desarrollo).
- [ ] Objetivos de NFR-10 medidos; resultados reales en el README (aunque no se alcancen todos, se documentan).
- [ ] Logs JSON con `batchId` y `requestId` correlacionables entre ambos servicios.
- [ ] `/actuator/health`, `/health`, `/v1/info` operativos.
- [ ] Cobertura ≥ 70 % global por servicio; ≥ 90 % en validación, política y verificadores.
- [ ] Sin secretos en el historial de git (revisado con `gitleaks` o equivalente).
- [ ] CI verde en la rama principal.

### 3.4 Documentación y presentación

- [ ] README raíz con: pitch de 3 líneas, diagrama, stack, quickstart, demo de 5 minutos (comandos `curl` o `scripts/demo.sh`), capturas/GIF, resultados de evaluación, decisiones clave (enlaces a ADRs), limitaciones conocidas, roadmap (fase 2).
- [ ] Swagger de ambos servicios con descripciones y ejemplos.
- [ ] `/docs` coherente con el código (revisión final punto por punto de `02` y `06`).
- [ ] ADRs de todas las decisiones tomadas durante el desarrollo.
- [ ] Sección "Cómo cambiar de proveedor LLM" verificada con al menos dos proveedores (`mock` + uno real), incluida la advertencia de configurar un límite de gasto en el proveedor.
- [ ] README con sección "Limitaciones conocidas" que incluya al menos: sin autenticación; la redacción no aplica a `MALFORMED_ROW`; un lote `FAILED` sin FR-21 solo se reprocesa con `down -v`; `MAP_TO_ENUM` solo auto-corrige alias presentes en `country-aliases.v1.yaml`; la confianza del LLM no está calibrada. <!-- rev: R-23, R-12, R-06 -->

### 3.5 Prueba de aceptación final (guion)

Se ejecuta completa antes de etiquetar `v1.0`:

> Las muestras son idempotentes por checksum (AC-03.4): este guion supone una base vacía. Para repetirlo, `docker compose down -v` primero. <!-- rev: R-35 -->

1. `git clone` en un directorio nuevo → `cp .env.example .env` → `docker compose up --build -d`.
2. Esperar `healthy`; abrir ambos Swagger.
3. `POST /api/v1/ingestions/samples/clean-1k.csv` → `COMPLETED`, 1000 `DIRECT`.
4. Subir `dirty-1k.csv` → `COMPLETED`; `counts` coinciden con lo esperado por `labels.csv` (±0 con `mock`).
5. Subir de nuevo `dirty-1k.csv` → `409`.
6. Ver una corrección (`GET /api/v1/corrections?batchId=...`) y un registro en cuarentena con explicación.
7. `POST .../samples/drift-legacy-headers.csv` → procesado con mapeo AI (si FR-05 no se recortó).
8. `docker compose stop ai-service` → subir `dirty-10k.csv` → `COMPLETED` con cuarentena `AI_UNAVAILABLE`; `docker compose start ai-service`.
8 bis. Consultar el registro de un defecto `DATE_AMBIGUOUS` (fila tomada de `labels.csv`) y comprobar que está en cuarentena con una explicación de la ambigüedad día/mes y, si llegó a la política, con `policyDecision` que lo justifica. <!-- rev: R-29 -->
9. `GET /api/v1/metrics/summary` → números coherentes (invariante).
10. `python scripts/evaluate.py --batch <id>` → reporte generado.
11. `docker compose down -v` → limpio.
