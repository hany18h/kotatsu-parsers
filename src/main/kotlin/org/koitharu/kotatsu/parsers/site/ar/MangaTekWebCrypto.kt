package org.koitharu.kotatsu.parsers.site.ar

import org.json.JSONObject

/** Run only the current public reader's local crypto; never its network/session functions. */
internal object MangaTekWebCrypto {
    fun buildScript(moduleSource: String, input: JSONObject, requestId: String): String {
        require(moduleSource.length in 1..300_000) { "Unexpected MangaTek client size" }
        var source = moduleSource.removePrefix("\uFEFF").trimStart()
        while (source.startsWith("import{") || source.startsWith("import\"") ||
            source.startsWith("import'") || source.startsWith("import ")) {
            val end = source.indexOf(';')
            require(end in 1..10_000) { "Unexpected MangaTek module import" }
            source = source.substring(end + 1).trimStart()
        }
        source = source.replace(Regex("export\\s*\\{[^}]*\\}\\s*;?\\s*$"), "")
        val functions = Regex("async function ([A-Za-z_$][A-Za-z0-9_$]*)\\(").findAll(source)
            .map { it.groupValues[1] }.distinct().toList()
        require(functions.isNotEmpty()) { "MangaTek client crypto was not found" }
        return """
            (function() {
              const stateKey = ${JSONObject.quote("__mangaTekCrypto_$requestId")};
              let state = window[stateKey];
              if (!state) {
                state = window[stateKey] = {done:false};
                (async function() {
                  if (!window.crypto || !crypto.subtle || typeof WebAssembly === 'undefined')
                    throw new Error('Web crypto unavailable');
                  const input = ${input};
                  // The fetched module is the website's own public ChapterImageViewer.
                  // Imported React/fingerprint functions are not called. Timers and
                  // fetch are shadowed so this copy cannot send requests or start UI.
                  const factory = function(window,setInterval,setTimeout,clearTimeout,fetch) {
                    $source
                    return [${functions.joinToString(",")}];
                  };
                  const noop = function(){return 0;};
                  const noNetwork = function(){throw new Error('Network disabled in crypto bridge');};
                  const functions = factory({setInterval:noop,setTimeout:noop,clearTimeout:noop},noop,noop,noop,noNetwork);
                  if (input.mode === 'proof') {
                    // Identify a pure two-argument proof helper by its result, not
                    // a rotating minified name. Current clients can compute the
                    // proof directly with WebCrypto. Exclude loops, decoders and
                    // network helpers; only a 64-hex result qualifies as a proof.
                    for (const fn of functions) {
                      const body = Function.prototype.toString.call(fn);
                      if (fn.length !== 2 || /\b(?:for|while)\s*\(|TextDecoder|\bfetch\b/.test(body)) continue;
                      const sample = await fn('mangapeak-probe',1);
                      if (typeof sample === 'string' && /^[a-f0-9]{64}$/i.test(sample)) {
                        const proof = await fn(input.token,input.chapterId);
                        if (typeof proof === 'string' && /^[a-f0-9]{64}$/i.test(proof)) {
                          state.result = {proof:proof}; return;
                        }
                      }
                    }
                    throw new Error('Proof helper unavailable');
                  }
                  const decrypt = functions.find(function(fn) {
                    const body = Function.prototype.toString.call(fn);
                    return fn.length === 4 && /\bcrypto\b/.test(body) && /TextDecoder/.test(body);
                  });
                  if (!decrypt) throw new Error('Decrypt helper unavailable');
                  const pages = await decrypt(input.overlay,input.ks,input.token,input.chapterId);
                  if (!Array.isArray(pages) || pages.length === 0) throw new Error('Empty decrypted pages');
                  state.result = {pages:pages};
                })().catch(function(){state.result={error:'MangaTek current client crypto failed'};})
                  .finally(function(){state.done=true;});
              }
              return state.done ? JSON.stringify(state.result) : null;
            })()
        """.trimIndent()
    }
}
