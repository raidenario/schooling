(ns school.professor
  "O professor do School — Fase 1 (núcleo de ensino).

   GOAP: três ações com pré-condições LAZY que releem o vault (ADR-0002 —
   edição manual no Obsidian muda o plano no turno seguinte); o planner
   escolhe a ação do estágio. Cada turno de conversa é um processo com goal
   :respondido?.

   Divisão de trabalho com o LLM (lição do embabel-lab, confirmada aqui:
   tool-calling iniciado pelo modelo em modo streaming vazou como texto no
   free tier): STREAMING para a prosa da aula; create-edn!/ask BLOQUEANTES,
   validados por malli, para toda decisão e todo conteúdo que vira arquivo.
   Quem decide salvar é o código; o modelo produz o conteúdo."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.schema :as schema]
            [school.events :as events]
            [school.vault :as vault])
  (:import [com.embabel.agent.api.streaming StreamingPromptRunnerBuilder]
           [com.embabel.chat AssistantMessage SystemMessage UserMessage]
           [java.util.concurrent CountDownLatch]))

(def model (or (System/getenv "SCHOOL_MODEL") "qwen/qwen3.5-397b-a17b"))

;; ---------------------------------------------------------------------------
;; histórico de conversa — sobrevive a restart (dado derivado, fora do OneDrive)
;; ---------------------------------------------------------------------------

(def ^:private history-dir
  (io/file (or (System/getenv "SCHOOL_STATE_DIR")
               (str (System/getenv "LOCALAPPDATA") "\\school"))
           "history"))

(defonce history (atom {})) ; slug -> [{:role :user|:assistant :text ...}]

(defn- history-file ^java.io.File [slug]
  (io/file history-dir (str slug ".edn")))

(defn- get-history [slug]
  (or (get @history slug)
      (let [f (history-file slug)
            h (if (.exists f)
                (try (read-string (slurp f :encoding "UTF-8"))
                     (catch Exception _ []))
                [])]
        (swap! history assoc slug h)
        h)))

(defn- append-history! [slug entries]
  (let [h (into (get-history slug) entries)]
    (swap! history assoc slug h)
    (try
      (io/make-parents (history-file slug))
      (spit (history-file slug) (pr-str h) :encoding "UTF-8")
      (catch Exception e
        (binding [*out* *err*]
          (println "append-history! falhou:" (.getMessage e)))))))

;; ---------------------------------------------------------------------------
;; streaming (mecanismo do spike-gate) + chamadas estruturadas
;; ---------------------------------------------------------------------------

(defn- consumer ^java.util.function.Consumer [f]
  (reify java.util.function.Consumer (accept [_ x] (f x))))

(defn- stream!
  "Streama prosa via emit!. Devolve {:text :interrupted?}."
  [ctx messages emit!]
  (let [streaming (-> (StreamingPromptRunnerBuilder.
                       (-> (.ai (:oc ctx)) (.withLlm ^String model)))
                      (.streaming)
                      (.withMessages ^java.util.List messages))
        acc  (StringBuilder.)
        done (CountDownLatch. 1)
        err  (atom nil)
        disp (.subscribe (.generateStream streaming)
                         (consumer (fn [chunk]
                                     (.append acc (str chunk))
                                     (emit! (str chunk))))
                         (consumer (fn [e] (reset! err e) (.countDown done)))
                         ^Runnable (fn [] (.countDown done)))]
    (try
      (.await done)
      (when-let [e @err]
        (throw (if (instance? Exception e) e (RuntimeException. ^Throwable e))))
      {:text (str acc) :interrupted? false}
      (catch InterruptedException _
        (.dispose disp)
        (Thread/interrupted)
        {:text (str acc) :interrupted? true}))))

(defn- ask-edn!
  "Decisão/extração estruturada, bloqueante, validada por malli (re-pergunta
   com os erros em caso de resposta inválida)."
  [ctx {:keys [schema* prompt]}]
  (schema/create-edn! ctx {:schema schema* :llm model :max-tokens 4096
                           :timeout-s 180 :retries 2 :prompt prompt}))

