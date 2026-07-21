# Reasoning seletivo por chamada: GLM 5.2 thinking via extraBody, política ON/OFF por ponto de chamada

O GLM 5.2 no NIM da NVIDIA liga/desliga o thinking POR REQUEST via
`chat_template_kwargs` no corpo (spike aprovado 3/3 —
[resultado](../spike-reasoning-resultado.md)). O School adota reasoning
SELETIVO, decidido pelo perfil de cada ponto de chamada (grilling de
2026-07-20), não um default global:

**ON** — onde a chamada é rara, bloqueante e o erro é caro (o dado vira arquivo
que dirige semanas de estudo), ou onde o pensamento é parte da experiência:

- Prosa streamada (todos os estágios) — com o raciocínio VISÍVEL no TUI,
  esmaecido, estilo Claude Code (`reasoning_content` chega por chunk separado
  do texto).
- Prova fria e prova de módulo (`prova/gerar!`).
- Diagnóstico e currículo (×2 cada — fria e módulo).
- Aula-documento.
- `EntendimentoCheck` — exceção deliberada entre os judges: julgamento
  pedagógico com consequência dupla (re-explica/segue/prova + memória fina).

**OFF** — frequente e mecânico, latência pós-turno visível:

- Os 6 judges de roteamento (MateriaCheck, MissaoCheck, CalibragemCheck,
  AulaCheck, EnsinoCheck, CapstoneCheck).
- Cards de itens fracos (conteúdo simples de recall).

## Considered Options

- **Reasoning global (modelo decide)** — rejeitado: o default "max" do GLM 5.2
  pensa demais para judges triviais (latência pós-turno em TODA mensagem) e o
  free tier paga a conta.
- **OFF na prosa** — considerado (fluidez é o critério 1 do spike-gate da Fase
  0); decidido ON com pensando visível: o atraso do primeiro token de conteúdo
  vira transparência do processo, não silêncio.
- **HTTP direto ao NIM (http-kit cru) nos pontos ON** — rejeitado: duplicaria
  retry/timeout/parse que já existem na stack.
- **Conversor customizado registrado via
  `OpenAiCompatibleModelFactory.openAiCompatibleLlm(..., OptionsConverter)` +
  `:sources` do platform.clj** — era o plano do spike, preterido na
  implementação: exige gen-class @Configuration e briga com o registry
  auto-configurado. O caminho escolhido entrega o mesmo com uma fração do
  mecanismo (fica como alternativa se um dia precisarmos de tools nos pontos
  ON).

## Consequences

- Wiring IMPLEMENTADO (2026-07-20): **`school.llm`** — transporte Spring AI
  DIRETO (OpenAiChatModel + extraBody), fora do registry do embabel, usado só
  nos pontos ON. Nos structured calls, entra pelo **`:ask-fn`** do
  `create-edn!` (escape hatch documentado do embabel-clj) — retry e validação
  malli continuam onde estavam. O conversor padrão do embabel NÃO repassa
  thinking/extensions (provado por bytecode), por isso o desvio. `stream!` do
  professor consome o reasoning por chunk e o WS ganha `{:type "thinking"}`
  para o TUI esmaecer; judges OFF e cards seguem no caminho embabel intocado
  (que também conserva tools, se algum dia precisarem).
- **Token budget é obrigatório nos pontos ON**: reasoning consome `max_tokens`
  (no spike, 2048 tokens inteiros de pensamento sem resposta). A política usa
  tetos generosos (16384 conteúdo/prova, 8192 prosa/entendimento) e a prosa
  pensa com `reasoning_effort "high"` (mais leve que o default "max").
- A política vive num mapa único em código (`professor/reasoning`: ponto de
  chamada → orçamento), não espalhada nos call sites; `SCHOOL_MODEL` continua
  trocando o modelo — em modelo sem thinking os kwargs viram no-op do template.
- Embabel pinado no 0.5.0-SNAPSHOT segue valendo (ADR-0009): o seam usado
  existe igual no 1.0.0 (verificado por bytecode em ambos).
