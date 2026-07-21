import React, {useEffect, useRef, useState} from 'react';
import {render, Box, Static, Text, useInput, useStdout} from 'ink';
import TextInput from 'ink-text-input';
import Spinner from 'ink-spinner';
import WebSocket from 'ws';
import {mdAnsi} from './md.js';

const URL = process.env.SCHOOL_WS ?? 'ws://localhost:7777';
const SUBJECT_ARG = process.argv[2]; // opcional: pnpm run dev -- <matéria>

// paleta inspirada no Claude Code; o acento muda com o MODO do professor —
// azul calibrando, verde com prova aberta (modo consulta), coral no resto
const ACCENT = '#D97757'; // coral — modo normal
const DIM = 'gray';

type Modo = 'normal' | 'calibragem' | 'consulta';
const MODO_COR: Record<Modo, string> = {
  normal: ACCENT,
  calibragem: '#1cb0f6', // azul
  consulta: '#58cc02', // verde
};
const MODO_AVISO: Record<Modo, string> = {
  normal: '✳ modo aula — seguimos no ritmo normal',
  calibragem: '🎯 modo calibragem — cada resposta sua afina a prova',
  consulta: '📝 modo consulta — prova aberta; oriento seu raciocínio, não dou resposta',
};

// A região DINÂMICA do Ink (streaming/spinner/input) precisa caber na
// viewport: maior que a tela, o Ink não consegue apagar o frame anterior e
// re-renders "assam" cópias no scrollback (pior em resize/zoom). Então o
// bloco vivo mostra só a CAUDA do texto, orçada em linhas visuais; o texto
// completo vai para o histórico (Static) no done.
function tailByRows(s: string, cols: number, maxRows: number): string {
  const lines = s.split('\n');
  const larg = Math.max(20, cols - 4);
  let rows = 0;
  const out: string[] = [];
  for (let i = lines.length - 1; i >= 0; i--) {
    rows += Math.max(1, Math.ceil(lines[i].length / larg));
    if (rows > maxRows) {
      // uma linha sozinha pode estourar o orçamento (parágrafo sem \n):
      // corta a própria linha pela cauda
      if (out.length === 0) out.unshift('…' + lines[i].slice(-(larg * maxRows)));
      else out.unshift('…');
      break;
    }
    out.unshift(lines[i]);
  }
  return out.join('\n');
}

type Entry = {role: 'aprendiz' | 'professor' | 'sistema'; text: string; interrupted?: boolean; modo?: Modo};
type Item = {kind: 'banner'} | ({kind: 'msg'} & Entry);
type Server =
  | {type: 'token'; text: string}
  | {type: 'thinking'; text: string}
  | {type: 'turn_start'}
  | {type: 'done'; interrupted?: boolean}
  | {type: 'info'; text: string}
  | {type: 'error'; text: string}
  | {type: 'modo'; modo: Modo}
  | {type: 'records'; items: string[]};

const HELP = [
  '/help           — esta ajuda',
  '/materia <nome> — abre/troca a matéria (ou só diga o que quer aprender)',
  '/stage          — matéria e estágio atuais',
  '/vault          — caminho dos arquivos desta matéria',
  'ESC             — interrompe a aula no meio do stream',
].join('\n');

function Banner() {
  return (
    <Box borderStyle="round" borderColor={ACCENT} flexDirection="column" paddingX={2} marginTop={1}>
      <Text>
        <Text color={ACCENT} bold>✳ School</Text>
        <Text color={DIM}> — professor particular adaptativo</Text>
      </Text>
      <Text color={DIM}>diga o que quer aprender, ou continue uma matéria existente</Text>
      <Text color={DIM}>/help para comandos · os arquivos aparecem no Obsidian</Text>
    </Box>
  );
}

function Message({e}: {e: Entry}) {
  const marker =
    e.role === 'aprendiz' ? <Text color={DIM}>{'❯ '}</Text>
    : e.role === 'professor' ? <Text color={MODO_COR[e.modo ?? 'normal']}>{'⏺ '}</Text>
    : <Text color={DIM}>{'⎿  '}</Text>;
  return (
    <Box marginTop={1}>
      {marker}
      <Box flexGrow={1}>
        <Text color={e.role === 'sistema' ? DIM : undefined} dimColor={e.role === 'aprendiz'}>
          {e.role === 'professor' ? mdAnsi(e.text) : e.text}
          {e.interrupted ? <Text color="yellow"> [interrompido]</Text> : null}
        </Text>
      </Box>
    </Box>
  );
}

