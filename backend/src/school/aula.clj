(ns school.aula
  "A entidade Aula (ADR-0008): explicação detalhada como DOCUMENTO, não como
   despejo no chat. Divisão de trabalho: o LLM gera o CORPO (fragmento HTML
   rico — SVG, callouts, tabelas); a casca é código fixo — tipografia, tema e
   o formulário 'o que você entendeu' que POSTa ao backend. O ciclo fecha no
   professor: entendimento → avaliação → re-explica | segue | prova."
  (:require [clojure.string :as str]
            [embabel-clj.schema :as schema]))

(defn- esc ^String [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

;; ---------------------------------------------------------------------------
;; corpo — o LLM escreve; instruções fecham o contrato visual com a casca
;; ---------------------------------------------------------------------------

(defn gerar-corpo!
  "Fragmento HTML do corpo da aula, bloqueante. `lacunas` (re-explicação)
   carrega o que travou na versão anterior — o ângulo TEM que mudar."
  ^String [ctx {:keys [llm topico contexto versao lacunas]}]
  (-> (schema/ask ctx
        {:llm llm :max-tokens 8192 :timeout-s 300
         :prompt
         (str "Você é o professor do School escrevendo uma AULA COMPLETA, em pt-BR, "
              "sobre \"" topico "\" — um documento web para o aprendiz LER com calma "
              "(não é chat: capriche na estrutura e no visual).\n\n"
              "CONTEXTO:\n" contexto "\n\n"
              (when (and versao (> versao 1))
                (str "⚠️ Esta é a " versao "ª EXPLICAÇÃO: o aprendiz leu a anterior e "
                     "AINDA não entendeu. NÃO repita o mesmo caminho — troque o ângulo: "
                     "outra analogia, mais desenho, passos menores, mais exemplos "
                     "concretos.\nO QUE TRAVOU:\n" lacunas "\n\n"))
              "FORMATO — devolva SOMENTE um fragmento HTML (sem <html>/<head>/<body>, "
              "sem markdown, sem cercas de código):\n"
              "- <h1> com o título; seções <h2>/<h3>; parágrafos CURTOS.\n"
              "- Callouts prontos da página (use!): <div class='dica'>💡 …</div>, "
              "<div class='atencao'>⚠️ …</div>, <div class='exemplo'>🧪 …</div>, "
              "<div class='analogia'>🎭 …</div>.\n"
              "- Código em <pre><code>…</code></pre>.\n"
              "- Quando um DESENHO ajudar (triângulos, eixos, fluxos, setas), faça um "
              "SVG inline simples e limpo (<svg viewBox='…'>…</svg>) — a página "
              "centraliza e dimensiona sozinha.\n"
              "- Matemática SEM LaTeX ($$ NÃO renderiza): use HTML puro — x<sup>2</sup>, "
              "√, ×, ÷, frações escritas por extenso ou em tabela.\n"
              "- <table> quando comparar coisas.\n"
              "- NADA de <script>, links externos, imagens externas ou formulários — a "
              "página já tem o campo de entendimento no final.\n"
              "- Feche com <h2>Recapitulando</h2> e 3-5 bullets.\n\n"
              "Responda SOMENTE com o fragmento HTML.")})
      schema/clean-fences
      str/trim))

;; ---------------------------------------------------------------------------
;; casca — código fixo: tema, tipografia de leitura e o formulário
;; ---------------------------------------------------------------------------

(def ^:private css "
:root{--verde:#58cc02;--verde-esc:#46a302;--azul:#1cb0f6;--roxo:#8b5cf6;
--amarelo:#ffc800;--cinza:#e5e5e5;--texto:#2f3337;--fundo:#f7f7f7}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',system-ui,sans-serif;background:var(--fundo);color:var(--texto)}
.topo{position:sticky;top:0;background:#fff;padding:14px 20px;box-shadow:0 2px 8px #0001;z-index:9}
.topo small{color:#999}
main{max-width:760px;margin:0 auto;padding:28px 18px 80px}
article{background:#fff;border:2px solid var(--cinza);border-radius:16px;
padding:32px 34px;line-height:1.75;font-size:16.5px}
article h1{font-size:28px;line-height:1.25;margin:0 0 18px;color:#111}
article h2{font-size:21px;margin:30px 0 10px;padding-top:14px;border-top:1px solid #f0f0f0;color:#111}
article h3{font-size:17px;margin:20px 0 8px}
article p{margin:0 0 14px}
article ul,article ol{margin:0 0 14px 24px}
article li{margin-bottom:6px}
article strong{color:#111}
article svg{display:block;max-width:100%;height:auto;margin:18px auto}
article table{border-collapse:collapse;margin:14px 0;width:100%;font-size:15px}
article th,article td{border:1px solid var(--cinza);padding:8px 12px;text-align:left}
article th{background:#fafafa}
article pre{background:#2b3137;color:#e6edf3;padding:14px;border-radius:10px;overflow-x:auto;
font-size:14px;margin:0 0 14px;font-family:Consolas,monospace;white-space:pre-wrap}
article code{font-family:Consolas,monospace;font-size:.92em;background:#f0f3f6;
padding:1px 5px;border-radius:5px}
article pre code{background:none;padding:0}
article blockquote{border-left:4px solid var(--cinza);padding:2px 14px;color:#666;margin:0 0 14px}
.dica,.atencao,.exemplo,.analogia{border-radius:12px;padding:14px 16px;margin:0 0 14px;
border:2px solid;font-size:15.5px}
.dica{background:#eef8ff;border-color:var(--azul)}
.atencao{background:#fff7dd;border-color:var(--amarelo)}
.exemplo{background:#f1fbe6;border-color:var(--verde)}
.analogia{background:#f4efff;border-color:var(--roxo)}
.versao{background:#fff7dd;border:2px solid var(--amarelo);border-radius:12px;
padding:12px 16px;margin-bottom:18px;font-size:15px}
.entender{background:#fff;border:2px solid var(--azul);border-radius:16px;
padding:26px 28px;margin-top:26px}
.entender h2{font-size:20px;margin-bottom:6px}
.entender p{color:#666;font-size:15px;margin-bottom:12px}
.entender textarea{width:100%;border:2px solid var(--cinza);border-radius:12px;padding:14px;
font-size:15px;font-family:inherit;line-height:1.6;resize:vertical;min-height:140px}
.entender textarea:focus{outline:none;border-color:var(--azul)}
.entender .acao{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:12px}
.entender .hint{color:#999;font-size:13px}
button#enviar{background:var(--verde);color:#fff;border:none;border-bottom:4px solid var(--verde-esc);
border-radius:14px;padding:14px 34px;font-size:16px;font-weight:800;letter-spacing:.5px;cursor:pointer}
button#enviar:disabled{background:var(--cinza);border-bottom-color:#d0d0d0;color:#999;cursor:not-allowed}
button#enviar:not(:disabled):active{transform:translateY(2px);border-bottom-width:2px}
#fim{display:none;text-align:center;padding:60px 20px}
#fim h1{color:var(--verde);font-size:32px;margin-bottom:12px}
")

(def ^:private js "
const ta=document.getElementById('ent'),btn=document.getElementById('enviar');
const MIN=30;
function atualiza(){
  const n=ta.value.trim().length;
  btn.disabled=n<MIN;
  document.getElementById('hint').textContent=
    n>=MIN?'pronto para enviar':`escreva um pouco mais (${n}/${MIN})`;
}
ta.addEventListener('input',atualiza);
async function enviar(){
  btn.disabled=true;btn.textContent='ENVIANDO…';
  try{
    const res=await fetch(POST_URL,{method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({texto:ta.value.trim()})});
    if(!res.ok)throw new Error(await res.text());
    document.getElementById('doc').style.display='none';
    document.getElementById('fim').style.display='block';
    window.scrollTo(0,0);
  }catch(e){btn.disabled=false;btn.textContent='ENVIAR';alert('falha ao enviar: '+e.message);}
}
atualiza();
")

(defn render-html
  "A página da aula: corpo do LLM dentro da casca fixa. `post-url` recebe
   {texto} com o entendimento do aprendiz."
  ^String [{:keys [titulo corpo versao]} post-url]
  (str "<!doctype html><html lang='pt-BR'><head><meta charset='utf-8'>"
       "<meta name='viewport' content='width=device-width,initial-scale=1'>"
       "<title>" (esc titulo) "</title><style>" css "</style></head><body>"
       "<div class='topo'><b>✳ School</b> — Aula: " (esc titulo)
       "<small> · leia com calma; no final, conte o que entendeu</small></div>"
       "<main><div id='doc'>"
       (when (and versao (> versao 1))
         (str "<div class='versao'>🔁 Segunda chance de explicação — ângulo novo. "
              "A anterior não colou, e tudo bem: é assim que se aprende.</div>"))
       "<article>" corpo "</article>"
       "<section class='entender'><h2>✍️ Agora é com você</h2>"
       "<p>Sem olhar o texto de novo: explique <b>com as suas palavras</b> o que você "
       "entendeu. Pode incluir exemplo próprio, dúvida que sobrou, o que achar. "
       "Honestidade &gt; perfeição — é assim que eu decido o próximo passo.</p>"
       "<textarea id='ent' placeholder='O que eu entendi foi…'></textarea>"
       "<div class='acao'><span class='hint' id='hint'></span>"
       "<button id='enviar' onclick='enviar()' disabled>ENVIAR</button></div></section>"
       "</div><div id='fim'><h1>✅ Entendimento enviado!</h1>"
       "<p>Volte ao chat — o professor já está lendo o que você escreveu.</p></div></main>"
       "<script>const POST_URL='" post-url "';\n" js "</script></body></html>"))
