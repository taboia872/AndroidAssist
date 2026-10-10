# AndroidAssist

Assistente Android em Kotlin/Compose, reconstruído do zero com arquitetura dinâmica.

## Princípios

1. **Zero hardcode** — provider, endpoint, API key, modelo e prompt são configuração, não código. Nenhuma tela lê SharedPreferences direto: tudo passa pelo `AppSettings` (StateFlow único).
2. **Catálogo de modelos sempre ao vivo** — a lista vem do endpoint ativo (`GET /models`), nunca de uma lista fixa no app.
3. **Um provider genérico** — qualquer endpoint OpenAI-compatible funciona (OpenRouter, Groq, Ollama Cloud/PC, NVIDIA NIM...), incluindo base URL customizada para Ollama local.

## Estado (fase 0 — esqueleto)

- Chat funcional com streaming (content + reasoning separados)
- Settings: provider, base URL, API key (encriptada), modelo (catálogo ao vivo), system prompt
- Próximas fases: voz (STT), integração como assistente do sistema, busca web

## Build

```bash
# Requer Android SDK; CI (GitHub Actions) builda o APK a cada push
./gradlew assembleDebug
```
