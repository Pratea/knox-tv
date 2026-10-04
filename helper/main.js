// KNOX TV - motor de navegador (processo auxiliar).
//
// O mod Java inicia este processo sem janela visivel. A comunicacao e:
//   comandos/eventos : JSON, um por linha, num socket local (--port <n>);
//                      o Java abre o servidor em 127.0.0.1 e o motor conecta.
//                      (stdin nao serve: no Windows o processo principal do
//                      Electron nao le stdin de forma confiavel)
//   quadros          : arquivo de memoria compartilhada (--frames <caminho>)
//
// Layout do arquivo de quadros (inteiros little-endian):
//   [0]  magic  'ZTV1'
//   [4]  seq    contador; IMPAR = quadro sendo escrito, PAR = quadro pronto
//   [8]  width
//   [12] height
//   [64] pixels BGRA, width*height*4 bytes
// O Java le o seq antes e depois da copia (seqlock) e descarta o quadro se
// mudou no meio -- assim nunca desenha um quadro pela metade.

const { app, BrowserWindow } = require('electron');
const fs = require('fs');
const net = require('net');
const path = require('path');
const readline = require('readline');

const HEADER_SIZE = 64;
const MAX_WIDTH = 1920;
const MAX_HEIGHT = 1080;
const FILE_SIZE = HEADER_SIZE + MAX_WIDTH * MAX_HEIGHT * 4;

function arg(name, fallback) {
  const i = process.argv.indexOf(name);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}

const framesPath = path.resolve(arg('--frames', 'frames.bin'));
const width = Math.min(parseInt(arg('--width', '1280'), 10), MAX_WIDTH);
const height = Math.min(parseInt(arg('--height', '720'), 10), MAX_HEIGHT);
const fps = parseInt(arg('--fps', '30'), 10);
const port = parseInt(arg('--port', '0'), 10);

// Cookies, logins (YouTube/Crunchyroll) e cache ficam em Zomboid/KNOXTV,
// junto do resto do mod, em vez de %APPDATA% -- e sobrevivem entre sessoes.
const dataDir = arg('--data-dir', '');
if (dataDir) app.setPath('userData', path.resolve(dataDir));

let socket = null;

function emit(obj) {
  const line = JSON.stringify(obj) + '\n';
  if (socket && !socket.destroyed) socket.write(line);
  process.stdout.write(line);
}

// --- Arquivo de quadros -----------------------------------------------------

// Tamanho fixo desde o inicio: no Windows nao da pra redimensionar um arquivo
// que o Java ja mapeou na memoria.
const fd = fs.openSync(framesPath, fs.existsSync(framesPath) ? 'r+' : 'w+');
if (fs.fstatSync(fd).size !== FILE_SIZE) fs.ftruncateSync(fd, FILE_SIZE);

let seq = 0;
const header = Buffer.alloc(16);
header.write('ZTV1', 0, 'ascii');
fs.writeSync(fd, header, 0, 16, 0);

function writeInt(value, offset) {
  const b = Buffer.alloc(4);
  b.writeInt32LE(value, 0);
  fs.writeSync(fd, b, 0, 4, offset);
}

function writeFrame(image) {
  const size = image.getSize();
  if (size.width > MAX_WIDTH || size.height > MAX_HEIGHT) return;
  const bitmap = image.toBitmap(); // BGRA no Windows
  writeInt(++seq, 4); // impar: escrevendo
  writeInt(size.width, 8);
  writeInt(size.height, 12);
  fs.writeSync(fd, bitmap, 0, bitmap.length, HEADER_SIZE);
  writeInt(++seq, 4); // par: pronto
}

// --- Navegador fora da tela -------------------------------------------------

// Videos precisam tocar sem o usuario clicar na pagina primeiro.
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
// Sem isso o Chromium reduz a taxa de quadros por achar que a janela esta escondida.
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');

let win = null;

function chromeUserAgent(ua) {
  // Alguns sites (YouTube incluso) tratam diferente o UA do Electron.
  return ua.replace(/\s?Electron\/\S+/, '').replace(/\s?zomboid-tv-helper\/\S+/, '');
}

function createBrowser() {
  win = new BrowserWindow({
    show: false,
    width,
    height,
    useContentSize: true,
    // Tela cheia de pagina (botao do YouTube) preenche so a area do
    // navegador, sem tentar mudar o tamanho da "janela" -- o tamanho dos
    // quadros precisa ficar fixo pra caber na textura do jogo.
    fullscreenable: false,
    webPreferences: {
      offscreen: true,
      backgroundThrottling: false,
      sandbox: true,
      contextIsolation: true,
    },
  });

  const wc = win.webContents;
  wc.setUserAgent(chromeUserAgent(wc.getUserAgent()));
  wc.setFrameRate(fps);
  wc.on('paint', (_event, _dirty, image) => writeFrame(image));

  wc.on('did-navigate', (_e, url) => emit({ event: 'navigated', url }));
  wc.on('did-navigate-in-page', (_e, url) => emit({ event: 'navigated', url }));
  wc.on('page-title-updated', (_e, title) => emit({ event: 'title', title }));
  wc.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
    // -3 = carregamento cancelado (normal ao trocar de pagina no meio)
    if (code !== -3 && isMainFrame) emit({ event: 'error', code, desc, url });
  });
  wc.on('render-process-gone', (_e, details) => emit({ event: 'crashed', reason: details.reason }));
  wc.on('enter-html-full-screen', () => emit({ event: 'fullscreen', value: 'on' }));
  wc.on('leave-html-full-screen', () => emit({ event: 'fullscreen', value: 'off' }));

  // Popups (target=_blank) abrem na mesma aba em vez de criar janela nova.
  wc.setWindowOpenHandler(({ url }) => {
    wc.loadURL(url);
    return { action: 'deny' };
  });

  setInterval(probeMedia, 500);
  emit({ event: 'ready', frames: framesPath, width, height });
}

