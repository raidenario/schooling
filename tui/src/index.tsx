import React, {useEffect, useRef, useState} from 'react';
import {render, Box, Static, Text, useInput} from 'ink';
import TextInput from 'ink-text-input';
import Spinner from 'ink-spinner';
import WebSocket from 'ws';

const URL = process.env.SCHOOL_WS ?? 'ws://localhost:7777';
const SUBJECT_ARG = process.argv[2]; // opcional: pnpm run dev -- <matéria>

// paleta inspirada no Claude Code
const ACCENT = '#D97757'; // coral
const DIM = 'gray';

type Entry = {role: 'aprendiz' | 'professor' | 'sistema'; text: string; interrupted?: boolean};
type Item = {kind: 'banner'} | ({kind: 'msg'} & Entry);
type Server =
  | {type: 'token'; text: string}
  | {type: 'done'; interrupted?: boolean}
  | {type: 'info'; text: string}
  | {type: 'error'; text: string}
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
    : e.role === 'professor' ? <Text color={ACCENT}>{'⏺ '}</Text>
    : <Text color={DIM}>{'⎿  '}</Text>;
  return (
    <Box marginTop={1}>
      {marker}
      <Box flexGrow={1}>
        <Text color={e.role === 'sistema' ? DIM : undefined} dimColor={e.role === 'aprendiz'}>
          {e.text}
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
  const ws = useRef<WebSocket | null>(null);
  const streamRef = useRef<string | null>(null);
  const busyRef = useRef(false);

  const push = (e: Entry) => setItems(l => [...l, {kind: 'msg', ...e}]);
  const sys = (text: string) => push({role: 'sistema', text});

  useEffect(() => {
    if (!busy) return;
    setElapsed(0);
    const t = setInterval(() => setElapsed(s => s + 1), 1000);
    return () => clearInterval(t);
  }, [busy]);

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
        } else if (msg.type === 'done') {
          const text = streamRef.current ?? '';
          streamRef.current = null;
          setStreaming(null);
          busyRef.current = false;
          setBusy(false);
          if (text) push({role: 'professor', text, interrupted: msg.interrupted});
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

      {streaming !== null && (
        <Box marginTop={1}>
          <Text color={ACCENT}>{'⏺ '}</Text>
          <Box flexGrow={1}>
            <Text>
              {streaming}
              <Text color={ACCENT}>▌</Text>
            </Text>
          </Box>
        </Box>
      )}

      {busy && (
        <Box marginTop={1}>
          <Text color={ACCENT}><Spinner type="dots" /></Text>
          <Text color={DIM}>
            {' '}{streaming !== null ? 'ensinando' : 'professor pensando'}… ({elapsed}s · esc para interromper)
          </Text>
        </Box>
      )}

      <Box borderStyle="round" borderColor={busy ? DIM : ACCENT} paddingX={1} marginTop={1}>
        <Text color={busy ? DIM : ACCENT}>{'❯ '}</Text>
        <TextInput
          value={input}
          onChange={setInput}
          onSubmit={submit}
          placeholder={busy ? 'aguarde o professor…' : materia ? `estudando ${materia}` : 'o que você quer aprender?'}
        />
      </Box>

      <Text color={DIM}>
        {connected ? '●' : '○'} {connected ? 'conectado' : 'reconectando…'}
        {materia ? ` · ${materia}` : ''}
        {stage ? ` · ${stage}` : ''}
        {busy ? ' · esc interrompe' : ''}
      </Text>
    </Box>
  );
}

render(<App />);
