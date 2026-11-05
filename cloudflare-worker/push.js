























const ANILIST_ENDPOINT = "https://graphql.anilist.co";
const FCM_SEND = "https://fcm.googleapis.com/v1/projects";


const TOPIC_ANIME = "anime-";
const TOPIC_TV = "tv-";




const AIRING_LOOKAHEAD = 90; 
const AIRING_MAX_LOOKBACK = 6 * 3600; 
const ANILIST_PAGE = 100;
const MAX_ANILIST_PAGES = 3;




const MAX_TMDB_CHECKS = 15;
const TMDB_STALE = 12 * 3600;
const TMDB_WINDOW_DAYS = 3;

const MAX_TOPIC_SENDS = 30; 
const TOPIC_CALL_BUDGET = 35; 
const SENT_KEEP = 3 * 86400; 

const ANILIST_SINCE_KEY = "anilistSince";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));



function b64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\
}

function b64urlStr(str) {
  return b64url(new TextEncoder().encode(str));
}

function pemToDer(pem) {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function signJwt(payload, privateKeyPem) {
  const input =
    b64urlStr(JSON.stringify({ alg: "RS256", typ: "JWT" })) +
    "." +
    b64urlStr(JSON.stringify(payload));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(privateKeyPem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(input),
  );
  return input + "." + b64url(new Uint8Array(sig));
}


let cachedAccess = { value: null, exp: 0 };
let cfgCache = null;
let anilistProxy = "";
let lastAniListError = null;

async function loadConfig(env) {
  if (cfgCache) return cfgCache;
  let kv = null;
  try {
    kv = (await env.SUBS.get("cfg:config", { type: "json" })) || {};
  } catch {
    kv = {};
  }
  const cfg = {
    projectId: kv.projectId || env.FCM_PROJECT_ID || "",
    clientEmail: kv.clientEmail || env.FCM_CLIENT_EMAIL || "",
    privateKey: kv.privateKey || env.FCM_PRIVATE_KEY || "",
    adminKey: kv.adminKey || env.ADMIN_KEY || "",
    tmdbKey: kv.tmdbKey || env.TMDB_API_KEY || "",
    anilistProxy: kv.anilistProxy || env.ANILIST_PROXY || "",
  };
  anilistProxy = cfg.anilistProxy;
  cfgCache = cfg;
  return cfg;
}

async function getAccessToken(cfg) {
  if (cachedAccess.value && Date.now() < cachedAccess.exp - 60_000) {
    return cachedAccess.value;
  }
  const now = Math.floor(Date.now() / 1000);
  const jwt = await signJwt(
    {
      iss: cfg.clientEmail,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    },
    cfg.privateKey,
  );
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
  });
  if (!res.ok) {
    throw new Error(`token exchange failed: ${res.status} ${await res.text()}`);
  }
  const data = await res.json();
  cachedAccess = {
    value: data.access_token,
    exp: Date.now() + (data.expires_in || 3600) * 1000,
  };
  return cachedAccess.value;
}



function topicFor(kind, id) {
  return (kind === "a" ? TOPIC_ANIME : TOPIC_TV) + id;
}


async function topicSubscribe(cfg, token, topic) {
  const access = await getAccessToken(cfg);
  const res = await fetch(
    `${FCM_SEND}/${cfg.projectId}/registrations/${encodeURIComponent(token)}` +
      `/topicSubscriptions?topic_name=${encodeURIComponent(topic)}`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
      body: "{}",
    },
  );
  if (res.ok || res.status === 409) return "ok"; 
  const text = await res.text();
  if (res.status === 404 || /UNREGISTERED|INVALID_REGISTRATION|NOT_FOUND/i.test(text)) {
    return "dead";
  }
  console.log(`topicSubscribe ${res.status}: ${text}`);
  return "error";
}

async function topicUnsubscribe(cfg, token, name) {
  const access = await getAccessToken(cfg);
  const res = await fetch(
    `${FCM_SEND}/${cfg.projectId}/registrations/${encodeURIComponent(token)}/topicSubscriptions/${encodeURIComponent(name)}`,
    {
      method: "DELETE",
      headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
      body: "{}",
    },
  );
  if (res.ok || res.status === 404) return "ok";
  const text = await res.text();
  if (/UNREGISTERED|INVALID_REGISTRATION/.test(text)) return "dead";
  console.log(`topicUnsubscribe ${res.status}: ${text}`);
  return "error";
}


