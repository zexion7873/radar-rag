"use strict";

/**
 * A row as /ask returns it: a source, or a citation with the passages cited from it.
 * @typedef {{ id: string, source: string, repo?: string, title?: string, url?: string, week?: string,
 *   score?: number, citedText?: string[] }} Row
 * @typedef {{ answer: string, citations: Row[], sources: Row[],
 *   usage: { model: string, inputTokens: number, outputTokens: number } }} AskResponse
 */

/** Every id app.js asks for is in index.html. @param {string} id */
const $ = (id) => /** @type {HTMLElement} */ (document.getElementById(id));
const question = () => /** @type {HTMLTextAreaElement} */ ($("q"));
/** @type {string | null} */
let siteKey = null;
/** @type {string | undefined} */
let widgetId;
/** @type {string | null} */
let token = null;

/** @param {string} text */
function setStatus(text) {
  $("status").textContent = text;
}

function updateSubmit() {
  /** @type {HTMLButtonElement} */ ($("submit")).disabled = !token || !question().value.trim();
}

/** @param {number} status */
function messageFor(status) {
  switch (status) {
    case 429: return "問得太快了，請稍候再試（每人每分鐘 5 題、每天 20 題）。 · Too many questions; wait a moment.";
    case 403: return "機器人驗證沒有通過，請再試一次。 · The bot check failed; try again.";
    case 502:
    case 503: return "模型暫時無法回答，請稍後再試。 · The model is unavailable right now.";
    default: return `出了點問題（${status}），請再試一次。 · Something went wrong (${status}).`;
  }
}

// Called by the Turnstile script, which is loaded only once the site key is known.
window.onTurnstileLoad = () => {
  widgetId = window.turnstile.render("#turnstile", {
    sitekey: siteKey,
    callback: (/** @type {string} */ t) => { token = t; updateSubmit(); },
    "expired-callback": () => { token = null; updateSubmit(); },
    "error-callback": () => {
      token = null;
      updateSubmit();
      // Also fires when Cloudflare judges the browser automated (600xxx), where a reload rarely helps.
      setStatus("機器人驗證沒有通過，請重新整理，或換一個瀏覽器試試。 · The bot check did not pass; reload, or try another browser.");
    },
  });
};

/**
 * Appends text with **bold** and `code` runs as DOM nodes: model output never becomes HTML.
 * @param {HTMLElement} parent
 * @param {string} text
 */
function appendInline(parent, text) {
  for (const part of text.split(/(\*\*[^*]+\*\*|`[^`]+`)/)) {
    if (part.startsWith("**") && part.endsWith("**") && part.length > 4) {
      const strong = document.createElement("strong");
      strong.textContent = part.slice(2, -2);
      parent.append(strong);
    } else if (part.startsWith("`") && part.endsWith("`") && part.length > 2) {
      const code = document.createElement("code");
      code.textContent = part.slice(1, -1);
      parent.append(code);
    } else if (part) {
      parent.append(document.createTextNode(part));
    }
  }
}

/** @param {HTMLElement} container @param {string} text */
function renderAnswer(container, text) {
  container.replaceChildren();
  /** @type {HTMLUListElement | null} */
  let list = null;
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    const bullet = line.match(/^(?:[-*]|\d+\.)\s+(.*)$/);
    if (bullet) {
      if (!list) {
        list = document.createElement("ul");
        container.append(list);
      }
      const li = document.createElement("li");
      appendInline(li, bullet[1]);
      list.append(li);
      continue;
    }
    list = null;
    if (!line) continue;
    const heading = line.match(/^#{1,6}\s+(.*)$/);
    const p = document.createElement(heading ? "h3" : "p");
    appendInline(p, heading ? heading[1] : line);
    container.append(p);
  }
}

/** @param {Row} row */
function label(row) {
  return row.repo || row.title || row.url || row.id;
}

// github-radar-ui resolves any week's Notion page id to the repo's full history; it keeps these two
// route shapes stable for this page.
const RADAR_UI = "https://whyisthistrending.vercel.app";
const PAGE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** @param {unknown} url @returns {url is string} */
function isHttp(url) {
  return typeof url === "string" && /^https?:\/\//.test(url);
}

/** @param {string} href @param {string} text */
function anchor(href, text) {
  const a = document.createElement("a");
  a.href = href;
  a.textContent = text;
  a.rel = "noopener noreferrer";
  a.target = "_blank";
  return a;
}

/** @param {Row} row */
function detailPage(row) {
  if (!PAGE_ID.test(row.id ?? "")) return null;
  return `${RADAR_UI}/${row.source === "blog" ? "blog" : "trending"}/${row.id}`;
}

/** @param {Row} row */
function link(row) {
  const name = label(row);
  const page = detailPage(row);
  if (page) return anchor(page, name);
  if (isHttp(row.url)) return anchor(row.url, name);
  return document.createTextNode(name);
}

/** @param {HTMLElement} list @param {Row[]} rows @param {boolean} withQuotes */
function renderRows(list, rows, withQuotes) {
  list.replaceChildren();
  for (const row of rows) {
    const li = document.createElement("li");
    li.append(link(row));
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = ` · ${row.source === "blog" ? "blog" : "trending"} · ${row.week ?? ""}`;
    if (detailPage(row) && isHttp(row.url)) {
      meta.append(" · ", anchor(row.url, row.source === "blog" ? "原文 ↗" : "GitHub ↗"));
    }
    li.append(meta);
    if (withQuotes) {
      for (const quote of row.citedText ?? []) {
        const q = document.createElement("blockquote");
        q.textContent = quote;
        li.append(q);
      }
    }
    list.append(li);
  }
}

/** @param {AskResponse} data */
function render(data) {
  renderAnswer($("answer"), data.answer);
  renderRows($("citations"), data.citations, true);
  renderRows($("sources"), data.sources, false);
  $("usage").textContent = `${data.usage.model} · ${data.usage.inputTokens} in / ${data.usage.outputTokens} out tokens`;
  $("result").hidden = false;
}

/** @param {SubmitEvent} event */
async function ask(event) {
  event.preventDefault();
  const q = question().value.trim();
  if (!q || !token) return;
  const used = token;
  token = null;
  updateSubmit();
  $("result").hidden = true;
  setStatus("思考中，通常要 10 到 30 秒…… · Thinking, usually 10-30 s…");
  try {
    const res = await fetch("/ask", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Turnstile-Token": used },
      body: JSON.stringify({ q }),
    });
    if (!res.ok) {
      setStatus(messageFor(res.status));
      return;
    }
    render(await res.json());
    setStatus("");
  } catch {
    setStatus("連線失敗，請再試一次。 · The request failed; try again.");
  } finally {
    // Each token verifies once, so the next question needs a fresh one.
    window.turnstile.reset(widgetId);
  }
}

async function init() {
  $("ask-form").addEventListener("submit", ask);
  $("q").addEventListener("input", updateSubmit);
  for (const example of document.querySelectorAll(".example")) {
    example.addEventListener("click", () => {
      question().value = example.textContent ?? "";
      updateSubmit();
    });
  }
  try {
    const res = await fetch("/config");
    siteKey = (await res.json()).turnstileSiteKey;
  } catch {
    setStatus("服務暫時無法連線，請稍後再試。 · The service is unreachable right now.");
    return;
  }
  const script = document.createElement("script");
  script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=onTurnstileLoad";
  script.async = true;
  document.head.append(script);
}

init();