function App() {
  const [items, setItems] = useState<Item[]>([{kind: 'banner'}]);
  const [streaming, setStreaming] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [materia, setMateria] = useState<string | null>(SUBJECT_ARG ?? null);
  const [stage, setStage] = useState<string | null>(null);
  const [vaultDir, setVaultDir] = useState<string | null>(null);
  const [modo, setModo] = useState<Modo>('normal');
  const [thinking, setThinking] = useState<string | null>(null);
  const {stdout} = useStdout();
  const [dims, setDims] = useState({cols: stdout?.columns ?? 80, rows: stdout?.rows ?? 30});
  const ws = useRef<WebSocket | null>(null);
  const streamRef = useRef<string | null>(null);
  const thinkingRef = useRef<string | null>(null);
  const busyRef = useRef(false);
  const modoRef = useRef<Modo>('normal');

  const accent = MODO_COR[modo];
  const push = (e: Entry) => setItems(l => [...l, {kind: 'msg', ...e}]);
  const sys = (text: string) => push({role: 'sistema', text});

  useEffect(() => {
    if (!busy) return;
    setElapsed(0);
    const t = setInterval(() => setElapsed(s => s + 1), 1000);
    return () => clearInterval(t);
  }, [busy]);

  // resize/zoom (ctrl+scroll) refluem o frame anterior e o Ink apaga as
  // linhas erradas → réplicas. Limpa a viewport e redesenha do zero com as
  // novas dimensões (o histórico segue no scrollback do terminal).
  useEffect(() => {
    if (!stdout) return;
    const onResize = () => {
      process.stdout.write('\x1b[2J\x1b[H');
      setDims({cols: stdout.columns ?? 80, rows: stdout.rows ?? 30});
    };
    stdout.on('resize', onResize);
    return () => { stdout.off('resize', onResize); };
  }, [stdout]);

  useEffect(() => {
    let unmounted = false;
    let sock: WebSocket;
    const connect = () => {
      sock = new WebSocket(URL);
      ws.current = sock;
      sock.on('open', () => {
        sock.send(JSON.stringify({type: 'start', subject: SUBJECT_ARG ?? null}));
        setConnected(true);
      });
      sock.on('close', () => {
        if (unmounted) return;
        streamRef.current = null;
        setStreaming(null);
        thinkingRef.current = null;
        setThinking(null);
        busyRef.current = false;
        setBusy(false);
        setConnected(false);
        setTimeout(() => { if (!unmounted) connect(); }, 2000);
      });
      sock.on('error', () => {});
      sock.on('message', (raw: Buffer) => {
        const msg = JSON.parse(raw.toString()) as Server;
        if (msg.type === 'token') {
          streamRef.current = (streamRef.current ?? '') + msg.text;
          setStreaming(streamRef.current);
        } else if (msg.type === 'thinking') {
          thinkingRef.current = (thinkingRef.current ?? '') + msg.text;
          setThinking(thinkingRef.current);
        } else if (msg.type === 'turn_start') {
          // turnos disparados pelo servidor (correção de prova, avaliação de
          // aula) também travam o input — digitar no meio não mata mais nada
          busyRef.current = true;
          setBusy(true);
        } else if (msg.type === 'done') {
          const text = streamRef.current ?? '';
          streamRef.current = null;
          setStreaming(null);
          thinkingRef.current = null;
          setThinking(null);
          busyRef.current = false;
          setBusy(false);
          if (text) push({role: 'professor', text, interrupted: msg.interrupted, modo: modoRef.current});
        } else if (msg.type === 'modo') {
          if (msg.modo !== modoRef.current) {
            modoRef.current = msg.modo;
            setModo(msg.modo);
            sys(MODO_AVISO[msg.modo]);
          }
        } else if (msg.type === 'info') {
          const mt = /matéria:\s*([^·]+)/.exec(msg.text)?.[1]?.trim();
          const st = /estágio:\s*([\w-]+)/.exec(msg.text)?.[1];
          const v = /vault:\s*(.+)$/.exec(msg.text)?.[1]?.trim();
          if (mt) setMateria(mt);
          if (st) setStage(st);
          if (v) setVaultDir(v);
          if (!st && !v) sys(msg.text);
        } else if (msg.type === 'error') {
          busyRef.current = false;
          setBusy(false);
          thinkingRef.current = null;
          setThinking(null);
          sys(`erro: ${msg.text}`);
        }
      });
    };
    connect();
    return () => { unmounted = true; sock?.close(); };
  }, []);

  useInput((_input, key) => {
    if (key.escape && busyRef.current) {
      ws.current?.send(JSON.stringify({type: 'interrupt'}));
    }
  });

  const slash = (cmd: string): boolean => {
    if (!cmd.startsWith('/')) return false;
    const [nome, ...resto] = cmd.split(/\s+/);
    switch (nome) {
      case '/help': sys(HELP); break;
      case '/clear': setItems([{kind: 'banner'}]); break;
      case '/stage': sys(`matéria: ${materia ?? '(nenhuma)'} · estágio: ${stage ?? '?'}`); break;
      case '/vault': sys(vaultDir ?? 'nenhuma matéria aberta ainda'); break;
      case '/materia': {
        const alvo = resto.join(' ').trim();
        if (!alvo) { sys('uso: /materia <nome>'); break; }
        ws.current?.send(JSON.stringify({type: 'start', subject: alvo}));
        break;
      }
      default: sys(`comando desconhecido: ${nome} (/help)`);
    }
    return true;
  };

  const submit = (text: string) => {
    const t = text.trim();
    if (!t) return;
    setInput('');
    if (slash(t)) return;
    if (busyRef.current || !connected) return;
    ws.current?.send(JSON.stringify({type: 'user_msg', text: t}));
    push({role: 'aprendiz', text: t});
    busyRef.current = true;
    setBusy(true);
  };

  return (
    <Box flexDirection="column" paddingX={1}>
      <Static items={items}>
        {(item, i) =>
          item.kind === 'banner'
            ? <Banner key="banner" />
            : <Message key={i} e={item} />}
      </Static>

      {busy && thinking !== null && streaming === null && (
        <Box marginTop={1}>
          <Text color={DIM}>{'🧠 '}</Text>
          <Box flexGrow={1}>
            <Text color={DIM} wrap="wrap">
              {tailByRows(thinking, dims.cols, 4)}
            </Text>
          </Box>
        </Box>
      )}

      {streaming !== null && (
        <Box marginTop={1}>
          <Text color={accent}>{'⏺ '}</Text>
          <Box flexGrow={1}>
            <Text>
              {mdAnsi(tailByRows(streaming, dims.cols, Math.max(5, dims.rows - 14)))}
              <Text color={accent}>▌</Text>
            </Text>
          </Box>
        </Box>
      )}

      {busy && (
        <Box marginTop={1}>
          <Text color={accent}><Spinner type="dots" /></Text>
          <Text color={DIM}>
            {' '}{streaming !== null ? 'ensinando' : thinking !== null ? 'pensando' : 'professor pensando'}… ({elapsed}s · esc para interromper)
          </Text>
        </Box>
      )}

      <Box borderStyle="round" borderColor={busy ? DIM : accent} paddingX={1} marginTop={1}>
        <Text color={busy ? DIM : accent}>{'❯ '}</Text>
        <TextInput
          value={input}
          onChange={setInput}
          onSubmit={submit}
          placeholder={
            busy ? 'aguarde o professor…'
            : modo === 'calibragem' ? 'calibrando… responda livre (ou "pode gerar")'
            : modo === 'consulta' ? 'prova aberta — posso orientar, não dou a resposta'
            : materia ? `estudando ${materia}` : 'o que você quer aprender?'
          }
        />
      </Box>

      <Text color={DIM}>
        {connected ? '●' : '○'} {connected ? 'conectado' : 'reconectando…'}
        {materia ? ` · ${materia}` : ''}
        {stage ? ` · ${stage}` : ''}
        {modo === 'calibragem' ? ' · 🎯 calibragem' : modo === 'consulta' ? ' · 📝 consulta' : ''}
        {busy ? ' · esc interrompe' : ''}
      </Text>
    </Box>
  );
}

render(<App />);