async function sendTopic(cfg, topic, payload) {
  const access = await getAccessToken(cfg);
  const res = await fetch(`${FCM_SEND}/${cfg.projectId}/messages:send`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${access}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ message: { topic, ...payload } }),
  });
  if (res.ok) return true;
  console.log(`sendTopic ${topic} ${res.status}: ${await res.text()}`);
  return false;
}


async function sendToken(cfg, token, payload) {
  const access = await getAccessToken(cfg);
  const res = await fetch(`${FCM_SEND}/${cfg.projectId}/messages:send`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${access}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ message: { token, ...payload } }),
  });
  if (res.ok) return "ok";
  const text = await res.text();
  if (res.status === 404 || /UNREGISTERED|INVALID_REGISTRATION|NOT_FOUND/.test(text)) {
    return "dead";
  }
  console.log(`FCM ${res.status}: ${text}`);
  return "error";
}

function notificationPayload(title, body, image, data) {
  return {
    notification: { title, body, ...(image ? { image } : {}) },
    data: Object.fromEntries(
      Object.entries(data).map(([k, v]) => [k, String(v)]),
    ),
    android: {
      priority: "high",
      notification: {
        channel_id: "anime_push",
        icon: "notification_icon",
        ...(image ? { image } : {}),
        color: "#039BE5",
        click_action: "ani.sanin.OPEN_MEDIA",
        visibility: "public",
      },
    },
  };
}



async function anilistPost(body) {
  const init = {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      "User-Agent": "Sanin-Push/1.0 (+https://github.com/Shippunn/sanin)",
    },
    body: JSON.stringify(body),
  };
  const endpoint = anilistProxy || ANILIST_ENDPOINT;
  const ATTEMPTS = 3;
  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    let res;
    try {
      res = await fetch(endpoint, init);
    } catch (e) {
      lastAniListError = `fetch: ${e}`;
      if (attempt < ATTEMPTS) {
        await sleep(400 * attempt);
        continue;
      }
      return null;
    }
    if (res.status === 403 || res.status === 429 || res.status >= 500) {
      lastAniListError = `http ${res.status}`;
      if (attempt < ATTEMPTS) {
        await sleep(400 * attempt);
        continue;
      }
      console.log(`AniList ${res.status}`);
      return null;
    }
    if (!res.ok) {
      lastAniListError = `http ${res.status}`;
      return null;
    }
    const jsonBody = await res.json();
    if (jsonBody.errors) {
      lastAniListError = JSON.stringify(jsonBody.errors);
      return null;
    }
    lastAniListError = null;
    return jsonBody.data;
  }
  return null;
}


async function airingRange(from, to, page) {
  const query = `query ($page: Int, $perPage: Int, $from: Int, $to: Int) {
    Page(page: $page, perPage: $perPage) {
      pageInfo { hasNextPage }
      airingSchedules(airingAt_greater: $from, airingAt_lesser: $to, sort: TIME) {
        mediaId
        episode
        airingAt
        media { title { romaji english native } coverImage { large } }
      }
    }
  }`;
  const data = await anilistPost({
    query,
    variables: { page, perPage: ANILIST_PAGE, from, to },
  });
  if (!data) return { list: [], hasNext: false };
  return {
    list: data.Page?.airingSchedules || [],
    hasNext: Boolean(data.Page?.pageInfo?.hasNextPage),
  };
}



function today() {
  return new Date().toISOString().slice(0, 10);
}

async function tvLookup(cfg, id) {
  const res = await fetch(
    `https://api.themoviedb.org/3/tv/${id}?api_key=${cfg.tmdbKey}&language=en-US`,
  );
  if (!res.ok) return null;
  const show = await res.json();
  const ep = show.last_episode_to_air || null;
  const next = show.next_episode_to_air || null;
  return {
    name: show.name,
    poster: show.poster_path
      ? `https://image.tmdb.org/t/p/w500${show.poster_path}`
      : "",
    season: ep ? ep.season_number : null,
    episode: ep ? ep.episode_number : null,
    airDate: ep ? ep.air_date : null,
    episodeTitle: ep ? ep.name || "" : "",
    nextAir: next ? next.air_date : null,
  };
}