(defn- ask-conteudo!
  "Conteúdo bruto de arquivo (markdown/HTML), bloqueante."
  ^String [ctx prompt]
  ;; conteúdo de arquivo demora mais que o timeout default de 60s do framework
  ;; no free tier — :timeout-s evita a tempestade de retries (nota de campo
  ;; do embabel-clj, paga de novo aqui)
  (-> (schema/ask ctx {:llm model :max-tokens 6144 :timeout-s 300
                       :prompt (str prompt "\n\nResponda SOMENTE com o conteúdo "
                                    "pedido, sem cercas de código, sem comentários "
                                    "antes ou depois.")})
      schema/clean-fences
      str/trim))

;; ---------------------------------------------------------------------------
;; conversa
;; ---------------------------------------------------------------------------

(defn- ->messages [system slug user-text]
  (into [(SystemMessage. ^String system)]
        (concat (map (fn [{:keys [role text]}]
                       (case role
                         :user      (UserMessage. ^String text)
                         :assistant (AssistantMessage. ^String text)))
                     (get-history slug))
                [(UserMessage. ^String user-text)])))

(defn- transcript
  "Transcrição compacta (últimas 12 mensagens + turno atual) para os prompts
   de decisão/extração."
  [slug user-text assistant-text]
  (->> (concat (take-last 12 (get-history slug))
               [{:role :user :text user-text}
                {:role :assistant :text assistant-text}])
       (map (fn [{:keys [role text]}]
              (str (case role :user "APRENDIZ" :assistant "PROFESSOR") ": " text)))
       (str/join "\n")))

(defn- preamble [resumo*]
  (str "Você é o professor do School — um professor particular adaptativo. "
       "Responda SEMPRE em pt-BR, didático, direto, socrático quando couber. "
       "Matéria: " (:subject resumo*) ". Os arquivos vivem em " (:dir resumo*)
       " (o aprendiz vê tudo no Obsidian). Toda avaliação cita evidência.\n\n"))

;; ---------------------------------------------------------------------------
;; estágio :missao
;; ---------------------------------------------------------------------------

(def ^:private MissaoCheck
  [:map
   [:missao-clara? {:description "true se a missão REAL do aprendiz já está clara o suficiente para escrever MISSION.md"}
    :boolean]
   [:conteudo {:optional true
               :description "se clara: o MISSION.md completo (frontmatter: subject, created; seções: ## Objetivo, ## Contexto, ## Critério de sucesso)"}
    :string]])

(defn- turno-missao! [ctx subject resumo* user-text emit!]
  (let [slug   (vault/slugify subject)
        system (str (preamble resumo*)
                    "ESTÁGIO: definição de missão. Entreviste o aprendiz para "
                    "descobrir a missão REAL (passar numa prova? entrevista? "
                    "construir algo? hobby?). Poucas perguntas, uma por vez. "
                    "Quando estiver clara — pode ser já na primeira mensagem — "
                    "confirme a missão em uma frase e diga que vai registrá-la "
                    "e que o próximo passo é a prova fria. NÃO invente detalhes "
                    "que o aprendiz não deu.")
        r      (stream! ctx (->messages system slug user-text) emit!)]
    (when-not (:interrupted? r)
      (let [{:keys [missao-clara? conteudo]}
            (ask-edn! ctx {:schema* MissaoCheck
                           :prompt (schema/edn-prompt MissaoCheck
                                    {:preamble (str "Você acompanha uma entrevista de missão "
                                                    "de estudo da matéria \"" subject "\". "
                                                    "Julgue APENAS pelo que o APRENDIZ disse:\n\n"
                                                    (transcript slug user-text (:text r)))
                                     :extra (str "Se :missao-clara? for true, :conteudo é "
                                                 "OBRIGATÓRIO: o markdown completo do MISSION.md, "
                                                 "fiel ao que o aprendiz declarou. created: "
                                                 (java.time.LocalDate/now) ".")})})]
        (when (and missao-clara? (not (str/blank? (str conteudo))))
          (let [p (vault/write-file! subject vault/mission-path conteudo)]
            (events/emit! :missao-definida {:subject slug})
            (emit! (str "\n\n✅ Missão registrada em " p
                        "\nPróximo passo: a prova fria. Diga \"pronto\" quando quiser começar."))))))
    r))

