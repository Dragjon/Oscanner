// Oscanner's translation worker. Runs Mozilla's bergamot-translator (MPL-2.0: the wasm build and
// its glue, bergamot-translator.js, as Firefox ships them) on models Oscanner downloaded, which
// come zstd-compressed and are unpacked here with fzstd (MIT). Set up as Firefox's own
// translations-engine.worker.js sets it up.
'use strict';

importScripts('fzstd.js', 'bergamot-translator.js');

const ALIGNMENT = {model: 256, lex: 64, vocab: 64, srcvocab: 64, trgvocab: 64};

let bergamot = null;
let service = null;
const models = new Map(); // "from-to" -> TranslationModel

async function unpacked(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`Missing ${url}: ${response.status}`);
    const bytes = new Uint8Array(await response.arrayBuffer());
    return url.endsWith('.zst') ? fzstd.decompress(bytes) : bytes;
}

async function start() {
    const wasmBinary = await unpacked('bergamot-translator.wasm.zst');
    bergamot = await new Promise((resolve, reject) => {
        const module = loadBergamot({
            // As Firefox: start small and let the memory grow.
            INITIAL_MEMORY: 41_943_040,
            print: () => {},
            printErr: message => console.warn(message),
            onAbort: () => reject(new Error('The translation engine failed to start')),
            onRuntimeInitialized: async () => {
                await Promise.resolve();
                resolve(module);
            },
            wasmBinary,
        });
    });
    service = new bergamot.BlockingService({cacheSize: 0});
}

// Marian's config wants this exact layout.
function configText(config) {
    const indent = '            ';
    return '\n' + Object.entries(config).map(([key, value]) => `${indent}${key}: ${value}\n`).join('') + indent;
}

// files: {model, lex, vocab | srcvocab + trgvocab}: URLs of the (compressed) files.
async function load({from, to, files}) {
    const key = `${from}-${to}`;
    if (models.has(key)) return;
    const memory = {};
    for (const [type, url] of Object.entries(files)) {
        const bytes = await unpacked(url);
        const aligned = new bergamot.AlignedMemory(bytes.byteLength, ALIGNMENT[type]);
        aligned.getByteArrayView().set(bytes);
        memory[type] = aligned;
    }
    const vocabs = new bergamot.AlignedMemoryList();
    if (memory.vocab) {
        vocabs.push_back(memory.vocab);
    } else {
        vocabs.push_back(memory.srcvocab);
        vocabs.push_back(memory.trgvocab);
    }
    const config = configText({
        'beam-size': '1',
        normalize: '1.0',
        'word-penalty': '0',
        'max-length-break': '128',
        'mini-batch-words': '1024',
        workspace: '128',
        'max-length-factor': '2.0',
        'skip-cost': 'true',
        'cpu-threads': '0',
        quiet: 'true',
        'quiet-translation': 'true',
        'gemm-precision': files.model.replace(/\.zst$/, '').endsWith('intgemm8.bin') ? 'int8shiftAll' : 'int8shiftAlphaAll',
        alignment: 'soft',
    });
    models.set(key, new bergamot.TranslationModel(from, to, config, memory.model, memory.lex ?? null, vocabs, null));
}

function forget({from, to}) {
    const key = `${from}-${to}`;
    models.get(key)?.delete();
    models.delete(key);
}

// route: one or two {from, to, files}; two translate through English. texts: plain strings.
async function translate({route, texts}) {
    for (const step of route) await load(step);
    const messages = new bergamot.VectorString();
    const options = new bergamot.VectorResponseOptions();
    // Empty paragraphs break the translation: they're passed through as they are.
    const asked = texts.map((text, index) => ({text, index})).filter(({text}) => text.trim());
    asked.forEach(({text}) => {
        messages.push_back(text);
        options.push_back({qualityScores: false, alignment: false, html: false});
    });
    const out = texts.slice();
    if (asked.length) {
        const [first, second] = route.map(({from, to}) => models.get(`${from}-${to}`));
        const responses = second
            ? service.translateViaPivoting(first, second, messages, options)
            : service.translate(first, messages, options);
        asked.forEach(({index}, i) => { out[index] = responses.get(i).getTranslatedText(); });
        responses.delete();
    }
    messages.delete();
    options.delete();
    return out;
}

const started = start();
const actions = {load, forget, translate};

self.addEventListener('message', async ({data: {id, name, args}}) => {
    try {
        await started;
        const result = name === 'ready' ? true : await actions[name](args);
        self.postMessage({id, result});
    } catch (error) {
        self.postMessage({id, error: String(error && (error.message || error))});
    }
});