function parseJson(raw, fallback) {
  try {
    const v = JSON.parse(raw);
    return v == null ? fallback : v;
  } catch {
    return fallback;
  }
}

async function getMeta(env, k) {
  const row = await env.DB.prepare("SELECT v FROM meta WHERE k = ?").bind(k).first();
  return row ? row.v : null;
}

async function setMeta(env, k, v) {
  await env.DB.prepare(
    "INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
  )
    .bind(k, v)
    .run();
}


async function loadFollows(env) {
  const { results } = await env.DB.prepare(
    "SELECT kind, media_id FROM follow WHERE n > 0",
  ).all();
  const a = new Set();
  const t = new Set();
  for (const r of results || []) (r.kind === "a" ? a : t).add(r.media_id);
  return { a, t };
}

async function followAdd(env, kind, ids) {
  if (!ids.length) return;
  await env.DB.batch(
    ids.map((id) =>
      env.DB.prepare(
        "INSERT INTO follow (kind, media_id, n) VALUES (?, ?, 1) " +
          "ON CONFLICT(kind, media_id) DO UPDATE SET n = n + 1",
      ).bind(kind, id),
    ),
  );
}

async function followRemove(env, kind, ids) {
  if (!ids.length) return;
  await env.DB.batch(
    ids.map((id) =>
      env.DB.prepare(
        "UPDATE follow SET n = MAX(0, n - 1) WHERE kind = ? AND media_id = ?",
      ).bind(kind, id),
    ),
  );
}

async function followDiff(env, kind, prev, next) {
  const p = new Set(prev);
  const n = new Set(next);
  await followAdd(env, kind, next.filter((x) => !p.has(x)));
  await followRemove(env, kind, prev.filter((x) => !n.has(x)));
}

async function sentHas(env, ref) {
  const row = await env.DB.prepare("SELECT 1 FROM sent WHERE ref = ?").bind(ref).first();
  return Boolean(row);
}

async function markSent(env, ref) {
  await env.DB.prepare("INSERT OR IGNORE INTO sent (ref, ts) VALUES (?, ?)")
    .bind(ref, Math.floor(Date.now() / 1000))
    .run();
}

async function pruneSent(env) {
  await env.DB.prepare("DELETE FROM sent WHERE ts < ?")
    .bind(Math.floor(Date.now() / 1000) - SENT_KEEP)
    .run();
}

async function dueTvShows(env, limit) {
  const stale = Math.floor(Date.now() / 1000) - TMDB_STALE;
  const { results } = await env.DB.prepare(
    "SELECT f.media_id AS id, t.season AS season, t.episode AS episode, " +
      "t.next_air_date AS next_air, t.checked_at AS checked_at " +
      "FROM follow f LEFT JOIN tv t ON t.id = f.media_id " +
      "WHERE f.kind = 't' AND f.n > 0 AND (t.id IS NULL OR t.checked_at < ?1 " +
      "OR (t.next_air_date IS NOT NULL AND t.next_air_date <= ?2)) " +
      "ORDER BY COALESCE(t.checked_at, 0) ASC LIMIT ?3",
  )
    .bind(stale, today(), limit)
    .all();
  return results || [];
}

async function saveTv(env, id, info) {
  await env.DB.prepare(
    "INSERT INTO tv (id, season, episode, air_date, next_air_date, checked_at) " +
      "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET " +
      "season = excluded.season, episode = excluded.episode, air_date = excluded.air_date, " +
      "next_air_date = excluded.next_air_date, checked_at = excluded.checked_at",
  )
    .bind(id, info.season, info.episode, info.airDate, info.nextAir, Math.floor(Date.now() / 1000))
    .run();
}

async function getSub(env, token) {
  return env.DB.prepare(
    "SELECT anilist, tmdb, tsynced, created FROM subs WHERE token = ?",
  )
    .bind(token)
    .first();
}

