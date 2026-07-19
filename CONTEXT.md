# School Harness

Harness de aprendizado guiado: o modelo atua como professor e auxiliar de estudos, com diagnóstico stateful do aprendiz, currículo adaptativo, provas e flashcards. Evolução-produto das skills agent-schools e /teach.

## Language

**School**:
O produto como um todo — backend de orquestração + frontends de estudo.

**Aprendiz**:
A pessoa estudando. É quem o diagnóstico descreve e para quem o currículo é construído.
_Avoid_: usuário, aluno

**Vault**:
O grafo de conhecimento markdown (Obsidian) do aprendiz — fonte de verdade de toda prosa: diagnóstico, currículo, notas, provas, conteúdo de cards.

**Diagnóstico**:
O mapa stateful de habilidades do aprendiz, com evidência por item; toda decisão adaptativa cita ele.
_Avoid_: perfil, nível

**Matéria**:
Um assunto em estudo, com sua própria missão, diagnóstico e currículo (`school/<subject>/`).
_Avoid_: curso, trilha

**Missão**:
O objetivo real do aprendiz com uma matéria (passar numa prova, entrevista, construir algo) — molda currículo e capstone.

**Módulo**:
Unidade do currículo, encerrada por uma prova de consolidação; tem status pending | active | passed | skipped.

**Prova fria**:
Avaliação inicial de uma matéria, feita sem ensino prévio, que semeia o diagnóstico.

**Prova de consolidação**:
Prova ao fim de um módulo — recall + aplicação, com re-teste de itens fracos de módulos anteriores.

**Gabarito**:
Documento pós-correção com cada questão, a resposta do aprendiz, a correta e a explicação. Vive no servidor até a correção — nunca dentro da prova.

**Justificativa**:
O "por quê" que o aprendiz escreve ao escolher uma alternativa — sinal de diagnóstico tão importante quanto o acerto.

**Não sei**:
Alternativa obrigatória em toda questão de prova; a dúvida honesta vale mais para o diagnóstico que o chute.

**Calibragem**:
Conversa pré-prova sobre conceitos-chave da matéria que dosa o nível da prova fria.

**Modo consulta**:
O comportamento do professor enquanto uma prova está aberta: orienta socraticamente, nunca dá a resposta.

**Capstone**:
O exame final de uma matéria: entregável real julgado contra a missão, seguido de defesa.

**Superfície**:
Um frontend que opera o estado da escola — TUI próprio, Claude Code via skills, futuramente MCP/web/mobile.

**Memória do professor**:
A memória fina e viva sobre o aprendiz (micro-fatos, misconceptions, episódios), com confiança e esquecimento — distinta do Diagnóstico, que é o mapa macro de habilidades.

**Trajetória**:
A história auditável do aprendizado — a sequência de eventos que explica por que o professor acredita no que acredita sobre o aprendiz.

**Agenda**:
O estado de agendamento FSRS dos flashcards (stability, difficulty, due date, review log) — dado temporal, separado da prosa.
