/* =============================================================
   bce.js — Batch & Cheque Entry global functions
   ============================================================= */

/* ── Live clock ─────────────────────────────────────────────── */
(function(){
    function tick(){
        var t = new Date();
        var ts = [t.getHours(),t.getMinutes(),t.getSeconds()]
                   .map(function(n){return n<10?'0'+n:''+n;}).join(':');
        var c = document.getElementById('hdrClock');
        if(c) c.textContent = ts;
    }
    tick();
    setInterval(tick,1000);
})();

/* ── Session timer ──────────────────────────────────────────── */
(function(){
    var start = Date.now();

    setInterval(function(){
        var sec = Math.floor((Date.now()-start)/1000);
        var h=Math.floor(sec/3600),
            m=Math.floor((sec%3600)/60),
            s=sec%60;

        var el = document.getElementById('sbSession');

        if(el){
            el.textContent =
                'Session: '
                +(h<10?'0'+h:h)+':'
                +(m<10?'0'+m:m)+':'
                +(s<10?'0'+s:s);
        }
    },1000);
})();

/* ── Batch modal ─────────────────────────────────────────────── */
function bce_openBatchModal(){
    var el = document.getElementById('batchModal');
    if(!el) return;
    if(el.parentNode !== document.body) document.body.appendChild(el);
    el.style.display = 'flex'; // FIX: was classList.add('open') — broken in ZK
}

function bce_closeBatchModal(){
    var el = document.getElementById('batchModal');
    if(el) el.style.display = 'none'; // FIX: was classList.remove('open')
}

/* ── Batch count label ───────────────────────────────────────── */
function bce_updateBatchLabel(count){
    var el=document.getElementById('batchCountLabel');

    if(el){
        el.textContent =
            count + ' batch' + (count!==1 ? 'es' : '');
    }
}

/* ── Open cheque panel ───────────────────────────────────────── */
function bce_openChequePanel(batchId,branch,status,count){

    var bid=document.getElementById('cpBatchIdLabel');
    var br=document.getElementById('cpBranchLabel');
    var st=document.getElementById('cpStatusLabel');
    var ct=document.getElementById('cpCountLabel');

    if(bid) bid.textContent=batchId;
    if(br)  br.textContent='Branch: '+branch;
    if(st)  st.textContent='Status: '+status;
    if(ct)  ct.textContent=count+' record'+(count!==1?'s':'');

    setTimeout(function(){
        var el=document.getElementById('chequeGridPanel');
        if(el){
            el.scrollIntoView({
                behavior:'smooth',
                block:'start'
            });
        }
    },80);
}

/* ── Close cheque panel ──────────────────────────────────────── */
function bce_closeChequePanel(){
    var dp=document.getElementById('chequeDetailPanel');
    if(dp) dp.style.display='none';
}

/* ── Open detail panel ───────────────────────────────────────── */
function bce_openDetailPanel(chequeNo){

    var lbl=document.getElementById('detailChequeNoLabel');
    if(lbl) lbl.textContent=chequeNo;

    var dp=document.getElementById('chequeDetailPanel');

    if(dp){
        dp.style.display='';

        setTimeout(function(){
            dp.scrollIntoView({
                behavior:'smooth',
                block:'start'
            });
        },80);
    }
}

/* ── Close detail panel ──────────────────────────────────────── */
function bce_closeDetailPanel(){
    var dp=document.getElementById('chequeDetailPanel');
    if(dp) dp.style.display='none';
}

/* ── Image renderer ──────────────────────────────────────────── */
function bce_renderImages(chequeDbId){

    if(!chequeDbId){

        var ph =
            '<div class="img-empty">' +
                '<div class="img-empty-icon">&#x1F5BC;</div>' +
                '<div class="img-empty-text">No image available</div>' +
            '</div>';

        var f=document.getElementById('frontImageBox');
        var r=document.getElementById('rearImageBox');

        if(f) f.innerHTML=ph;
        if(r) r.innerHTML=ph;

        return;
    }

    var imgStyle =
        'max-width:100%;max-height:320px;object-fit:contain;' +
        'display:block;margin:auto;border-radius:4px;' +
        'box-shadow:0 2px 12px rgba(0,0,0,.1);';

    function loadInto(boxId, side){

        var box=document.getElementById(boxId);
        if(!box) return;

        var img=document.createElement('img');

        img.alt=side;
        img.style.cssText=imgStyle;

        img.onerror=(function(b,s){
            return function(){
                b.innerHTML =
                    '<div class="img-empty">' +
                        '<div class="img-empty-icon">&#x274C;</div>' +
                        '<div class="img-empty-text">'+s+' image unavailable</div>' +
                    '</div>';
            };
        })(box,side);

        box.innerHTML='';
        box.appendChild(img);

        img.src='chequeImage?id='+chequeDbId+'&side='+side;
    }

    loadInto('frontImageBox','front');
    loadInto('rearImageBox','rear');
}