// --- Comandos vindos do Java ------------------------------------------------

const BUTTONS = { 0: 'left', 1: 'right', 2: 'middle' };
const DOUBLE_CLICK_MS = 400;
const DOUBLE_CLICK_DIST = 5;

// Estado do mouse: o jogo so manda "apertou/soltou/moveu", e o Chromium
// precisa saber quais botoes estao segurados (pra arrastar a barra do video)
// e quantos cliques seguidos foram (duplo clique = tela cheia no YouTube).
const pressed = new Set();
let lastDown = { time: 0, x: 0, y: 0, button: '', count: 0 };

function heldModifiers() {
  return [...pressed].map((b) => b + 'ButtonDown');
}

function clickCountFor(x, y, button) {
  const now = Date.now();
  const near = Math.abs(x - lastDown.x) <= DOUBLE_CLICK_DIST && Math.abs(y - lastDown.y) <= DOUBLE_CLICK_DIST;
  const count = near && button === lastDown.button && now - lastDown.time <= DOUBLE_CLICK_MS ? lastDown.count + 1 : 1;
  lastDown = { time: now, x, y, button, count };
  return count;
}

function handleMouse(wc, cmd) {
  const x = Math.round(cmd.x), y = Math.round(cmd.y);
  const button = BUTTONS[cmd.button] || 'left';
  switch (cmd.type) {
    case 'down': {
      const clickCount = clickCountFor(x, y, button);
      pressed.add(button);
      wc.sendInputEvent({ type: 'mouseDown', x, y, button, clickCount, modifiers: heldModifiers() });
      break;
    }
    case 'up':
      pressed.delete(button);
      wc.sendInputEvent({ type: 'mouseUp', x, y, button, clickCount: lastDown.count || 1 });
      break;
    case 'move':
      wc.sendInputEvent({ type: 'mouseMove', x, y, modifiers: heldModifiers() });
      break;
    case 'wheel':
      wc.sendInputEvent({ type: 'mouseWheel', x, y, deltaX: 0, deltaY: -cmd.delta * 100 });
      break;
  }
}

/**
 * Teclado. Teclas especiais chegam pelo nome (Enter, Backspace, Left...);
 * texto chega como `char`. O Chromium precisa da sequencia completa
 * keyDown -> char -> keyUp: o keyDown dispara atalhos da pagina (espaco/k
 * pausam o YouTube) e o char e o que de fato escreve num campo de texto.
 */
function handleKey(wc, cmd) {
  const modifiers = cmd.modifiers || [];
  const keyCode = cmd.key || cmd.char;
  if (!keyCode) return;
  try {
    wc.sendInputEvent({ type: 'keyDown', keyCode, modifiers });
  } catch (_) {
    // caractere sem tecla correspondente (ex: acentos): so o char basta
  }
  if (cmd.char) wc.sendInputEvent({ type: 'char', keyCode: cmd.char, modifiers });
  try {
    wc.sendInputEvent({ type: 'keyUp', keyCode, modifiers });
  } catch (_) { /* idem */ }
}

const EDIT_ACTIONS = new Set(['copy', 'cut', 'paste', 'selectAll']);

// --- Video da pagina (sincronizacao) ----------------------------------------

// O "video principal" da pagina e o maior <video> (no YouTube, o player).
const PICK_VIDEO = `(() => {
  const vids = [...document.querySelectorAll('video')];
  vids.sort((a, b) => (b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight));
  return vids[0] || null;
})()`;

let volume = 1;

/**
 * Volume do jogo (distancia da TV) como MULTIPLICADOR por cima do volume da
 * propria pagina. O Electron nao tem volume por pagina (so mudo), entao a
 * propriedade `volume` dos <video>/<audio> e trocada: a pagina continua
 * lendo/escrevendo o volume "dela" (a barra do YouTube funciona normal), e o
 * que de fato toca e volume_da_pagina x ganho_do_jogo.
 * Idempotente: roda a cada sondagem, mas so instala uma vez por pagina.
 */
