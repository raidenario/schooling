---
status: amended by ADR-0006 — as duas camadas de memória (DIAGNOSIS.md macro, DICE fino) e o chronicle na Fase 2 permanecem; "Clojure confinado" caducou porque Clojure virou a língua do backend
---

# DICE + chronicle como memória do professor; Clojure confinado e embutido

A memória do aprendiz tem duas camadas. O `DIAGNOSIS.md` no vault segue sendo o mapa **macro** de habilidades — escrito nas provas, contrato com as skills (ADR-0004), legível no Obsidian. O **DICE** (substrato de memória do ecossistema Embabel: proposições com confiança/decay/contradição) entra na Fase 2 como a memória **fina** do professor — micro-fatos, misconceptions, episódios de aula — classificada como dado derivado/temporal (mesma família da Agenda FSRS, ADR-0002): store JSON/SQLite fora do OneDrive, perda recuperável, nunca fonte de verdade de prosa. O **dice-chronicle** (projeto próprio do autor, Clojure) roda embutido na mesma JVM do backend, decorando o repositório de proposições com um log EDN append-only — timeline, replay e time travel da trajetória do aprendiz.

Clojure fica **confinado**: módulo de memória/chronicle atrás de interfaces Kotlin, mais nREPL de desenvolvimento. Não entra no domínio, na orquestração nem no TUI. O backend permanece Kotlin + Embabel idiomático (ADR-0001/0003 — reconfirmado em 2026-07-19 após análise do embabel-lab).

## Consequences

- A v1 já emite **eventos de domínio** (provas, reviews, mudanças de status) mesmo sem DICE — é o gancho barato que o chronicle consome na Fase 2 sem retrabalho.
- O decay do DICE modela esquecimento (knowledge tracing); a contradição modela atualização de diagnóstico por evidência nova de prova — "citar evidência" vira propriedade estrutural, não disciplina de prompt.
- DICE é 0.1.1-SNAPSHOT incubating: entra na Fase 2, nunca na v1, e o School vira o caso de uso real da proposta upstream do chronicle (issue #46 do dice), onde o criador do framework já sinalizou interesse em Clojure.
