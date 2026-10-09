# rule-service

Decides whether a parked vehicle breaks a parking rule and turns breaches into violations and fines.
Part of the Park & View platform (engineering contract: `smart-parking-e2e/docs/platform-standards.md`).

## Responsibilities

* Consumes `vehicle.detected`, matches the vehicle to the single spot it occupies and evaluates the rules:
  `NO_PARKING`, `WRONG_PERMIT` (permit spot, known plate without a valid permit) and `BOUNDARY_EXCEEDED`
  (vehicle not inside the lines).
* Opens a violation with a grace period, keeps it alive while detections re-confirm it, resolves it when the
  vehicle corrects its position or leaves, and issues a fine when the grace period expires.
* Owns the permit register (`plate_permits`) with an operator API.
* Marks fines as paid when `payment.completed.fine` arrives and tells payment-service what a fine costs.

## Architecture position

```
ingestion --vehicle.detected--> [ rule-service ] --violation.created / violation.resolved / fine.issued--> notification, event-streaming
payment-service --payment.completed.fine--> [ rule-service ]
payment-service --GET /internal/v1/fines/{id}--> [ rule-service ]
frontend --JWT via api-gateway--> [ rule-service ]  (violations, permits)
rule-service reads parking_spots, zones, user_plates (owned by other services, behind the ports in client/)
```

Decision flow for one detection (`DetectionService`):

1. No plate: ignored (a violation cannot be attributed).
2. Bounding-box pre-filter, then the spot with the largest overlap is chosen; the vehicle counts as parked
   there when at least `min-overlap` of it lies in the spot. Only that spot is evaluated.
3. Rules, first match wins: no-parking spot, wrong permit, boundary exceeded. Inside the lines means
   IoU >= `iou-threshold` or containment >= `containment-threshold`.
4. Violation found: opened once per plate+spot+type (database partial unique index), otherwise re-confirmed
   (`last_seen_at`). A vehicle that was already fined and is still there does not get a second violation.
5. No violation, or the vehicle is elsewhere: the plate's other open violations in the zone are resolved
   (`CORRECTED` when it is in some spot, `LEFT` when it is in none).

Jobs (safe on several instances):

* fine sweep (`expire-sweep-ms`): claims due violations one by one with `FOR UPDATE SKIP LOCKED`, one
  transaction per violation. Violations not re-confirmed for `stale-minutes` are not fined.
* stale sweep (`stale-sweep-ms`): resolves violations not re-confirmed for `stale-minutes` as `LEFT`.
  Set `stale-minutes` well above the interval at which ingestion re-publishes a persisting violation.

## API

Interactive docs: `/swagger-ui.html` and `/v3/api-docs` when `DOCS_ENABLED=true`. Errors are RFC 7807
`application/problem+json`. Lists are plain JSON arrays, bounded by `?page=0&size=100` (max 500), total in `X-Total-Count`.

| Endpoint | Role | Notes |
|---|---|---|
| `GET /api/v1/violations/zone/{zoneId}` | operator (zone in `zones` claim), admin | active (not resolved, not fined) violations, newest first |
| `GET /api/v1/violations/plate/{plate}` | driver (own plate), operator, admin | all violations of the plate, newest first |
| `PATCH /api/v1/violations/{id}/resolve` | operator (zone of the violation), admin | 204; 404 unknown; 409 when already fined; publishes `violation.resolved` (`MANUAL`) |
| `POST /api/v1/permits` | operator (zone), admin | 201 + `Location`; a permit without `zoneId` is valid everywhere and needs an admin |
| `GET /api/v1/permits?plate=&zoneId=` | operator, admin | operators see their zones and global permits |
| `GET /api/v1/permits/{id}` | operator, admin | |
| `DELETE /api/v1/permits/{id}` | operator (zone), admin | 204 |
| `GET /internal/v1/fines/{id}` | `X-Internal-Api-Key` | `{id, plate, userId, zoneId, amountSek, currency, paid}` |

Violation JSON: `id, spotId, zoneId, plate, userId, violationType, detectedAt, graceUntil, lastSeenAt, resolvedAt,
resolutionReason, fineIssuedAt, fineId, imagePath, notifiedAt, createdAt`. `violationType` is `BOUNDARY_EXCEEDED | WRONG_PERMIT | OVERSTAY | NO_PARKING`.

## Events

| Direction | Exchange / routing key | Queue | Payload |
|---|---|---|---|
| consumed | `parkview.vehicle` / `vehicle.detected` | `vehicle.detected` | `cameraId, zoneId, plate?, vehiclePolygon[[x,y]], spotId?, spotNumber?, iou, confidence, timestamp(ms)` |
| consumed | `parkview.payment` / `payment.completed.fine` | `payment.completed.rule-engine` | `paymentId, referenceId (fine id), referenceType, userId?, amountSek, timestamp(ms)` |
| published | `parkview.violation` / `violation.created` | | `violationId, plate, zoneId, zoneAddress?, cameraId?, userId?, spotId, spotNumber?, violationType, graceMinutes, timestamp(ms)` |
| published | `parkview.violation` / `violation.resolved` | | `violationId, plate, zoneId, reason (CORRECTED\|LEFT\|MANUAL), occurredAt` |
| published | `parkview.violation` / `fine.issued` | | `fineId, violationId, plate, userId?, zoneId, zoneAddress?, amountSek, timestamp(ms)` |

