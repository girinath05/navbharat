/* =============================================================
   batch-cheque-entry.js — Scan Module global functions
   NavbharatCTS · Outward Clearing · Batch & Cheque Entry

   Modal, toast, and dialog visibility (batchModal, scanModal,
   batchSuccessToast, mismatchDialog, duplicateDialog) is now
   driven entirely by BatchChequeEntryComposer (pure ZK MVC —
   setVisible() + @Listen + Timer). No JS open/close/innerHTML
   helpers remain for those.

   Remaining functions here are cosmetic, client-only widgets
   that have no server-side equivalent and don't touch app state:
   - Live clock / session timer (header chrome)
   - bce_updateBatchLabel — cosmetic pill text, pushed from composer
   =============================================================*/

/* ── Live clock ─────────────────────────────────────────────── */
(function(){
    function tick(){
        var t  = new Date();
        var ts = [t.getHours(),t.getMinutes(),t.getSeconds()]
                   .map(function(n){return n<10?'0'+n:''+n;}).join(':');
        var c  = document.getElementById('hdrClock');
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
        var h=Math.floor(sec/3600), m=Math.floor((sec%3600)/60), s=sec%60;
        var el = document.getElementById('sbSession');
        if(el) el.textContent = 'Session: '
            +(h<10?'0'+h:h)+':'+(m<10?'0'+m:m)+':'+(s<10?'0'+s:s);
    },1000);
})();

/* ── Batch count label ───────────────────────────────────────── */
/* ──────────────────────────────────────────────────────────────
   bce_updateBatchLabel
   Cosmetic label update for batch count pill ("3 batches").
   Value is pushed in from server-rendered ZK label via composer.
   ────────────────────────────────────────────────────────────── */
function bce_updateBatchLabel(count){
    var el = document.getElementById('batchCountLabel');
    if(el) el.textContent = count + ' batch' + (count!==1?'es':'');
}