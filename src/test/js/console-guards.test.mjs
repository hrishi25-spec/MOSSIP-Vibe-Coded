#!/usr/bin/env node
/* Console guard tests — a lightweight DOM harness for the browser code.
 *
 * The Java suites cannot see src/main/resources/static at all: a green
 * ./mvnw test says nothing about whether the console's guards are still
 * wired after a refactor. This loads the real index.html and executes the
 * real console.js against a minimal DOM (no npm dependencies, so CI needs
 * nothing but a node binary), then drives it the way an operator would:
 *
 *   1. the dirty marker appears on an edit and clears on revert,
 *   2. the per-field diff lists old → new for every pending change,
 *   3. switching workflows with pending edits asks first and aborts on "no",
 *   4. the Discard-edits button reloads the policy and drops the edits,
 *   5. beforeunload warns for a dirty form and for an in-flight session,
 *   6. the change history flags edits that weaken liveness (risk level
 *      and the named reason, per entry).
 *
 * Run: node src/test/js/console-guards.test.mjs   (exit 0 = all assertions pass)
 *
 * Element lookup is by id, so removing an id from index.html (or the wiring
 * from console.js) throws during load or fails an assertion below — the test
 * cannot silently pass on a console that no longer has these controls.
 */
import { readFileSync } from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
const STATIC = path.join(ROOT, "src/main/resources/static");
const html = readFileSync(path.join(STATIC, "index.html"), "utf8");
const consoleJs = readFileSync(path.join(STATIC, "console.js"), "utf8");

// ---------- minimal DOM ----------

class EventTargetLike {
  constructor() {
    this._listeners = new Map();
  }
  addEventListener(type, fn) {
    if (!this._listeners.has(type)) this._listeners.set(type, []);
    this._listeners.get(type).push(fn);
  }
  removeEventListener(type, fn) {
    const list = this._listeners.get(type) || [];
    const i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  }
  dispatch(type, init = {}) {
    const event = {
      type,
      defaultPrevented: false,
      preventDefault() { event.defaultPrevented = true; },
      ...init
    };
    for (const fn of [...(this._listeners.get(type) || [])]) fn(event);
    return event;
  }
}

class ClassList {
  constructor(el) { this.el = el; }
  _read() { return new Set(this.el.className.split(/\s+/).filter(Boolean)); }
  _write(set) { this.el.className = [...set].join(" "); }
  add(...names) { const s = this._read(); names.forEach(n => s.add(n)); this._write(s); }
  remove(...names) { const s = this._read(); names.forEach(n => s.delete(n)); this._write(s); }
  toggle(name, force) {
    const s = this._read();
    const want = force === undefined ? !s.has(name) : Boolean(force);
    want ? s.add(name) : s.delete(name);
    this._write(s);
    return want;
  }
  contains(name) { return this._read().has(name); }
}

class Element extends EventTargetLike {
  constructor(tagName) {
    super();
    this.tagName = tagName.toUpperCase();
    this.id = "";
    this.attrs = {};
    this.children = [];
    this._text = "";
    this.className = "";
    this.classList = new ClassList(this);
    this.style = {};
    this.hidden = false;
    this.disabled = false;
    this.checked = false;
    this._value = "";
    this.type = undefined;
    this.scrollTop = 0;
  }
  // A real input/select always exposes its value as a string; coerce the same
  // way so fingerprint and diff comparisons behave as they do in a browser.
  get value() { return this._value; }
  set value(v) {
    const formControl = this.tagName === "INPUT" || this.tagName === "SELECT"
        || this.tagName === "TEXTAREA";
    this._value = formControl ? String(v) : v;
  }
  get textContent() {
    return this._text + this.children.map(c => c.textContent || "").join("");
  }
  set textContent(v) { this.children = []; this._text = String(v); }
  get innerHTML() { return this._text; }
  set innerHTML(v) { this.children = []; this._text = String(v); }
  get scrollHeight() { return this._text.length; }
  setAttribute(name, value) {
    this.attrs[name] = value;
    if (name === "id") this.id = value;
    if (name === "type") this.type = value;
    if (name === "class") this.className = value;
  }
  getAttribute(name) { return name in this.attrs ? this.attrs[name] : null; }
  append(...nodes) { for (const n of nodes) this.children.push(n); }
  appendChild(node) { this.children.push(node); return node; }
  focus() {}
  // The one canvas API console.js touches (frame capture stubs out cleanly).
  getContext() { return { drawImage() {} }; }
  toDataURL() { return "data:image/jpeg;base64,STUB"; }
}

