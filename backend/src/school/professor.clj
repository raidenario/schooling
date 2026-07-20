(ns school.professor
  "O professor do School — Fase 1 (núcleo de ensino).

   GOAP: ações com pré-condições LAZY que releem o vault (ADR-0002); o
   planner escolhe a ação do estágio. Cada turno de conversa é um processo
   com goal :respondido?.

   Divisão de trabalho com o LLM: STREAMING para prosa; create-edn!/ask
   BLOQUEANTES validados por malli para decisões e conteúdo de arquivo.
   Provas são a entidade fixa de school.prova (ADR-0007): o LLM gera dados,
   o renderer fixo gera o HTML gamificado, a correção de alternativas é
   código, e durante uma prova aberta o chat entra em MODO CONSULTA."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [embabel-clj.blackboard :as bb]
            [embabel-clj.core :as ec]
            [embabel-clj.schema :as schema]
            [school.aula :as aula]
            [school.events :as events]
            [school.prova :as prova]
            [school.vault :as vault])
  (:import [com.embabel.agent.api.streaming StreamingPromptRunnerBuilder]
           [com.embabel.chat AssistantMessage SystemMessage UserMessage]
           [java.util.concurrent CountDownLatch]))

(def model (or (System/getenv "SCHOOL_MODEL") "z-ai/glm-5.2"))
(def base-url (or (System/getenv "SCHOOL_PUBLIC_URL") "http://localhost:7777"))

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
  "Decisão/extração estruturada, bloqueante, validada por malli."
  [ctx {:keys [schema* prompt]}]
  (schema/create-edn! ctx {:schema schema* :llm model :max-tokens 4096
                           :timeout-s 180 :retries 2 :prompt prompt}))

(defn- ask-conteudo!
  "Conteúdo bruto de arquivo (markdown), bloqueante. :timeout-s explícito —
   o default de 60s do framework vira tempestade de retries no free tier."
  ^String [ctx prompt]
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
  "Transcrição compacta (últimas 12 mensagens + turno atual)."
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
;; sem matéria — onboarding fluido (a matéria nasce da conversa)
;; ---------------------------------------------------------------------------

(def ^:private MateriaCheck
  [:map
   [:materia-identificada? {:description "true se a mensagem do aprendiz deixa claro O QUE ele quer estudar (nova matéria ou uma das existentes)"}
    :boolean]
   [:materia {:optional true
              :description "se identificada: nome curto da matéria, ex: typescript, guitarra, calculo-1"}
    :string]])

(defn- turno-sem-materia! [ctx user-text emit!]
  (let [existentes (vault/list-subjects)
        {:keys [materia-identificada? materia]}
        (ask-edn! ctx {:schema* MateriaCheck
                       :prompt (schema/edn-prompt MateriaCheck
                                {:preamble (str "Mensagem do aprendiz ao abrir o School: \""
                                                user-text "\"\n\nMatérias já existentes: "
                                                (if (seq existentes) (str/join ", " existentes) "(nenhuma)")
                                                ".")
                                 :extra (str "Se a mensagem citar uma matéria existente, use o "
                                             "nome EXATO dela. Saudação sem assunto claro => "
                                             ":materia-identificada? false.")})})]
    (if (and materia-identificada? (not (str/blank? (str materia))))
      (do (bb/put! ctx :materia-escolhida (vault/slugify materia))
          {:text "" :interrupted? false :skip-history? true})
      (let [system (str "Você é o professor do School — um professor particular "
                        "adaptativo. Responda em pt-BR, caloroso e breve. O aprendiz "
                        "ainda não escolheu matéria. Pergunte o que ele quer aprender"
                        (when (seq existentes)
                          (str " e ofereça continuar uma das matérias existentes: "
                               (str/join ", " existentes)))
                        ".")]
        (stream! ctx (->messages system "sem-materia" user-text) emit!)))))

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
                    "confirme a missão em uma frase e diga que vai registrá-la. "
                    "O próximo passo será uma conversa curta de calibragem. NÃO "
                    "invente detalhes que o aprendiz não deu.")
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
                        "\nAgora vamos calibrar: vou te fazer algumas perguntas "
                        "rápidas sobre o que você já conhece."))))))
    r))