;; ---------------------------------------------------------------------------
;; estágio :prova-fria
;; ---------------------------------------------------------------------------

(def ^:private RespostasCheck
  [:map
   [:todas-respondidas? {:description "true se o aprendiz já enviou respostas para TODAS as questões da prova"}
    :boolean]])

(defn- gerar-prova! [ctx subject resumo* emit!]
  (let [slug (vault/slugify subject)
        html (ask-conteudo! ctx
               (str "Gere a PROVA FRIA da matéria \"" subject "\" para esta missão:\n\n"
                    (:mission resumo*)
                    "\n\nRegras: exatamente 8 questões-cenário numeradas (Q1..Q8), da "
                    "mais fácil à mais difícil, ponderadas pela missão; uma questão "
                    "por seção. PROIBIDO incluir respostas, gabarito ou dicas. "
                    "Formato: um documento HTML completo e self-contained, COMPACTO "
                    "(CSS mínimo embutido, tipografia limpa, sem JavaScript)."))
        p    (vault/write-file! subject vault/prova-fria-path html)]
    (events/emit! :prova-fria-gerada {:subject slug})
    (emit! (str "📝 Prova fria gerada: " p
                "\n\nAbra o arquivo no navegador, responda no seu ritmo e mande as "
                "respostas aqui no chat — todas de uma vez (Q1: ..., Q2: ...) ou uma a uma. "
                "A prova é silenciosa: não vou dar dicas até a correção."))
    {:text "" :interrupted? false}))

(defn- corrigir! [ctx subject resumo* slug prova-html transcript* emit!]
  (let [base (str "A prova fria da matéria \"" subject "\" (missão abaixo) foi "
                  "respondida pelo aprendiz no chat.\n\nMISSÃO:\n" (:mission resumo*)
                  "\n\nPROVA (HTML):\n" prova-html
                  "\n\nCONVERSA COM AS RESPOSTAS:\n" transcript*
                  "\n\nCorreção FRIA: errado é errado, sem meio-certo.\n\n")
        resultado (ask-conteudo! ctx (str base "Produza o prova-fria-resultado.md: "
                                          "frontmatter (subject, date, prova: prova-fria), "
                                          "nota final, e uma linha por questão (acertou/errou + resumo)."))
        _  (vault/write-file! subject vault/prova-fria-resultado-path resultado)
        gabarito (ask-conteudo! ctx (str base "Produza o GABARITO como HTML self-contained: "
                                         "para cada questão — enunciado, resposta do aprendiz, "
                                         "resposta correta, explicação curta."))
        _  (vault/write-file! subject vault/gabarito-fria-path gabarito)
        _  (events/emit! :prova-corrigida {:subject slug :prova "prova-fria"})
        diagnostico (ask-conteudo! ctx
                      (str base "Produza o DIAGNOSIS.md EXATAMENTE neste formato "
                           "(níveis: forte/ok/fraco/não-avaliado; TODA linha do mapa "
                           "cita evidência tipo `prova-fria q3, q5`; máx ~60 linhas):\n\n"
                           "---\nsubject: " slug "\nupdated: " (java.time.LocalDate/now)
                           "\nlast-assessment: prova-fria\noverall: iniciante | básico | intermediário | avançado\n---\n\n"
                           "# Diagnóstico — " subject "\n\n## Resumo\n(2-3 frases)\n\n"
                           "## Mapa de competências\n| Competência | Nível | Evidência |\n|---|---|---|\n\n"
                           "## Padrões de erro\n\n## Recomendações ativas"))
        _  (vault/write-file! subject vault/diagnosis-path diagnostico)
        _  (events/emit! :diagnostico-escrito {:subject slug})
        curriculo (ask-conteudo! ctx
                    (str base "DIAGNÓSTICO JÁ ESCRITO:\n" diagnostico
                         "\n\nProduza o CURRICULUM.md: 4-8 módulos ordenados por "
                         "dependência (`## NN — Nome` + linha `status: pending|active|skipped`), "
                         "o primeiro módulo não-skipped como active, skipped SOMENTE com "
                         "evidência da prova fria; cada módulo termina numa prova de consolidação."))
        p  (vault/write-file! subject vault/curriculum-path curriculo)]
    (events/emit! :curriculo-escrito {:subject slug})
    (emit! (str "\n\n✅ Correção completa. Diagnóstico e currículo escritos (" p ").\n"
                "Gabarito: abra gabarito-fria.html. No próximo turno começamos o módulo ativo — "
                "ou me peça para ajustar o plano."))))

