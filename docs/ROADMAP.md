# Roadmap AndroidAssist

Estratégia e backlog de funcionalidades. **Este arquivo é a fonte da verdade do
que está planejado** — se um chat encher, retome lendo este arquivo + a skill
`androidassist-app` (regras, armadilhas, fluxo de build).

Última atualização: 2026-11-17 (play_media + apagar pasta + subpastas + pasta de backup).

---

## Estado atual (v0.3.0, 2026-11-16)

Já existe e funciona:

- Chat multi-provider com streaming, blocos (pensamento/resposta/tool), markdown,
  visão (imagem), PDFs.
- Voz: STT (sistema ou Groq Whisper) e TTS (sistema ou FishAudio), com VAD adaptativo.
- Tools independentes com **toggle por habilidade**: busca web, 8 habilidades
  Android (alarme, timer, agenda, abrir app,剪贴板, compartilhar, localização,
  mapa) e 3 tools de memória. Cada grupo tem seu switch.
- O prompt agora **lista as tools disponíveis e as desligadas** — o modelo para de
  tentar chamar habilidade desativada.
- Personas completas (prompt + modelo + voz + busca).
- Backup fracionado (personas / memórias / chaves / chats / endpoints).
- Overlay do gesto do assistente.

O que **NÃO** existe ainda (verificado no código em 2026-11-16):

| Capacidade | Status |
|---|---|
| Pasta apontada + tools de arquivo | ✅ feito (`493e508` + `359a4dd`) — inclusive subpastas |
| Pasta de backup dentro da pasta apontada | ✅ feito (`96a8c57`) |
| Agendamento de tarefa (executor em background) | ❌ não existe (manifest sem nenhum `receiver`, sem `BOOT_COMPLETED`, sem `FOREGROUND_SERVICE`) |
| Página HTML gerada + link no chat | ❌ não existe (nenhum `WebView` no app) |
| Modelo por tarefa / pipeline imagem→principal | ❌ não existe (`AppConfig` tem UM `model`) |
| Tocar mídia (música/arquivo/YouTube) | ✅ feito (`c63795c`) — MediaStore + intent pro player; YouTube abre a busca |
| Enviar mensagem a contato específico | ⚠️ parcial — `share_text` abre o share sheet genérico |
| Leitura de contatos | ❌ não existe (`ContactsContract` ausente) |

---

## Ordem de execução (acordada com o Juliano)

| # | Item | Esforço | Impacto | Status |
|---|---|---|---|---|
| 1 | **(a) Pasta apontada + tools de arquivo** | médio | alto | ✅ **FEITO** (commit `493e508`) |
| 2 | **(e) Contato + WhatsApp** | médio | alto | ✅ **FEITO** (commit `493e508`) |
| 3 | (d) `play_media` — tocar música | baixo | alto | ✅ **FEITO** (commit `c63795c`) |
| 4 | (c) `modelVision` — pipeline de imagem | médio | médio | pendente |
| 5 | (b) Agendamento de tarefas | alto | alto | pendente (por último) |

### Por que (a) e (e) juntas

(a) cria a infraestrutura de "pasta apontada" (SAF `ACTION_OPEN_DOCUMENT_TREE` +
persistência de URI). (e) precisa de um lugar para gravar a fila de mensagens e
de um padrão de permissão (`READ_CONTACTS`). As duas são **tools novas no mesmo
mecanismo**: `ToolAbility` + `DeviceTools` + `AppSettings` + a aba Ferramentas.
Fazê-las no mesmo ciclo evita dois passes pelo mesmo código.

### O que foi feito em (a)+(e) — commits `493e508` + `326c71a`

1. ✅ `AppSettings`: `workspaceFolderUri` + `contactsPermissionGranted`.
2. ✅ SAF: `SettingsScreen` → botão "escolher pasta" (`OpenDocumentTree`)
   + `takePersistableUriPermission` (o grant persiste após restart).
3. ✅ Tools de arquivo: `write_file`, `read_file`, `list_files`, `delete_file`
   (`tools/Workspace.kt`, via `DocumentsContract`).
4. ✅ Tool de contato: `send_message` — resolve nome→telefone via
   `ContactsContract` (`tools/Contacts.kt`, busca sem acento) e abre o
   WhatsApp/Telegram/SMS com o texto **pré-preenchido**.
5. ✅ **FEITO** (commit `96a8c57`): o backup grava direto na pasta do app
   dentro da pasta apontada — `<pasta>/AndroidAssist/backups/`. O botão de
   exportar não abre mais o seletor do sistema e o de importar restaura o
   backup mais recente da pasta.
6. ✅ **FEITO** (commit `359a4dd`): subpastas. `list_files` devolve arquivos
   **e** pastas, todas as tools de arquivo aceitam `folder` (caminho
   relativo à raiz) e há a tool `create_folder`.

### Por que (b) fica por último

É o único que muda a natureza do app (de "assistente que você chama" para
"assistente que age"). Traz custo de bateria, a política de background do
Android (janela de doze horas) e um consumidor de cota que precisa de limite.
Faz por último, com (a) já no lugar (é onde o resultado da tarefa é gravado) e
com o **modelo local** como padrão do agendamento — buscar e resumir notícias
todo dia às 7h gasta cota da API.

