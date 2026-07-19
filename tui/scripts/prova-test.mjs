// Transição de estágio: com MISSION.md no vault, o turno seguinte deve cair
// em conduzir-prova-fria e gerar prova-fria.html.
import WebSocket from 'ws';
import fs from 'node:fs';
import path from 'node:path';

const vaultRoot = process.env.SCHOOL_TEST_VAULT;
const ws = new WebSocket('ws://localhost:7777');
const fail = (m) => { console.error('FAIL:', m); process.exit(1); };
setTimeout(() => fail('timeout de 420s'), 420000);

ws.on('open', () => {
  ws.send(JSON.stringify({type: 'start', subject: 'spike-teste'}));
  setTimeout(() => ws.send(JSON.stringify({type: 'user_msg', text: 'Pronto, pode gerar a prova fria.'})), 500);
});

let text = '';
ws.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'error') fail(m.text);
  if (m.type === 'token') text += m.text;
  if (m.type === 'info' && m.text?.startsWith('estágio:')) console.log('[', m.text, ']');
  if (m.type === 'done') {
    const f = path.join(vaultRoot, 'spike-teste', 'prova-fria.html');
    const ok = fs.existsSync(f);
    console.log('mensagem do professor:', text.slice(0, 200));
    console.log(`prova-fria.html gerada: ${ok ? 'PASS' : 'FAIL'}`);
    if (ok) {
      const html = fs.readFileSync(f, 'utf8');
      const questoes = (html.match(/Q\d+/g) ?? []).length;
      console.log(`  ${html.length} bytes, marcadores de questão encontrados: ${questoes}`);
      const temGabarito = /gabarito|resposta correta/i.test(html);
      console.log(`  sem gabarito embutido: ${temGabarito ? 'FAIL (contém!)' : 'PASS'}`);
    }
    console.log(ok ? '\nPROVA-TEST PASS' : '\nPROVA-TEST FAIL');
    process.exit(ok ? 0 : 1);
  }
});
ws.on('error', (e) => fail(e.message));