(defn- turno-prova! [ctx subject resumo* user-text emit!]
  (let [slug (vault/slugify subject)]
    (if-not (:prova-gerada? resumo*)
      (gerar-prova! ctx subject resumo* emit!)
      (let [system (str (preamble resumo*)
                        "ESTÁGIO: prova fria em andamento — o aprendiz está "
                        "respondendo no chat. A prova é SILENCIOSA: colete as "
                        "respostas, não dê dicas, não ensine, não corrija ainda. "
                        "Confirme o recebimento e diga quantas faltam, se souber.")
            r (stream! ctx (->messages system slug user-text) emit!)]
        (when-not (:interrupted? r)
          (let [{:keys [todas-respondidas?]}
                (ask-edn! ctx {:schema* RespostasCheck
                               :prompt (schema/edn-prompt RespostasCheck
                                        {:preamble (str "Prova fria da matéria \"" subject "\" "
                                                        "(HTML abaixo). O aprendiz respondeu no chat.\n\nPROVA:\n"
                                                        (apply vault/read-file subject vault/prova-fria-path)
                                                        "\n\nCONVERSA:\n"
                                                        (transcript slug user-text (:text r)))})})]
            (when todas-respondidas?
              (corrigir! ctx subject resumo* slug
                         (apply vault/read-file subject vault/prova-fria-path)
                         (transcript slug user-text (:text r))
                         emit!))))
        r))))

;; ---------------------------------------------------------------------------
;; estágio :ensino — aula, prova de consolidação, adaptação
;; ---------------------------------------------------------------------------

(def ^:private EnsinoCheck
  [:map
   [:registrar? {:description "true se o aprendiz demonstrou aprendizado ou erro relevante NESTE turno"}
    :boolean]
   [:titulo {:optional true :description "título curto do learning record"} :string]
   [:conteudo {:optional true :description "markdown: o que aconteceu, evidência, implicação"} :string]
   [:gerar-prova-modulo? {:description "true se o aprendiz pediu ou aceitou fazer a prova do módulo ativo NESTE turno"}
    :boolean]])

(defn- gerar-prova-modulo! [ctx subject resumo* slug m emit!]
  (let [html (ask-conteudo! ctx
               (str "Gere a PROVA DE CONSOLIDAÇÃO do módulo " (:nn m) " — "
                    (:nome m) " da matéria \"" subject "\".\n\nDIAGNÓSTICO ATUAL:\n"
                    (:diagnosis resumo*)
                    "\n\nRegras: 6 a 8 questões numeradas (Q1..Qn) — recall + "
                    "aplicação do módulo — MAIS 1-2 questões finais re-testando "
                    "itens `fraco` de módulos anteriores do diagnóstico "
                    "(interleaving), marcadas como [revisão]. PROIBIDO incluir "
                    "respostas ou dicas. Formato: HTML completo self-contained, "
                    "compacto (CSS mínimo, sem JavaScript)."))
        p    (vault/write-file! subject (vault/module-prova-path m) html)]
    (events/emit! :prova-modulo-gerada {:subject slug :modulo (vault/module-dir m)})
    (emit! (str "📝 Prova do módulo " (:nn m) " gerada: " p
                "\n\nAbra no navegador e mande as respostas aqui. A prova é "
                "silenciosa: sem dicas até a correção."))))

