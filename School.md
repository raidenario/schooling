# School Harness — Plano

Harness de aprendizado guiado: o modelo atua como professor e auxiliar de estudos. Evolução-produto das skills `agent-schools` + `/teach`, com diagnóstico stateful, currículo adaptativo, provas, flashcards e o vault Obsidian como memória.

Língua do domínio: [CONTEXT.md](CONTEXT.md). Decisões: [docs/adr/](docs/adr/).

## Arquitetura (decidida na grilling de 2026-07-19)

```
┌─────────────┐  WebSocket   ┌────────────────────────────────────┐
│  TUI (Ink,  │◄────────────►│  Backend Clojure (embabel-clj)      │
│ TypeScript) │   stream +    │  Embabel GOAP + chat · malli        │──► Anthropic API
└─────────────┘   comandos    │  Fase 2: DICE + chronicle · FSRS    │
                              │  (Kotlin só como jars consumidos)   │
                              └──────────────┬─────────────────────┘
      Obsidian (leitura/edição livre) ◄──── Vault markdown
                                        (formatos agent-schools//teach)
```

- **Loop agêntico no backend** ([ADR-0001](docs/adr/0001-backend-kotlin-dono-do-loop-agentico.md)); TUI é cliente fino, substituível.
- **Backend em Clojure sobre embabel-clj** ([ADR-0006](docs/adr/0006-backend-em-clojure-sobre-embabel-clj.md)) — agentes como dados EDN, REPL-driven; Kotlin só como jars consumidos.
- **Prosa no vault, agenda no SQLite** ([ADR-0002](docs/adr/0002-prosa-no-vault-agenda-no-sqlite.md)); edição manual no Obsidian sempre vence.
- **Embabel de ponta a ponta** — macro GOAP *e* conversa de ensino — condicionado ao spike-gate da semana 1 ([ADR-0003](docs/adr/0003-tudo-embabel-com-spike-gate.md)); plano B revisado no ADR-0006.
- **Formatos das skills são o contrato** ([ADR-0004](docs/adr/0004-formatos-das-skills-sao-o-contrato.md)); Claude Code permanece como escape hatch.
- **DICE + chronicle como memória do professor na Fase 2** ([ADR-0005](docs/adr/0005-dice-chronicle-memoria-do-professor.md)); v1 já emite eventos de domínio como gancho.

### Defaults reversíveis (não-ADR)

- Monorepo: `backend/` (tools.deps + tools.build; embabel-agent e dice pinados nas versões provadas pelo lab) + `tui/` (pnpm, Ink + React + TS).
- Embabel pinado em `0.5.0-SNAPSHOT` — a família que o DICE exige (verificado 2026-07-20: upstream do dice sem migração 1.0). O embabel-clj já roda no 1.0.0 GA com interop adaptativa; sobe tudo junto quando o DICE acompanhar.
- Servidor WebSocket em Clojure (http-kit ou ring/jetty); Spring sobe só porque o Embabel exige (padrão `platform.clj` do embabel-clj).
- Protocolo front↔back: WebSocket (bidirecional simplifica interrupção mid-stream); trocar por SSE+POST é barato se incomodar.
- Modelo: `z-ai/glm-5.2` via NVIDIA como professor padrão (`SCHOOL_MODEL` troca; DeepSeek V4 Pro e Inkling no catálogo — decisão 2026-07-19). Reasoning SELETIVO por ponto de chamada ([ADR-0011](docs/adr/0011-reasoning-seletivo-por-chamada.md); spike 3/3, implementado em 2026-07-20 via `school.llm`): ON na prosa (pensando visível no TUI, esmaecido) e no conteúdo bloqueante; OFF nos judges de roteamento e cards.
- Pesquisa web ORQUESTRADA em código ([ADR-0012](docs/adr/0012-pesquisa-web-orquestrada.md); `school.research`, Tavily via `SCHOOL_SEARCH_APIKEY` opcional): o sistema gera queries → busca → aterra a Aula nas fontes e cataloga no `RESOURCES.md`. Dispara ao gerar Aula, ao escrever o currículo e sob demanda no chat; graciosa sem chave. NÃO é tool-call do modelo (que vaza no free tier) — é fluxo determinístico.
- Provas continuam HTML self-contained abertos no browser (contrato de formato); o TUI só aponta e coleta respostas.
- Conteúdo em pt-BR; código/exercícios na língua da matéria.

## Fases

### Fase 0 — Spike-gate Embabel ✅ (aprovado 4/4 em 2026-07-19 — [resultado](docs/spike-gate-resultado.md))

Uma aula mínima de ponta a ponta: ação GOAP (embabel-clj) → chat Embabel/Spring AI → WebSocket → Ink. Critérios binários (falhou um → a conversa recua para o SDK Java da Anthropic dirigido do Clojure, mantendo GOAP no macro — ver ADR-0006 — sem renegociar):

1. Streaming token-a-token fluido no TUI
2. Tool use no meio da conversa (ex.: gravar learning record)
3. Controle integral do system prompt do professor
4. Interrupção pelo usuário mid-stream

### Fase 1 — Núcleo de ensino (v1) — em construção (esqueleto vivo desde 2026-07-19)