/* ── Loading spinner ─────────────────────────────────────────── */
function bce_imagesLoading(){

    var spin =
        '<div class="img-scanning">'
      + '<div class="img-scan-frame">'
      + '<div class="img-scan-corners">'
      + '<span class="isc-tl"></span><span class="isc-tr"></span>'
      + '<span class="isc-bl"></span><span class="isc-br"></span>'
      + '</div>'
      + '<div class="img-scan-beam"></div>'
      + '<div class="img-scan-icon">&#x1F4C4;</div>'
      + '</div>'
      + '<div class="img-scan-label">'
      + '<span class="img-scan-dot"></span>'
      + '<span class="img-scan-text">Scanning Instrument</span>'
      + '</div>'
      + '<div class="img-scan-bar"><div class="img-scan-fill"></div></div>'
      + '</div>';

    var f=document.getElementById('frontImageBox');
    var r=document.getElementById('rearImageBox');

    if(f) f.innerHTML=spin;
    if(r) r.innerHTML=spin;
}

/* ── Batch success toast ─────────────────────────────────────── */
function bce_showBatchSuccessToast(batchId, count, amt, durationMs){
    var dur = durationMs || 4000;
    var t = document.getElementById('batchSuccessToast');
    if(!t) return;
    t.className = 'bce-toast bce-toast-success';
    t.innerHTML =
        '<div class="bce-toast-stripe"></div>'
      + '<div class="bce-toast-icon">&#x2705;</div>'
      + '<div class="bce-toast-body">'
      + '<div class="bce-toast-title">Batch Created Successfully</div>'
      + '<div class="bce-toast-meta">'
      + '<span class="bce-toast-bid">' + batchId + '</span>'
      + '<span class="bce-toast-dot">&#xB7;</span>'
      + '<span>' + count + ' cheques</span>'
      + '<span class="bce-toast-dot">&#xB7;</span>'
      + '<span>&#x20B9;' + amt + '</span>'
      + '</div>'
      + '<div class="bce-toast-actions">'
      + '<button class="bce-toast-btn-dismiss" onclick="bce_hideToast()">&#x2715; Close</button>'
      + '</div>'
      + '</div>'
      + '<button class="bce-toast-x" onclick="bce_hideToast()">&#x2715;</button>';

    /* FIX: push below header (52px header + 8px gap) */
    t.style.cssText = [
        'display:flex',
        'position:fixed',
        'top:68px',          /* below 52px header + gap */
        'left:50%',
        'transform:translateX(-50%) translateY(-20px)',
        'z-index:99998',     /* below modals (99999) but above page */
        'min-width:320px',
        'max-width:440px',
        'background:#fff',
        'border-radius:12px',
        'box-shadow:0 8px 32px rgba(0,0,0,.18),0 0 0 1px #e2e8f0',
        'overflow:hidden',
        'opacity:0',
        'transition:transform .32s cubic-bezier(.34,1.56,.64,1),opacity .28s ease'
    ].join(';');

    setTimeout(function(){
        t.style.transform  = 'translateX(-50%) translateY(0)';
        t.style.opacity    = '1';
    }, 20);

    setTimeout(function(){
        bce_hideToast();
    }, dur);
}


/* ── 
function bce_hideToast(){
    var t = document.getElementById('batchSuccessToast');
    if(!t) return;
    t.style.transform = 'translateX(-50%) translateY(-20px)';
    t.style.opacity   = '0';
    setTimeout(function(){ t.style.display = 'none'; }, 320);
}

── Toast hide (FIXED) ───────────────────────────────────────
function bce_hideToast(){

    var t = document.getElementById('batchSuccessToast');

    if(!t) return;

    t.classList.remove('bce-toast-show');

    setTimeout(function(){
        t.style.display = 'none';
    },380);
}    ──────── */


function bce_hideToast(){
    var t = document.getElementById('batchSuccessToast');
    if(!t) return;
    t.style.transform = 'translateX(-50%) translateY(-20px)';
    t.style.opacity   = '0';
    setTimeout(function(){ t.style.display = 'none'; }, 320);
}


/* ── Scan modal ─────────────────────────────────────────────── */
function bce_openScanModal(batchId){
    var bid = document.getElementById('scanBatchIdLabel');
    if(bid) bid.textContent = batchId || '—';
    bce_scanHideProgress();
    var m = document.getElementById('scanModal');
    if(!m) return;
    if(m.parentNode !== document.body) document.body.appendChild(m);
    m.style.display = 'flex'; // FIX: was classList.add('open')
}


