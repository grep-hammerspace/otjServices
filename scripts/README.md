# scripts/otj

A dependency-free wrapper over `curl` for exercising the API by hand. `curl` is the only
hard requirement; `jq` is used for pretty-printing and safe JSON quoting when present.

```sh
./scripts/otj --help
```

## Why this works without a tailnet

`TailscaleIdentityHelper.getUser` reads the `Tailscale-User-Login` header and nothing
else. The security model is that the app binds to `127.0.0.1:8945`, where `tailscale
serve` is the only process that can reach it and set that header. Locally there is no
such gatekeeper, so the script sets the header itself — which is exactly what makes
hand-testing possible.

Use `--user` to act as a different identity. Every record in Mongo is keyed by it, so
this is how you check that users cannot see each other's rows.

## Start the app first

```sh
# whole stack in podman — closest to production
bash deploy/bootstrap.sh

# dev loop: Mongo in podman, app from the jar so you can rebuild fast
podman-compose -f deploy/podman-compose.yaml up mongo -d
set -a; source .env; set +a
mvn -q package -DskipTests && java -jar target/app.jar
```

The app reads configuration from the process environment only — `dotenv-java` is
declared in `pom.xml` but never imported — so `.env` has to be sourced into the shell
first. Two variables fail late rather than at startup, which makes them confusing:
`PASSWORD_ENCRYPTION_KEY` breaks `register`, and a missing Anthropic key breaks
`log` at call time.

## Typical session

```sh
./scripts/otj health
./scripts/otj register --learner-id 12345
./scripts/otj log "worked on IOT554 from 11:00 to 13:00 today"
./scripts/otj delete-last
```

## The one behaviour that looks like a bug

Re-sending identical text does nothing:

```sh
./scripts/otj log "2 hours on revision today"     # rowsAdded: 1
./scripts/otj log "2 hours on revision today"     # {"status": "no new content"}
```

`ContentDiffer.computeDiff` is a *set difference over lines*, not a true diff — any line
already present in the stored `lastContent` is dropped, and when nothing survives the
endpoint returns `no new content` with no rows and no error. This also means an
activity legitimately repeated on a later day is silently ignored.

`log --fresh` clears the stored history first so the same text can be replayed:

```sh
./scripts/otj log --fresh "2 hours on revision today"   # rowsAdded: 1 again
```

Plain `log` deliberately keeps the production behaviour, so the quirk itself stays
testable.

## Endpoints deliberately left out

`/prepare-browser`, `/submit-with-mfa`, `/azure-id/prepare` and `/azure-id/complete`
drive real sessions against OneAdvanced and Azure AD using stored credentials, and
`submit-with-mfa` posts entries into a real education record. They have no command of
their own. `otj raw` still reaches them and prints a warning first:

```sh
./scripts/otj raw GET /otj-services/azure-id/prepare
```

## Checking what landed in Mongo

No endpoint lists stored rows — `log` returns the rows it just created, and beyond that:

```sh
podman exec -it deploy_mongo_1 mongosh otjdb \
  --eval 'db.activitylogs.find({tailscaleUserId:"dev@localhost"}).sort({_id:-1}).limit(3)'
```