;; ---------------------------------------------------------------------------
;; estágio :prova-fria — calibragem → geração → consulta → correção
;; ---------------------------------------------------------------------------

(def ^:private CalibragemCheck
  [:map
   [:calibragem-completa? {:description "true se já há noção suficiente do que o aprendiz conhece dos conceitos-chave para dosar a prova fria"}
    :boolean]
   [:resumo {:optional true
             :description "se completa: calibragem.md — lista dos conceitos-chave e o que o aprendiz declarou conhecer/desconhecer de cada um"}
    :string]])

(defn- turno-calibragem! [ctx subject resumo* user-text emit!]
  (let [slug   (vault/slugify subject)
        system (str (preamble resumo*)
                    "ESTÁGIO: calibragem pré-prova. Missão:\n" (:mission resumo*)
                    "\n\nDescubra o nível REAL do aprendiz ANTES da prova fria: "
                    "pergunte sobre os conceitos-chave da matéria (ex.: para rust — "
                    "ownership, borrowing, lifetimes, você já usou linguagem com GC?), "
                    "2-3 conceitos por mensagem, tom leve, sem ensinar ainda. "
                    "'Nunca ouvi falar' é resposta perfeita — é para isso que serve. "
                    "Em 1-3 trocas você deve ter o suficiente.")
        r      (stream! ctx (->messages system slug user-text) emit!)]
    (when-not (:interrupted? r)
      (let [{:keys [calibragem-completa? resumo]}
            (ask-edn! ctx {:schema* CalibragemCheck
                           :prompt (schema/edn-prompt CalibragemCheck
                                    {:preamble (str "Conversa de calibragem da matéria \""
                                                    subject "\":\n\n"
                                                    (transcript slug user-text (:text r)))})})]
        (when (and calibragem-completa? (not (str/blank? (str resumo))))
          (vault/write-file! subject vault/calibragem-path resumo)
          (events/emit! :calibragem-registrada {:subject slug})
          (emit! "\n\n🎯 Calibragem registrada. No próximo turno eu gero sua prova fria — diga \"pode gerar\" quando quiser."))))
    r))

(defn- consulta-system [resumo* que-prova]
  (str (preamble resumo*)
       "MODO CONSULTA — a " que-prova " está ABERTA no navegador do aprendiz. "
       "REGRA ABSOLUTA: NUNCA dê a resposta de nenhuma questão, NUNCA elimine "
       "alternativas, NUNCA confirme nem negue um palpite. Oriente socraticamente: "
       "faça perguntas que levem o aprendiz a raciocinar até chegar sozinho; "
       "relembre conceitos gerais sem conectá-los à questão específica. Incentive "
       "o 🤷 'não sei' honesto — vale mais para o diagnóstico que um chute. As "
       "respostas chegam pelo botão CONCLUIR da página, não por aqui."))

(defn- gerar-prova-fria! [ctx subject resumo* emit!]
  (let [slug (vault/slugify subject)
        p    (prova/gerar! ctx {:llm model
                                :titulo (str "Prova fria — " subject)
                                :n-questoes 8
                                :contexto (str "MATÉRIA: " subject
                                               "\n\nMISSÃO:\n" (:mission resumo*)
                                               "\n\nCALIBRAGEM (o que o aprendiz declarou conhecer):\n"
                                               (apply vault/read-file subject vault/calibragem-path))})
        _    (vault/write-edn! subject vault/prova-fria-edn-path p)
        html (prova/render-html p (str base-url "/respostas/" slug "/fria"))
        _    (vault/write-file! subject vault/prova-fria-path html)]
    (events/emit! :prova-fria-gerada {:subject slug})
    (emit! (str "📝 Prova fria pronta!\n\n👉 Abra: " base-url "/prova/" slug "/fria\n\n"
                (count (:questoes p)) " questões de alternativa, com justificativa e a opção "
                "🤷 não sei (use sem medo — dúvida honesta vale mais que chute). Enquanto "
                "a prova estiver aberta eu fico em modo consulta: posso orientar seu "
                "raciocínio, mas nunca dou a resposta. Ao final, clique em CONCLUIR."))
    {:text "" :interrupted? false}))

