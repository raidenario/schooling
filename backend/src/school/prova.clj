(ns school.prova
  "A entidade Prova (ADR-0007): o LLM gera DADOS validados por malli; o
   renderer fixo gera a experiência — HTML gamificado (vibe Duolingo) com
   'Não sei' obrigatório, justificativa por questão e botão Concluir que
   POSTa as respostas ao backend. O gabarito vive no prova.edn do servidor,
   nunca no HTML. Correção de alternativas é código; o LLM só diagnostica."
  (:require [clojure.string :as str]
            [embabel-clj.schema :as schema]))

(def NAO-SEI "ns")

(def ProvaSchema
  [:map
   [:titulo :string]
   [:questoes
    [:vector
     [:map
      [:id {:description "q1, q2, ..."} :string]
      [:enunciado :string]
      [:contexto {:optional true
                  :description "código ou cenário que a questão referencia (texto puro)"}
       :string]
      [:alternativas {:description "3 a 4 alternativas plausíveis"}
       [:vector [:map
                 [:letra {:description "a, b, c, d"} :string]
                 [:texto :string]]]]
      [:correta {:description "a letra da alternativa correta"} :string]
      [:explicacao {:description "por que a correta é correta (vai só para o gabarito)"} :string]
      [:revisao {:optional true :description "true se re-testa item fraco de módulo anterior"}
       :boolean]]]]])