---

## Detalhes de cada item planejado

### (a) Pasta apontada + tools de arquivo

O caminho técnico já existe: `BackupStore` usa SAF (`CreateDocument` /
`OpenDocument`). Falta o **modo pasta**: `ACTION_OPEN_DOCUMENT_TREE` devolve uma
pasta e `takePersistableUriPermission` mantém o acesso depois do restart.

Tools a criar (nomes candidatos, aligning com o estilo existente):
`write_file`, `read_file`, `list_dir`, `delete_file`.

Ganho duplo: backup incremental (só o que mudou) + é o "workspace" do app.

### (e) Contato + WhatsApp

`share_text` hoje abre o share sheet genérico — o usuário escolhe app e
contato à mão. Duas camadas possíveis:

- **sem permissão nova** (recomendado, 90% do caso): `ContactsContract` para ler
  nome→telefone, `Intent.ACTION_SEND` com `package = "com.whatsapp"` e o número
  no texto. O WhatsApp faz a confirmação final (a plataforma exige).
- **com permissão**: `SmsManager` para SMS direto + receiver de resposta. Mais
  invasivo; único jeito de "ler e responder".

### (d) play_media — ✅ FEITO (`c63795c`)

Duas tools: `play_media` (toca) e `list_media` (lista a biblioteca).

O catálogo é o **MediaStore** (`MediaStore.Audio.Media`), que é o índice do
próprio sistema — a música do usuário já está lá, em /Music, /Downloads, no
cartão SD. **Não se copia nada para a pasta do assistente**: a pasta é do
assistente, a música é do usuário. A leitura é só de consulta.

Permissão: `READ_MEDIA_AUDIO` (Android 13+) ou `READ_EXTERNAL_STORAGE`
(Android 12 e anteriores), no mesmo padrão já usado para os contatos
(Configurações › Ferramentas › Permissão de mídia).

O playback é `Intent.ACTION_VIEW` com o `content://` da faixa, mime
`audio/*` e `FLAG_GRANT_READ_URI_PERMISSION` (sem a flag o player não
consegue ler o arquivo). O parâmetro `player` aceita o **nome** do app
("Spotify") e é resolvido para o package name antes do intent.

Para YouTube não existe API pública que toque uma faixa a partir de outro
app — o honesto é abrir a busca no YouTube no dispositivo, onde o usuário
escolhe a faixa. O parâmetro `source` decide entre "device" e "youtube".

Habilidade `MEDIA` com switch próprio.

### (c) modelVision

`AppConfig` tem um `model`. Desenho: config por slot — `model` (principal) +
`modelVision` (análise de imagem). O `LlmClient` já monta o formato OpenAI
vision; falta rotear: se a mensagem tem imagem e existe `modelVision`, a 1ª
chamada vai para ele e o texto gerado entra como contexto do modelo principal.
Resolve o caso de o modelo atual não ter visão.

### (b) Agendamento de tarefas

Peças que faltam: `AlarmManager.setExactAndAllowWhileIdle` + `BroadcastReceiver`
+ `BOOT_COMPLETED` (o manifest hoje não tem nenhum receiver); um worker que roda
`LlmClient.chat()` fora da UI; e a página HTML (`WebView` com `loadData(html,
"text/html", "utf-8")` + `ACTION_VIEW` de `file://` para abrir no navegador, e o
link mostrado no chat).

O resumo das notícias em si é fácil — a tool `web_search` já existe. O difícil
é o gatilho no background e o modelo rodando sem tela.

---

## Melhorias de arquitetura (baratas, pré-requisitos das grandes)

| # | Item | Nota |
|---|---|---|
| (g) | `ToolExecutor` em vez do `when` gigante | `DeviceTools.execute` hoje é um `when` de 9 braços; ao chegar em ~20 tools vira problema de manutenção. Separar por arquivo (um tool por arquivo, com `spec()` + `run()`) faz catálogo, prompt e execução derivarem da **mesma** lista — é o padrão que o `ToolCatalog` já usa. Isso é o que faz (a) e (d) saírem baratos depois. |
| (h) | Pasta de tools = backup nativo | Com (a), cada arquivo que o modelo cria passa a ter versionamento, diff, histórico e rollback. É o que separa um assistente de um chat com botões. |
| (i) | Personas por especialidade | O app já tem personas completas (prompt + modelo + voz). Falta o eixo de **pipeline**: uma persona "analista de imagem" e outra "redator", com encadeamento via (c). |
| (j) | Custo e latência visíveis | Mostrar "rebuscando…", tokens acumulados e o tempo de cada tool no bloco da timeline (o `detail` do bloco `tool` já existe — só falta preencher com métricas). |

---

## Como retomar este roadmap

1. Ler este arquivo.
2. Carregar a skill `androidassist-app` (regras + armadilhas + fluxo de build).
3. Conferir o estado real do repo:
   `cd /root/androidassist && git log --oneline -5 && git status --short && git fetch origin`
4. Continuar o item **EM ANDAMENTO** na tabela acima.
5. Build só no CI (sem SDK local): commit → push → monitorar → baixar o APK
   para `/root/androidassist-debug-<sha8>.apk`.
