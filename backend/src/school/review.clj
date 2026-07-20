(ns school.review
  "A sessão de revisão FSRS como página (Fase 2a) — mesmo padrão das
   entidades Prova (ADR-0007) e Aula (ADR-0008): renderer FIXO em código,
   gamificado; um card por vez (frente → revelar → rating Errei/Difícil/
   Bom/Fácil); cada rating POSTa na hora (sessão abandonada não perde nada).
   O FSRS roda no servidor; a página nunca vê a agenda."
  (:require [clojure.string :as str]
            [school.vault :as vault]))

(defn- esc ^String [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- md-lite
  "Markdown mínimo e determinístico dos cards minerados: cercas de código
   viram <pre>, **negrito** vira <b>, parágrafos por linha em branco."
  ^String [^String md]
  (let [blocos (str/split (str md) #"```")]
    (->> blocos
         (map-indexed
          (fn [i b]
            (if (odd? i)
              (str "<pre>" (esc (str/trim b)) "</pre>")
              (->> (str/split (esc b) #"\n\n+")
                   (map #(let [p (str/trim %)]
                           (when-not (str/blank? p)
                             (str "<p>" (str/replace p #"\*\*(.+?)\*\*" "<b>$1</b>")
                                  "</p>"))))
                   (str/join "")))))
         (str/join ""))))

(defn parse-card
  "Extrai {:frente :verso} (HTML) do markdown de um card do vault."
  [^String md]
  (let [corpo (last (str/split (str md) #"(?m)^---$" 3))
        [_ frente verso] (re-find #"(?s)## Frente\s*(.*?)\s*## Verso\s*(.*)" (str corpo))]
    {:frente (md-lite frente) :verso (md-lite verso)}))

(defn carrega-cards
  "Materializa os cards due para a página: lê o conteúdo de cada um do vault."
  [subject due]
  (into []
        (keep (fn [{:keys [id path]}]
                (when-let [md (apply vault/read-file subject (str/split path #"/"))]
                  (assoc (parse-card md) :id id))))
        due))

(def ^:private css "
:root{--verde:#58cc02;--verde-esc:#46a302;--azul:#1cb0f6;--vermelho:#ff4b4b;
--laranja:#ff9600;--cinza:#e5e5e5;--texto:#3c3c3c;--fundo:#f7f7f7}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',system-ui,sans-serif;background:var(--fundo);color:var(--texto)}
.topo{position:sticky;top:0;background:#fff;padding:14px 20px;box-shadow:0 2px 8px #0001;z-index:9}
.topo small{color:#999}
.barra{height:14px;background:var(--cinza);border-radius:8px;overflow:hidden;margin-top:8px}
.barra>div{height:100%;width:0;background:var(--verde);border-radius:8px;transition:width .3s}
main{max-width:680px;margin:0 auto;padding:26px 16px 40px}
.card{background:#fff;border:2px solid var(--cinza);border-radius:16px;padding:26px;
min-height:220px;font-size:17px;line-height:1.65}
.card p{margin:0 0 12px}
.card pre{background:#2b3137;color:#e6edf3;padding:12px;border-radius:10px;overflow-x:auto;
font-size:14px;margin:0 0 12px;font-family:Consolas,monospace;white-space:pre-wrap}
.verso{display:none;border-top:2px dashed var(--cinza);margin-top:16px;padding-top:16px}
.acoes{display:flex;gap:10px;margin-top:18px;justify-content:center;flex-wrap:wrap}
.acoes button{border:none;border-radius:14px;padding:14px 22px;font-size:15px;font-weight:800;
color:#fff;cursor:pointer;border-bottom:4px solid #0003}
.acoes button:active{transform:translateY(2px);border-bottom-width:2px}
#mostrar{background:var(--azul);min-width:220px}
.r1{background:var(--vermelho)}.r2{background:var(--laranja)}
.r3{background:var(--verde)}.r4{background:#2ec4b6}
.ratings{display:none}
#fim{display:none;text-align:center;padding:60px 20px}
#fim h1{color:var(--verde);font-size:32px;margin-bottom:12px}
#fim p{color:#666}
.vazio{text-align:center;padding:60px 20px}
.vazio h1{color:var(--verde);font-size:30px;margin-bottom:10px}
.vazio p{color:#666}
")

(def ^:private js "
let i=0,feitos=0;
const total=CARDS.length;
function atual(){return CARDS[i];}
function render(){
  const c=atual();
  document.getElementById('frente').innerHTML=c.frente;
  document.getElementById('verso').innerHTML=c.verso;
  document.getElementById('verso').style.display='none';
  document.querySelector('.ratings').style.display='none';
  document.getElementById('mostrar').style.display='inline-block';
  document.getElementById('pos').textContent=`card ${i+1} de ${total}`;
  document.querySelector('.barra>div').style.width=(100*feitos/total)+'%';
}
function mostrar(){
  document.getElementById('verso').style.display='block';
  document.querySelector('.ratings').style.display='flex';
  document.getElementById('mostrar').style.display='none';
}
async function rate(r){
  const c=atual();
  document.querySelectorAll('.ratings button').forEach(b=>b.disabled=true);
  try{
    const res=await fetch(POST_URL,{method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({card:c.id,rating:r})});
    if(!res.ok)throw new Error(await res.text());
    feitos++;i++;
    if(i>=total){
      document.getElementById('sessao').style.display='none';
      document.querySelector('.barra>div').style.width='100%';
      document.getElementById('fim').style.display='block';
    }else{render();}
  }catch(e){alert('falha ao registrar: '+e.message);}
  finally{document.querySelectorAll('.ratings button').forEach(b=>b.disabled=false);}
}
if(total>0)render();
")

(defn- json-cards ^String [cards]
  (str "[" (str/join ","
             (for [{:keys [id frente verso]} cards]
               (str "{\"id\":" id
                    ",\"frente\":\"" (-> frente (str/replace "\\" "\\\\")
                                        (str/replace "\"" "\\\"")
                                        (str/replace "\n" "\\n")) "\""
                    ",\"verso\":\"" (-> verso (str/replace "\\" "\\\\")
                                       (str/replace "\"" "\\\"")
                                       (str/replace "\n" "\\n")) "\"}")))
       "]"))

(defn render-html
  "A página da sessão de revisão. `cards` = carrega-cards; vazia => 'em dia'."
  ^String [{:keys [subject cards stats]} post-url]
  (str "<!doctype html><html lang='pt-BR'><head><meta charset='utf-8'>"
       "<meta name='viewport' content='width=device-width,initial-scale=1'>"
       "<title>Revisão — " (esc subject) "</title><style>" css "</style></head><body>"
       "<div class='topo'><b>✳ School</b> — Revisão: " (esc subject)
       "<small> · " (count cards) " cards na fila · <span id='pos'></span></small>"
       "<div class='barra'><div></div></div></div><main>"
       (if (empty? cards)
         (str "<div class='vazio'><h1>🎉 Tudo em dia!</h1>"
              "<p>Nenhum card vencido agora"
              (when stats (str " — " (:total stats) " no total, "
                               (:reviews stats) " revisões feitas"))
              ". Volte amanhã.</p></div>")
         (str "<div id='sessao'><div class='card'><div id='frente'></div>"
              "<div class='verso' id='verso'></div></div>"
              "<div class='acoes'>"
              "<button id='mostrar' onclick='mostrar()'>MOSTRAR RESPOSTA</button></div>"
              "<div class='acoes ratings'>"
              "<button class='r1' onclick='rate(1)'>😖 Errei</button>"
              "<button class='r2' onclick='rate(2)'>😅 Difícil</button>"
              "<button class='r3' onclick='rate(3)'>🙂 Bom</button>"
              "<button class='r4' onclick='rate(4)'>😎 Fácil</button></div></div>"
              "<div id='fim'><h1>✅ Sessão concluída!</h1>"
              "<p>Cada resposta já reagendou o card — a fila de amanhã se monta sozinha.</p></div>"))
       "</main><script>const POST_URL='" post-url "';const CARDS=" (json-cards cards)
       ";\n" js "</script></body></html>"))
