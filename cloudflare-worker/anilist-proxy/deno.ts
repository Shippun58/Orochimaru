// AniList GraphQL relay for the sanin-push Cloudflare Worker (Deno Deploy).
//
// Deno Deploy runs on Deno's own infrastructure (not Cloudflare), so requests
// leave from an IP AniList accepts. Deploy at https://dash.deno.com — create a
// playground, paste this file, and use the resulting URL as ANILIST_PROXY on
// the sanin-push worker.

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: { "Access-Control-Allow-Origin": "*" },
    });
  }

  const body = await req.text();
  try {
    const upstream = await fetch("https://graphql.anilist.co", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body,
    });
    return new Response(upstream.body, {
      status: upstream.status,
      headers: { "Content-Type": "application/json" },
    });
  } catch (e) {
    return new Response(JSON.stringify({ error: String(e) }), {
      status: 502,
      headers: { "Content-Type": "application/json" },
    });
  }
});
