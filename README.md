# rule-service

Decides whether a parked vehicle breaks a parking rule and turns breaches into violations and fines.
Part of the Park & View platform (engineering contract: `smart-parking-e2e/docs/platform-standards.md`).

## Responsibilities

* Consumes `vehicle.detected` (an observation of every occupied spot), matches the vehicle to the single spot it
  occupies and runs the rule engine: spot types, the operator-defined zone rules (`zone_rules`), permits, the vehicle
  registry and the parking session decide between `NO_PARKING`, `WRONG_VEHICLE_TYPE`, `WRONG_PERMIT`, `OVERSTAY` and
  `BOUNDARY_EXCEEDED` (see "Rule engine").
* Opens a violation with a grace period, keeps it alive while observations re-confirm it, resolves it when the
  vehicle corrects its situation or leaves, and issues a fine when the grace period expires.
* Owns the permit register (`plate_permits`) with an operator API.
* Marks fines as paid when `payment.completed.fine` arrives and tells payment-service what a fine costs.

## Architecture position

```
ingestion --vehicle.detected--> [ rule-service ] --violation.created / violation.resolved / fine.issued--> notification, event-streaming
payment-service --payment.completed.fine--> [ rule-service ]
payment-service --GET /internal/v1/fines/{id}--> [ rule-service ]
frontend --JWT via api-gateway--> [ rule-service ]  (violations, permits)
rule-service reads parking_spots, zones, zone_rules, sessions, user_plates (owned by other services, behind the ports in client/)
rule-service --GET /internal/v1/vehicles/{plate}?country=--> lookup-service   (only when a decision needs vehicle data)
```

Flow for one observation (`DetectionService`):

1. No plate: ignored (a violation cannot be attributed).
2. Bounding-box pre-filter, then the spot with the largest overlap is chosen (`BoundaryService`); the vehicle counts
   as parked there when at least `min-overlap` of it lies in the spot. Only that spot is evaluated. Inside the lines
   means IoU >= `iou-threshold` or containment >= `containment-threshold`.
3. The rule engine (`ViolationEvaluator`) returns the violation, if any (see "Rule engine").
4. Violation found: opened once per plate+spot+type (database partial unique index), otherwise re-confirmed
   (`last_seen_at` is refreshed at most every `touch-interval-seconds`). A vehicle that was already fined and is
   still there does not get a second violation. Every other open violation of the plate in the zone (another type on
   the same spot, or another spot) is resolved as `CORRECTED`.
5. No violation: the plate's open violations in the zone are resolved as `CORRECTED`; the vehicle is in no spot:
   resolved as `LEFT`.

Jobs (safe on several instances):

* fine sweep (`expire-sweep-ms`): claims due violations one by one with `FOR UPDATE SKIP LOCKED`, one
  transaction per violation. Violations not re-confirmed for `stale-minutes` are not fined.
* stale sweep (`stale-sweep-ms`): resolves violations not re-confirmed for `stale-minutes` as `LEFT`.
  Set `stale-minutes` well above the interval at which ingestion re-publishes a persisting violation.

## Rule engine

Classes (`com.parkview.ruleengine.engine`): `ViolationEvaluator` (decision order), `RuleResolver` (which zone rules
apply, first match wins), `RuleSchedule` (time windows), `VehicleContext` (lazy vehicle data). Ports in `client/`:
`HolidayCalendar` (Jollyday), `VehicleDirectory` (lookup-service), `ActiveSessionDirectory`, `ZoneRuleDirectory`,
`ZoneDirectory`, `SpotDirectory`.

### Decision order

For a vehicle observed in a spot the first violation that applies wins:

| # | Violation | When |
|---|---|---|
| 1 | `NO_PARKING` | the spot type is `no_parking`, or the deciding zone rule has `no_parking = true` |
| 2 | `WRONG_VEHICLE_TYPE` | spot type `ev`, plate known, and the registry says the vehicle is not electric (`fuelType` `El`/`Electric`; hybrids such as `El/Bensin` are not electric) |
| 3 | `WRONG_PERMIT` | the plate lacks a valid `plate_permits` permit of every required type: `disabled` for spot type `disabled`; the spot's `permit_type` for spot type `permit`; the deciding zone rule's `permit_required` (`resident`, `disabled`, `ev`, `loading`). Types are compared case-insensitively. One query for all required types |
| 4 | `OVERSTAY` | age of the active session (`sessions.ended_at is null`) of the plate in the zone exceeds the limit: deciding zone rule's `max_minutes`, else the spot's `max_minutes` (spot types `timed`, `loading` only), else `zones.max_minutes` when > 0. Exactly the limit is not yet an overstay |
| 5 | `BOUNDARY_EXCEEDED` | the vehicle is not inside the lines (last, so a more specific cause wins) |

