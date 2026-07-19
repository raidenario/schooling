# Os formatos das skills existentes são o contrato de estado

O backend School lê e escreve exatamente os formatos e a estrutura de arquivos já usados pelas skills agent-schools e /teach (`school/<subject>/` com MISSION.md, DIAGNOSIS.md, CURRICULUM.md, GLOSSARY.md, RESOURCES.md, learning-records/, modules/, capstone/, provas em HTML). Matérias existentes funcionam sem migração, e uma sessão /teach ou agent-schools no Claude Code opera o mesmo estado — o Claude Code permanece como segunda superfície e escape hatch permanente.

## Consequences

- Mudança de formato deixa de ser edit casual e vira migração versionada, coordenada com as skills.
- Conceitos novos do School (ex.: Agenda FSRS) entram como arquivos/stores novos, nunca alterando os formatos existentes.
- "Qualquer agente que fale markdown opera a escola" vira propriedade do produto — é o que torna a futura superfície MCP trivial.
