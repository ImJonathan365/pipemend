# ADR-0004 — Abstracción de proveedor LLM con `mock` por defecto

- **Estado:** Accepted
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-17, NFR-02, NFR-11, NFR-13

## Contexto
El sistema debe poder cambiar de proveedor sin tocar código, levantarse sin API key para evaluadores, y testearse sin red ni costo.

## Decisión
- `Protocol LLMProvider` en `ai-service` con implementaciones `mock`, `openai_compatible` (cubre OpenAI, Ollama, Groq, OpenRouter, LM Studio vía `LLM_BASE_URL`) y `anthropic`.
- Selección por `LLM_PROVIDER`; modelo por `LLM_MODEL`. Ningún nombre de modelo en código.
- `mock` es el default: reglas deterministas que cubren todo el catálogo de defectos y producen respuestas con el mismo contrato.
- Prompts versionados en archivos; salida validada con Pydantic; un intento de reparación.
- Sin frameworks de orquestación (LangChain, LlamaIndex): el caso de uso es una llamada estructurada, no un agente.

## Consecuencias
- Demo y CI gratuitas y deterministas.
- Riesgo: que el `mock` "maquille" la calidad real → mitigado publicando evaluaciones con un LLM real (FR-20).