Payloads are validated; invalid ones go to `parkview.dlq` without retry, transient failures are retried 4 times
(exponential) before dead-lettering. Handlers are idempotent. Events are published after the database commit.
`fine.issued` replaces the former key `violation.expired` (same payload).

## Data

Owned: `spot_violations`, `fines`, `plate_permits` (Liquibase `db/changelog`, idempotent and reconciling because the
Supabase migrations created older shapes of the first two). Read only: `parking_spots`, `zones`, `user_plates`.

* `spot_violations.violation_type` is upper-case; `fines.reason` is lower-case (JPA converter).
* One open violation per plate, spot and type: partial unique index `spot_violations_open_uq`.
* `plate_permits(plate, permit_type, zone_id null = all zones, valid_from, valid_to null = open ended, created_by)`.
  Permit types are compared case-insensitively against `parking_spots.permit_type`.

## Configuration

| Variable | Default | Required | Description |
|---|---|---|---|
| `PORT` | `8081` | no | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/parkview` | no | JDBC URL |
| `DB_USER` | `postgres` | no | database user |
| `DB_PASS` | none | yes | database password |
| `DB_POOL_SIZE` | `10` | no | Hikari pool size |
| `RABBIT_HOST` / `RABBIT_PORT` | `localhost` / `5672` | no | broker |
| `RABBIT_USER` / `RABBIT_PASS` | `guest` / `guest` | no | broker credentials (set real ones outside local use) |
| `SUPABASE_URL` | none | yes | JWT issuer is `${SUPABASE_URL}/auth/v1`, keys from its `.well-known/jwks.json` |
| `SUPABASE_AUDIENCE` | `authenticated` | no | required `aud` claim |
| `INTERNAL_API_KEY` | empty | no | secret for `/internal/**` and non-health actuator endpoints; empty disables them |
| `DOCS_ENABLED` | `false` | no | Swagger UI and OpenAPI spec |

Business settings, Spring property `parkview.rule-engine.*` (set with `SPRING_APPLICATION_JSON` or the matching `PARKVIEW_RULE_ENGINE_*` variables):

| Property | Default | Description |
|---|---|---|
| `iou-threshold` / `containment-threshold` | `0.85` / `0.90` | vehicle counts as inside the lines when either is reached |
| `min-overlap` | `0.5` | share of the vehicle that must lie in a spot for it to be parked there |
| `default-boundary-grace-minutes` / `default-permit-grace-minutes` | `10` / `30` | used when the spot defines no grace |
| `fine-amount-{no-parking,overstay,wrong-permit,boundary-exceeded}-sek` | `900` / `450` / `700` / `900` | fine amounts |
| `expire-sweep-ms` / `expire-batch-size` | `60000` / `20` | fine sweep delay and batch size |
| `stale-minutes` / `stale-sweep-ms` | `15` / `60000` | re-confirmation window and sweep delay |
| `touch-interval-seconds` | `30` | minimum time between `last_seen_at` writes per violation |
| `spot-cache-ttl-minutes` | `5` | how long zone polygons are cached |

## Run locally

```bash
export JAVA_HOME=<jdk 21>
SPRING_PROFILES_ACTIVE=local SUPABASE_URL=https://<ref>.supabase.co mvn spring-boot:run   # needs Postgres and RabbitMQ
mvn -B -ntp verify        # unit, web-layer and Testcontainers tests (needs a Docker daemon)
docker build -t rule-service . && docker run --rm -p 8081:8081 -e DB_URL=... -e DB_PASS=... -e SUPABASE_URL=... rule-service
```

The `local` profile only sets a database password and an internal API key for development.
Tests: geometry table tests (clockwise and counter-clockwise polygons), boundary/permit decisions, violation
lifecycle, fine issuing, MockMvc security tests (401/403/404/409/400, problem+json), listener payloads,
Testcontainers persistence tests (unique index, `SKIP LOCKED` claim, permit queries), and a full flow test
against real Postgres and RabbitMQ.

## Operations

* Health: `/actuator/health/liveness` and `/readiness` (public); metrics: `/actuator/prometheus` (internal key). Logs carry `requestId`; plates are masked.
* Several instances may run: inserts are protected by the unique index, fines by `SKIP LOCKED`.
* Messages in `parkview.dlq` are invalid payloads or exhausted retries; inspect `x-death` headers.
* Fines are never issued while the database or broker is unreachable; the sweep retries every `expire-sweep-ms`.
  A violation that failed repeatedly is logged at ERROR with its id.
* Without detections (camera or ingestion down) open violations are resolved as `LEFT` after `stale-minutes`
  instead of being fined.
* Detections without a plate and polygons that cannot be parsed are ignored.
* The Supabase migration `20260512_spot_violations.sql` schedules a `pg_cron` job that also fines expired
  violations; it must be unscheduled (`select cron.unschedule('process-spot-violations')`) so this service is the only issuer.
