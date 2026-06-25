# Navbharat CTS Outward

Cheque Truncation System (CTS-2010 compliant) outward clearing module for **Nav Bharat Bank**. Handles the full Maker → Verifier lifecycle for outward cheques: batch creation, ZIP-based cheque capture, MICR/IQA validation, image storage, two-level verification (V1/V2), and CXF/CIBF file generation for the clearing network.

---

## Tech Stack

| Layer | Technology |
|---|---|
| UI framework | ZK Framework 10 CE (`10.0.0-jakarta`) — pure MVC, `SelectorComposer` + `@Listen`/`@Wire` |
| Language | Java 21 |
| ORM | Hibernate 6 (`6.4.4.Final`) — native PostgreSQL SQL, no HQL |
| Connection pool | HikariCP 5.1.0 |
| Database | PostgreSQL via Supabase |
| Servlet container | Apache Tomcat 10 (Jakarta Servlet 6.0.0) |
| Reporting | JasperReports 6.21.3 (batch/cheque/CXF PDF exports) |
| Auth | `jbcrypt` (password hashing) |
| JSON | Jackson Databind (CBS Firebase API integration) |
| Build | Maven, packaged as WAR (`finalName: Navbharat`) |

---

## Architecture

Strict layered MVC — no business logic in ZUL, no SQL outside DAO:

```
ZUL (view)
  │  onClick / onChange / onUpload / onTimer
  ▼
Composer (SelectorComposer, @Wire / @Listen)
  │
  ▼
Service (interface + Impl)
  │
  ▼
DAO (interface + Impl — native PostgreSQL via Hibernate Session)
  │
  ▼
PostgreSQL (Supabase)
```

- **No JavaScript** for new business logic — pure ZK server-side MVC. Two legacy JS files (`batch-cheque-entry.js`, `batch-detail.js`) remain for cases ZK can't reach directly (live header clock, session timer, native `<img>` tab-switching, Ctrl+S shortcut) — loaded via ZK's `<script src="...">` component (not raw `<n:script>`, to avoid context-path case bugs).
- **No native HTML** in ZUL without the `n:` namespace.
- ZUL is the source of truth for DOM/component IDs — composer `@Wire` fields must match exactly.

---

## Package Structure

```
com.cts
├── composer/              Shell-level composer (DashboardComposer — SPA-style page loader)
├── filter/                 AccessControlFilter, AuSecurityFilter, SecurityFilter
├── scheduler/               UserUnlockScheduler
├── util/                    HibernateUtil, SecurityUtil
│
├── uam/                      User Access Management module
│   ├── composer / dao / dto / enums / model / service
│
└── outward/                  Outward Clearing module (this project's core)
    ├── composer/             ChequeScanComposer, BatchDetailComposer, MyBatchesComposer,
    │                         VerificationOneComposer, VerificationIIComposer,
    │                         CxfCibfComposer, OutwardDashboardComposer, OutwardReportComposer
    ├── service/              BatchService, ChequeService, ZipImportService, ZipProcessingService,
    │                         CxfCibfService, CBSService, VerificationOneService, VerificationIIService,
    │                         OutwardDashboardService, OutwardReportService
    ├── dao/                  BatchDAO, ChequeDAO, CxfCibfDAO, CBSDAO, VerificationIIDAO,
    │                         OutwardDashboardDAO, OutwardReportDAO
    ├── parser/               CtsZipParser(Impl), CtsXmlParser(Impl) — ZIP/XML scan-data parsing
    ├── entity/               BatchEntity, ChequeEntity
    ├── model/                BatchModel, ChequeModel, BatchSummary, CbsAccountDetails, OutwardDashboardStats
    ├── dto/                  CxfBatchDTO, CxfChequeDTO, ReportBatchDTO, ReportChequeDTO
    ├── enums/                BatchStatus, ChequeStatus
    ├── exception/            BatchSubmitException
    ├── servlet/              ChequeImageServlet (serves cheque front/rear BLOBs)
    ├── listener/             AppStartupListener
    └── util/                 AmountToWords
```

---

## Core Workflow

