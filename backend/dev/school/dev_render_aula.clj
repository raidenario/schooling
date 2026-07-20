(ns school.dev-render-aula
  "Renderiza uma aula de exemplo (corpo fixo, sem LLM) para inspeção visual:
   clojure -M:render-aula <arquivo-de-saida>"
  (:require [school.aula :as aula]))

(def ^:private corpo
  (str "<h1>O Teorema de Pitágoras — do zero</h1>"
       "<p>Um <strong>triângulo retângulo</strong> é qualquer triângulo com um "
       "ângulo de 90° (um canto em L).</p>"
       "<svg viewBox='0 0 300 200' width='300'>"
       "<polygon points='40,160 40,40 220,160' fill='#eef8ff' stroke='#1cb0f6' stroke-width='3'/>"
       "<rect x='40' y='140' width='20' height='20' fill='none' stroke='#1cb0f6' stroke-width='2'/>"
       "<text x='20' y='105' font-size='14'>b</text>"
       "<text x='125' y='180' font-size='14'>a</text>"
       "<text x='140' y='90' font-size='14'>h</text></svg>"
       "<div class='analogia'>🎭 Pense nos catetos como as duas ruas que você anda "
       "para chegar num ponto; a hipotenusa é o atalho em linha reta.</div>"
       "<h2>A fórmula</h2>"
       "<p>h<sup>2</sup> = a<sup>2</sup> + b<sup>2</sup> — o quadrado da hipotenusa "
       "é a soma dos quadrados dos catetos.</p>"
       "<div class='exemplo'>🧪 Catetos 3 e 4: h<sup>2</sup> = 9 + 16 = 25, "
       "logo h = √25 = <strong>5</strong>.</div>"
       "<div class='atencao'>⚠️ Só vale para triângulos com ângulo de 90°.</div>"
       "<div class='dica'>💡 Se souber a hipotenusa e um cateto, inverta: "
       "a<sup>2</sup> = h<sup>2</sup> − b<sup>2</sup>.</div>"
       "<pre><code>h = sqrt(a*a + b*b)</code></pre>"
       "<h2>Recapitulando</h2>"
       "<ul><li>Vale só no triângulo retângulo</li>"
       "<li>Hipotenusa é o maior lado, oposto ao ângulo de 90°</li>"
       "<li>h² = a² + b²</li></ul>"))

(defn -main [& [out]]
  (let [out (or out "aula-sample.html")]
    (spit out
          (aula/render-html {:titulo "Teorema de Pitágoras" :corpo corpo :versao 1}
                            "http://localhost:7777/entendimento/matematica")
          :encoding "UTF-8")
    (println "escrito em" out)))
