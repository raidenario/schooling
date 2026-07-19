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
- Servidor WebSocket em Clojure (http-kit ou ring/jetty); Spring sobe só porque o Embabel exige (padrão `platform.clj` do embabel-clj).
- Protocolo front↔back: WebSocket (bidirecional simplifica interrupção mid-stream); trocar por SSE+POST é barato se incomodar.
- Modelo: `claude-opus-4-8` como professor padrão (adaptive thinking), configurável por matéria.
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

`school learn <matéria>` e `school continue <matéria>` no TUI, com paridade agent-schools: entrevista de missão → prova fria → DIAGNOSIS.md → CURRICULUM.md → ensino do módulo em chat → prova de consolidação → adaptação citando o diagnóstico. O backend já emite **eventos de domínio** (prova corrigida, módulo passou, status mudou) — sem consumidor ainda; é o gancho da Fase 2 ([ADR-0005](docs/adr/0005-dice-chronicle-memoria-do-professor.md)). Pronto quando: uma matéria real estudada por uma semana inteira só pelo TUI, com o vault legível no Obsidian e operável pelo Claude Code.

### Fase 2 — Memória

A família inteira de dado temporal derivado, de uma vez:

- **Flashcards**: port do FSRS para Clojure (referência: ts-fsrs), Agenda no SQLite, conteúdo dos cards no vault. Mineração automática: item `fraco` no diagnóstico e questão errada de prova viram cards. `school review` roda a fila do dia com interleaving de módulos antigos.
- **DICE** como memória fina do professor (store JSON, sem Neo4j): micro-fatos, misconceptions, episódios — injetada no prompt via `PromptContributor` e consultável como `Tool`. `DIAGNOSIS.md` segue sendo o mapa macro ([ADR-0005](docs/adr/0005-dice-chronicle-memoria-do-professor.md)).
- **dice-chronicle** embutido na mesma JVM (Clojure, atrás de interface Kotlin), consumindo os eventos de domínio da v1: timeline, replay e time travel da trajetória do aprendiz.

### Fase 3 — Superfícies

Servidor MCP (School dentro do Claude Code/Codex), ambientes caderno e exercício como modos do TUI (exercício com execução real de código), e o que a prática pedir — web, plugin Obsidian, mobile (aí sim, Kotlin no front).