### Which zone rules apply (`zone_rules`)

A rule applies when all of this holds:

* `enabled`, and the weekday is in `weekdays` (ISO 1 = Monday .. 7 = Sunday) in the zone's timezone (`zones.timezone`,
  default `Europe/Stockholm`; daylight saving is handled by the wall clock).
* The time is in the window `[start_time, end_time)`: start inclusive, end exclusive. `end_time` of `23:59` (the UI default)
  means until midnight; `start_time = end_time` means the whole day.
* A window that crosses midnight (`start_time > end_time`, e.g. 22:00-06:00) belongs to the weekday it **starts** on:
  Friday 22:00-06:00 covers Saturday 03:00, and Saturday 22:00 does not match a Friday-only rule.
* Holidays (`HolidayCalendar`, Jollyday, country from `zones.country`, default `SE`): when the date is a public holiday
  and `applies_holidays = false` the rule does not apply; with `true` the holiday makes no difference. For a window that
  crosses midnight the date of the window start counts. A country Jollyday does not know has no holidays.
* The vehicle filter matches: an empty `vehicle_types` means all vehicles. Otherwise the vehicle's code must be known and
  listed (`car`, `mc`, `bus`, `truck`), or `ev` must be listed and the vehicle known to be electric. An unknown vehicle
  never matches a filter. Detector classes map `car->car`, `motorcycle->mc`, `bus->bus`, `truck->truck`; when the detector
  class is unknown the registry vehicle type decides.

**First match wins, per outcome category.** Rules are tried by `priority` ascending (lower number first, the UI sorts the
same way), then by id. There are three outcome categories: `no_parking`, `permit_required`, `max_minutes`. The first
applicable rule that defines a category decides it, and later rules cannot override it. A rule that only defines
categories that are already decided is skipped. Example, Monday 10:00:

| priority | window | filter | outcome |
|---|---|---|---|
| 10 | 08:00-18:00 | - | `max_minutes = 30` |
| 20 | 00:00-23:59 | - | `max_minutes = 240`, `permit_required = resident` |
| 30 | 00:00-23:59 | `truck` | `no_parking` |