(defn- valida-consistencia!
  "correta precisa existir entre as letras — pega alucinação estrutural."
  [prova]
  (doseq [{:keys [id correta alternativas]} (:questoes prova)]
    (when-not (some #(= correta (:letra %)) alternativas)
      (throw (ex-info (str "prova inválida: questão " id " tem correta '" correta
                           "' fora das alternativas")
                      {:questao id}))))
  prova)

(defn gerar!
  "Gera a Prova como dados via create-edn! (prompt fixo + contexto do chamador).
   `contexto` descreve matéria/missão/calibragem/diagnóstico/interleaving.
   `:ask-fn` (opcional) troca o transporte — é como o reasoning entra (ADR-0011)
   sem que a validação/retry saiam daqui."
  [ctx {:keys [llm titulo contexto n-questoes ask-fn] :or {n-questoes 8}}]
  (-> (schema/create-edn! ctx
        (cond->
         {:schema ProvaSchema :llm llm :max-tokens 8192 :timeout-s 300 :retries 2
          :prompt (schema/edn-prompt ProvaSchema
                  {:preamble (str "Você é um elaborador de provas do School. Gere a prova \""
                                  titulo "\" com EXATAMENTE " n-questoes " questões de "
                                  "alternativa, da mais fácil à mais difícil, DOSADAS pelo "
                                  "contexto abaixo — o aprendiz nunca deve sentir que a prova "
                                  "está 'em grego'.\n\nCONTEXTO:\n" contexto)
                   :extra (str "Regras: 3-4 alternativas plausíveis por questão (SEM opção "
                               "'não sei' — o sistema adiciona sozinho); distratores baseados "
                               "em erros comuns; :contexto com código quando fizer sentido; "
                               ":explicacao curta e didática. Conteúdo em pt-BR.\n"
                               "Exemplo de questão: {:id \"q1\" :enunciado \"O que imprime?\" "
                               ":contexto \"let x = 1\" :alternativas [{:letra \"a\" :texto \"1\"} "
                               "{:letra \"b\" :texto \"erro\"}] :correta \"a\" :explicacao \"...\"}")})}
          ask-fn (assoc :ask-fn ask-fn)))
      (assoc :titulo titulo)
      valida-consistencia!))

(defn consulta-texto
  "As questões da prova em texto legível para o professor no MODO CONSULTA —
   SEM :correta e SEM :explicacao. O professor enxerga exatamente o que o
   aprendiz vê na tela (enunciado, contexto, alternativas), nunca o gabarito:
   assim orienta a Q4 sem pedir print, mas não tem como vazar a resposta."
  ^String [prova]
  (->> (:questoes prova)
       (map (fn [{:keys [id enunciado contexto alternativas]}]
              (str (str/upper-case (str id)) ". " enunciado
                   (when-not (str/blank? (str contexto)) (str "\n" contexto))
                   "\n"
                   (str/join "\n"
                             (for [{:keys [letra texto]} alternativas]
                               (str "  " letra ") " texto))))))
       (str/join "\n\n")))

;; ---------------------------------------------------------------------------
;; render — o molde fixo e gamificado
;; ---------------------------------------------------------------------------

(defn- esc ^String [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(def ^:private css "
:root{--verde:#58cc02;--verde-esc:#46a302;--azul:#1cb0f6;--cinza:#e5e5e5;
--texto:#3c3c3c;--amarelo:#ffc800;--fundo:#f7f7f7}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',system-ui,sans-serif;background:var(--fundo);color:var(--texto)}
.topo{position:sticky;top:0;background:#fff;padding:14px 20px;box-shadow:0 2px 8px #0001;z-index:9}
.barra{height:14px;background:var(--cinza);border-radius:8px;overflow:hidden;margin-top:8px}
.barra>div{height:100%;width:0;background:var(--verde);border-radius:8px;transition:width .3s}
.topo small{color:#999}
main{max-width:720px;margin:0 auto;padding:24px 16px 120px}
.q{background:#fff;border:2px solid var(--cinza);border-radius:16px;padding:20px;margin-bottom:20px}
.q.ok{border-color:var(--verde)}
.q h2{font-size:15px;color:var(--azul);margin-bottom:6px}
.q .rev{color:var(--amarelo);font-weight:700}
.q p.enun{font-size:17px;font-weight:600;margin-bottom:12px}
pre{background:#2b3137;color:#e6edf3;padding:12px;border-radius:10px;overflow-x:auto;
font-size:14px;margin-bottom:12px;font-family:Consolas,monospace;white-space:pre-wrap}
.alt{display:block;width:100%;text-align:left;background:#fff;border:2px solid var(--cinza);
border-radius:12px;padding:12px 14px;margin:8px 0;font-size:15px;cursor:pointer;transition:.15s}
.alt:hover{background:#f0faff;border-color:var(--azul)}
.alt.sel{border-color:var(--verde);background:#e8f9d8;font-weight:600}
.alt.ns{color:#888;font-style:italic}
.alt.ns.sel{border-color:var(--amarelo);background:#fff7dd}
textarea{width:100%;border:2px solid var(--cinza);border-radius:10px;padding:10px;
font-size:14px;font-family:inherit;margin-top:8px;resize:vertical;min-height:56px}
textarea:focus{outline:none;border-color:var(--azul)}
.rodape{position:fixed;bottom:0;left:0;right:0;background:#fff;box-shadow:0 -2px 12px #0002;padding:14px 20px}
.rodape .linha{max-width:720px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;gap:16px}
#faltam{color:#999;font-size:14px}
button#concluir{background:var(--verde);color:#fff;border:none;border-bottom:4px solid var(--verde-esc);
border-radius:14px;padding:14px 34px;font-size:16px;font-weight:800;letter-spacing:.5px;cursor:pointer}
button#concluir:disabled{background:var(--cinza);border-bottom-color:#d0d0d0;color:#999;cursor:not-allowed}
button#concluir:not(:disabled):active{transform:translateY(2px);border-bottom-width:2px}
#fim{display:none;text-align:center;padding:60px 20px}
#fim h1{color:var(--verde);font-size:32px;margin-bottom:12px}
")

(def ^:private js "
const respostas={};
function sel(qid,letra,el){
  respostas[qid]=respostas[qid]||{};respostas[qid].alternativa=letra;
  const card=el.closest('.q');
  card.querySelectorAll('.alt').forEach(a=>a.classList.remove('sel'));
  el.classList.add('sel');card.classList.add('ok');atualiza();
}
function just(qid,el){respostas[qid]=respostas[qid]||{};respostas[qid].justificativa=el.value;}
function atualiza(){
  const total=document.querySelectorAll('.q').length;
  const feitas=Object.values(respostas).filter(r=>r.alternativa).length;
  document.querySelector('.barra>div').style.width=(100*feitas/total)+'%';
  document.getElementById('faltam').textContent=
    feitas===total?'tudo respondido!':`${total-feitas} questão(ões) restante(s)`;
  document.getElementById('concluir').disabled=feitas!==total;
}
async function concluir(){
  const btn=document.getElementById('concluir');
  btn.disabled=true;btn.textContent='ENVIANDO…';
  const answers=Object.entries(respostas).map(([id,r])=>
    ({id,alternativa:r.alternativa,justificativa:r.justificativa||''}));
  try{
    const res=await fetch(POST_URL,{method:'POST',
      headers:{'Content-Type':'application/json'},body:JSON.stringify({answers})});
    if(!res.ok)throw new Error(await res.text());
    document.querySelector('main').style.display='none';
    document.querySelector('.rodape').style.display='none';
    document.getElementById('fim').style.display='block';
  }catch(e){btn.disabled=false;btn.textContent='CONCLUIR';alert('falha ao enviar: '+e.message);}
}
atualiza();
")

(defn- questao-html [{:keys [id enunciado contexto alternativas revisao]}]
  (str "<div class='q' id='" (esc id) "'>"
       "<h2>" (str/upper-case (esc id))
       (when revisao " <span class='rev'>· revisão</span>") "</h2>"
       "<p class='enun'>" (esc enunciado) "</p>"
       (when contexto (str "<pre>" (esc contexto) "</pre>"))
       (apply str
              (for [{:keys [letra texto]} alternativas]
                (str "<button class='alt' onclick=\"sel('" (esc id) "','" (esc letra)
                     "',this)\"><b>" (str/upper-case (esc letra)) ".</b> "
                     (esc texto) "</button>")))
       "<button class='alt ns' onclick=\"sel('" (esc id) "','" NAO-SEI
       "',this)\">🤷 Ainda não sei</button>"
       "<textarea placeholder='Por que você escolheu essa alternativa? (opcional, "
       "mas ajuda muito o diagnóstico)' oninput=\"just('" (esc id) "',this)\"></textarea>"
       "</div>"))

(defn render-html
  "O HTML interativo da prova — SEM gabarito. `post-url` recebe as respostas."
  ^String [prova post-url]
  (let [qs (:questoes prova)]
    (str "<!doctype html><html lang='pt-BR'><head><meta charset='utf-8'>"
         "<meta name='viewport' content='width=device-width,initial-scale=1'>"
         "<title>" (esc (:titulo prova)) "</title><style>" css "</style></head><body>"
         "<div class='topo'><b>✳ School</b> — " (esc (:titulo prova))
         "<small> · " (count qs) " questões · responda com calma; 🤷 vale mais que chute</small>"
         "<div class='barra'><div></div></div></div>"
         "<main>" (apply str (map questao-html qs)) "</main>"
         "<div id='fim'><h1>✅ Respostas enviadas!</h1>"
         "<p>Volte ao chat — o professor já está corrigindo.</p></div>"
         "<div class='rodape'><div class='linha'><span id='faltam'></span>"
         "<button id='concluir' onclick='concluir()' disabled>CONCLUIR</button></div></div>"
         "<script>const POST_URL='" post-url "';\n" js "</script></body></html>")))

;; ---------------------------------------------------------------------------
;; correção — código, não prosa
;; ---------------------------------------------------------------------------

(defn grade
  "Corrige em código: prova (com gabarito) + respostas [{:id :alternativa
   :justificativa}] → {:score-pct :acertos :total :itens [...]}."
  [prova respostas]
  (let [por-id (into {} (map (juxt :id identity)) respostas)
        itens  (mapv (fn [{:keys [id correta] :as q}]
                       (let [{:keys [alternativa justificativa]} (get por-id id)]
                         (-> (select-keys q [:id :enunciado :correta :explicacao :revisao])
                             (assoc :alternativa (or alternativa NAO-SEI)
                                    :justificativa (str justificativa)
                                    :nao-sei? (= alternativa NAO-SEI)
                                    :acertou? (= alternativa correta)))))
                     (:questoes prova))
        acertos (count (filter :acertou? itens))]
    {:total (count itens) :acertos acertos
     :score-pct (int (Math/round (* 100.0 (/ acertos (max 1 (count itens))))))
     :itens itens}))

(defn resultado-md
  "prova-result.md determinístico, no formato do contrato."
  ^String [{:keys [score-pct acertos total itens]} {:keys [subject prova-id]}]
  (str "---\nsubject: " subject "\ndate: " (java.time.LocalDate/now)
       "\nprova: " prova-id "\nscore: " score-pct "%\n---\n\n"
       "# Resultado — " prova-id " (" acertos "/" total ")\n\n"
       (str/join "\n"
                 (for [{:keys [id acertou? nao-sei? alternativa correta enunciado revisao]} itens]
                   (str "- **" id "** " (cond nao-sei? "🤷 não sei"
                                              acertou? "✅ acertou"
                                              :else (str "❌ errou (marcou " alternativa
                                                         ", correta " correta ")"))
                        (when revisao " _[revisão]_")
                        " — " enunciado)))
       "\n"))

(defn gabarito-html
  "Gabarito determinístico: questão, resposta do aprendiz, correta, explicação."
  ^String [{:keys [score-pct acertos total itens]} titulo]
  (str "<!doctype html><html lang='pt-BR'><head><meta charset='utf-8'>"
       "<title>Gabarito — " (esc titulo) "</title><style>" css
       ".g{border-left:6px solid var(--cinza)}.g.certo{border-left-color:var(--verde)}"
       ".g.errado{border-left-color:#ff4b4b}.g.ns{border-left-color:var(--amarelo)}"
       ".resp{margin:6px 0;font-size:15px}.expl{background:#f0faff;border-radius:10px;"
       "padding:10px;margin-top:8px;font-size:14px}.just{color:#777;font-size:13px;"
       "font-style:italic;margin-top:6px}</style></head><body>"
       "<div class='topo'><b>✳ School</b> — Gabarito: " (esc titulo)
       "<small> · " acertos "/" total " (" score-pct "%)</small>"
       "<div class='barra'><div style='width:" score-pct "%'></div></div></div><main>"
       (apply str
              (for [{:keys [id enunciado alternativa correta explicacao justificativa
                            acertou? nao-sei?]} itens]
                (str "<div class='q g " (cond acertou? "certo" nao-sei? "ns" :else "errado")
                     "'><h2>" (str/upper-case (esc id)) "</h2>"
                     "<p class='enun'>" (esc enunciado) "</p>"
                     "<p class='resp'>Sua resposta: <b>"
                     (if nao-sei? "🤷 não sei" (str/upper-case (esc alternativa))) "</b>"
                     (cond acertou? " ✅" nao-sei? "" :else " ❌") "</p>"
                     (when-not acertou?
                       (str "<p class='resp'>Correta: <b>" (str/upper-case (esc correta)) "</b></p>"))
                     "<div class='expl'>" (esc explicacao) "</div>"
                     (when-not (str/blank? justificativa)
                       (str "<p class='just'>sua justificativa: “" (esc justificativa) "”</p>"))
                     "</div>")))
       "</main></body></html>"))
