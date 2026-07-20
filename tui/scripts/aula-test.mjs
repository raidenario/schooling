// ADR-0008 — teste vivo do ciclo de aula-documento:
// pedido "do zero" → aula HTML gerada → GET /aula → POST /entendimento →
// avaliação + decisão (re-explica | segue | prova).
// Env: SCHOOL_TEST_VAULT (raiz do vault de teste com matéria em :ensino).
import WebSocket from 'ws';
import fs from 'node:fs';
import path from 'node:path';

const vaultRoot = process.env.SCHOOL_TEST_VAULT;
if (!vaultRoot) { console.error('faltou SCHOOL_TEST_VAULT'); process.exit(1); }
const SUBJECT = 'clojure-e2e';
const BASE = 'http://localhost:7777';
const dir = path.join(vaultRoot, SUBJECT);
const abertaFile = path.join(dir, 'aula-aberta.edn');

const fail = (msg) => { console.error('FAIL:', msg); process.exit(1); };
setTimeout(() => fail('timeout global de 560s'), 560000);

const ws = new WebSocket('ws://localhost:7777');
let fase = 'start', tokens = 0, texto = '';

ws.on('error', (e) => fail(e.message));
ws.on('open', () => ws.send(JSON.stringify({ type: 'start', subject: SUBJECT })));

async function aposAulaGerada() {
  if (!fs.existsSync(abertaFile)) fail('aula-aberta.edn não foi escrito');
  const aberta = fs.readFileSync(abertaFile, 'utf8');
  console.log('aula-aberta.edn:', aberta.slice(0, 200));
  if (!texto.includes('/aula/')) fail('resposta não contém o link da aula');
  console.log('link na resposta: PASS');

  const page = await fetch(`${BASE}/aula/${SUBJECT}`);
  if (page.status !== 200) fail(`GET /aula -> ${page.status}`);
  const html = await page.text();
  if (!html.includes('ENVIAR') || !html.includes('entendimento')) fail('página sem formulário de entendimento');
  console.log(`GET /aula: PASS (${html.length} bytes)`);

  fase = 'avaliacao'; tokens = 0; texto = '';
  const post = await fetch(`${BASE}/entendimento/${SUBJECT}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      texto: 'Entendi que uma closure é uma função que captura as variáveis do escopo ' +
             'léxico onde foi criada e continua enxergando esses valores mesmo depois ' +
             'que o escopo externo terminou. Por exemplo, (defn contador [] (let [n (atom 0)] ' +
             '(fn [] (swap! n inc)))) devolve uma função que lembra do atom n.',
    }),
  });
  if (post.status !== 200) fail(`POST /entendimento -> ${post.status}: ${await post.text()}`);
  console.log('POST /entendimento: PASS (turno de avaliação disparado)');
}

function aposAvaliacao() {
  if (fs.existsSync(abertaFile)) fail('aula-aberta.edn deveria ter sido apagado após a avaliação');
  const aulasDir = path.join(dir, 'modules', '01-closures', 'aulas');
  const regs = fs.existsSync(aulasDir) ? fs.readdirSync(aulasDir).filter(f => f.endsWith('.edn')) : [];
  if (!regs.length) fail('nenhum registro de aula (.edn) escrito no módulo');
  const reg = fs.readFileSync(path.join(aulasDir, regs[0]), 'utf8');
  console.log('registro da aula:', reg.slice(0, 300));
  if (!/:nivel "(solido|parcial|confuso)"/.test(reg)) fail('registro sem :nivel válido');
  const decisao = /confuso/.test(reg) ? 're-explicou' :
    (texto.includes('prova') && fs.existsSync(path.join(dir, 'modules', '01-closures', 'prova.edn'))
      ? 'gerou prova' : 'seguiu');
  console.log(`feedback + decisão (${decisao}): ${texto.slice(0, 220).replace(/\n/g, ' ')}…`);
  console.log('\nAULA-TEST PASS');
  process.exit(0);
}

ws.on('message', async (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.type === 'error') fail('erro do servidor: ' + m.text);
  if (m.type === 'token') { tokens++; texto += m.text; return; }

  if (m.type === 'info') {
    console.log('info:', m.text);
    if (fase === 'start' && m.text?.includes('estágio: ensino')) {
      fase = 'aula'; tokens = 0; texto = '';
      ws.send(JSON.stringify({
        type: 'user_msg',
        text: 'Não lembro nada de closures. Me explica do zero, com calma e em detalhe, por favor.',
      }));
    }
    return;
  }

  if (m.type === 'done') {
    console.log(`done (fase ${fase}): ${tokens} chunks`);
    if (fase === 'aula') { fase = 'pos-aula'; await aposAulaGerada().catch(e => fail(e.message)); }
    else if (fase === 'avaliacao') aposAvaliacao();
  }
});