// Build an element per id="…" in the real markup; selects get the value a
// browser would give them (first option), which is what the guards compare
// against as the "loaded workflow".
function buildDom(source) {
  const byId = new Map();
  const tagRe = /<([A-Za-z][A-Za-z0-9-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
  let m;
  while ((m = tagRe.exec(source))) {
    const [, tag, rawAttrs] = m;
    const attrs = {};
    const attrRe = /([A-Za-z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
    let a;
    while ((a = attrRe.exec(rawAttrs))) attrs[a[1]] = a[2] ?? a[3];
    if (!attrs.id) continue;
    const el = new Element(tag);
    el.id = attrs.id;
    if (tag === "input") el.type = attrs.type || "text";
    for (const [k, v] of Object.entries(attrs)) el.attrs[k] = v;
    byId.set(attrs.id, el);
  }
  const selectRe = /<select[^>]*\bid="([^"]+)"[^>]*>([\s\S]*?)<\/select>/g;
  for (const sm of source.matchAll(selectRe)) {
    const el = byId.get(sm[1]);
    const first = sm[2].match(/<option[^>]*\bvalue="([^"]*)"/);
    if (el && first) el.value = first[1];
  }
  return byId;
}

const byId = buildDom(html);
const $id = id => {
  const el = byId.get(id);
  if (!el) throw new Error(`index.html has no element #${id} — the console wiring is incomplete`);
  return el;
};

const document = {
  hidden: false,
  getElementById: id => byId.get(id) || null,
  createElement: tag => new Element(tag)
};

// ---------- fetch / storage stubs ----------

const POLICY = {
  passiveThreshold: 0.8,
  challengeTimeoutMs: 20000,
  minChallengeCount: 1,
  maxRetryCount: 3,
  onRepeatedFailure: "ESCALATE",
  livenessEnabled: true,
  activeLivenessEnabled: true,
  challengeTypes: ["blink", "smile"],
  updatedAt: "2026-10-05T00:00:00Z"
};

const fetchCalls = [];
let confirmAnswer = false;
let hangFrames = false;   // when set, frame POSTs never answer (check in flight)

// Sample policy-change history: one weakening edit (HIGH), one benign
// edit (LOW), and one written before the risk classification existed
// (no risk key — the console must not guess a level for it).
const AUDIT = [
  {
    id: "audit-2",
    eventType: "CONFIG_CHANGED",
    workflowType: "RESIDENT",
    createdAt: "2026-10-05T10:02:00Z",
    details: {
      workflowType: "RESIDENT",
      action: "UPDATED",
      actor: "key:9f2c1a7b4e0d",
      sourceIp: "203.0.113.10",
      changes: { passiveThreshold: { from: 0.8, to: 0.4 } },
      risk: { level: "HIGH", reasons: ["passiveThreshold lowered"] }
    }
  },
  {
    id: "audit-1",
    eventType: "CONFIG_CHANGED",
    workflowType: "OPERATOR",
    createdAt: "2026-10-05T10:01:00Z",
    details: {
      workflowType: "OPERATOR",
      action: "UPDATED",
      actor: "key:1a2b3c4d5e6f",
      sourceIp: "unknown",
      changes: { maxRetryCount: { from: 3, to: 1 } },
      risk: { level: "LOW", reasons: [] }
    }
  },
  {
    id: "audit-0",
    eventType: "CONFIG_CHANGED",
    workflowType: "SUPERVISOR",
    createdAt: "2026-10-04T09:00:00Z",
    details: {
      workflowType: "SUPERVISOR",
      action: "CREATED",
      actor: "key:abcdefabcdef",
      sourceIp: "unknown",
      changes: { passiveThreshold: { from: 0.5, to: 0.82 } }
    }
  }
];

async function fetch(url, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  fetchCalls.push({ url, method, headers: options.headers || {} });
  const respond = body => ({
    ok: true,
    status: 200,
    headers: { get: () => null },
    text: async () => JSON.stringify(body),
    json: async () => body
  });
  if (url === "/health") return respond({ status: "UP", engine: "available" });
  if (url === "/api/v1/metrics") return respond({ framesProcessed: 1 });
  if (url.startsWith("/api/v1/config/audit")) return respond(AUDIT);
  if (url.startsWith("/api/v1/config/")) {
    if (method === "PUT") return respond({ saved: true });
    return respond(POLICY);
  }
  if (url === "/api/v1/sessions" && method === "POST") {
    return respond({ id: "sess-00000000-0000-0000-0000-000000000001",
                     workflowType: JSON.parse(options.body).workflowType,
                     status: "ACTIVE" });
  }
  if (url.includes("/frames")) {
    if (hangFrames) return new Promise(() => {});   // never settles
    return respond({ action: "proceed", message: "stub" });
  }
  if (url.includes("/challenges/")) return respond({ passed: true, action: "proceed" });
  if (url.includes("/close")) {
    return respond({ status: "CLOSED", totalFramesEvaluated: 1,
                     totalChallengesIssued: 0, durationMs: 1 });
  }
  if (url.includes("/audit")) return respond([]);
  throw new Error(`unexpected fetch in test: ${method} ${url}`);
}

function makeStorage() {
  const map = new Map();
  return {
    getItem: k => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: k => map.delete(k)
  };
}

const windowTarget = new EventTargetLike();
windowTarget.confirm = () => confirmAnswer;
const intervals = [];   // recorded, never scheduled: the harness must be able to exit

const sandbox = {
  document,
  window: windowTarget,
  sessionStorage: makeStorage(),
  fetch,
  console,
  setTimeout,
  clearTimeout,
  clearInterval: () => {},
  setInterval: (fn, ms) => { intervals.push({ fn, ms }); return intervals.length; }
};
vm.createContext(sandbox);
vm.runInContext(consoleJs, sandbox, { filename: "console.js" });

// Let the fire-and-forget loads at the bottom of console.js settle.
const settle = async (rounds = 25) => {
  for (let i = 0; i < rounds; i++) await new Promise(r => setImmediate(r));
};

// ---------- assertions ----------

let passed = 0;
let failed = 0;
function section(name) { console.log(`\n${name}`); }
function assert(cond, msg) {
  if (cond) { passed++; console.log(`  ok - ${msg}`); }
  else { failed++; console.log(`  FAIL - ${msg}`); }
}
const status = () => $id("cfg-status");
const diff = () => $id("cfg-diff");
const diffRows = () => diff().children.filter(c => c.className === "cfg-diff-row");
const isDirtyShown = () => status().className.includes("dirty");
const edit = (el, value) => {
  if (typeof el.checked === "boolean" && typeof value === "boolean") el.checked = value;
  else el.value = value;
  el.dispatch("input");
  el.dispatch("change");
};
const getFetch = suffix => fetchCalls.filter(c => c.method === "GET" && c.url.endsWith(suffix));
const discardBtn = () => $id("btn-cfg-discard");
const beforeunload = () => windowTarget.dispatch("beforeunload");

await settle();

section("initial load");
assert(status().textContent.includes("Loaded RESIDENT policy"),
    "the RESIDENT policy loads on start");
assert(!isDirtyShown(), "a freshly loaded form is not dirty");
assert(diff().hidden === true, "the diff panel starts hidden");
assert($id("cfg-workflow").value === "RESIDENT", "the workflow select starts on RESIDENT");

section("per-field diff (item 1)");
edit($id("cfg-threshold"), "0.55");
assert(isDirtyShown(), "editing a field turns the dirty marker on");
assert(diff().hidden === false, "the diff panel appears with a pending edit");
assert(diffRows().length === 1, "one changed field shows one diff row");
assert(diff().textContent.includes("passiveThreshold"), "the row names the payload field");
assert(diff().textContent.includes("0.8") && diff().textContent.includes("0.55"),
    "the row shows loaded → pending values");
assert(diff().textContent.includes("1 field differs from the loaded RESIDENT policy"),
    "the diff title is grammatical for a single field");

edit($id("cfg-ch-left"), true);
assert(diffRows().length === 2, "a second changed field adds a second row");
assert(diff().textContent.includes("2 fields differ from the loaded RESIDENT policy"),
    "the diff title pluralises when several fields differ");
assert(diff().textContent.includes("challengeTypes"),
    "the four challenge checkboxes diff as their one payload field");
assert(diff().textContent.includes("blink, smile, turn_left"),
    "the pending challenge list is shown");

edit($id("cfg-retries"), "5");
assert(diffRows().length === 3, "a third changed field adds a third row");
assert(diff().textContent.includes("maxRetryCount"),
    "the retries field is listed under its payload name");

section("reverting by hand clears the marker and the diff");
edit($id("cfg-threshold"), "0.8");
edit($id("cfg-ch-left"), false);
edit($id("cfg-retries"), "3");
assert(!isDirtyShown(), "reverting every field turns the dirty marker off");
assert(diff().hidden === true, "the diff panel hides again when nothing differs");

section("workflow switch guard");
edit($id("cfg-threshold"), "0.7");
confirmAnswer = false;
$id("cfg-workflow").value = "OPERATOR";
$id("cfg-workflow").dispatch("change");
assert($id("cfg-workflow").value === "RESIDENT",
    "answering 'no' puts the workflow select back on the loaded policy");
assert(isDirtyShown(), "answering 'no' keeps the pending edits");
assert(diff().hidden === false, "the diff still shows what would be lost");

confirmAnswer = true;
$id("cfg-workflow").value = "OPERATOR";
$id("cfg-workflow").dispatch("change");
await settle();
assert($id("cfg-workflow").value === "OPERATOR", "answering 'yes' switches workflows");
assert(getFetch("/api/v1/config/OPERATOR").length > 0, "the new workflow's policy is fetched");
assert(status().textContent.includes("Loaded OPERATOR policy"), "the form reloads");
assert(!isDirtyShown() && diff().hidden === true, "a reload clears the dirty marker and diff");

section("discard-and-reload button (item 2)");
edit($id("cfg-threshold"), "0.66");
assert(isDirtyShown() && diff().hidden === false, "the form is dirty before discarding");
const fetchesBefore = fetchCalls.length;
discardBtn().dispatch("click");
await settle();
assert(fetchCalls.slice(fetchesBefore).some(c => c.url === "/api/v1/config/OPERATOR"),
    "discard re-reads the loaded policy from the server");
assert(status().textContent.includes("Discarded pending edits"),
    "the status line confirms the discard");
assert(!isDirtyShown(), "discard turns the dirty marker off");
assert(diff().hidden === true, "discard empties the diff panel");
assert($id("cfg-threshold").value === String(POLICY.passiveThreshold),
    "the edited field is back on the loaded value");
discardBtn().dispatch("click");
await settle();
assert(status().textContent.includes("No pending edits"),
    "discarding a clean form is a no-op that says so");

section("beforeunload — dirty form (item 3)");
edit($id("cfg-threshold"), "0.9");
assert(beforeunload().defaultPrevented === true, "closing with pending edits is intercepted");
discardBtn().dispatch("click");
await settle();
assert(beforeunload().defaultPrevented === false, "closing a clean form is not intercepted");

section("beforeunload — in-flight session (item 3)");
hangFrames = true;
$id("btn-run").dispatch("click");
await settle();
assert(fetchCalls.some(c => c.method === "POST" && c.url === "/api/v1/sessions"),
    "the run created a session before blocking on frames");
assert(beforeunload().defaultPrevented === true,
    "closing or refreshing mid-check is intercepted while the session is in flight");

section("policy change history — risk classification (item 23)");
$id("btn-cfg-audit").dispatch("click");
await settle();
const history = () => $id("cfg-audit").textContent;
assert(history().includes("RESIDENT  UPDATED  by key:9f2c1a7b4e0d  [risk: HIGH]"),
    "a weakening edit shows its level on the entry line");
assert(history().includes("! passiveThreshold lowered"),
    "the reason names the weakening move, above the field diff");
assert(history().includes("passiveThreshold: 0.8 → 0.4"),
    "the field diff still renders under the risk line");
assert(history().includes("OPERATOR  UPDATED  by key:1a2b3c4d5e6f  [risk: LOW]"),
    "a benign edit shows LOW");
assert(history().includes("SUPERVISOR  CREATED  by key:abcdefabcdef  [risk: —]"),
    "an entry from before the classification shows an em dash, never a guessed level");

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