async function putSub(env, token, anilist, tmdb, tsynced, created) {
  await env.DB.prepare(
    "INSERT INTO subs (token, anilist, tmdb, noted, tsynced, created) VALUES (?, ?, ?, '{}', ?, ?) " +
      "ON CONFLICT(token) DO UPDATE SET anilist = excluded.anilist, tmdb = excluded.tmdb, " +
      "tsynced = excluded.tsynced",
  )
    .bind(token, JSON.stringify(anilist), JSON.stringify(tmdb), JSON.stringify(tsynced), created)
    .run();
}

async function deleteSub(env, token) {
  await env.DB.prepare("DELETE FROM subs WHERE token = ?").bind(token).run();
}




async function syncTopics(cfg, token, alreadySynced, desired, budget = TOPIC_CALL_BUDGET) {
  const desiredSet = new Set(desired);
  const synced = new Set();
  let calls = 0;

  for (const topic of alreadySynced) {
    if (desiredSet.has(topic)) {
      synced.add(topic);
      continue;
    }
    if (calls >= budget) {
      synced.add(topic); 
      continue;
    }
    calls++;
    const r = await topicUnsubscribe(cfg, token, topic);
    if (r === "dead") return { synced, dead: true };
    if (r === "ok") continue; 
    synced.add(topic); 
  }

  for (const topic of desired) {
    if (synced.has(topic)) continue;
    if (calls >= budget) break;
    calls++;
    const r = await topicSubscribe(cfg, token, topic);
    if (r === "dead") return { synced, dead: true };
    if (r === "ok") synced.add(topic);
  }

  return { synced, dead: false };
}



function topicName(kind, id) {
  return (kind === "a" ? TOPIC_ANIME : TOPIC_TV) + id;
}

async function sweepAniList(env, cfg, followed, budget) {
  const now = Math.floor(Date.now() / 1000);
  let since = parseInt((await getMeta(env, ANILIST_SINCE_KEY)) || "", 10);
  if (!Number.isFinite(since) || since < now - AIRING_MAX_LOOKBACK) {
    since = now - 300;
  }
  const to = now + AIRING_LOOKAHEAD;

  const aired = [];
  for (let page = 1; page <= MAX_ANILIST_PAGES; page++) {
    const { list, hasNext } = await airingRange(since, to, page);
    aired.push(...list);
    if (!hasNext) break;
  }
  await setMeta(env, ANILIST_SINCE_KEY, String(to));

  let sent = 0;
  for (const a of aired) {
    if (!a || !(a.episode > 0)) continue;
    if (!followed.a.has(a.mediaId)) continue;
    const ref = `a:${a.mediaId}:${a.episode}`;
    if (await sentHas(env, ref)) continue;
    if (sent >= budget) break;
    const title =
      a.media?.title?.english || a.media?.title?.romaji || a.media?.title?.native || "New episode";
    const image = a.media?.coverImage?.large || "";
    const ok = await sendTopic(
      cfg,
      topicName("a", a.mediaId),
      notificationPayload(title, `Episode ${a.episode}`, image, {
        type: "airing",
        source: "anilist",
        mediaId: a.mediaId,
        episode: a.episode,
      }),
    );
    if (ok) {
      await markSent(env, ref);
      sent++;
    }
    if (sent >= budget) break;
  }
  return { aired: aired.length, sent };
}

async function sweepTmdb(env, cfg, budget) {
  if (!cfg.tmdbKey) return { checked: 0, sent: 0 };
  const cutoff = new Date(Date.now() - TMDB_WINDOW_DAYS * 86400_000)
    .toISOString()
    .slice(0, 10);
  const rows = await dueTvShows(env, MAX_TMDB_CHECKS);

  let checked = 0;
  let sent = 0;
  for (const row of rows) {
    const info = await tvLookup(cfg, row.id);
    checked++;
    if (!info) continue;

    const prevKnown = row.checked_at != null;
    const changed =
      prevKnown && (info.season !== row.season || info.episode !== row.episode);
    const fresh = info.airDate && info.airDate >= cutoff;

    await saveTv(env, row.id, info);

    if (!changed || !fresh || !(info.episode > 0)) continue;
    const ref = `t:${row.id}:${info.season}x${info.episode}`;
    if (await sentHas(env, ref)) continue;
    if (sent >= budget) break;

    const ok = await sendTopic(
      cfg,
      topicName("t", row.id),
      notificationPayload(
        info.name || "New episode",
        `S${info.season}E${info.episode}${info.episodeTitle ? ` - ${info.episodeTitle}` : ""}`,
        info.poster,
        {
          type: "airing",
          source: "tmdb",
          tmdbId: row.id,
          season: info.season,
          episode: info.episode,
        },
      ),
    );
    if (ok) {
      await markSent(env, ref);
      sent++;
    }
  }
  return { checked, sent };
}

