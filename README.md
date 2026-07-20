# School

Harness de aprendizado guiado: o modelo atua como professor particular adaptativo —
diagnóstico stateful, currículo por missão, provas com evidência, e o vault Obsidian
como memória. Evolução-produto das skills `agent-schools` + `/teach`.

- **Plano e arquitetura**: [School.md](School.md)
- **Língua do domínio**: [CONTEXT.md](CONTEXT.md)
- **Decisões**: [docs/adr/](docs/adr/) · **Spike-gate**: [docs/spike-gate-resultado.md](docs/spike-gate-resultado.md)

## Stack

Backend **Clojure** sobre [embabel-clj](https://github.com/raidenario) (agentes GOAP do
Embabel como dados; ADR-0006) — Kotlin só como jars consumidos. Frontend TUI em
**TypeScript/Ink**, cliente fino via WebSocket (ADR-0001). Estado: prosa no vault
Obsidian, dado temporal fora dele (ADR-0002); formatos das skills como contrato (ADR-0004).

## Rodar

Pré-requisitos: JDK 21+, Clojure CLI, Node 20+ + pnpm, `embabel-clj` clonado como
irmão do repo (`../embabel-lab/embabel-clj`), jars `embabel-agent 0.5.0-SNAPSHOT`
no `~/.m2`, e `NVIDIA_APIKEY` no ambiente (provider OpenAI-compatível; troque com
`SCHOOL_BASE_URL`/`SCHOOL_APIKEY`/`SCHOOL_MODEL`).

```powershell
# backend (ws://localhost:7777)
cd backend
clojure -M:server

# TUI (em outro terminal)
cd tui
pnpm install
pnpm run dev -- <matéria>      # ex.: pnpm run dev -- clojure-avancado
```

No TUI: converse; ESC interrompe a aula no meio do stream. Os arquivos da matéria
aparecem no vault (`SCHOOL_VAULT_ROOT` para usar outra raiz).

## Testes

```powershell
# smoke offline (sem LLM) — use raízes descartáveis:
cd backend
$env:SCHOOL_VAULT_ROOT="C:\tmp\vault-teste"; $env:SCHOOL_EVENTS_FILE="C:\tmp\events.edn"
clojure -M:smoke

# ciclo offline da Fase 2a (FSRS/cards/review) — mesmas raízes + agenda:
$env:SCHOOL_AGENDA_DB="C:\tmp\agenda-teste.db"
clojure -M:f2a-test

# protocolo WS (contra o backend em modo fake: SCHOOL_SPIKE=fake clojure -M:spike)
cd tui
node scripts/pipe-test.mjs

# spike-gate completo (contra SCHOOL_SPIKE=embabel clojure -M:spike)
node scripts/spike-test.mjs
node scripts/interrupt-test.mjs

# ciclo de aula-documento (ADR-0008) — contra o server com vault descartável
# em estágio :ensino (SCHOOL_VAULT_ROOT no server, SCHOOL_TEST_VAULT aqui):
node scripts/aula-test.mjs
```
