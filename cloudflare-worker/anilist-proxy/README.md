# AniList relay for sanin-push

AniList blocks Cloudflare Workers' shared egress IPs, so `sanin-push` cannot
call `https://graphql.anilist.co` directly (it receives
`403 "You have been manually blocked"`). AniList staff confirm this:
_"Most Cloudflare Workers have been blocked due to increased abuse from the
platform. Use a service that doesn't share IPs if you want to reliably access
the AniList API."_

This folder contains a tiny relay that forwards the GraphQL POST body to
AniList and returns the response unchanged. Deploy it on any host that is **not
Cloudflare** (which includes Vercel **Edge** and any Cloudflare Worker — do not
use those), then point the worker at it.

## Option A — Vercel (Node.js serverless, runs on AWS)

1. Push this repo to GitHub.
2. On https://vercel.com → **Add New → Project** → import the repo.
3. Set **Root Directory** to `cloudflare-worker/anilist-proxy` (leave Framework
   preset as "Other"). Deploy.
4. Your endpoint is `https://<project>.vercel.app/api/anilist`.

> Use the Node.js runtime (default). Vercel **Edge** functions run on
> Cloudflare's network and will be blocked.
>
> The `package.json` (`"type": "module"`) is required so Vercel treats
> `api/anilist.js` as ESM; without it the `export default` fails at runtime.
>
> Use the **production** alias (`https://<project>.vercel.app`). Per-deployment
> URLs are behind Vercel Authentication and return `401`.

## Option B — Deno Deploy

1. Go to https://dash.deno.com → **New Playground**.
2. Paste the contents of `deno.ts` and deploy.
3. Your endpoint is `https://<project>.deno.dev`.

## Option C — your own VPS / any non-Cloudflare host

Run anything that accepts `POST /` with a JSON body and forwards it to
`https://graphql.anilist.co`, returning the upstream status + body.

## Wire it up

Set the relay URL on the worker (either is fine, the KV value wins):

- **wrangler var:** add `ANILIST_PROXY = "https://…/api/anilist"` to
  `[vars]` in `wrangler.toml`, then `wrangler deploy`.
- **KV config:** add `"anilistProxy": "https://…/api/anilist"` to the
  `cfg:config` JSON value in the `PUSH_SUBS` KV namespace.

Verify: open `https://sanin-push.shemaus58.workers.dev/debug?key=<adminKey>` —
`anilist` should report schedules with no `last error`.

If `ANILIST_PROXY` is empty the worker falls back to calling AniList directly
(which works outside Cloudflare but is blocked from Workers).
