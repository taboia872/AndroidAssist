# AndroidAssist

Assistente Android nativo em Kotlin/Compose — chat multi-provider com voz, busca web e integração como assistente do sistema.

## Recursos

- **Chat multi-provider** — qualquer endpoint OpenAI-compatible: OpenRouter, Groq, Ollama Cloud/PC, NVIDIA NIM, endpoints customizados salvos
- **Streaming completo** — tokens ao vivo, blocos de pensamento colapsáveis, blocos de ferramenta com resultados da busca, respostas agênticas em múltiplas rodadas
- **Markdown real** — tabelas GFM, code blocks com botão de copiar, listas, headers (lib [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer))
- **Busca web** (function calling) — DuckDuckGo (sem chave), Parallel MCP (sem chave), Brave, Exa, LangSearch, Jina Reader; parâmetros: nº de resultados, região, recenticidade
- **Voz** — STT: reconhecedor do sistema ou Groq Whisper; TTS: motor do sistema ou FishAudio; idiomas de captura e síntese independentes
- **Assistente do sistema** — responde ao gesto do assistente com overlay "bloquinho", auto-start de escuta
- **Temas** — sistema (teal), dinâmico (Material You), claro, escuro + cor de destaque
- **Personas** — perfis completos (prompt, servidor, modelo, busca, voz)
- **Notificações** — aviso quando o modelo responde com o app em background
- **Modo sem pensamento** — `chat_template_kwargs {enable_thinking:false}` pra modelos locais rápidos

## Build

```bash
# Requer Android SDK; CI (GitHub Actions) builda o APK a cada push em main
./gradlew assembleDebug
```

APKs debug e release são assinados com a mesma chave (secrets `AA_KEYSTORE_BASE64`, `AA_KEY_ALIAS`, `AA_KEY_PASSWORD`, `AA_STORE_PASSWORD`) — **instala por cima da versão anterior sem desinstalar** (dados de API keys e históricos preservados).

## Releases

Tags `v*` disparam o workflow `.github/workflows/release.yml`: builda debug+release assinados e publica um GitHub Release com os dois APKs.

## Instalação

1. Baixe o APK do [GitHub Releases](../../releases)
2. Instale permitindo "fontes desconhecidas"
3. Atualizações futuras instalam direto por cima (mesma assinatura)