(defn- diagnostico-prompt [subject slug graded calibragem last-assessment diagnostico-anterior]
  (str "Prova corrigida da matéria \"" subject "\" (correção feita por código; "
       "score " (:score-pct graded) "%, " (:acertos graded) "/" (:total graded) ").\n\n"
       "ITENS (com justificativas do aprendiz — use-as: erro confiante ≠ dúvida honesta; "
       "'não sei' é lacuna declarada, não erro de raciocínio):\n"
       (pr-str (:itens graded))
       (when calibragem (str "\n\nCALIBRAGEM:\n" calibragem))
       (when diagnostico-anterior (str "\n\nDIAGNÓSTICO ANTERIOR:\n" diagnostico-anterior))
       "\n\nProduza o DIAGNOSIS.md EXATAMENTE neste formato (níveis: "
       "forte/ok/fraco/não-avaliado; TODA linha do mapa cita evidência tipo `"
       last-assessment " q3, q5`; item `fraco` só sobe após dois acertos "
       "consecutivos; máx ~60 linhas). Se os erros apontarem fundação faltante "
       "FORA da matéria, recomende o detour em Recomendações ativas — o aprendiz "
       "é soberano; se ele já recusou antes, registre e não re-litigue:\n\n"
       "---\nsubject: " slug "\nupdated: " (java.time.LocalDate/now)
       "\nlast-assessment: " last-assessment
       "\noverall: iniciante | básico | intermediário | avançado\n---\n\n"
       "# Diagnóstico — " subject "\n\n## Resumo\n(2-3 frases)\n\n"
       "## Mapa de competências\n| Competência | Nível | Evidência |\n|---|---|---|\n\n"
       "## Padrões de erro\n\n## Recomendações ativas"))

(defn- corrigir-fria! [ctx subject resumo* emit!]
  (let [slug     (vault/slugify subject)
        p        (vault/read-edn subject vault/prova-fria-edn-path)
        resps    (vault/read-edn subject vault/prova-fria-respostas-path)
        graded   (prova/grade p resps)
        _        (vault/write-file! subject vault/prova-fria-resultado-path
                                    (prova/resultado-md graded {:subject slug :prova-id "prova-fria"}))
        _        (vault/write-file! subject vault/gabarito-fria-path
                                    (prova/gabarito-html graded (:titulo p)))
        _        (events/emit! :prova-corrigida {:subject slug :prova "prova-fria"
                                                 :score (:score-pct graded)})
        _        (emit! (str "📊 Corrigido: " (:acertos graded) "/" (:total graded)
                             " (" (:score-pct graded) "%). Gabarito: " base-url
                             "/gabarito/" slug "/fria\n\nEscrevendo seu diagnóstico…"))
        diag     (ask-conteudo! ctx (diagnostico-prompt subject slug graded
                                     (apply vault/read-file subject vault/calibragem-path)
                                     "prova-fria" nil))
        _        (vault/write-file! subject vault/diagnosis-path diag)
        _        (events/emit! :diagnostico-escrito {:subject slug})
        curric   (ask-conteudo! ctx
                   (str "MISSÃO:\n" (:mission resumo*) "\n\nDIAGNÓSTICO:\n" diag
                        "\n\nProduza o CURRICULUM.md: 4-8 módulos ordenados por "
                        "dependência (`## NN — Nome` + linha `status: pending|active|skipped`), "
                        "o primeiro não-skipped como active, skipped SOMENTE com evidência "
                        "da prova fria; cada módulo termina numa prova de consolidação."))
        _        (vault/write-file! subject vault/curriculum-path curric)]
    (events/emit! :curriculo-escrito {:subject slug})
    (emit! (str "\n\n✅ Diagnóstico e currículo prontos (veja no Obsidian). "
                "No próximo turno começamos o módulo ativo — ou me peça para ajustar o plano."))
    {:text "" :interrupted? false}))

