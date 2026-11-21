// LinguaLive — serveur de sessions temps réel + résumé IA + transcription de fichiers.
// Le serveur ne traduit pas : il relaie le texte d'origine, et chaque téléphone traduit
// lui-même vers la langue de son utilisateur (ML Kit, sur l'appareil).

import http from 'node:http';
import express from 'express';
import multer from 'multer';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT || 8080);
const app = express();
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 25 * 1024 * 1024 } });

/** code -> { code, lang, presenter, attendees:Set, history:[], ended } */
const sessions = new Map();

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
function newCode() {
  let c;
  do {
    c = Array.from({ length: 6 }, () => ALPHABET[Math.floor(Math.random() * ALPHABET.length)]).join('');
  } while (sessions.has(c));
  return c;
}
let nextClientId = 1;
const send = (ws, obj) => ws && ws.readyState === 1 && ws.send(JSON.stringify(obj));
function broadcast(s, obj, except = null) {
  const data = JSON.stringify(obj);
  for (const a of s.attendees) if (a !== except && a.readyState === 1) a.send(data);
}
// Mode réunion : tout le monde peut parler, chacun voit qui est là.
function presence(s) {
  broadcast(s, { type: 'presence', count: s.attendees.size, names: [...s.attendees].map((w) => w.name) });
}
function joinMeeting(ws, s, m) {
  ws.role = 'member';
  ws.session = s;
  ws.name = String(m.name || 'Invité').slice(0, 40);
  s.attendees.add(ws);
  send(ws, { type: 'joined', mode: 'meeting', code: s.code, you: ws.id, ended: s.ended, history: s.history });
  presence(s);
}
const stats = (s) => send(s.presenter, { type: 'stats', listeners: s.attendees.size });

function endSession(s) {
  if (!s || s.ended) return;
  s.ended = true;
  broadcast(s, { type: 'ended' });
  // On garde l'historique 1 h pour que les retardataires récupèrent la transcription.
  setTimeout(() => sessions.delete(s.code), 60 * 60 * 1000).unref();
}

app.get('/health', (_req, res) => res.json({ ok: true, sessions: sessions.size }));

// ---------- Résumé IA (Claude) ----------
app.post('/api/summarize', express.json({ limit: '2mb' }), async (req, res) => {
  const { text, lang = 'fr' } = req.body || {};
  if (!text || !String(text).trim()) return res.status(400).json({ error: 'Texte manquant' });
  if (!process.env.ANTHROPIC_API_KEY) return res.status(501).json({ error: 'ANTHROPIC_API_KEY non configurée' });
  try {
    const r = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-api-key': process.env.ANTHROPIC_API_KEY,
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({
        model: process.env.ANTHROPIC_MODEL || 'claude-sonnet-5-5',
        max_tokens: 1500,
        messages: [{
          role: 'user',
          content:
            `Here is the transcript of a meeting or event (speech-to-text, may lack punctuation).\n` +
            `Write the summary in the language whose ISO code is "${lang}".\n` +
            `Format: a 2–3 sentence overview, then "Key points" as bullets, then "Decisions & action items" as bullets (omit if none). Plain text, no markdown headers.\n\n` +
            `<transcript>\n${String(text).slice(0, 400000)}\n</transcript>`,
        }],
      }),
    });
    const data = await r.json();
    if (!r.ok) throw new Error(data?.error?.message || r.statusText);
    res.json({ summary: data.content.filter((b) => b.type === 'text').map((b) => b.text).join('\n').trim() });
  } catch (e) {
    res.status(502).json({ error: String(e.message || e) });
  }
});

