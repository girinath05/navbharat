/* =============================================================
   batch-cheque-entry.js
   Global JS for batch-cheque-entry.zul / dashboard.zul

   FIXES APPLIED (v2):
   1. bce_openBatchModal  — classList.add('open') → style.display='flex'
   2. bce_closeBatchModal — classList.remove('open') → style.display='none'
   3. bce_openScanModal   — classList.add('open') → style.display='flex'
   4. bce_closeScanModal  — classList.remove('open') → style.display='none'

   Root cause: ZK renders pages inside an iframe/shadow context where
   external CSS class rules like .modal-overlay.open{display:flex}
   are NOT reliably applied. Inline style always wins.
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
    tick(); setInterval(tick,1000);
})();

/* ── Session timer ──────────────────────────────────────────── */
(function(){
    var start = Date.now();
    setInterval(function(){
        var sec = Math.floor((Date.now()-start)/1000);
        var h=Math.floor(sec/3600), m=Math.floor((sec%3600)/60), s=sec%60;
        var el = document.getElementById('sbSession');
        if(el) el.textContent = 'Session: '
            +(h<10?'0'+h:h)+':'+(m<10?'0'+m:m)+':'+(s<10?'0'+s:s);
    },1000);
})();

/* ── Batch modal ─────────────────────────────────────────────── */
/* FIX v2: was classList.add('open') — broken inside ZK frame    */
function bce_openBatchModal(){
    var el = document.getElementById('batchModal');
    if(el) el.style.display = 'flex';
}
function bce_closeBatchModal(){
    var el = document.getElementById('batchModal');
    if(el) el.style.display = 'none';
}

/* ── Batch count label ───────────────────────────────────────── */
function bce_updateBatchLabel(count){
    var el = document.getElementById('batchCountLabel');
    if(el) el.textContent = count + ' batch' + (count!==1?'es':'');
}

/* ── Open cheque panel ───────────────────────────────────────── */
function bce_openChequePanel(batchId, branch, status, count){
    var bid = document.getElementById('cpBatchIdLabel');
    var br  = document.getElementById('cpBranchLabel');
    var st  = document.getElementById('cpStatusLabel');
    var ct  = document.getElementById('cpCountLabel');
    if(bid) bid.textContent = batchId;
    if(br)  br.textContent  = 'Branch: '+branch;
    if(st)  st.textContent  = 'Status: '+status;
    if(ct)  ct.textContent  = count+' record'+(count!==1?'s':'');
    setTimeout(function(){
        var el = document.getElementById('chequeGridPanel');
        if(el) el.scrollIntoView({behavior:'smooth', block:'start'});
    },80);
}

/* ── Close cheque panel ──────────────────────────────────────── */
function bce_closeChequePanel(){
    var dp = document.getElementById('chequeDetailPanel');
    if(dp) dp.style.display = 'none';
}

/* ── Open detail panel ───────────────────────────────────────── */
function bce_openDetailPanel(chequeNo){
    var lbl = document.getElementById('detailChequeNoLabel');
    if(lbl) lbl.textContent = chequeNo;
    var dp = document.getElementById('chequeDetailPanel');
    if(dp){
        dp.style.display = '';
        setTimeout(function(){
            dp.scrollIntoView({behavior:'smooth', block:'start'});
        },80);
    }
}

/* ── Close detail panel ──────────────────────────────────────── */
function bce_closeDetailPanel(){
    var dp = document.getElementById('chequeDetailPanel');
    if(dp) dp.style.display = 'none';
}

/* ── Image renderer ──────────────────────────────────────────── */
function bce_renderImages(chequeDbId){
    var ph = '<div class="img-empty">'
           + '<div class="img-empty-icon">&#x1F5BC;</div>'
           + '<div class="img-empty-text">No image available</div>'
           + '</div>';
    if(!chequeDbId){
        var f = document.getElementById('frontImageBox');
        var r = document.getElementById('rearImageBox');
        if(f) f.innerHTML = ph;
        if(r) r.innerHTML = ph;
        return;
    }
    var imgStyle = 'max-width:100%;max-height:320px;object-fit:contain;'
                 + 'display:block;margin:auto;border-radius:4px;'
                 + 'box-shadow:0 2px 12px rgba(0,0,0,.1);';
    function loadInto(boxId, side){
        var box = document.getElementById(boxId);
        if(!box) return;
        var img = document.createElement('img');
        img.alt = side;
        img.style.cssText = imgStyle;
        img.onerror = (function(b,s){return function(){
            b.innerHTML = '<div class="img-empty">'
                + '<div class="img-empty-icon">&#x274C;</div>'
                + '<div class="img-empty-text">'+s+' image unavailable</div></div>';
        };})(box, side);
        box.innerHTML = '';
        box.appendChild(img);
        img.src = 'chequeImage?id='+chequeDbId+'&side='+side;
    }
    loadInto('frontImageBox','front');
    loadInto('rearImageBox','rear');
}