(defn- turno-prova! [ctx subject resumo* user-text emit!]
  (cond
    (not (apply vault/exists? subject vault/calibragem-path))
    (turno-calibragem! ctx subject resumo* user-text emit!)

    (not (apply vault/exists? subject vault/prova-fria-edn-path))
    (gerar-prova-fria! ctx subject resumo* emit!)

    (not (apply vault/exists? subject vault/prova-fria-respostas-path))
    (let [slug (vault/slugify subject)]
      (stream! ctx (->messages (consulta-system resumo* "prova fria") slug user-text) emit!))

    :else
    (corrigir-fria! ctx subject resumo* emit!)))

;; ---------------------------------------------------------------------------
;; estágio :ensino — aula, prova de consolidação (entidade), adaptação
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
  (let [p    (prova/gerar! ctx {:llm model
                                :titulo (str "Prova — módulo " (:nn m) ": " (:nome m))
                                :n-questoes 7
                                :contexto (str "MATÉRIA: " subject
                                               "\nMÓDULO: " (:nn m) " — " (:nome m)
                                               "\n\nDIAGNÓSTICO:\n" (:diagnosis resumo*)
                                               "\n\nRegra extra: as ÚLTIMAS 1-2 questões devem "
                                               "re-testar itens `fraco` de módulos anteriores do "
                                               "diagnóstico, com :revisao true (interleaving).")})
        _    (vault/write-edn! subject (vault/module-prova-edn-path m) p)
        html (prova/render-html p (str base-url "/respostas/" slug "/modulo/" (vault/module-dir m)))
        _    (vault/write-file! subject (vault/module-prova-path m) html)
        ;; a prova zera o acúmulo de aulas do módulo (ADR-0008)
        _    (vault/write-edn! subject (vault/module-acumulado-path m) {:peso-total 0})]
    (events/emit! :prova-modulo-gerada {:subject slug :modulo (vault/module-dir m)})
    (emit! (str "\n\n📝 Prova do módulo " (:nn m) " pronta!\n👉 Abra: " base-url
                "/prova/" slug "/modulo/" (vault/module-dir m)
                "\nEnquanto ela estiver aberta fico em modo consulta. Ao final, CONCLUIR."))))