function bce_closeScanModal(){
    var m = document.getElementById('scanModal');
    if(m) m.style.display = 'none'; // FIX: was classList.remove('open')
    bce_scanHideProgress();
    // FIX: discard empty batch if user cancelled before uploading ZIP
    setTimeout(function(){
        var btn = document.querySelector('[id$="btnScanCancelDiscard"]');
        if(btn) btn.click();
    }, 50);
}



function bce_scanShowProgress(msg){

    var wrap = document.getElementById('scanProgress');
    var fill = document.getElementById('scanProgressFill');
    var text = document.getElementById('scanProgressText');

    if(wrap) wrap.style.display='';
    if(text) text.textContent=msg || 'Scanning Instruments…';

    if(fill){

        fill.style.transition='none';
        fill.style.width='0%';

        setTimeout(function(){
            fill.style.transition='width 1s cubic-bezier(.4,0,.2,1)';
            fill.style.width='72%';
        },40);

        setTimeout(function(){
            fill.style.width='90%';
        },2600);
    }
}

function bce_scanHideProgress(){

    var wrap = document.getElementById('scanProgress');
    var fill = document.getElementById('scanProgressFill');

    if(wrap) wrap.style.display='none';

    if(fill){
        fill.style.transition='none';
        fill.style.width='0%';
    }
}

/* ── Mismatch Dialog ────────────────────────────────────────── */
function bce_openMismatchDialog(){
    var d = document.getElementById('mismatchDialog');
    if(d) d.style.display='flex';
}

function bce_closeMismatchDialog(){
    var d = document.getElementById('mismatchDialog');
    if(d) d.style.display='none';
}
/* ═══════════════════════════════════════════════════════════════════
   BCE CHEQUE DETAIL POPUP — image helpers
   Target: bceFrontImageBox / bceRearImageBox (inside popup)
═══════════════════════════════════════════════════════════════════ */

function bce_bceRenderImages(chequeDbId) {
    var ph = '<div class="img-empty">'
           + '<div class="img-empty-icon">&#x1F5BC;</div>'
           + '<div class="img-empty-text">No image available</div>'
           + '</div>';
    if (!chequeDbId) {
        var f = document.getElementById('bceFrontImageBox');
        var r = document.getElementById('bceRearImageBox');
        if (f) f.innerHTML = ph;
        if (r) r.innerHTML = ph;
        return;
    }
    var imgStyle = 'max-width:100%;max-height:196px;object-fit:contain;'
                 + 'display:block;margin:auto;border-radius:4px;'
                 + 'box-shadow:0 2px 12px rgba(0,0,0,.1);';
    function loadInto(boxId, side) {
        var box = document.getElementById(boxId);
        if (!box) return;
        var img = document.createElement('img');
        img.alt = side; img.style.cssText = imgStyle;
        img.onerror = (function(b, s) {
            return function() {
                b.innerHTML = '<div class="img-empty">'
                    + '<div class="img-empty-icon">&#x274C;</div>'
                    + '<div class="img-empty-text">' + s + ' image unavailable</div>'
                    + '</div>';
            };
        })(box, side);
        box.innerHTML = ''; box.appendChild(img);
        img.src = 'chequeImage?id=' + chequeDbId + '&side=' + side;
    }
    loadInto('bceFrontImageBox', 'front');
    loadInto('bceRearImageBox',  'rear');
}

function bce_bceImagesLoading() {
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
    var f = document.getElementById('bceFrontImageBox');
    var r = document.getElementById('bceRearImageBox');
    if (f) f.innerHTML = spin;
    if (r) r.innerHTML = spin;
}

/* ═══════════════════════════════════════════════════════════════════
   BCE POPUP SHOW / HIDE
   Portal to document.body so position:fixed escapes ZK stacking context.
═══════════════════════════════════════════════════════════════════ */

function bce_showChequePop() {
    var pop = document.getElementById('bceChequePop');
    if (!pop) return;
    if (pop.parentNode !== document.body) document.body.appendChild(pop);
    pop.style.cssText = 'display:flex !important;'
        + 'position:fixed !important;top:0 !important;left:0 !important;'
        + 'right:0 !important;bottom:0 !important;'
        + 'width:100vw !important;height:100vh !important;'
        + 'z-index:99999 !important;background:rgba(10,20,50,.6) !important;'
        + 'align-items:flex-start !important;justify-content:center !important;'
        + 'padding:18px 12px 12px !important;overflow-y:auto !important;';
}

function bce_hideChequePop() {
    var pop = document.getElementById('bceChequePop');
    if (pop) pop.style.display = 'none';
}