```
1. CREATE BATCH (Maker)
   ChequeScanComposer → BatchService.createBatch()
   → sequential batchId "BATCH{NNNN}" generated from DB MAX(seq)
   → status = Draft

2. SCAN / CAPTURE (Maker)
   Upload ZIP (cheque images + MICR XML)
   → CtsZipParserImpl parses ZIP
   → ZipImportServiceImpl validates count vs declared, checks duplicates
   → ChequeDAOImpl persists cheques (fast metadata INSERT, async BLOB image write)
   → 3 outcomes: Clean Import / Count Mismatch dialog / All-Duplicates dialog

3. EDIT & READY (Maker)
   BatchDetailComposer — per-cheque MICR/payee/amount entry, payee account lookup (CBS)
   status: Pending → Ready (per cheque)

4. SUBMIT BATCH (Maker)
   BatchServiceImpl.submitBatch()
   → validates: no Pending cheques, no MICR_Repair cheques
   → routes each cheque by amount vs HIGH_VALUE_THRESHOLD (₹50,000):
        amount <  ₹50,000 → V1_PENDING  (Verification I queue)
        amount >= ₹50,000 → V2_PENDING  (Verification II queue)
   → batch status → ReadyForVerification

5. VERIFY (V1 / V2 Verifiers)
   VerificationOneComposer / VerificationIIComposer
   → Accept → VERIFIED | Reject → REJECTED | Refer (V1 only) → V2_PENDING
   → BatchServiceImpl.checkAndFinalizeBatch() auto-advances batch to "Verified"
     once every cheque has been actioned (event-driven, no polling)

6. CXF / CIBF GENERATION
   CxfCibfComposer → CxfCibfServiceImpl
   → builds CXF/CIBF XML for all VERIFIED cheques → batch status: Dispatched
```

---

## Status Enums

**`BatchStatus`**

| DB value | Label |
|---|---|
| `Draft` | Draft |
| `VerificationInProgressAtMaker` | Pending |
| `ReadyForVerification` | Ready for Verification |
| `VerificationInProgress` | Verification In Progress |
| `Verified` | Verified |
| `CXF_CIBF_GENERATED` | CXF Generated |
| `Dispatched` | Dispatched |

**`ChequeStatus`**

| DB value | Label | Set by |
|---|---|---|
| `Pending` | Pending | ZIP import (initial) |
| `Ready` | Ready | Maker saves cheque fields |
| `Submitted` | Submitted | Batch submission |
| `V1_PENDING` | V1 Pending | Routing — amount < ₹50,000 |
| `V2_PENDING` | V2 Pending | Routing — amount ≥ ₹50,000, or V1 referral |
| `VERIFIED` | Verified | V1/V2 accept (terminal) |
| `REJECTED` | Rejected | V1/V2 reject (terminal) |
| `REFERRED` | Referred | V1 escalates to V2 (transient → V2_PENDING) |

`ChequeStatus.fromDb()` defaults safely to `PENDING` on null/blank/unrecognized input — never throws, never returns blank.

---

## Database

PostgreSQL (Supabase), accessed exclusively via native SQL through Hibernate `Session.createNativeQuery()` — no HQL in DAO classes.

Core tables:
- `cts_batches` — one row per batch
- `cts_cheques` — one row per cheque, FK to `cts_batches.batch_id`

⚠️ Shared live DB — schema migrations, FK constraints, and `DEFAULT NOW()` changes require team coordination (Anusha owns V1 verification screen, Girinath owns V2). Safe-only index additions are the max unilateral DB change permitted.

---

## Build & Deploy

```bash
mvn clean package
```

- WAR output: `target/Navbharat.war` (note: capital `N` — set by `<finalName>` in `pom.xml`; deployed Tomcat context path matches this casing exactly — case-sensitive on Linux deployments)
- Java 21, Maven, Tomcat 10 (Jakarta Servlet 6.0.0, `provided` scope)
- `pip`/`npm` not used — pure Java/Maven build

### Key servlet mappings (`web.xml`)
| Path | Handler |
|---|---|
| `*.zul` | ZK `DHtmlLayoutServlet` (zkLoader) |
| `/zkau/*` | ZK AU engine (`DHtmlUpdateServlet`) |
| `/chequeImage` | `ChequeImageServlet` — serves cheque front/rear BLOB images |

---

## Known Issues / Tech Debt

- `findExistingChequeNos()` (ChequeDAOImpl) fails open on DB error — returns empty set, may allow duplicate inserts on transient DB failure.
- Brief image-visibility window between the fast metadata `INSERT` and the async BLOB `UPDATE` in `ChequeDAOImpl.saveCheques()`.
- Two legacy JS files (`bce.js`, `batch-cheque-entry.js`) define identically-named global functions (`bce_updateBatchLabel`, `bce_renderImages`, etc.) — if both are ever wired into the same page, the second `<script>` tag silently overwrites the first. Only one should be kept long-term.
- `main` and `master` both exist as branches in the remote repo — confirm with the team which is the actual default before merging.

---

## Team Conventions

- **No JavaScript** for new interactive behavior — ZK MVC only. Legacy JS exceptions are explicitly flagged in composer Javadoc (`@see` "JS exceptions retained").
- **No native HTML** in ZUL without `n:` namespace.
- File delivery: full files, one at a time — not diffs, not ZIPs.
- When merging new code into an existing file, the existing file's patterns/naming/annotations take precedence.
- `V1`/`V2` verification code (`loadChequesByVerLevel` and related) is **owned by Anusha (V1)** and **Girinath (V2)** — out of scope for unrelated refactors.
- Author header block + `@author` Javadoc tag on every file.
- Senior-level Javadoc throughout: full `{@inheritDoc}` on implementations, inline comments for non-obvious decisions.

---

## Author

**Umesh M.** — Navbharat CTS Outward module