(defn- corrigir-modulo! [ctx subject resumo* slug m emit!]
  (let [p        (vault/read-edn subject (vault/module-prova-edn-path m))
        resps    (vault/read-edn subject (vault/module-respostas-path m))
        graded   (prova/grade p resps)
        aprovado? (>= (:score-pct graded) 70)
        _  (vault/write-file! subject (vault/module-result-path m)
                              (prova/resultado-md graded {:subject slug :prova-id (vault/module-dir m)}))
        _  (vault/write-file! subject (vault/module-gabarito-path m)
                              (prova/gabarito-html graded (:titulo p)))
        _  (events/emit! :prova-corrigida {:subject slug :prova (vault/module-dir m)
                                           :score (:score-pct graded)})
        _  (emit! (str "📊 Corrigido: " (:acertos graded) "/" (:total graded)
                       " (" (:score-pct graded) "%). Gabarito: " base-url
                       "/gabarito/" slug "/modulo/" (vault/module-dir m)
                       "\n\nAtualizando diagnóstico e currículo…"))
        diag (ask-conteudo! ctx (diagnostico-prompt subject slug graded nil
                                 (str "modules/" (:nn m)) (:diagnosis resumo*)))
        _  (vault/write-file! subject vault/diagnosis-path diag)
        _  (events/emit! :diagnostico-escrito {:subject slug})
        curric (ask-conteudo! ctx
                 (str "CURRÍCULO ATUAL:\n" (:curriculum resumo*)
                      "\n\nRESULTADO DO MÓDULO " (:nn m) ": "
                      (if aprovado? "APROVADO (>= 70%)" "REPROVADO (< 70%)")
                      " — score " (:score-pct graded) "%."
                      "\n\nREESCREVA o CURRICULUM.md inteiro (mesmo formato): "
                      (if aprovado?
                        (str "módulo " (:nn m) " vira `status: passed`; o próximo "
                             "pending vira `status: active`.")
                        (str "módulo " (:nn m) " continua `status: active` e ganha uma "
                             "linha `remediação:` com o passo extra antes de re-tentar, "
                             "baseado nos erros."))))
        _  (vault/write-file! subject vault/curriculum-path curric)]
    (events/emit! (if aprovado? :modulo-passou :modulo-reprovado)
                  {:subject slug :modulo (vault/module-dir m)})
    (events/emit! :curriculo-escrito {:subject slug})
    ;; prova encerrada: respostas arquivadas no resultado; libera nova tentativa
    (when-not aprovado?
      (let [f (apply vault/subject-file subject (vault/module-respostas-path m))
            g (apply vault/subject-file subject (vault/module-prova-edn-path m))]
        (.delete f) (.delete g)
        (.delete (apply vault/subject-file subject (vault/module-prova-path m)))))
    (emit! (str "\n\n" (if aprovado? "✅ Módulo aprovado!" "📚 Ainda não foi dessa vez.")
                (if aprovado?
                  " No próximo turno seguimos para o próximo módulo."
                  " Vamos fazer a remediação e depois gero uma prova nova.")))
    {:text "" :interrupted? false}))

;; -- aula-documento (ADR-0008): explicação detalhada vira página, não despejo --

(def ^:private AulaCheck
  [:map
   [:aula-documento? {:description "true SÓ se o próximo passo pedagógico é uma explicação LONGA/do zero de um tópico — o aprendiz pediu ('explica do zero', 'não lembro', 'me ensina X direito') ou o professor prometeu explicação completa. Resposta curta, correção de exercício ou dúvida pontual => false"}
    :boolean]
   [:topico {:optional true :description "se true: o tópico a explicar, curto (ex: teorema de pitágoras)"}
    :string]
   [:peso {:optional true :description "se true: complexidade/extensão do tópico, de 1 (trivial) a 5 (grande e denso)"}
    :int]])

(def ^:private EntendimentoCheck
  [:map
   [:nivel {:description "solido | parcial | confuso — quanto o texto demonstra compreensão REAL (exemplo próprio e relações corretas valem mais que repetir termos da aula)"}
    :string]
   [:lacunas {:optional true :description "o que faltou ou está errado, 1-3 frases"}
    :string]
   [:feedback {:description "feedback direto ao aprendiz: 2-4 frases em pt-BR, citando o que ele escreveu"}
    :string]])

(def ^:private peso-prova-imediata 4)  ; tópico grande => prova logo após compreender
(def ^:private peso-acumulado-prova 8) ; soma de pesos compreendidos => prova