A car gets `max_minutes = 30` (rule 10 decides that category; rule 20's 240 is ignored) and `permit_required = resident`
(rule 20 is the first to define it); the truck rule does not apply to a car. A truck additionally gets `no_parking`.
A `max_minutes` of zero or less does not count as a limit.

### Fail open: when nothing is judged

Whatever cannot be judged is **not** a violation (a debug log says why):

* no plate (the observation is ignored altogether);
* a permit is required but the plate is unknown;
* a stay limit exists but the plate is unknown, or has no active session in the zone;
* spot type `ev` but the vehicle data (registry) is unavailable: unknown plate, unknown to the registry, fuel type
  missing, lookup disabled, timeout or error;
* a rule with a vehicle filter while the vehicle is unknown.

Because an observation without violation resolves the plate's open violations as `CORRECTED`, a violation that depends on
registry data can be resolved and reopened (with a new grace period) when lookup-service is temporarily unreachable.

### Vehicle registry (lookup-service)

Used only when a decision needs it (spot type `ev`, or an applicable rule with a vehicle filter while the detector class
is unknown), at most once per observation. `GET {LOOKUP_SERVICE_URL}/internal/v1/vehicles/{plate}?country=<zones.country>`
with `X-Internal-Api-Key`; only `vehicleType` and `fuelType` are read (never owner data). Found vehicles are cached
`parkview.lookup.cache-ttl-minutes`; "not found" and failed lookups for `negative-cache-ttl-seconds`.
Explicit connect/read timeouts, no retries. `parkview.rule-engine.vehicle-lookup-enabled=false` switches it off.

### Metrics

`ruleengine_observations_evaluated_total`, `ruleengine_violations_created_total{type}`,
`ruleengine_vehicle_lookups_failed_total`, `ruleengine_rules_matched_total{outcome=no_parking|permit|max_minutes}`.

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
resolutionReason, fineIssuedAt, fineId, imagePath, notifiedAt, createdAt`. `violationType` is `BOUNDARY_EXCEEDED | WRONG_PERMIT | OVERSTAY | NO_PARKING | WRONG_VEHICLE_TYPE`.

## Events

| Direction | Exchange / routing key | Queue | Payload |
|---|---|---|---|
| consumed | `parkview.vehicle` / `vehicle.detected` | `vehicle.detected` | `cameraId, zoneId, plate?, vehiclePolygon[[x,y]], spotId?, spotNumber?, iou, confidence, timestamp(ms), vehicleClass?` |
| consumed | `parkview.payment` / `payment.completed.fine` | `payment.completed.rule-engine` | `paymentId, referenceId (fine id), referenceType, userId?, amountSek, timestamp(ms)` |
| published | `parkview.violation` / `violation.created` | | `violationId, plate, zoneId, zoneAddress?, cameraId?, userId?, spotId, spotNumber?, violationType, graceMinutes, timestamp(ms)` |
| published | `parkview.violation` / `violation.resolved` | | `violationId, plate, zoneId, reason (CORRECTED\|LEFT\|MANUAL), occurredAt` |
| published | `parkview.violation` / `fine.issued` | | `fineId, violationId, plate, userId?, zoneId, zoneAddress?, amountSek, timestamp(ms), violationType` |

`vehicle.detected` is an **observation** published by ingestion for every occupied spot (throttled to about one per
spot and plate every 30 s), not only for spots the detector flagged; the plate may be null (such observations are
ignored). `vehicleClass` is `car | motorcycle | bus | truck`, absent or null when unknown; other values are tolerated and
treated as unknown. `violationType` of `violation.created` and `fine.issued` is the upper-case enum name
(`BOUNDARY_EXCEEDED | WRONG_PERMIT | OVERSTAY | NO_PARKING | WRONG_VEHICLE_TYPE`); `fine.issued.violationType` is the
type of the violation the fine was issued for.

Payloads are validated; invalid ones go to `parkview.dlq` without retry, transient failures are retried 4 times
(exponential) before dead-lettering. Handlers are idempotent. Events are published after the database commit.
`fine.issued` replaces the former key `violation.expired` (same payload).

## Data

Owned: `spot_violations`, `fines`, `plate_permits` (Liquibase `db/changelog`, idempotent and reconciling because the
Supabase migrations created older shapes of the first two). Read only: `parking_spots`, `zones`, `zone_rules`, `sessions`, `user_plates`
(see "Data read from other services").

* `spot_violations.violation_type` is upper-case; `fines.reason` is lower-case (JPA converter, e.g. `wrong_vehicle_type`).
  Changeset 006 widens `spot_violations_violation_type_check` and `fines_reason_check` idempotently.
* One open violation per plate, spot and type: partial unique index `spot_violations_open_uq`.
* `plate_permits(plate, permit_type, zone_id null = all zones, valid_from, valid_to null = open ended, created_by)`.
  Permit types are compared case-insensitively against `parking_spots.permit_type`.

## Data read from other services

Tables owned by other services are read through small read-only JPA read models owned by this service
(package `com.parkview.ruleengine.readmodel`): `@Entity @Immutable` classes that map only the columns used, with
getters only, and Spring Data repositories that extend `Repository` (no save or delete API). The ports in `client/`
are implemented on top of them. Extra columns in the owner's table do not matter; `ddl-auto: validate` only checks
the mapped ones. Liquibase never creates or alters these tables and this service never writes to them.

| Table | Owner | Columns used | Read model class |
|---|---|---|---|
| `zones` | Supabase schema / parking-mapping-service | `id`, `address`, `max_minutes`, `country`, `timezone` (null: `0` / `SE` / `Europe/Stockholm`) | `ZoneView` |
| `zone_rules` | Supabase schema / parking-mapping-service | `id`, `zone_id`, `label`, `priority`, `enabled`, `vehicle_types` (`text[]`), `permit_required`, `weekdays` (`int[]`), `start_time`, `end_time` (`time`, read as text and parsed), `applies_holidays`, `max_minutes`, `no_parking` | `ZoneRuleView` |
| `sessions` | session-service | `id`, `plate`, `zone_id`, `started_at`, `ended_at` | `SessionView` |
| `user_plates` | Supabase schema (driver plates) | `id`, `user_id`, `plate` | `PlateOwnerView` |
| `parking_spots` | parking-mapping-service | `id`, `zone_id`, `spot_number`, `type`, `shape` (only `polygon` is read), `permit_type`, `coordinates` (jsonb), `boundary_grace_min`, `permit_grace_min`, `max_minutes` | `ParkingSpotView` |

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
| `INTERNAL_API_KEY` | empty | no | secret for `/internal/**` and non-health actuator endpoints; empty disables them. Also sent as `X-Internal-Api-Key` to lookup-service |
| `LOOKUP_SERVICE_URL` | `http://localhost:8089` | no | lookup-service base URL for vehicle registry data |
| `DOCS_ENABLED` | `false` | no | Swagger UI and OpenAPI spec |

Business settings, Spring property `parkview.rule-engine.*` (set with `SPRING_APPLICATION_JSON` or the matching `PARKVIEW_RULE_ENGINE_*` variables):

| Property | Default | Description |
|---|---|---|
| `iou-threshold` / `containment-threshold` | `0.85` / `0.90` | vehicle counts as inside the lines when either is reached |
| `min-overlap` | `0.5` | share of the vehicle that must lie in a spot for it to be parked there |
| `default-boundary-grace-minutes` / `default-permit-grace-minutes` | `10` / `30` | grace of `BOUNDARY_EXCEEDED` / `WRONG_PERMIT` when the spot defines none (`boundary_grace_min` / `permit_grace_min`) |
| `no-parking-grace-minutes` | `5` | grace of `NO_PARKING` |
| `overstay-grace-minutes` | `5` | grace of `OVERSTAY` |
| `wrong-vehicle-type-grace-minutes` | `10` | grace of `WRONG_VEHICLE_TYPE` |
| `fine-amount-{no-parking,overstay,wrong-permit,boundary-exceeded,wrong-vehicle-type}-sek` | `900` / `450` / `700` / `900` / `700` | fine amounts |
| `expire-sweep-ms` / `expire-batch-size` | `60000` / `20` | fine sweep delay and batch size |
| `stale-minutes` / `stale-sweep-ms` | `15` / `60000` | re-confirmation window and sweep delay |
| `touch-interval-seconds` | `10` | minimum time between `last_seen_at` writes per violation |
| `spot-cache-ttl-minutes` | `5` | how long zone polygons are cached |
| `rule-cache-ttl-seconds` | `60` | how long a zone's rules and master data (`zones`) are cached; operator edits take effect within this time |
| `vehicle-lookup-enabled` | `true` | whether vehicle registry data may be fetched; off means every decision that needs it is "cannot judge" |

Lookup client, Spring property `parkview.lookup.*` (env `LOOKUP_SERVICE_URL` sets `base-url`):

| Property | Default | Description |
|---|---|---|
| `base-url` | `${LOOKUP_SERVICE_URL:http://localhost:8089}` | lookup-service |
| `connect-timeout-ms` / `read-timeout-ms` | `500` / `1500` | explicit timeouts; a slower answer is a failed lookup |
| `cache-ttl-minutes` | `60` | cache of found vehicles |
| `negative-cache-ttl-seconds` | `60` | cache of "not found" and failed lookups |
| `cache-max-size` | `10000` | cached plates |

## Run locally

```bash
export JAVA_HOME=<jdk 21>
SPRING_PROFILES_ACTIVE=local SUPABASE_URL=https://<ref>.supabase.co mvn spring-boot:run   # needs Postgres and RabbitMQ
mvn -B -ntp verify        # unit, web-layer and Testcontainers tests (needs a Docker daemon)
docker build -t rule-service . && docker run --rm -p 8081:8081 -e DB_URL=... -e DB_PASS=... -e SUPABASE_URL=... rule-service
```

The `local` profile only sets a database password and an internal API key for development.
Tests: geometry table tests (clockwise and counter-clockwise polygons), table-driven rule engine tests (time windows
incl. midnight crossing, holidays and Europe/Stockholm DST dates; priority and first match wins; every violation type and
fail-open case; vehicle-code mapping; grace values; overstay with a fixed clock), the Jollyday calendar for the Swedish
2026 holidays, the lookup client (MockRestServiceServer), violation lifecycle (type change, grace per type), fine issuing,
MockMvc security tests (401/403/404/409/400, problem+json), listener payloads, Testcontainers tests (unique index,
`SKIP LOCKED` claim, permit queries, read models against Supabase-style tables with extra columns, `time`/array columns,
Liquibase 006) and a full flow test against real Postgres and RabbitMQ.

## Operations

* Health: `/actuator/health/liveness` and `/readiness` (public); metrics: `/actuator/prometheus` (internal key). Logs carry `requestId`; plates are masked.
* Several instances may run: inserts are protected by the unique index, fines by `SKIP LOCKED`.
* Messages in `parkview.dlq` are invalid payloads or exhausted retries; inspect `x-death` headers.
* Fines are never issued while the database or broker is unreachable; the sweep retries every `expire-sweep-ms`.
  A violation that failed repeatedly is logged at ERROR with its id.
* Without detections (camera or ingestion down) open violations are resolved as `LEFT` after `stale-minutes`
  instead of being fined.
* Observations without a plate and polygons that cannot be parsed are ignored.
* lookup-service down: decisions that need vehicle data are skipped (fail open) and `ruleengine_vehicle_lookups_failed_total`
  rises; after `negative-cache-ttl-seconds` the next observation asks again.
* Rules are read through a cache: after an operator edit in the UI allow `rule-cache-ttl-seconds` before it is enforced.
* The Supabase migration `20260512_spot_violations.sql` schedules a `pg_cron` job that also fines expired
  violations; it must be unscheduled (`select cron.unschedule('process-spot-violations')`) so this service is the only issuer.
