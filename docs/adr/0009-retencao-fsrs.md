# Retenção como código: FSRS-5 puro, Agenda no SQLite, cards minerados dos erros

A Fase 2 foi fatiada: **2a (retenção)** entra primeiro — alto valor, zero dependência do DICE; **2b (DICE + chronicle)** espera o upstream, que segue pinado no embabel `0.5.0-SNAPSHOT` (verificado em 2026-07-20: nenhuma migração 1.0 em andamento no embabel/dice). Por isso o School permanece no `0.5.0-SNAPSHOT`; o embabel-clj já suporta 1.0.0 com interop adaptativa, então a subida é barata quando o DICE acompanhar.

A retenção segue a divisão de trabalho da casa:

- **`school.fsrs`** — port do FSRS-5 como funções PURAS (variante long-term, sem learning steps intra-dia; w17/w18 fora). Tempo entra como `Instant` — determinismo é o teste.
- **`school.agenda`** — estado de agendamento no SQLite (`SCHOOL_AGENDA_DB`, default fora do OneDrive), ADR-0002: dado temporal derivado, perder é re-minerável. Tabelas `cards` (identidade + caminho no vault), `agenda` (estado FSRS 1:1) e `review_log` (append-only — insumo futuro do chronicle).
- **`school.cards`** — mineração em CÓDIGO puro, sem LLM: questão errada ou 🤷 de prova corrigida vira card (frente = enunciado+contexto; verso = correta+explicação; evidência = prova+qN). Conteúdo no vault (`cards/NNNN-<slug>.md`, editável no Obsidian); dedup por slug estável do enunciado — re-testar a mesma lacuna não duplica card.
- **`school.review`** — a sessão como página (padrão ADR-0007/0008): renderer fixo, um card por vez, frente → revelar → rating (Errei/Difícil/Bom/Fácil); **cada rating POSTa na hora** (abandonar a sessão não perde nada). O FSRS roda só no servidor.

## Considered Options

- **LLM minerando/julgando cards** — rejeitado: o dado da prova já é estruturado; mineração determinística é grátis e auditável. O LLM entra depois (cards de aula/diagnóstico, Fase 2b+).
- **Learning steps intra-dia (scheduler completo)** — rejeitado na v1: a fila do School é diária; o long-term scheduler é mais simples e igualmente fundamentado (ts-fsrs oferece o mesmo recorte).
- **Agenda no vault (markdown)** — rejeitado por ADR-0002: estado FSRS muda a cada review e não é prosa; frontmatter viraria churn de sync no Obsidian/OneDrive.
- **Batch de ratings no fim da sessão (como a prova)** — rejeitado: sessão de revisão é interrompível por natureza; por-card é resiliente.

## Consequences

- Rotas novas `GET/POST /review/<slug>`; eventos `:cards-minerados`, `:card-revisado` (gancho do chronicle).
- A correção de prova agora também minera cards e anuncia a fila no chat.
- `review_log` acumula o histórico que permitirá otimizar os pesos FSRS por aprendiz no futuro.
- Teste offline do ciclo completo: `clojure -M:f2a-test` (raízes descartáveis).

## Fechamento da fatia (2026-07-20)

Os dois "depois" entraram, fechando a Fase 2a em código:

- **Itens `fraco` do diagnóstico viram cards de recall** — na linha desta ADR: gate e dedup DETERMINÍSTICOS (parse do Mapa de competências do DIAGNOSIS.md; slug `diag-<competência>` na Agenda), e só o conteúdo frente/verso sai do LLM via `create-edn!` (uma chamada em lote por diagnóstico novo), porque competência é semântica, não dado estruturado de prova. Roda após cada diagnóstico escrito (prova fria e módulo); falha na mineração nunca derruba a correção. Evento: `:cards-minerados` com `:prova "diagnostico"`.
- **Interleaving explícito entre matérias** — `GET/POST /review` (sem slug): fila do dia global montada em round-robin entre as matérias com card vencido, badge de matéria por card na página; o servidor anuncia a fila global na abertura do TUI. A fila por matéria (`/review/<slug>`) continua existindo.

Resta apenas o dogfood da fila diária (uso real).
