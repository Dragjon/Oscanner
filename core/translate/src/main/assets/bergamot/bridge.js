// Between Oscanner (the `Oscanner` object, added by Translator.kt) and the translation worker.
'use strict';

const worker = new Worker('worker.js');
const pending = new Map();
let nextId = 1;

worker.addEventListener('message', ({data: {id, result, error}}) => {
    const call = pending.get(id);
    if (!call) return;
    pending.delete(id);
    if (error) call.reject(new Error(error));
    else call.resolve(result);
});
worker.addEventListener('error', event => Oscanner.failed(0, String(event.message || 'Worker failed')));

function call(name, args) {
    return new Promise((resolve, reject) => {
        const id = nextId++;
        pending.set(id, {resolve, reject});
        worker.postMessage({id, name, args});
    });
}

call('ready').then(() => Oscanner.loaded(), error => Oscanner.failed(0, String(error.message || error)));

// request: {route: [{from, to, files}], texts: [string]}
window.oscannerTranslate = (requestId, json) => {
    call('translate', JSON.parse(json)).then(
        texts => Oscanner.done(requestId, JSON.stringify(texts)),
        error => Oscanner.failed(requestId, String(error.message || error)),
    );
};

window.oscannerForget = (from, to) => call('forget', {from, to});