(defn- corrigir-modulo! [ctx subject resumo* slug m transcript* emit!]
  (let [base (str "A prova de consolidação do módulo " (:nn m) " — " (:nome m)
                  " da matéria \"" subject "\" foi respondida no chat.\n\nPROVA (HTML):\n"
                  (apply vault/read-file subject (vault/module-prova-path m))
                  "\n\nCONVERSA COM AS RESPOSTAS:\n" transcript*
                  "\n\nCorreção FRIA: errado é errado, sem meio-certo.\n\n")
        resultado (ask-conteudo! ctx
                    (str base "Produza o prova-result.md: frontmatter (subject, "
                         "date, modulo: " (vault/module-dir m) "), nota final em "
                         "percentual (linha `score: NN%`), uma linha por questão "
                         "(acertou/errou + resumo), incluindo as de [revisão]."))
        _  (vault/write-file! subject (vault/module-result-path m) resultado)
        _  (events/emit! :prova-corrigida {:subject slug :prova (vault/module-dir m)})
        aprovado? (if-let [[_ n] (re-find #"score:\s*(\d+)" resultado)]
                    (>= (parse-long n) 70)
                    false)
        diagnostico (ask-conteudo! ctx
                      (str base "RESULTADO JÁ ESCRITO:\n" resultado
                           "\n\nDIAGNÓSTICO ANTERIOR:\n" (:diagnosis resumo*)
                           "\n\nREESCREVA o DIAGNOSIS.md inteiro (mesmo formato, "
                           "last-assessment: modules/" (:nn m) ", updated: "
                           (java.time.LocalDate/now) "): atualize níveis com a "
                           "evidência nova (item `fraco` só vira `ok`/`forte` após "
                           "dois acertos consecutivos), registre padrões de erro. "
                           "Se os erros apontarem fundação faltante FORA da matéria, "
                           "recomende o detour em Recomendações ativas — o aprendiz "
                           "é soberano para recusar; se recusar, registre a recusa."))
        _  (vault/write-file! subject vault/diagnosis-path diagnostico)
        _  (events/emit! :diagnostico-escrito {:subject slug})
        curriculo (ask-conteudo! ctx
                    (str "CURRÍCULO ATUAL:\n" (:curriculum resumo*)
                         "\n\nRESULTADO DA PROVA DO MÓDULO " (:nn m) ": "
                         (if aprovado? "APROVADO (>= 70%)" "REPROVADO (< 70%)")
                         "\n\nREESCREVA o CURRICULUM.md inteiro (mesmo formato): "
                         (if aprovado?
                           (str "módulo " (:nn m) " vira `status: passed`; o próximo "
                                "pending vira `status: active`.")
                           (str "módulo " (:nn m) " continua `status: active` e ganha "
                                "uma linha `remediação:` descrevendo o passo extra "
                                "antes de re-tentar a prova, baseado nos erros."))))
        _  (vault/write-file! subject vault/curriculum-path curriculo)]
    (events/emit! (if aprovado? :modulo-passou :modulo-reprovado)
                  {:subject slug :modulo (vault/module-dir m)})
    (events/emit! :curriculo-escrito {:subject slug})
    (emit! (str "\n\n" (if aprovado? "✅ Módulo aprovado!" "📚 Ainda não foi dessa vez.")
                " Diagnóstico e currículo atualizados; gabarito na correção acima. "
                (if aprovado?
                  "No próximo turno seguimos para o próximo módulo."
                  "No próximo turno fazemos a remediação e re-tentamos.")))))

(defn- turno-prova-modulo! [ctx subject resumo* user-text emit!]
  (let [slug (vault/slugify subject)
        m    (:modulo resumo*)
        system (str (preamble resumo*)
                    "ESTÁGIO: prova de consolidação do módulo " (:nn m) " — "
                    (:nome m) " em andamento. A prova é SILENCIOSA: colete as "
                    "respostas, não dê dicas, não ensine, não corrija ainda.")
        r (stream! ctx (->messages system slug user-text) emit!)]
    (when-not (:interrupted? r)
      (let [{:keys [todas-respondidas?]}
            (ask-edn! ctx {:schema* RespostasCheck
                           :prompt (schema/edn-prompt RespostasCheck
                                    {:preamble (str "Prova do módulo " (:nn m) " (HTML abaixo). "
                                                    "O aprendiz respondeu no chat.\n\nPROVA:\n"
                                                    (apply vault/read-file subject (vault/module-prova-path m))
                                                    "\n\nCONVERSA:\n"
                                                    (transcript slug user-text (:text r)))})})]
        (when todas-respondidas?
          (corrigir-modulo! ctx subject resumo* slug m
                            (transcript slug user-text (:text r)) emit!))))
    r))

(defn- turno-ensino! [ctx subject resumo* user-text emit!]
  (let [slug (vault/slugify subject)
        m    (:modulo resumo*)]
    (if (and m (:modulo-prova-gerada? resumo*) (not (:modulo-prova-corrigida? resumo*)))
      (turno-prova-modulo! ctx subject resumo* user-text emit!)
      (let [system (str (preamble resumo*)
                        "ESTÁGIO: ensino do módulo ativo"
                        (when m (str " (" (:nn m) " — " (:nome m) ")"))
                        ".\n\nDIAGNÓSTICO:\n" (:diagnosis resumo*)
                        "\n\nCURRÍCULO:\n" (:curriculum resumo*)
                        "\n\nEnsine o módulo ativo na zona de desenvolvimento "
                        "proximal: explicações curtas, exemplos, exercícios com "
                        "feedback imediato. Cite o diagnóstico ao adaptar. Quando "
                        "sentir o módulo consolidado, ofereça a prova de "
                        "consolidação. Se notar fundação faltante FORA da matéria, "
                        "recomende estudá-la antes — mas o aprendiz é soberano.")
            r (stream! ctx (->messages system slug user-text) emit!)]
        (when-not (:interrupted? r)
          (let [{:keys [registrar? titulo conteudo gerar-prova-modulo?]}
                (ask-edn! ctx {:schema* EnsinoCheck
                               :prompt (schema/edn-prompt EnsinoCheck
                                        {:preamble (str "Turno de aula da matéria \"" subject "\":\n\n"
                                                        (transcript slug user-text (:text r)))
                                         :extra "Registre com parcimônia: só demonstrações reais de aprendizado ou erro."})})]
            (when (and registrar? titulo conteudo)
              (vault/write-file! subject (vault/learning-record-path subject titulo) conteudo)
              (events/emit! :learning-record {:subject slug :titulo titulo}))
            (when (and gerar-prova-modulo? m)
              (gerar-prova-modulo! ctx subject resumo* slug m emit!))))
        r))))

;; ---------------------------------------------------------------------------
;; estágio :capstone
;; ---------------------------------------------------------------------------

(def ^:private CapstoneCheck
  [:map
   [:concluido? {:description "true se o aprendiz ENTREGOU o capstone e DEFENDEU as perguntas neste ponto da conversa"}
    :boolean]
   [:avaliacao {:optional true
                :description "se concluído: capstone/avaliacao.md — veredito, forças, lacunas, evidência da defesa"}
    :string]])

(defn- turno-capstone! [ctx subject resumo* user-text emit!]
  (let [slug   (vault/slugify subject)
        system (str (preamble resumo*)
                    "ESTÁGIO: capstone — todos os módulos passados. Missão:\n"
                    (:mission resumo*)
                    "\n\nDIAGNÓSTICO:\n" (:diagnosis resumo*)
                    "\n\nConduza o exame final: um entregável REAL julgado contra "
                    "a missão, seguido de defesa (perguntas sobre o que o aprendiz "
                    "construiu). Proponha o entregável se ainda não foi combinado; "
                    "acompanhe; na defesa, pergunte com rigor.")
        r      (stream! ctx (->messages system slug user-text) emit!)]
    (when-not (:interrupted? r)
      (let [{:keys [concluido? avaliacao]}
            (ask-edn! ctx {:schema* CapstoneCheck
                           :prompt (schema/edn-prompt CapstoneCheck
                                    {:preamble (str "Capstone da matéria \"" subject "\" (missão + conversa):\n\nMISSÃO:\n"
                                                    (:mission resumo*)
                                                    "\n\nCONVERSA:\n"
                                                    (transcript slug user-text (:text r)))
                                     :extra "concluido? só é true com entregável apresentado E defesa respondida."})})]
        (when (and concluido? avaliacao)
          (let [p (vault/write-file! subject ["capstone" "avaliacao.md"] avaliacao)]
            (events/emit! :capstone-concluido {:subject slug})
            (emit! (str "\n\n🎓 Capstone avaliado: " p
                        " — o diagnóstico de saída entra na próxima revisão."))))))
    r))

;; ---------------------------------------------------------------------------
;; a action de um turno + o agente
;; ---------------------------------------------------------------------------

(defn- turno-fn
  "A :fn das três ações — o planner escolhe pela pré-condição de estágio."
  [ctx]
  (let [subject   (bb/fetch ctx :subject)
        user-text (bb/fetch ctx :message)
        emit!     (bb/fetch ctx :emit!)
        slug      (vault/slugify subject)
        resumo*   (vault/resumo subject) ; RELÊ o vault (ADR-0002)
        {:keys [text interrupted?]}
        (case (:stage resumo*)
          :missao     (turno-missao!   ctx subject resumo* user-text emit!)
          :prova-fria (turno-prova!    ctx subject resumo* user-text emit!)
          :ensino     (turno-ensino!   ctx subject resumo* user-text emit!)
          :capstone   (turno-capstone! ctx subject resumo* user-text emit!))]
    (append-history! slug
                     (cond-> [{:role :user :text user-text}]
                       (not (str/blank? (str text))) (conj {:role :assistant :text text})))
    (bb/put! ctx :interrupted? (boolean interrupted?))
    (bb/put! ctx :stage (name (:stage resumo*)))
    (bb/set-condition! ctx :respondido? true)))

(defn- stage-condition [nome esperado]
  {:name nome
   :fn (fn [ctx]
         (= esperado (vault/stage (bb/fetch ctx :subject))))})

(defn professor []
  (ec/agent
   {:name        "professor-school"
    :description "professor adaptativo do School — missão, prova fria, ensino"
    :conditions [(stage-condition :estagio-missao? :missao)
                 (stage-condition :estagio-prova? :prova-fria)
                 (stage-condition :estagio-ensino? :ensino)
                 (stage-condition :estagio-capstone? :capstone)]
    :goals   [{:name "turno-respondido" :pre [:respondido?] :value 1.0}]
    :actions [{:name "entrevistar-missao" :llm? true :retries 1
               :pre [:estagio-missao?] :post [:respondido?]
               :description "entrevista o aprendiz até a missão ficar clara"
               :fn turno-fn}
              {:name "conduzir-prova-fria" :llm? true :retries 1
               :pre [:estagio-prova?] :post [:respondido?]
               :description "gera, aplica e corrige a prova fria; escreve diagnóstico e currículo"
               :fn turno-fn}
              {:name "ensinar-modulo" :llm? true :retries 1
               :pre [:estagio-ensino?] :post [:respondido?]
               :description "ensina o módulo ativo, aplica provas de consolidação e adapta"
               :fn turno-fn}
              {:name "conduzir-capstone" :llm? true :retries 1
               :pre [:estagio-capstone?] :post [:respondido?]
               :description "conduz o exame final: entregável real + defesa"
               :fn turno-fn}]}))