(defn- gerar-aula! [ctx subject resumo* slug m {:keys [topico peso versao lacunas]} emit!]
  (let [peso   (max 1 (min 5 (or peso 3)))
        versao (or versao 1)
        n      (vault/proxima-aula-n subject m)
        _      (emit! (str "📖 Preparando a aula sobre \"" topico "\"… (leva alguns instantes)"))
        corpo  (aula/gerar-corpo! ctx {:llm model :topico topico :versao versao :lacunas lacunas
                                       :contexto (str "MATÉRIA: " subject
                                                      "\nMÓDULO ATIVO: " (:nn m) " — " (:nome m)
                                                      "\n\nMISSÃO:\n" (:mission resumo*)
                                                      "\n\nDIAGNÓSTICO:\n" (:diagnosis resumo*))})
        html   (aula/render-html {:titulo topico :corpo corpo :versao versao}
                                 (str base-url "/entendimento/" slug))
        hsegs  (vault/module-aula-path m n (vault/slugify topico) "html")]
    (vault/write-file! subject hsegs html)
    (vault/write-edn! subject vault/aula-aberta-path
                      {:topico topico :peso peso :versao versao :n n
                       :html-segs hsegs :modulo (vault/module-dir m)})
    (events/emit! :aula-gerada {:subject slug :topico topico :peso peso :versao versao})
    (let [txt (str (if (> versao 1)
                     "\n\n🔁 Nova explicação pronta — outro ângulo, mais visual."
                     (str "\n\nAula pronta!"))
                   "\n👉 Abra: " base-url "/aula/" slug
                   "\n\nLeia com calma. No FINAL da página tem um campo: escreva com as "
                   "SUAS palavras o que você entendeu e clique ENVIAR. É com base nisso "
                   "que eu decido se reforço, se seguimos, ou se já vale uma prova.")]
      (emit! txt)
      {:text txt :interrupted? false})))