// ---------- Transcription de fichiers (vidéo à la demande) ----------
// Compatible avec toute API "OpenAI audio/transcriptions" (OpenAI Whisper, Groq, serveur Whisper local…)
app.post('/api/transcribe', upload.single('file'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'Fichier manquant' });
  if (!process.env.STT_API_KEY) return res.status(501).json({ error: 'STT_API_KEY non configurée' });
  try {
    const form = new FormData();
    form.append('file', new Blob([req.file.buffer], { type: req.file.mimetype || 'application/octet-stream' }),
      req.file.originalname || 'media.mp4');
    form.append('model', process.env.STT_MODEL || 'whisper-1');
    form.append('response_format', 'verbose_json');
    if (req.body?.lang) form.append('language', req.body.lang);

    const base = (process.env.STT_API_URL || 'https://api.openai.com/v1').replace(/\/$/, '');
    const r = await fetch(`${base}/audio/transcriptions`, {
      method: 'POST',
      headers: { authorization: `Bearer ${process.env.STT_API_KEY}` },
      body: form,
    });
    const data = await r.json();
    if (!r.ok) throw new Error(data?.error?.message || r.statusText);
    const raw = data.segments?.length ? data.segments : [{ start: 0, text: data.text || '' }];
    const segments = raw
      .map((s) => ({ start: Number(s.start) || 0, text: String(s.text || '').trim() }))
      .filter((s) => s.text);
    res.json({ segments });
  } catch (e) {
    res.status(502).json({ error: String(e.message || e) });
  }
});

// ---------- Sessions en direct (WebSocket) ----------
const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', (ws) => {
  ws.id = nextClientId++;
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (raw) => {
    let m;
    try { m = JSON.parse(raw); } catch { return; }

    switch (m.type) {
      case 'create': {
        const code = newCode();
        const mode = m.mode === 'meeting' ? 'meeting' : 'presentation';
        const s = { code, mode, lang: m.lang || 'fr', presenter: mode === 'meeting' ? null : ws,
          attendees: new Set(), history: [], ended: false, seq: 0 };
        sessions.set(code, s);
        if (mode === 'meeting') {
          joinMeeting(ws, s, m);
          console.log(`Réunion ${code} créée`);
          break;
        }
        ws.role = 'presenter';
        ws.session = s;
        send(ws, { type: 'created', code });
        console.log(`Session ${code} créée (${s.lang})`);
        break;
      }
      case 'join': {
        const s = sessions.get(String(m.code || '').toUpperCase().trim());
        if (!s) return send(ws, { type: 'error', message: 'Session introuvable' });
        if (s.mode === 'meeting') { joinMeeting(ws, s, m); break; }
        s.attendees.add(ws);
        ws.role = 'attendee';
        ws.session = s;
        send(ws, { type: 'joined', code: s.code, lang: s.lang, ended: s.ended, history: s.history });
        stats(s);
        break;
      }
      case 'segment': {
        const s = ws.session;
        if (s && ws.role === 'member' && !s.ended) {
          const text = String(m.text || '').slice(0, 2000);
          if (!text) return;
          if (m.final) {
            const seg = { id: ++s.seq, from: ws.id, name: ws.name, text, final: true, lang: m.lang || 'fr', t: Date.now() };
            s.history.push(seg);
            if (s.history.length > 5000) s.history.shift();
            broadcast(s, { type: 'segment', ...seg });            // y compris l'auteur (ordre commun)
          } else {
            broadcast(s, { type: 'segment', from: ws.id, name: ws.name, text, final: false, lang: m.lang || 'fr' }, ws);
          }
          return;
        }
        if (!s || ws.role !== 'presenter' || s.ended) return;
        const seg = {
          id: Number(m.id) || 0,
          text: String(m.text || '').slice(0, 2000),
          final: !!m.final,
          lang: m.lang || s.lang,
          t: Date.now(),
        };
        if (!seg.text) return;
        if (seg.final) {
          s.history.push(seg);
          if (s.history.length > 5000) s.history.shift();
        }
        broadcast(s, { type: 'segment', ...seg });
        break;
      }
      case 'end':
        if (ws.role === 'presenter') endSession(ws.session);
        break;
    }
  });

  ws.on('close', () => {
    const s = ws.session;
    if (!s) return;
    if (ws.role === 'member') {
      s.attendees.delete(ws);
      broadcast(s, { type: 'left', from: ws.id, name: ws.name });
      presence(s);
      if (s.attendees.size === 0) endSession(s);
    } else if (ws.role === 'attendee') {
      s.attendees.delete(ws);
      stats(s);
    } else if (ws.role === 'presenter' && s.presenter === ws) {
      endSession(s);
    }
  });
});

setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000).unref();

server.listen(PORT, () => console.log(`LinguaLive server sur http://0.0.0.0:${PORT} (WebSocket: /ws)`));
