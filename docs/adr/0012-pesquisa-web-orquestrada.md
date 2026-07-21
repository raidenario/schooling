# Pesquisa web ORQUESTRADA em código: Tavily, RESOURCES.md determinístico, três gatilhos

O ensino não deve depender só do treino do GLM 5.2. O /teach do Claude pesquisa
a web e grava fontes; o School ganha o equivalente — mas do jeito da casa.

**A pegadinha que decide o desenho**: tool-calling iniciado pelo modelo em
streaming se mostrou NÃO-determinístico no free tier (o markup `<tool_call>`
vazava como texto — School.md, Fase 1). Dar uma tool `web_search` pro GLM e
torcer pra ele chamar na hora certa é repetir a falha. E o /teach, olhado de
perto, também NÃO deixa o modelo decidir: ele ORQUESTRA (código dispara a busca,
modelo consome). Então:

- **`school.research`** conduz em CÓDIGO, no mesmo espírito determinístico de
  cards/memória/provas: modelo gera 2-3 queries (`create-edn!`, SEM reasoning —
  extração mecânica, ADR-0011) → **código** faz o HTTP no Tavily → fontes (a)
  aterram o contexto da geração da Aula e (b) acumulam no `RESOURCES.md`.
- **Backend: Tavily** — feito para agentes: devolve conteúdo já EXTRAÍDO e limpo
  das páginas (pronto pra citar) + um `answer` sintetizado; free tier generoso,
  uma chave (`SCHOOL_SEARCH_APIKEY`). Bearer auth, `search_depth: basic` (1
  crédito) por default. `SCHOOL_SEARCH_URL` permite trocar o endpoint.
- **RESOURCES.md é DETERMINÍSTICO** (sem síntese-LLM na v1): o conteúdo do Tavily
  já serve; o arquivo acumula por matéria, dedup por URL (uma seção datada por
  pesquisa; só URLs inéditas entram). Mesma filosofia do "dado estruturado →
  arquivo" da mineração de cards.
- **Três gatilhos**: ao gerar uma **Aula** (aterra o conteúdo — o objetivo
  central), **sob demanda** no chat ("pesquisa sobre X" — detectado pelo
  EnsinoCheck, como a rede de segurança da aula), e ao escrever o **currículo**
  (varredura inicial que semeia o RESOURCES.md).
- **Graciosa**: sem `SCHOOL_SEARCH_APIKEY` => no-op; qualquer falha (HTTP, quota)
  é engolida com log — pesquisa NUNCA derruba uma aula (como memória/cards).

## Considered Options

- **Tool-use de verdade (embabel `Tool` + `withTools`)** — rejeitado: o free
  tier é não-determinístico em tool-call streaming (já provado), e o caminho
  reasoning (`school.llm`) contorna as tools do embabel — usar tools obrigaria a
  abrir mão do reasoning nos turnos de ensino. A orquestração entrega o valor
  sem o risco.
- **Síntese-LLM do RESOURCES.md** — adiado (provider pago): dobraria chamadas por
  aula (429 é real — dogfood 2026-07-20) e o conteúdo extraído do Tavily já é
  citável. Enriquecer com anotação por missão/diagnóstico fica como evolução.
- **Brave / DuckDuckGo** — Brave devolve resultado cru (extração por nossa
  conta); DDG é endpoint não-oficial e frágil. Tavily encaixa em "pesquisar e
  sintetizar" com menos código.
- **web_search_options nativo do endpoint** — é da OpenAI, não do GLM/NVIDIA;
  não se aplica.

## Consequences

- Deps novas: NENHUMA — http-kit (client) e cheshire já estavam no classpath.
- Env novo: `SCHOOL_SEARCH_APIKEY` (Tavily; opcional — sem ela o School roda
  igual, só sem pesquisa). `SCHOOL_SEARCH_URL` opcional.
- Custo por Aula: +1 chamada LLM pequena (queries, sem reasoning) + 2-3 buscas
  Tavily. Aterrado na graça: falhou, a aula sai mesmo assim.
- Evento novo `:pesquisa-feita` (gancho do chronicle/telemetria).
- Teste offline (mocks de LLM e HTTP): `clojure -M:research-test`. O round-trip
  real do Tavily valida no primeiro uso com chave (como o spike do reasoning
  dependeu da chave da NVIDIA).
- RESOURCES.md entra no vault como entidade legível/editável no Obsidian —
  fecha o par com o formato agent-schools/teach que o aprendiz já conhece.
