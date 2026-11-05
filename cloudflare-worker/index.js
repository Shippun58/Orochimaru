const ANIVAULT_BASE = "https://www.anivault.co/api/mobile";
const TTL_AIRING = 21600;    
const TTL_COMPLETED = 86400; 

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const anilistId = url.searchParams.get("anilist_id");
    const title = url.searchParams.get("q");
    const debug = url.searchParams.has("debug");

    if (!anilistId && (!title || title.trim().length === 0)) {
      return new Response(JSON.stringify({ error: "provide ?anilist_id= or ?q=" }), {
        status: 400,
        headers: { "Content-Type": "application/json" },
      });
    }

    
    const cacheKey = anilistId
      ? `al:${anilistId}`
      : `q:${title.toLowerCase().trim()}`;

    
    const cached = await env.CACHE.get(cacheKey, { type: "json" });
    if (cached && !debug) {
      return new Response(JSON.stringify(cached), {
        headers: { "Content-Type": "application/json", "X-Cache": "HIT" },
      });
    }

    let result;

    
    if (anilistId) {
      const resp = await fetch(`${ANIVAULT_BASE}/anime/${anilistId}`, {
        headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36" },
      });
      if (!resp.ok) {
        return new Response(JSON.stringify({ sub: 0, dub: 0, total: 0 }), {
          headers: { "Content-Type": "application/json" },
        });
      }
      const json = await resp.json();
      const anime = json.anime;
      if (!anime) {
        return new Response(JSON.stringify({ sub: 0, dub: 0, total: 0 }), {
          headers: { "Content-Type": "application/json" },
        });
      }
      const total = anime.totalEpisodes || 0;
      const aired = anime.airedSoFar || 0;
      const dubCount = (anime.dubbedLangs || []).length;
      
      const sub = aired > 0 ? Math.min(aired, total || aired) : total;
      const dub = dubCount > 0 ? sub : 0;
      const isAiring = anime.isAiring || false;
      result = { sub, dub, total };
      const ttl = isAiring ? TTL_AIRING : TTL_COMPLETED;
      await env.CACHE.put(cacheKey, JSON.stringify(result), { expirationTtl: ttl });

      if (debug) {
        return new Response(JSON.stringify({
          ...result,
          debug: { source: "anilist_id", title: anime.title, isAiring, dubbedLangs: anime.dubbedLangs || [] }
        }, null, 2), { headers: { "Content-Type": "application/json" } });
      }
      return new Response(JSON.stringify(result), {
        headers: { "Content-Type": "application/json", "X-Cache": "MISS" },
      });
    }

    
    const encoded = encodeURIComponent(title);
    const resp = await fetch(`${ANIVAULT_BASE}/browse?q=${encoded}`, {
      headers: { "User-Agent": "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36" },
    });
    if (!resp.ok) {
      return new Response(JSON.stringify({ sub: 0, dub: 0, total: 0 }), {
        headers: { "Content-Type": "application/json" },
      });
    }
    const json = await resp.json();
    const arr = json.data;
    if (!arr || arr.length === 0) {
      return new Response(JSON.stringify({ sub: 0, dub: 0, total: 0 }), {
        headers: { "Content-Type": "application/json" },
      });
    }

    
    const titleLower = title.toLowerCase().trim();
    let best = null;
    for (const item of arr) {
      const itemTitle = (item.title || "").toLowerCase().trim();
      const eps = item.episodes || 0;
      const type = item.type || "";
      const isTv = type.toLowerCase() === "tv";
      const dubCount = (item.dubbedLangs || []).length;
      let titleScore = 0;
      if (itemTitle === titleLower) titleScore = 100;
      else if (itemTitle.startsWith(titleLower) || titleLower.startsWith(itemTitle)) titleScore = 50;
      const tvScore = isTv ? 30 : 0;
      const dubScore = dubCount > 0 ? 10 : 0;
      const score = titleScore + tvScore + dubScore + (eps > 0 ? 1 : 0);
      if (!best || score > best.score || (score === best.score && eps > best.eps)) {
        best = { score, eps, item };
      }
    }

    const item = best ? best.item : arr[0];
    const episodes = item.episodes || 0;
    const aired = item.airedInfo?.aired || 0;
    const total = episodes > 0 ? episodes : aired;
    const sub = aired > 0 && total > 0 ? Math.min(aired, total) : aired > 0 ? aired : total;
    const dub = (item.dubbedLangs || []).length > 0 ? sub : 0;
    const isAiring = total > 0 && aired > 0 && aired < total;
    result = { sub, dub, total };
    const ttl = isAiring ? TTL_AIRING : TTL_COMPLETED;
    await env.CACHE.put(cacheKey, JSON.stringify(result), { expirationTtl: ttl });

    if (debug) {
      return new Response(JSON.stringify({
        ...result,
        debug: {
          source: "title_search",
          matchedTitle: item.title,
          matchedType: item.type,
          rawEpisodes: episodes,
          rawAired: aired,
          score: best?.score,
          allResults: arr.slice(0, 5).map(x => ({
            title: x.title, episodes: x.episodes, aired: x.airedInfo?.aired,
            type: x.type, dubbed: (x.dubbedLangs || []).length,
          })),
        }
      }, null, 2), { headers: { "Content-Type": "application/json" } });
    }

    return new Response(JSON.stringify(result), {
      headers: { "Content-Type": "application/json", "X-Cache": "MISS" },
    });
  },
};