/* ── Scan modal ─────────────────────────────────────────────── */
/* FIX v2: was classList.add('open') — broken inside ZK frame    */
function bce_openScanModal(batchId){
    var lbl = document.getElementById('scanBatchIdLabel');
    if(lbl) lbl.textContent = batchId || '—';
    bce_scanHideProgress();
    var el = document.getElementById('scanModal');
    if(el) el.style.display = 'flex';
}
function bce_closeScanModal(){
    var el = document.getElementById('scanModal');
    if(el) el.style.display = 'none';
    bce_scanHideProgress();
}

function bce_scanShowProgress(msg){
    var p = document.getElementById('scanProgress');
    var t = document.getElementById('scanProgressText');
    var f = document.getElementById('scanProgressFill');
    if(p) p.style.display = 'block';
    if(t) t.textContent = msg || 'Processing…';
    if(f){
        f.style.width = '0%';
        var w = 0;
        var iv = setInterval(function(){
            w += 2; if(w > 90){ clearInterval(iv); return; }
            f.style.width = w+'%';
        },80);
    }
}
function bce_scanHideProgress(){
    var p = document.getElementById('scanProgress');
    if(p) p.style.display = 'none';
}

/* ── Batch success toast ─────────────────────────────────────── */
function bce_showBatchSuccessToast(batchId, chequeCount, totalAmount){
    var existing = document.getElementById('batchSuccessToast');
    if(existing) existing.remove();
    var toast = document.createElement('div');
    toast.id = 'batchSuccessToast';
    toast.className = 'bce-toast bce-toast-success';
    toast.innerHTML =
        '<div class="bce-toast-left"><div class="bce-toast-icon">&#x2705;</div></div>'
      + '<div class="bce-toast-body">'
      + '<div class="bce-toast-title">Batch Created Successfully</div>'
      + '<div class="bce-toast-meta">'
      + '<span class="bce-toast-bid">'+batchId+'</span>'
      + '<span class="bce-toast-sep">&#xB7;</span>'
      + '<span>'+chequeCount+' cheques</span>'
      + '<span class="bce-toast-sep">&#xB7;</span>'
      + '<span>&#x20B9;'+totalAmount+'</span>'
      + '</div>'
      + '<div class="bce-toast-actions">'
      + '<button class="bce-toast-btn-primary" onclick="window.location.href=\'my-batches.zul\'">&#x1F4CB; View Batches</button>'
      + '<button class="bce-toast-btn-dismiss" onclick="document.getElementById(\'batchSuccessToast\').remove()">Dismiss</button>'
      + '</div></div>'
      + '<button class="bce-toast-close" onclick="document.getElementById(\'batchSuccessToast\').remove()">&#x2715;</button>';
    document.body.appendChild(toast);
    setTimeout(function(){ toast.classList.add('bce-toast-show'); },30);
    setTimeout(function(){
        if(toast.parentNode){
            toast.classList.remove('bce-toast-show');
            setTimeout(function(){ if(toast.parentNode) toast.remove(); },400);
        }
    },8000);
}

/* ── Upload success popup ────────────────────────────────────── */
function bce_showUploadSuccess(batchId, chequeCount, totalAmount){
    var existing = document.getElementById('uploadSuccessOverlay');
    if(existing) existing.remove();
    var overlay = document.createElement('div');
    overlay.id = 'uploadSuccessOverlay';
    overlay.className = 'upload-success-overlay';
    overlay.innerHTML =
        '<div class="upload-success-box">'
      + '<div class="us-icon">&#x2705;</div>'
      + '<div class="us-title">Batch Created Successfully</div>'
      + '<div class="us-batchid">'+batchId+'</div>'
      + '<div class="us-meta-row">'
      + '<div class="us-meta-item"><span class="us-meta-lbl">Cheques</span><span class="us-meta-val">'+chequeCount+'</span></div>'
      + '<div class="us-meta-item"><span class="us-meta-lbl">Total Amount</span><span class="us-meta-val">&#x20B9;'+totalAmount+'</span></div>'
      + '</div>'
      + '<hr class="us-divider"/>'
      + '<div class="us-actions">'
      + '<button class="btn btn-primary" onclick="window.location.href=\'my-batches.zul\'">&#x1F4CB; View Batches</button>'
      + '<button class="btn btn-outline" onclick="document.getElementById(\'uploadSuccessOverlay\').remove()">Stay Here</button>'
      + '</div></div>';
    document.body.appendChild(overlay);
    overlay.addEventListener('click', function(e){
        if(e.target === overlay) overlay.remove();
    });
}
function bce_closeUploadSuccess(){
    var el = document.getElementById('uploadSuccessOverlay');
    if(el) el.remove();
}

/* ── Loading spinner ─────────────────────────────────────────── */
function bce_imagesLoading(){
    var spin = '<div class="img-loading"><div class="img-spinner"></div>'
             + '<span>Loading image&#x2026;</span></div>';
    var f = document.getElementById('frontImageBox');
    var r = document.getElementById('rearImageBox');
    if(f) f.innerHTML = spin;
    if(r) r.innerHTML = spin;
}

/* ── Mismatch dialog ─────────────────────────────────────────── */
function bce_openMismatchDialog(){
    var d = document.getElementById('mismatchDialog');
    if(d) d.style.display = 'flex';
}
function bce_closeMismatchDialog(){
    var d = document.getElementById('mismatchDialog');
    if(d) d.style.display = 'none';
}