> Estado (2026-07-19, repo github.com/raidenario/schooling): fluxo COMPLETO
> implementado — missão → prova fria → diagnóstico/currículo → ensino → provas de
> consolidação (com interleaving de itens fracos e adaptação score<70 → remediação)
> → capstone, mais detour de pré-requisito nos prompts, histórico de conversa
> persistente e reconexão do TUI. Provado ao vivo: turno de missão e geração da
> prova fria. Falta o dogfood: estudar uma matéria real por uma semana só pelo TUI
> (critério de pronto) — correção de provas, ensino e capstone ainda não rodaram
> com LLM vivo.
> Decisão de implementação: streaming para prosa, `create-edn!`/`ask` bloqueantes
> (validados por malli, `:timeout-s` explícito) para tudo que vira arquivo —
> tool-calling iniciado pelo modelo em streaming se mostrou não-determinístico
> no free tier (markup `<tool_call>` vazando como texto).
> Provas são a entidade fixa do ADR-0007: LLM gera dados, renderer gamificado fixo
> (vibe Duolingo, 'não sei' + justificativa + Concluir), servidas em
> localhost:7777/prova/..., respostas por POST com correção automática em código
> streamada no chat, calibragem pré-prova e modo consulta — fluxo interativo
> completo provado ao vivo (PROVA-HTTP-TEST PASS).

> Atualização 2026-07-20: **Aula como documento** ([ADR-0008](docs/adr/0008-aula-como-documento.md))
> virou entidade de ensino de 1ª classe — explicação detalhada sai do chat e vira
> página com ciclo de entendimento (avaliação sólido/parcial/confuso; decisão
> re-explica/segue/prova em código por peso de complexidade). Modo consulta agora
> enxerga as questões da prova (nunca o gabarito) e a calibragem reconsolida a
> cada resposta do aprendiz, com "pode gerar" explícito como gate da prova fria.

`school learn <matéria>` e `school continue <matéria>` no TUI, com paridade agent-schools: entrevista de missão → prova fria → DIAGNOSIS.md → CURRICULUM.md → ensino do módulo em chat → prova de consolidação → adaptação citando o diagnóstico. O backend já emite **eventos de domínio** (prova corrigida, módulo passou, status mudou) — sem consumidor ainda; é o gancho da Fase 2 ([ADR-0005](docs/adr/0005-dice-chronicle-memoria-do-professor.md)). Pronto quando: uma matéria real estudada por uma semana inteira só pelo TUI, com o vault legível no Obsidian e operável pelo Claude Code. **O que falta é o dogfood** — o fluxo está em código e provado por partes ao vivo; o loop ensino→consolidação→capstone e a semana de uso real ainda não rodaram.

### Fase 2 — Memória ✅ (fatiada em 2026-07-20; concluída em código em 2026-07-20; [ADR-0009](docs/adr/0009-retencao-fsrs.md))

A família de dado temporal derivado, em duas fatias — **ambas concluídas em código**; o que resta é dogfood (uso real, junto com a semana de estudo da Fase 1):

**Fase 2a — Retenção ✅ (em código desde 2026-07-20; completa em 2026-07-20)**

- **`school.fsrs`**: FSRS-5 puro (variante long-term), determinístico, testado.
- **`school.agenda`**: estado de agendamento no SQLite (`SCHOOL_AGENDA_DB`, fora do OneDrive) — cards, agenda FSRS, review_log append-only.
- **`school.cards`**: mineração em código, sem LLM — questão errada/🤷 de prova corrigida vira card no vault (`cards/NNNN-<slug>.md`), dedup por slug; a correção anuncia a fila. **Itens `fraco` do diagnóstico** também viram cards de recall: gate e dedup determinísticos (parse do Mapa de competências, slug `diag-<competência>`), só o conteúdo frente/verso sai do LLM (`create-edn!`) — o "depois" previsto no ADR-0009; falha na mineração nunca derruba a correção.
- **`school.review`**: sessão de revisão como página (`/review/<matéria>`), um card por vez, rating Errei/Difícil/Bom/Fácil, POST por card, FSRS no servidor. **Interleaving explícito entre matérias**: `/review` (sem matéria) monta a fila do dia global em round-robin entre matérias, com badge de matéria por card; o TUI anuncia a fila global na abertura quando há cards vencidos.
- Ciclo offline testado (`clojure -M:f2a-test`, incluindo mineração de fracos e fila global). Falta só o dogfood da fila diária.

**Fase 2b — Memória fina + trajetória ✅ (em código desde 2026-07-20; [ADR-0010](docs/adr/0010-memoria-fina-dice-chronicle.md))**

- **`school.memoria`**: DICE embutido (dice `0.1.1-SNAPSHOT` + embabel `0.5.0-SNAPSHOT`, combo lab-provado), repo in-memory decorado pelo dice-chronicle; **o log EDN é a fonte de verdade** (replay no boot, time travel via `:upto`). `DIAGNOSIS.md` segue o mapa macro.
- **Escrita determinística** nos momentos-chave: prova corrigida (misconception/lacuna/episódio), aula avaliada (sólido CONTRADIZ o trave anterior — história auditável), learning record. Sem reviser LLM na v1 (adiado para provider pago).
- **Leitura**: bloco de memória por confiança efetiva injetado no preamble de todos os estágios e na geração de Aula.
- **Trajetória**: `GET /memoria/<matéria>` — memória ativa + timeline do chronicle.
- Ciclo offline testado (`clojure -M:f2b-test`). Falta só o dogfood com LLM vivo. Reviser LLM e contexto global do aprendiz seguem **adiados por decisão** (ADR-0010) — são evolução pós-Fase 2, não pendência dela.

### Fase 3 — Superfícies

Servidor MCP (School dentro do Claude Code/Codex), ambientes caderno e exercício como modos do TUI (exercício com execução real de código), **segunda interface gráfica inspirada em Codex/Claude app/Antigravity 2.0** (o TUI estilo Claude Code segue como primeira interface), e o que a prática pedir — web, plugin Obsidian, mobile (aí sim, Kotlin no front).
