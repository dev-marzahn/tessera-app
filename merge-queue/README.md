# Merge queue

PRs used to merge in parallel, each green against a `main` that didn't hold the others yet. On 2026-11-07, #395 started
using `BookOpener` and #398 removed it; they merged 4 minutes apart and `main` stopped compiling. GitHub's merge queue
needs Enterprise Cloud for a private repo, so [gitea-mq](https://github.com/Mic92/gitea-mq) (MIT) does the job instead,
self-hosted on Coolify next to the runners (`runner/README.md`).

## How it works

gitea-mq runs as a GitHub App, `tessera-merge-queue`, installed on this repo only.

1. The `merge-queue` label is the signal to queue a PR. `auto-merge.yml` puts it on agent PRs once they're ready, and
   takes it off a draft.
2. gitea-mq waits until the PR's own required checks are green (`build`, `claude-review`, `human-review`), then queues
   it. One PR at a time is tested (`GITEA_MQ_BATCH_MAX=1`).
3. It creates the branch `gitea-mq/<pr>` at `main`'s tip and merges the PR's head onto it. `build.yml` builds that merge
   commit. gitea-mq waits there for every check `main`'s rules require, not only `build` (`GITEA_MQ_REQUIRED_CHECKS` is
   only a fallback for a branch without any), so `merge-queue.yml` reports the others, `claude-review` and
   `human-review`, with the result they already have on the PR's head. The reviews don't run again: no second review,
   and approvals don't reset. A review still running on the head (`claude-review` re-runs when the PR is edited) is
   waited for.
