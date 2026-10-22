const assert = require("node:assert/strict");
const test = require("node:test");

const hook = require("../src/boss-api-hook.js");

const PAGE = "https://www.zhipin.com/web/geek/job?query=Java&city=101280600";
const LIST_URL = "/wapi/zpgeek/search/joblist.json?scene=1&query=Java&city=101280600&page=1";
const BODY = JSON.stringify({ code: 0, zpData: { jobList: [] } });

/** A page window whose fetch answers from a table and records every call it really makes. */
function fakeWindow(responses = {}) {
  const calls = [];
  const win = {
    location: { href: PAGE },
    fetch(input, init) {
      calls.push([input, init]);
      const url = typeof input === "string" ? input : input.url;
      const key = Object.keys(responses).find((prefix) => url.startsWith(prefix));
      if (!key) return Promise.reject(new TypeError("network down"));
      return Promise.resolve(new Response(responses[key], { status: 200 }));
    },
    // A subclass per window, so each test patches its own prototype.
    XMLHttpRequest: class extends FakeXhr {},
  };
  return { win, calls };
}

class FakeXhr {
  constructor() {
    this.listeners = {};
    this.responseType = "";
    this.sent = [];
  }
  open(method, url) {
    this.opened = [method, url];
  }
  send(body) {
    this.sent.push(body);
  }
  addEventListener(type, listener) {
    (this.listeners[type] ||= []).push(listener);
  }
  respond(value) {
    if (this.responseType === "json") this.response = value;
    else this.responseText = value;
    (this.listeners.load || []).forEach((listener) => listener());
  }
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

function drain(win) {
  return win[hook.BUFFER_KEY].drain();
}

test("fetch: the page's own list request is copied, and the page still reads the same response", async () => {
  const { win, calls } = fakeWindow({ "/wapi/": BODY });
  hook.install(win);
  const init = { credentials: "include" };
  const response = await win.fetch(LIST_URL, init);

  assert.equal(await response.text(), BODY);
  assert.deepEqual(calls, [[LIST_URL, init]]);
  await settle();
  assert.deepEqual(drain(win), [{ path: "/wapi/zpgeek/search/joblist.json", body: BODY }]);
  assert.deepEqual(drain(win), [], "draining empties the buffer");
});

test("fetch: other requests pass through untouched and are not copied", async () => {
  const { win, calls } = fakeWindow({ "/wapi/": BODY, "https://img.bosszhipin.com/": "img" });
  hook.install(win);
  await win.fetch("/wapi/zpgeek/friend/add.json?id=1");
  await win.fetch(new Request("https://img.bosszhipin.com/logo.png"));
  await settle();

  assert.equal(calls.length, 2);
  assert.deepEqual(drain(win), []);
});

test("fetch: a failed request still rejects for the page and leaves nothing behind", async () => {
  const { win } = fakeWindow({});
  hook.install(win);
  await assert.rejects(win.fetch(LIST_URL), TypeError);
  await settle();
  assert.deepEqual(drain(win), []);
});

test("XHR: list responses are copied for text and json response types", () => {
  const { win } = fakeWindow();
  hook.install(win);
  const text = new win.XMLHttpRequest();
  text.open("GET", LIST_URL);
  text.send(null);
  text.respond(BODY);

  const json = new win.XMLHttpRequest();
  json.responseType = "json";
  json.open("GET", `https://www.zhipin.com${LIST_URL}`);
  json.send(null);
  json.respond(JSON.parse(BODY));

  const other = new win.XMLHttpRequest();
  other.open("POST", "/wapi/zpgeek/other.json");
  other.send("x");
  other.respond("{}");

  assert.deepEqual(text.opened, ["GET", LIST_URL]);
  assert.deepEqual(other.sent, ["x"]);
  assert.deepEqual(drain(win).map((entry) => entry.body), [BODY, BODY]);
});

test("the query string with the user's search is never kept", async () => {
  const { win } = fakeWindow({ "/wapi/": BODY });
  hook.install(win);
  await win.fetch(LIST_URL);
  await settle();
  assert.doesNotMatch(JSON.stringify(drain(win)), /query=|city=/);
});

test("the buffer is bounded in entry count and body size", async () => {
  const { win } = fakeWindow({ "/wapi/zpgeek/search/joblist.json?page=big": "x".repeat(hook.MAX_BODY_CHARS + 1), "/wapi/": BODY });
  hook.install(win);
  for (let index = 0; index < hook.MAX_ENTRIES + 5; index += 1) await win.fetch(LIST_URL);
  await win.fetch("/wapi/zpgeek/search/joblist.json?page=big");
  await settle();
  assert.equal(drain(win).length, hook.MAX_ENTRIES);
});

test("only BOSS hosts over https count as the list API", () => {
  assert.equal(hook.listApiPath(LIST_URL, PAGE), "/wapi/zpgeek/search/joblist.json");
  assert.equal(hook.listApiPath("https://evil.example/wapi/zpgeek/search/joblist.json", PAGE), null);
  assert.equal(hook.listApiPath("http://www.zhipin.com/wapi/zpgeek/search/joblist.json", PAGE), null);
  assert.equal(hook.listApiPath("/wapi/zpgeek/search/joblist.jsonx", PAGE), null);
});

test("installing twice wraps fetch only once", () => {
  const { win } = fakeWindow();
  hook.install(win);
  const wrapped = win.fetch;
  hook.install(win);
  assert.equal(win.fetch, wrapped);
});

test("stop restores fetch and XHR, clears buffered responses and permits another task", async () => {
  const { win } = fakeWindow({ "/wapi/": BODY });
  const original = { fetch: win.fetch, open: win.XMLHttpRequest.prototype.open, send: win.XMLHttpRequest.prototype.send };
  hook.install(win);
  const capture = win[hook.BUFFER_KEY];
  await win.fetch(LIST_URL);
  await settle();
  capture.stop();

  assert.equal(win.fetch, original.fetch);
  assert.equal(win.XMLHttpRequest.prototype.open, original.open);
  assert.equal(win.XMLHttpRequest.prototype.send, original.send);
  assert.deepEqual(capture.drain(), []);
  assert.equal(win[hook.BUFFER_KEY], undefined);
  await win.fetch(LIST_URL);
  await settle();
  assert.deepEqual(capture.drain(), []);

  hook.install(win);
  capture.stop(); // A stale cleanup must not stop the new task.
  await win.fetch(LIST_URL);
  await settle();
  assert.deepEqual(drain(win), [{ path: "/wapi/zpgeek/search/joblist.json", body: BODY }]);
});

test("responses already in flight cannot refill the buffer after stop", async () => {
  let respond;
  let cloned = false;
  const { win } = fakeWindow();
  win.fetch = () => new Promise((resolve) => { respond = resolve; });
  hook.install(win);
  const capture = win[hook.BUFFER_KEY];
  const pending = win.fetch(LIST_URL);
  const xhr = new win.XMLHttpRequest();
  xhr.open("GET", LIST_URL);
  xhr.send(null);
  capture.stop();
  respond({ clone() { cloned = true; return new Response(BODY); } });
  await pending;
  xhr.respond(BODY);
  await settle();

  assert.equal(cloned, false);
  assert.deepEqual(capture.drain(), []);
});

test("a body read started before stop cannot refill the cleared buffer", async () => {
  let finishBody;
  const { win } = fakeWindow();
  win.fetch = async () => ({ clone: () => ({ text: () => new Promise((resolve) => { finishBody = resolve; }) }) });
  hook.install(win);
  const capture = win[hook.BUFFER_KEY];
  await win.fetch(LIST_URL);
  capture.stop();
  finishBody(BODY);
  await settle();
  assert.deepEqual(capture.drain(), []);
});

test("stopping leaves the page's later wrappers intact but disables capture through them", async () => {
  const { win } = fakeWindow({ "/wapi/": BODY });
  hook.install(win);
  const capture = win[hook.BUFFER_KEY];
  const installed = { fetch: win.fetch, open: win.XMLHttpRequest.prototype.open, send: win.XMLHttpRequest.prototype.send };
  const laterFetch = (...args) => installed.fetch(...args);
  const laterOpen = function (...args) { return installed.open.apply(this, args); };
  const laterSend = function (...args) { return installed.send.apply(this, args); };
  win.fetch = laterFetch;
  win.XMLHttpRequest.prototype.open = laterOpen;
  win.XMLHttpRequest.prototype.send = laterSend;
  capture.stop();

  assert.equal(win.fetch, laterFetch);
  assert.equal(win.XMLHttpRequest.prototype.open, laterOpen);
  assert.equal(win.XMLHttpRequest.prototype.send, laterSend);
  await win.fetch(LIST_URL);
  const xhr = new win.XMLHttpRequest();
  xhr.open("GET", LIST_URL);
  xhr.send(null);
  xhr.respond(BODY);
  await settle();
  assert.deepEqual(capture.drain(), []);
});
