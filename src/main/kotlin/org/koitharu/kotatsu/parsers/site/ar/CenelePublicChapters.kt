package org.koitharu.kotatsu.parsers.site.ar

/** Only operates the public chapter-list controls; the website owns its transport. */
internal object CenelePublicChapters {
	val SCRIPT: String = """
		(function() {
		  var key = '__kotatsuPublicChapters';
		  var state = window[key];
		  if (state) return state.result ? JSON.stringify(state.result) : null;
		  if (document.readyState !== 'complete') return null;
		  if (!document.querySelector('[data-nhv-section="chapters"]')) return null;
		  state = window[key] = {started: Date.now(), opened: false, clicks: 0};
		  function finish(result) { clearInterval(state.timer); state.result = result; }
		  function tick() {
		    if (Date.now() - state.started > 170000 || state.clicks > 500) {
		      finish({complete: false, error: 'Chapter list did not finish'}); return;
		    }
		    var tab = document.querySelector('[data-nhv-section="chapters"]');
		    if (!state.opened) {
		      state.opened = true;
		      if (tab.getAttribute('aria-selected') !== 'true') tab.click();
		      return;
		    }
		    var panel = document.querySelector('#nhv-novel-panel');
		    if (!panel || panel.getAttribute('aria-busy') === 'true') return;
		    if (panel.querySelector('.nhv-novel-error')) {
		      finish({complete: false, error: 'Website could not load chapters'}); return;
		    }
		    var list = panel.querySelector('[data-nhv-volume-list]');
		    if (!list || list.querySelector('.nhv-novel-loading, .is-loading')) return;
		    var groups = list.querySelectorAll('[data-nhv-volume]');
		    if (!groups.length) return;
		    for (var i = 0; i < groups.length; i++) {
		      var group = groups[i];
		      var toggle = group.querySelector('[data-nhv-volume-toggle]');
		      var more = group.querySelector('[data-nhv-volume-more]');
		      var count = group.querySelector('.nhv-novel-volume__count');
		      var expected = count ? parseInt(count.textContent.replace(/[٠-٩]/g, function(c) {
		        return String('٠١٢٣٤٥٦٧٨٩'.indexOf(c));
		      }).replace(/[٬,\s]/g, ''), 10) : NaN;
		      var actual = group.querySelectorAll('li.wp-manga-chapter').length;
		      if (!isNaN(expected) && actual >= expected) continue;
		      if (toggle && toggle.getAttribute('aria-expanded') !== 'true') {
		        toggle.click(); state.clicks++; return;
		      }
		      if (more && !more.hidden && !more.disabled) {
		        // Stop on a failed page instead of repeatedly hammering it or
		        // silently publishing an incomplete chapter list.
		        if (state.lastGroup === i && state.lastCount === actual) {
		          finish({complete: false, error: 'Chapter pagination stalled'}); return;
		        }
		        state.lastGroup = i; state.lastCount = actual;
		        more.click(); state.clicks++; return;
		      }
		      if (!isNaN(expected) && actual < expected) return;
		    }
		    finish({complete: true, html: list.outerHTML});
		  }
		  state.timer = setInterval(tick, 150);
		  tick();
		  return null;
		})()
	""".trimIndent()
}