4. On success it sets its `gitea-mq` check on the PR's head to success and merges the PR itself, through GitHub's
   merge API, so `main`'s rulesets still apply. `auto-merge.yml` then comments on the PR: T3 Code wakes an agent on a
   comment, not on a merge. On failure, gitea-mq sets `gitea-mq` to failure, takes the label off and comments with
   the failed check; the PR's next push puts the label back. Either way it deletes `gitea-mq/<pr>` and moves to the
   next PR.

   GitHub's auto-merge would queue a PR too, but then gitea-mq waits for GitHub to merge, which can take minutes
   (resolving a review thread doesn't make it look again), holds up the queue meanwhile, and drops the PR after 5.

A PR whose head already contains `main`'s tip skips step 3: its own `build` covered the same tree. gitea-mq also mirrors
the temporary branch's checks onto the PR's head as `gitea-mq/build` and so on, so they show on the PR.

The `gitea-mq` ruleset requires the `gitea-mq` check on `main`, from this App only. It's a ruleset of its own rather
than a fourth check in the `main` ruleset: switching the queue off is then deleting one ruleset, without touching the
rules every PR depends on. Its only bypass actor is the repo admin role, as in `main`'s: the owner can still merge past
it.

### What the App may do

| Repository permission | Access | Why |
|---|---|---|
| Checks | Read and write | Posts the `gitea-mq` check and its mirrors |
| Commit statuses | Read | Reads `merge-queue.yml`'s review statuses |
| Contents | Read and write | Creates, merges into and deletes `gitea-mq/*` branches, and merges PRs. It can't push to `main` directly: the `main` ruleset requires a PR and the App bypasses nothing |
| Pull requests | Read and write | Reads and removes the label, merges, and comments |
| Administration | **Read** | Reads `main`'s required checks. gitea-mq's README asks for write, so it can create its ruleset and add itself as a bypass actor to every other one, `main`'s included. With read it logs a warning at startup and changes nothing; the wizard creates the ruleset instead |
| Metadata | Read | Required for every App |

Events: Pull request, Check run, Status (installation events are always sent).

## Deployment

`docker-compose.yml` runs `ghcr.io/mic92/gitea-mq` at a pinned tag and digest, and PostgreSQL. It's one Coolify
resource with an HTTPS domain: GitHub delivers the App's webhooks to `<domain>/webhook/github`. The dashboard has no
login and lists PR titles, so two Traefik routers in the compose file's labels (one per entrypoint, `http` and
`https`), ahead of the ones Coolify makes for the domain
([custom routers](https://coolify.io/docs/core/networking/proxy/traefik/custom-middlewares#use-your-own-routers-in-docker-compose)),
answer every other path than `/webhook/…` and `/healthz` with Traefik's empty `noop@internal` service (418). They name
the domain, `gitea-mq.apps.marzahn.co.uk`, rather than a variable: Coolify escapes `$` in labels by default, so a
`${…}` would reach Traefik as literal text and the routers would match nothing. A different domain means changing
both `Host(…)` rules first.
The `gitea-mq` check's link to the dashboard lands there too. The queue's state shows in the PRs' checks and comments
and in the resource's logs.

`scripts/setup-merge-queue` walks the owner through every step only they can do, and generates the secrets without
printing or saving them: they only reach Coolify, through the clipboard.

1. **Domain.** `gitea-mq.apps.marzahn.co.uk` (the one in the compose file's labels), pointing at the Coolify server.
2. **GitHub App.** Created from github.com/settings/apps/new with the permissions above, webhook URL
   `<domain>/webhook/github`, and no webhook secret: gitea-mq writes its own secret and URL into the App on startup.
   Then a private key (a `.pem` download).
3. **Coolify.** A resource like the runners': Private Repository (with the GitHub App), this repo, branch `main`,
   build pack **Docker Compose**, base directory `/merge-queue`, compose file `/docker-compose.yml`. The
   `gitea-mq` service's domain is `https://<domain>:8080` (the port is the container's; the public URL stays
   `https://<domain>`). Environment variables:

   | Variable | Value |
   |---|---|
   | `GITEA_MQ_EXTERNAL_URL` | `https://<domain>` |
   | `GITEA_MQ_GITHUB_APP_ID` | The App's ID |
   | `GITEA_MQ_GITHUB_PRIVATE_KEY_BASE64` | The `.pem` as one line: `base64 < key.pem \| tr -d '\n'`. Coolify keeps only the first line of a multiline value, so the compose file's `key` service decodes it into a file for gitea-mq |
   | `GITEA_MQ_GITHUB_WEBHOOK_SECRET` | Random (the wizard generates it) |
   | `GITEA_MQ_DB_PASSWORD` | Random hex (the wizard generates it; it goes into a URL) |

   Limit auto-deploy to `/merge-queue/**` (watch paths). Deploy. A redeploy doesn't lose the queue: it's in the
   database, and gitea-mq picks up where it was.
4. **Check.** `https://<domain>/healthz` answers 200 and `https://<domain>/` answers 418. The labels assume
   Coolify's Traefik (v3) and its `http` and `https` entrypoints, the defaults.
5. **Install** the App on `leon-marzahn/sankaku-app` only, and only once `main` has `merge-queue.yml` and `build.yml`'s
   `gitea-mq/**` trigger: gitea-mq queues every labelled PR, and a temporary branch built by an older `build.yml` gets
   no `build`, so the PR would time out after an hour and lose its label. The App's Recent
   Deliveries (its Advanced tab) then show green ticks.
6. **Ruleset.** The wizard creates the `gitea-mq` ruleset with `gh api`; from then on every PR needs the queue.

Updating gitea-mq: read the [releases](https://github.com/Mic92/gitea-mq/releases), change the tag and digest in
`docker-compose.yml` (`docker buildx imagetools inspect ghcr.io/mic92/gitea-mq:<tag>` prints the digest) and
redeploy. `auto-merge.yml` matches the text of one gitea-mq comment, "new commits were pushed", to put the label back
after a push to a queued PR: check the new version's `internal/poller/poller.go` still says that. If it changed, such
a PR stays without the label (and unqueued) until its next push. The same for PostgreSQL within a major version; a new major needs a dump and restore.

## Switching it off

In order, so no PR is left waiting:

1. **Delete the `gitea-mq` ruleset** (Settings → Rules → Rulesets → `gitea-mq` → Delete, or
   `gh api --method DELETE repos/leon-marzahn/sankaku-app/rulesets/<id>`; `gh api repos/leon-marzahn/sankaku-app/rulesets`
   lists the ids). PRs merge in parallel again at once, on `build`, `claude-review` and `human-review`.
2. **Uninstall the App** (github.com/settings/installations → `tessera-merge-queue` → Uninstall), so it stops queueing
   PRs, merging and pushing branches. The `merge-queue` label then does nothing: switch agent PRs back to GitHub's
   auto-merge by reverting `auto-merge.yml` to its version before the label (git log), or for one PR with
   `scripts/agent-gh pr merge <pr> --auto --merge --delete-branch`.
3. Stop the Coolify resource, and delete any `gitea-mq/*` branch left behind.

The workflow changes can stay: `build.yml` and `merge-queue.yml` only run on `gitea-mq/*` branches, which nothing
creates any more. Switching back on is step 3, 2 and 1 in reverse: deploy, install, and create the ruleset (the
wizard's last stage).