const GAIN_PATCH = `(() => {
  if (window.__ztvApplyGain) return;
  const desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
  const pageVolume = new WeakMap();
  let gain = 1;
  const clamp = (v) => Math.max(0, Math.min(1, v));
  window.__ztvRealVolume = (m) => desc.get.call(m);
  Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
    configurable: true,
    enumerable: desc.enumerable,
    get() { return pageVolume.has(this) ? pageVolume.get(this) : desc.get.call(this); },
    set(v) { pageVolume.set(this, v); desc.set.call(this, clamp(v * gain)); },
  });
  // Confere todo <video>/<audio> (inclusive os que apareceram depois, como
  // ao trocar de video) e corrige so os que estao fora do volume certo.
  window.__ztvApplyGain = (g) => {
    gain = g;
    for (const m of document.querySelectorAll('video,audio')) {
      const base = pageVolume.has(m) ? pageVolume.get(m) : desc.get.call(m);
      pageVolume.set(m, base);
      const target = clamp(base * gain);
      if (Math.abs(desc.get.call(m) - target) > 0.005) desc.set.call(m, target);
    }
  };
})()`;

/** Le o estado do video a cada meio segundo e manda pro Java; aplica o ganho do jogo. */
async function probeMedia() {
  if (!win || win.isDestroyed()) return;
  const script = `(() => {
    ${GAIN_PATCH};
    window.__ztvApplyGain(${volume});
    const v = ${PICK_VIDEO};
    if (!v) return null;
    // Retangulo do video na pagina (cortado pela tela): a TV do mundo mostra
    // so o video, nao o resto da interface do site.
    const b = v.getBoundingClientRect();
    const rx = Math.max(0, b.left), ry = Math.max(0, b.top);
    const rw = Math.min(innerWidth, b.right) - rx, rh = Math.min(innerHeight, b.bottom) - ry;
    return {
      rx: Math.round(rx), ry: Math.round(ry), rw: Math.round(rw), rh: Math.round(rh),
      t: v.currentTime,
      d: isFinite(v.duration) ? v.duration : 0,
      p: v.paused,
      e: v.ended,
      ad: !!document.querySelector('.ad-showing'),
      pv: v.volume,                    // volume que a pagina acha que tem
      rv: window.__ztvRealVolume(v),   // volume que de fato toca (pagina x jogo)
    };
  })()`;
  try {
    const r = await win.webContents.executeJavaScript(script, false);
    emit(r ? { event: 'media', ...r } : { event: 'media', none: true });
  } catch (_) {
    // pagina trocando no meio: ignora, tenta de novo no proximo ciclo
  }
}

function controlMedia(wc, cmd) {
  let body;
  if (cmd.action === 'play') body = 'v.play().catch(() => {});';
  else if (cmd.action === 'pause') body = 'v.pause();';
  else if (cmd.action === 'seek') body = `v.currentTime = ${Number(cmd.time) || 0};`;
  else return;
  wc.executeJavaScript(`(() => { const v = ${PICK_VIDEO}; if (v) { ${body} } })()`, false).catch(() => {});
}

function handle(cmd) {
  if (!win) return;
  const wc = win.webContents;
  switch (cmd.cmd) {
    case 'load': {
      let url = String(cmd.url || '').trim();
      // Sem esquema = site digitado ("youtube.com"); about:blank fica como esta.
      if (url && !/^(about:|[a-z]+:\/\/)/i.test(url)) url = 'https://' + url;
      if (url) wc.loadURL(url).catch(() => {});
      break;
    }
    case 'mouse': handleMouse(wc, cmd); break;
    case 'key': handleKey(wc, cmd); break;
    case 'edit': if (EDIT_ACTIONS.has(cmd.action)) wc[cmd.action](); break;
    case 'focus': if (cmd.value) wc.focus(); break;
    case 'back': if (wc.navigationHistory.canGoBack()) wc.navigationHistory.goBack(); break;
    case 'forward': if (wc.navigationHistory.canGoForward()) wc.navigationHistory.goForward(); break;
    case 'reload': wc.reload(); break;
    case 'mute': wc.setAudioMuted(!!cmd.value); break;
    case 'media': controlMedia(wc, cmd); break;
    case 'volume': volume = Math.max(0, Math.min(1, Number(cmd.value) || 0)); break;
    case 'quit': app.quit(); break;
  }
}

function listen(input) {
  const rl = readline.createInterface({ input });
  rl.on('line', (line) => {
    try { handle(JSON.parse(line)); } catch (e) { emit({ event: 'bad-command', line }); }
  });
  // Se o jogo fechar (ou travar), a conexao cai -- o motor encerra junto
  // em vez de ficar orfao tocando audio em segundo plano.
  rl.on('close', () => app.quit());
}

app.whenReady().then(() => {
  if (!port) {
    // Sem --port: modo de desenvolvimento, comandos pelo stdin.
    createBrowser();
    listen(process.stdin);
    return;
  }
  socket = net.connect(port, '127.0.0.1', () => {
    createBrowser();
    listen(socket);
  });
  socket.setNoDelay(true);
  socket.on('error', () => app.quit());
});

app.on('window-all-closed', () => app.quit());
app.on('quit', () => { try { fs.closeSync(fd); } catch (_) {} });