(defn- avaliar-entendimento! [ctx subject resumo* slug m aberta emit!]
  (let [{:keys [topico peso versao n entendimento]} aberta
        {:keys [nivel lacunas feedback]}
        (ask-edn! ctx {:schema* EntendimentoCheck
                       :prompt (schema/edn-prompt EntendimentoCheck
                                {:preamble (str "O aprendiz leu uma aula sobre \"" topico
                                                "\" (matéria " subject ", módulo " (:nn m)
                                                " — " (:nome m) ") e escreveu o que entendeu:"
                                                "\n\n\"" entendimento "\"\n\nDIAGNÓSTICO ATUAL:\n"
                                                (:diagnosis resumo*))
                                 :extra "Julgue compreensão REAL, não paráfrase: exemplo próprio e relações causais corretas pesam mais que repetir os termos da aula."})})
        nivel (or (#{"solido" "parcial" "confuso"} (str/lower-case (str nivel))) "parcial")]
    (vault/write-edn! subject (vault/module-aula-path m n (vault/slugify topico) "edn")
                      {:topico topico :peso peso :versao versao :nivel nivel
                       :entendimento entendimento :lacunas lacunas})
    (.delete (apply vault/subject-file subject vault/aula-aberta-path))
    (events/emit! :aula-avaliada {:subject slug :topico topico :nivel nivel :versao versao})
    (emit! (str "🧠 " feedback))
    (if (= nivel "confuso")
      (if (>= versao 2)
        ;; dois documentos não destravaram — muda de mídia, não insiste
        (let [txt (str "\n\nJá tentamos dois ângulos por documento — vamos destravar "
                       "aqui no chat mesmo: me diga exatamente qual parte não fecha "
                       "para você, do jeito que vier.")]
          (emit! txt)
          {:text (str feedback txt) :interrupted? false})
        (do (emit! "\n\n🔁 Vou re-explicar por outro caminho, focando no que travou…")
            (gerar-aula! ctx subject resumo* slug m
                         {:topico topico :peso peso :versao (inc versao)
                          :lacunas (str (or lacunas "")
                                        "\n\nO QUE O APRENDIZ ESCREVEU:\n" entendimento)}
                         emit!)))
      ;; solido/parcial — compreendeu: acumula peso e decide EM CÓDIGO
      (let [acum   (+ (:peso-total (or (vault/read-edn subject (vault/module-acumulado-path m))
                                       {:peso-total 0}))
                      peso)
            _      (vault/write-edn! subject (vault/module-acumulado-path m) {:peso-total acum})
            prova? (or (>= peso peso-prova-imediata) (>= acum peso-acumulado-prova))]
        (if prova?
          (do (emit! (str "\n\n💪 " (if (>= peso peso-prova-imediata)
                                      "Tópico grande — vamos consolidar com uma prova antes de seguir."
                                      "Já acumulamos bastante conteúdo — hora de uma prova de consolidação.")))
              (gerar-prova-modulo! ctx subject resumo* slug m emit!)
              {:text feedback :interrupted? false})
          (let [txt (str "\n\n➡️ Seguimos! Me peça o próximo tópico quando quiser"
                         (when (= nivel "parcial")
                           " — e eu volto nessas lacunas mais adiante") ".")]
            (emit! txt)
            {:text (str feedback txt) :interrupted? false}))))))

(defn- aula-leitura-system [resumo* aberta slug]
  (str (preamble resumo*)
       "MODO LEITURA — há uma aula aberta sobre \"" (:topico aberta)
       "\" no navegador do aprendiz (" base-url "/aula/" slug "). Tire dúvidas "
       "pontuais sobre a leitura, mas NÃO re-explique o conteúdo inteiro por aqui: "
       "o próximo passo é ele escrever o que entendeu no campo no FINAL da página "
       "e clicar ENVIAR — é dali que sai a decisão de reforçar, seguir ou provar."))

(defn- pre-check-aula!
  "Antes de streamar a resposta: o turno pede um documento de aula?"
  [ctx subject slug m user-text]
  (ask-edn! ctx {:schema* AulaCheck
                 :prompt (schema/edn-prompt AulaCheck
                          {:preamble (str "Aula da matéria \"" subject "\", módulo "
                                          (:nn m) " — " (:nome m)
                                          ". Mensagem do aprendiz AGORA: \"" user-text
                                          "\"\n\nÚltimas mensagens:\n"
                                          (->> (take-last 6 (get-history slug))
                                               (map (fn [{:keys [role text]}]
                                                      (str (case role :user "APRENDIZ" :assistant "PROFESSOR")
                                                           ": " text)))
                                               (str/join "\n")))
                           :extra "Na dúvida, :aula-documento? false — o chat normal resolve."})}))

(declare turno-ensino-chat!)

(defn- turno-ensino! [ctx subject resumo* user-text emit!]
  (let [slug   (vault/slugify subject)
        m      (:modulo resumo*)
        aberta (when m (vault/read-edn subject vault/aula-aberta-path))]
    (cond
      (and m (apply vault/exists? subject (vault/module-respostas-path m))
             (not (apply vault/exists? subject (vault/module-result-path m))))
      (corrigir-modulo! ctx subject resumo* slug m emit!)

      (and m (apply vault/exists? subject (vault/module-prova-edn-path m))
             (not (apply vault/exists? subject (vault/module-result-path m))))
      (stream! ctx (->messages (consulta-system resumo* (str "prova do módulo " (:nn m)))
                               slug user-text)
               emit!)

      ;; entendimento chegou pela página → avaliar e decidir o próximo passo
      (and aberta (not (str/blank? (str (:entendimento aberta)))))
      (avaliar-entendimento! ctx subject resumo* slug m aberta emit!)

      ;; aula aberta, ainda sem entendimento → consulta de leitura
      aberta
      (stream! ctx (->messages (aula-leitura-system resumo* aberta slug) slug user-text)
               emit!)

      :else
      (let [{:keys [aula-documento? topico peso]} (when m (pre-check-aula! ctx subject slug m user-text))]
        (if (and aula-documento? (not (str/blank? (str topico))))
          (gerar-aula! ctx subject resumo* slug m {:topico topico :peso peso} emit!)
          (turno-ensino-chat! ctx subject resumo* slug m user-text emit!))))))

(defn- turno-ensino-chat! [ctx subject resumo* slug m user-text emit!]
  (let [system (str (preamble resumo*)
                        "ESTÁGIO: ensino do módulo ativo"
                        (when m (str " (" (:nn m) " — " (:nome m) ")"))
                        ".\n\nDIAGNÓSTICO:\n" (:diagnosis resumo*)
                        "\n\nCURRÍCULO:\n" (:curriculum resumo*)
                        "\n\nEnsine o módulo ativo na zona de desenvolvimento "
                        "proximal: explicações curtas, exemplos, exercícios com "
                        "feedback imediato. Cite o diagnóstico ao adaptar. "
                        "NUNCA despeje explicação longa no chat: para explicar um "
                        "tópico do zero/em detalhe, ofereça preparar uma AULA "
                        "completa (página bonita que o sistema gera quando o "
                        "aprendiz aceitar). Quando sentir o módulo consolidado, "
                        "ofereça a prova de consolidação. Se notar fundação "
                        "faltante FORA da matéria, recomende estudá-la antes — "
                        "mas o aprendiz é soberano.")
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
        r))

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
  "A :fn das ações — o planner escolhe pela pré-condição de estágio."
  [ctx]
  (let [subject   (bb/fetch ctx :subject)
        user-text (bb/fetch ctx :message)
        emit!     (bb/fetch ctx :emit!)
        sem?      (str/blank? (str subject))
        slug      (if sem? "sem-materia" (vault/slugify subject))
        resumo*   (when-not sem? (vault/resumo subject)) ; RELÊ o vault (ADR-0002)
        {:keys [text interrupted? skip-history?]}
        (if sem?
          (turno-sem-materia! ctx user-text emit!)
          (case (:stage resumo*)
            :missao     (turno-missao!   ctx subject resumo* user-text emit!)
            :prova-fria (turno-prova!    ctx subject resumo* user-text emit!)
            :ensino     (turno-ensino!   ctx subject resumo* user-text emit!)
            :capstone   (turno-capstone! ctx subject resumo* user-text emit!)))]
    (when-not skip-history?
      (append-history! slug
                       (cond-> [{:role :user :text user-text}]
                         (not (str/blank? (str text))) (conj {:role :assistant :text text}))))
    (bb/put! ctx :interrupted? (boolean interrupted?))
    (bb/put! ctx :stage (if sem? "sem-materia" (name (:stage resumo*))))
    (bb/set-condition! ctx :respondido? true)))

(defn- stage-condition [nome esperado]
  {:name nome
   :fn (fn [ctx]
         (let [subject (bb/fetch ctx :subject)]
           (and (not (str/blank? (str subject)))
                (= esperado (vault/stage subject)))))})

(defn professor []
  (ec/agent
   {:name        "professor-school"
    :description "professor adaptativo do School — missão, calibragem, provas, ensino, capstone"
    :conditions [{:name :sem-materia?
                  :fn (fn [ctx] (str/blank? (str (bb/fetch ctx :subject))))}
                 (stage-condition :estagio-missao? :missao)
                 (stage-condition :estagio-prova? :prova-fria)
                 (stage-condition :estagio-ensino? :ensino)
                 (stage-condition :estagio-capstone? :capstone)]
    :goals   [{:name "turno-respondido" :pre [:respondido?] :value 1.0}]
    :actions [{:name "escolher-materia" :llm? true :retries 1
               :pre [:sem-materia?] :post [:respondido?]
               :description "descobre da conversa qual matéria o aprendiz quer estudar"
               :fn turno-fn}
              {:name "entrevistar-missao" :llm? true :retries 1
               :pre [:estagio-missao?] :post [:respondido?]
               :description "entrevista o aprendiz até a missão ficar clara"
               :fn turno-fn}
              {:name "conduzir-prova-fria" :llm? true :retries 1
               :pre [:estagio-prova?] :post [:respondido?]
               :description "calibra, gera a prova fria interativa, consulta e corrige"
               :fn turno-fn}
              {:name "ensinar-modulo" :llm? true :retries 1
               :pre [:estagio-ensino?] :post [:respondido?]
               :description "ensina o módulo ativo, aplica provas de consolidação e adapta"
               :fn turno-fn}
              {:name "conduzir-capstone" :llm? true :retries 1
               :pre [:estagio-capstone?] :post [:respondido?]
               :description "conduz o exame final: entregável real + defesa"
               :fn turno-fn}]}))
