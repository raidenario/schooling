# Backend em Clojure sobre embabel-clj

Revertendo a escolha de linguagem dos ADRs 0001/0003: o backend inteiro é **Clojure**, usando a biblioteca própria embabel-clj como ponte para o Embabel — agentes como dados EDN, contratos malli nas duas fronteiras, desenvolvimento REPL-driven. Kotlin permanece no sistema apenas como jars consumidos (embabel-agent, dice); nenhum Kotlin autoral. Decidido pela evidência de velocidade do embabel-lab (6 fatias num dia, incluindo dois agentes gêmeos), pela simplificação real — o plano anterior já era poliglota por causa do chronicle; agora há uma língua autoral só — e pela coerência tudo-é-dado com o domínio (currículo, provas e trajetória como EDN).

Custos aceitos: o autor mantém a ponte embabel-clj (upgrades do Embabel quebram nela primeiro); LLMs assistem pior em Clojure que em Kotlin; o argumento de portfólio muda de "Kotlin/Spring" para "Clojure×Embabel×DICE com o School como caso flagship da contribuição upstream".

O que os ADRs anteriores mantêm: 0001 (loop agêntico no backend; TUI cliente fino) e 0003 (Embabel de ponta a ponta com **spike-gate na semana 1**) continuam válidos com "backend" lido como Clojure — os 4 critérios binários do spike agora testam o embabel-clj. 0005 muda de sentido: Clojure deixa de ser "confinado" porque é a língua do backend; chronicle e DICE entram nativamente na Fase 2.

## Plano B revisado

Se o spike-gate reprovar o embabel-clj na conversa streamada, o recuo NÃO é trocar de língua: a camada de conversa passa para o SDK Java da Anthropic dirigido do Clojure (interop direto), mantendo GOAP/embabel-clj na orquestração macro. O pivô de linguagem sobrevive ao pior cenário do spike.

## Defaults decorrentes

- Servidor WebSocket do TUI em Clojure (http-kit ou ring/jetty); o Spring sobe apenas porque o Embabel precisa (padrão `platform.clj` do embabel-clj).
- Build: tools.deps + tools.build, seguindo o embabel-clj; versões do embabel-agent e dice pinadas nas que o lab provou.
