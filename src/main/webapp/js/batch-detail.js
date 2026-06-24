/* =============================================================
   batch-detail.js
   Loaded via <n:script src="js/batch-detail.js"/>
   ============================================================= */

/* ── Portal fix: move chequePopup to <body> ─────────────────────
   ZK renders #chequePopup inside a z-div with its own stacking
   context — position:fixed inside it can be clipped or z-ordered
   below other ZK layers.  Moving it to document.body escapes
   the stacking context completely.
   Called once from BatchDetailComposer:
     Clients.evalJavaScript("bd_ensurePopupPortal();");
   ──────────────────────────────────────────────────────────── */
function bd_ensurePopupPortal() {
    var pop = document.getElementById('chequePopup');
    if (pop && pop.parentNode !== document.body) {
        document.body.appendChild(pop);
    }
}

/* ── Ctrl+S → trigger Save & Next button ──────────────────── */
document.addEventListener('keydown', function (e) {

    var popup = document.getElementById('chequePopup');

    /* FIX: offsetParent===null catches display:none whether set
       inline or removed entirely by ZK class toggling */
    if (!popup || popup.offsetParent === null) return;

    if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        e.stopPropagation();

        // Strategy 1: find "Save & Next" button by label text inside popup
        var btns = popup.querySelectorAll('.z-button');
        for (var i = 0; i < btns.length; i++) {
            var txt = (btns[i].textContent || '').trim();
            if (txt.indexOf('Save') !== -1 && txt.indexOf('Next') !== -1) {
                btns[i].click();
                return;
            }
        }

        // Strategy 2: direct DOM fallback
        var saveBtn = document.getElementById('btnPopSave');
        if (saveBtn) {
            saveBtn.click();
        }
    }
});

/* ── Image renderer for batch-detail popup ──────────────────────
   IDs: frontImageBox / rearImageBox  (inside #chequePopup)
   Called by BatchDetailComposer:
     Clients.evalJavaScript("bce_renderImages(" + c.getId() + ");");
   ──────────────────────────────────────────────────────────── */
function bce_renderImages(chequeDbId) {
    var ph = '<div class="img-empty">'
           + '<div class="img-empty-icon">&#x1F5BC;</div>'
           + '<div class="img-empty-text">No image available</div>'
           + '</div>';

    if (!chequeDbId) {
        var f = document.getElementById('frontImageBox');
        var r = document.getElementById('rearImageBox');
        if (f) f.innerHTML = ph;
        if (r) r.innerHTML = ph;
        return;
    }

    var imgStyle = 'max-width:100%;max-height:200px;object-fit:contain;'
                 + 'display:block;margin:auto;border-radius:4px;'
                 + 'box-shadow:0 2px 12px rgba(0,0,0,.1);';

    function loadInto(boxId, side) {
        var box = document.getElementById(boxId);
        if (!box) return;
        var img = document.createElement('img');
        img.alt = side;
        img.style.cssText = imgStyle;
        img.onerror = (function (b, s) {
            return function () {
                b.innerHTML = '<div class="img-empty">'
                    + '<div class="img-empty-icon">&#x274C;</div>'
                    + '<div class="img-empty-text">' + s + ' image unavailable</div>'
                    + '</div>';
            };
        })(box, side);
        box.innerHTML = '';
        box.appendChild(img);
        /* Use absolute path from context root — matches web.xml /chequeImage mapping */
        img.src = '/navbharat/chequeImage?id=' + chequeDbId + '&side=' + side;
    }

    loadInto('frontImageBox', 'front');
    loadInto('rearImageBox',  'rear');
}

/* ── Loading spinner for batch-detail popup ─────────────────── */
function bce_imagesLoading() {
    var spin = '<div class="img-scanning">'
        + '<div class="img-scan-frame"><div class="img-scan-corners">'
        + '<span class="isc-tl"></span><span class="isc-tr"></span>'
        + '<span class="isc-bl"></span><span class="isc-br"></span>'
        + '</div><div class="img-scan-beam"></div>'
        + '<div class="img-scan-icon">&#x1F4C4;</div></div>'
        + '<div class="img-scan-label"><span class="img-scan-dot"></span>'
        + '<span class="img-scan-text">Scanning Instrument</span></div>'
        + '<div class="img-scan-bar"><div class="img-scan-fill"></div></div>'
        + '</div>';
    var f = document.getElementById('frontImageBox');
    var r = document.getElementById('rearImageBox');
    if (f) f.innerHTML = spin;
    if (r) r.innerHTML = spin;
}