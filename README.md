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

## Instalar num PC novo (Windows)

Um comando bootstrapa tudo — scoop + ferramentas, os 4 clones no layout certo,
o jar do dice no `~/.m2`, a API key, o vault e o comando `schooling` no PATH:

```powershell
iwr https://raw.githubusercontent.com/raidenario/schooling/main/scripts/install.ps1 -OutFile install.ps1
powershell -ExecutionPolicy Bypass -File install.ps1
# abra um terminal novo e digite: schooling
```

## Rodar

Pré-requisitos (o install.ps1 cuida de todos): JDK 21+, Clojure CLI, Node 20+ +
pnpm, `embabel-clj` e `dice-chronicle` clonados como irmãos em
`../embabel-lab/`, jars `embabel-agent 0.5.0-SNAPSHOT` e `dice` no `~/.m2`, e
`NVIDIA_APIKEY` no ambiente (provider OpenAI-compatível; troque com
`SCHOOL_BASE_URL`/`SCHOOL_APIKEY`/`SCHOOL_MODEL`). Opcional:
`SCHOOL_SEARCH_APIKEY` (chave Tavily) liga a pesquisa web que aterra as aulas
em fontes atuais e as cataloga no `RESOURCES.md` — sem ela o School roda igual,
só sem pesquisa (ADR-0012).

```powershell
# um comando só: sobe o backend escondido (ou reaproveita um já de pé),
# espera ficar pronto e abre o TUI; ao sair, derruba o que ele subiu.
schooling                # ou: schooling <matéria>
```

O comando é um shim em `%USERPROFILE%\bin\schooling.cmd` apontando para
[scripts/schooling.ps1](scripts/schooling.ps1) (logs do backend em
`%LOCALAPPDATA%\school\backend.log`). Para rodar as pontas separadas:

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

# ciclo offline da Fase 2b (memória fina DICE+chronicle) — pré-requisito:
# dice instalado no ~/.m2 (cd ..\..\embabel-lab\dice; mvn install -pl dice -am -DskipTests)
$env:SCHOOL_CHRONICLE_LOG="C:\tmp\chronicle-teste.edn"
clojure -M:f2b-test

# pesquisa web orquestrada (ADR-0012) offline — mocks de LLM e HTTP:
clojure -M:research-test

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