async function runSweep(env) {
  const cfg = await loadConfig(env);
  await pruneSent(env);

  const followed = await loadFollows(env);
  const anilist = await sweepAniList(env, cfg, followed, MAX_TOPIC_SENDS);
  const tmdb = await sweepTmdb(env, cfg, MAX_TOPIC_SENDS - anilist.sent);

  return {
    follows: { anilist: followed.a.size, tv: followed.t.size },
    airing: anilist.aired,
    anilistSent: anilist.sent,
    tmdbChecked: tmdb.checked,
    tmdbSent: tmdb.sent,
    anilist: lastAniListError ? `error: ${lastAniListError}` : "ok",
  };
}



export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, time: Date.now() });
    }

    if (request.method === "POST" && url.pathname === "/subscribe") {
      let body;
      try {
        body = await request.json();
      } catch {
        return json({ error: "bad json" }, 400);
      }
      const token = typeof body.token === "string" ? body.token.trim() : "";
      if (!token || token.length < 80 || token.length > 600) {
        return json({ error: "bad token" }, 400);
      }
      const anilist = Array.isArray(body.anilist)
        ? [...new Set(body.anilist.filter((x) => Number.isInteger(x)))]
        : [];
      const tmdb = Array.isArray(body.tmdb)
        ? [...new Set(body.tmdb.filter((x) => Number.isInteger(x)))]
        : [];

      const cfg = await loadConfig(env);
      const existing = await getSub(env, token);
      const prevA = existing ? parseJson(existing.anilist, []) : [];
      const prevM = existing ? parseJson(existing.tmdb, []) : [];
      const alreadySynced = existing && existing.tsynced ? parseJson(existing.tsynced, []) : [];
      const created = existing && existing.created ? existing.created : Math.floor(Date.now() / 1000);

      await followDiff(env, "a", prevA, anilist);
      await followDiff(env, "t", prevM, tmdb);

      const desired = [
        ...anilist.map((id) => topicName("a", id)),
        ...tmdb.map((id) => topicName("t", id)),
      ];
      const { synced, dead } = await syncTopics(cfg, token, alreadySynced, desired);

      if (dead) {
        await followRemove(env, "a", anilist);
        await followRemove(env, "t", tmdb);
        await deleteSub(env, token);
        return json({ ok: true, dead: true });
      }

      await putSub(env, token, anilist, tmdb, [...synced], created);
      return json({
        ok: true,
        anilist: anilist.length,
        tmdb: tmdb.length,
        topics: synced.size,
        pending: desired.length - synced.size,
      });
    }

    if (request.method === "POST" && url.pathname === "/unsubscribe") {
      let body;
      try {
        body = await request.json();
      } catch {
        return json({ error: "bad json" }, 400);
      }
      const token = typeof body.token === "string" ? body.token.trim() : "";
      if (!token) return json({ ok: true });
      const cfg = await loadConfig(env);
      const existing = await getSub(env, token);
      if (existing) {
        const a = parseJson(existing.anilist, []);
        const m = parseJson(existing.tmdb, []);
        await followRemove(env, "a", a);
        await followRemove(env, "t", m);
        const synced = parseJson(existing.tsynced, []);
        const { dead } = await syncTopics(cfg, token, synced, []);
        void dead;
      }
      await deleteSub(env, token);
      return json({ ok: true });
    }

    if (request.method === "GET" && url.pathname === "/count") {
      const row = await env.DB.prepare("SELECT COUNT(*) AS c FROM subs").first();
      return json({ subscriptions: row ? row.c : 0 });
    }

    if (request.method === "GET" && url.pathname === "/debug") {
      const cfg = await loadConfig(env);
      if (cfg.adminKey && url.searchParams.get("key") !== cfg.adminKey) {
        return json({ error: "forbidden" }, 403);
      }
      const out = {
        fcm: "skipped",
        anilist: null,
        proxy: anilistProxy || "(direct)",
        tmdb: Boolean(cfg.tmdbKey),
        cfg: {
          projectSet: Boolean(cfg.projectId),
          emailSet: Boolean(cfg.clientEmail),
          key: cfg.privateKey ? { len: cfg.privateKey.length } : null,
        },
      };
      try {
        await getAccessToken(cfg);
        out.fcm = "ok";
      } catch (e) {
        out.fcm = String(e.message || e);
      }
      try {
        const now = Math.floor(Date.now() / 1000);
        const { list } = await airingRange(now - 3600, now + AIRING_LOOKAHEAD, 1);
        out.anilist = `${list.length} airing${
          lastAniListError ? ` (last error: ${lastAniListError})` : ""
        }`;
      } catch (e) {
        out.anilist = String(e.message || e);
      }
      const subs = await env.DB.prepare("SELECT COUNT(*) AS c FROM subs").first();
      const follows = await env.DB.prepare(
        "SELECT COUNT(*) AS c FROM follow WHERE n > 0",
      ).first();
      out.subscriptions = subs ? subs.c : 0;
      out.follows = follows ? follows.c : 0;
      return json(out);
    }

    
    if (request.method === "GET" && url.pathname === "/resync") {
      const cfg = await loadConfig(env);
      if (cfg.adminKey && url.searchParams.get("key") !== cfg.adminKey) {
        return json({ error: "forbidden" }, 403);
      }
      
      
      const after = url.searchParams.get("after") || "";
      const { results } = await env.DB.prepare(
        "SELECT token, anilist, tmdb, tsynced FROM subs WHERE token > ? ORDER BY token LIMIT 1",
      )
        .bind(after)
        .all();
      const r = (results || [])[0];
      if (!r) return json({ done: true, next: null });

      const desired = [
        ...parseJson(r.anilist, []).map((id) => topicName("a", id)),
        ...parseJson(r.tmdb, []).map((id) => topicName("t", id)),
      ];
      const already = parseJson(r.tsynced, []);
      const res = await syncTopics(cfg, r.token, already, desired);
      if (res.dead) {
        await deleteSub(env, r.token);
        return json({ dead: true, next: r.token });
      }
      await env.DB.prepare("UPDATE subs SET tsynced = ? WHERE token = ?")
        .bind(JSON.stringify([...res.synced]), r.token)
        .run();
      const pending = desired.length - res.synced.size;
      return json({
        token: String(r.token).slice(0, 12),
        topics: res.synced.size,
        pending,
        next: pending > 0 ? after || "" : r.token,
        done: false,
      });
    }

    if (
      (request.method === "GET" || request.method === "POST") &&
      url.pathname === "/sweep"
    ) {
      const cfg = await loadConfig(env);
      if (cfg.adminKey && url.searchParams.get("key") !== cfg.adminKey) {
        return json({ error: "forbidden" }, 403);
      }
      return json(await runSweep(env));
    }

    if (request.method === "GET" && url.pathname === "/test") {
      const cfg = await loadConfig(env);
      if (cfg.adminKey && url.searchParams.get("key") !== cfg.adminKey) {
        return json({ error: "forbidden" }, 403);
      }
      const only = url.searchParams.get("token");
      const topic = url.searchParams.get("topic");
      const payload = notificationPayload(
        "Sanin push test",
        "If you can see this, notifications are working.",
        "",
        { type: "test" },
      );
      if (topic) {
        const ok = await sendTopic(cfg, topic, payload);
        return json({ topic, ok });
      }
      if (only) {
        return json({ token: only.slice(0, 16), result: await sendToken(cfg, only, payload) });
      }
      const { results } = await env.DB.prepare(
        "SELECT token FROM subs ORDER BY token LIMIT 1000",
      ).all();
      const sent = [];
      for (const r of results || []) {
        sent.push({
          token: String(r.token).slice(0, 16),
          result: await sendToken(cfg, r.token, payload),
        });
      }
      return json({ count: sent.length, sent });
    }

    return json({ error: "not found" }, 404);
  },

  async scheduled(_event, env) {
    const result = await runSweep(env);
    console.log(`sweep: ${JSON.stringify(result)}`);
    return result;
  },
};
