# Memória fina do professor: DICE embutido, chronicle como fonte de verdade

Concretiza a Fase 2b do ADR-0005 com o padrão provado no embabel-lab (concierge-clj): o **DICE roda embutido** na JVM do backend (`com.embabel.dice/dice 0.1.1-SNAPSHOT` do `~/.m2`, embabel `0.5.0-SNAPSHOT`), repo **in-memory decorado pelo dice-chronicle** — e a persistência é Datomic-style: **o log EDN do chronicle é a fonte de verdade** (`SCHOOL_CHRONICLE_LOG`, fora do OneDrive — ADR-0002); no boot, replay reconstrói a memória; `:upto` dá time travel.

Duas camadas, como o ADR-0005 manda: `DIAGNOSIS.md` segue o mapa **macro** no vault; o DICE guarda o **fino** — micro-fatos, misconceptions, lacunas declaradas, episódios e preferências, com confiança/decay/contradição por proposição.

- **Escrita v1 DETERMINÍSTICA** (sem reviser LLM): os momentos-chave já são estruturados — mesma filosofia da mineração de cards (ADR-0009). Prova corrigida → erro confiante vira `misconception`, 🤷 vira `lacuna`, score vira `episódio` volátil (decay alto). Aula avaliada → sólido **CONTRADIZ** o "Travando em X" anterior (a superação fica auditável no chronicle); parcial/confuso viram lacuna/misconception. Learning record → episódio. Dedup por texto exato ACTIVE; `lembrar!` nunca lança (memória não derruba aula).
- **Leitura**: `bloco-prompt` injeta o top-12 por **confiança efetiva** (decay aplicado pelo DICE) no preamble de todos os estágios e no contexto de geração de Aula — o equivalente manual do `PromptContributor`.
- **Trajetória**: `GET /memoria/<matéria>` — memória ativa ranqueada + timeline do chronicle (por que o professor acredita no que acredita), read-only.

## Considered Options

- **Reviser LLM do DICE (`LlmPropositionReviser`) na escrita** — adiado: o concierge provou que funciona, mas dobra as chamadas por turno no free tier; os sinais do School já chegam estruturados. Fica como evolução natural quando houver provider pago (revisão semântica/reforço em vez de dedup por texto).
- **Store JSON próprio do DICE** — desnecessário: repo in-memory + replay do log cobre a persistência com MENOS estado (o log já existia para a trajetória); um store separado criaria segunda fonte de verdade.
- **Neo4j/Drivine** — rejeitado desde o ADR-0005; nada aqui exige grafo.
- **Memória global do aprendiz (cross-matéria)** — adiado: contexto por matéria (`school-<slug>`) mantém o prompt enxuto; um contexto `school-aprendiz` global pode ser adicionado sem migração (é só outro context-id no mesmo log).

## Consequences

- O professor agora "lembra de você" turno a turno (prompt) e entre sessões (replay) — e a superação de um trave é um evento de primeira classe, não um overwrite.
- Deps novas: dice do `~/.m2` (pré-requisito documentado no deps.edn) + dice-chronicle irmão via `:local/root` (slf4j-simple excluído — logback do Spring é quem loga).
- O School vira o segundo caso de uso real do chronicle (depois do concierge) — mais lenha para a proposta upstream (dice #46).
- Teste offline do ciclo (escrita/dedup/contradição/replay/página): `clojure -M:f2b-test`.
