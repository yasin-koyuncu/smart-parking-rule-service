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
* Marks fines as paid when `payment.completed.fine` arrives and tells payment-service what a fine costs (and whether it was voided).
* Lets operators with `fine:manage` void an unpaid fine or mark it as paid by hand (cash, transfer, other); every such command and
  every online payment is kept in an audit trail (`fine_actions`) and announced with `fine.voided` / `fine.paid`.
* Freezes the evidence of every fine when it is issued (`fine_evidence`) and lets a driver (or an operator on the phone with a driver) ask a
  person to review a fine, a **dispute**: while it is open the fine cannot be paid, an operator with `fine:manage` upholds or voids the fine
  with a written reason, everything is audited. Fines stay automatic; humans can always review the decision (ADR 0005 in smart-parking-e2e).
* Publishes every event through a transactional outbox (`rule_outbox_events`), written in the transaction of the state change.

## Architecture position

```
ingestion --vehicle.detected--> [ rule-service ] --violation.created / violation.resolved / fine.issued / fine.paid / fine.voided / fine.disputed / fine.dispute.decided--> notification, event-streaming
payment-service --payment.completed.fine--> [ rule-service ]
session-service --session.ended--> [ rule-service ]
parking-mapping-service --map.updated--> [ rule-service ]
payment-service --GET /internal/v1/fines/{id}--> [ rule-service ]
frontend --JWT via api-gateway--> [ rule-service ]  (violations, fines, permits)
rule-service reads parking_spots, zones, zone_rules, sessions, user_plates, detections (owned by other services, behind the ports in client/)
rule-service --GET /internal/v1/vehicles/{plate}?country=--> lookup-service   (only when a decision needs vehicle data)
```

Flow for one observation (`DetectionService`):

1. No plate: ignored (a violation cannot be attributed).
2. The candidate spots depend on the observation: with a `viewId` (the frame was checked against a camera view) they
   are the spots of that view and their geometry is `parking_spots.image_polygon`, the pixel space of the view's
   corrected image that `vehiclePolygon` is in; without one (legacy) they are the zone's spots that are on no view,
   geometry `coordinates` (raw camera pixels). The two sets are never mixed: a legacy observation cannot match a view
   spot and a view observation cannot match a legacy spot. Then a bounding-box pre-filter, and the spot with the
   largest overlap is chosen (`BoundaryService`); the vehicle counts
   as parked there when at least `min-overlap` of it lies in the spot. Only that spot is evaluated. Inside the lines
   means IoU >= `iou-threshold` or containment >= `containment-threshold`. **This is the only place that decides whether a
   vehicle is parked correctly:** ai-detection only reports which vehicle stands in which spot (and no longer sends
   `violation`/`violations`), and ingestion-service sends no threshold. Change the thresholds here.
3. The rule engine (`ViolationEvaluator`) returns the violation, if any (see "Rule engine").
   The time of an observation is the `timestamp` of the event (never later than now), not the time it is processed: after an
   outage or a backlog an old sighting neither refreshes `last_seen_at` nor starts a fresh grace period. An observation older than
   `observation-max-age-seconds` (default 10 minutes) is dropped (acknowledged, logged at debug without the plate).
4. Violation found: opened once per plate+spot+type (database partial unique index), otherwise re-confirmed
   (`last_seen_at` is refreshed at most every `touch-interval-seconds`). A vehicle that was already fined and is
   still there does not get a second violation, and neither does one that was fined for the same reason in the same zone
   within `refine-block-hours`, even when its spot was deleted and drawn again meanwhile (re-scan, "Töm ytan", rollback: the
   violation is gone with the spot and the new spot has a new id, so the fine itself is what remembers the stay; a voided
   fine counts too). Every other open violation of the plate in the zone (another type on
   the same spot, or another spot) is resolved as `CORRECTED`.
5. No violation: the plate's open violations in the zone are resolved as `CORRECTED`; the vehicle is in no spot:
   resolved as `LEFT`.

Jobs (safe on several instances):

* fine sweep (`expire-sweep-ms`): claims due violations one by one with `FOR UPDATE SKIP LOCKED`, one
  transaction per violation. A violation is fined only if a detection confirmed it within the last
  `fine-freshness-seconds` (default 120) when it is claimed: rule-service is not told directly that a car drove away during
  the grace period, so "grace over" alone must not fine it. Set `fine-freshness-seconds` well above the interval at which
  ingestion re-publishes a persisting violation (about 30 s) plus `touch-interval-seconds`; too low and a car that is still
  there is not fined, too high and a car that left shortly before the grace ended is. A violation that is not fresh stays open
  until `session.ended` or the stale sweep resolves it.
* stale sweep (`stale-sweep-ms`): resolves violations not re-confirmed for `stale-minutes` as `LEFT`.
  Set `stale-minutes` well above the interval at which ingestion re-publishes a persisting violation.
* `session.ended` (session-service): the open, not yet fined violations of that plate in that zone are resolved as `LEFT`
  at once (the same resolve path as the stale sweep, `violation.resolved` is published). A violation that already has a fine
  stays, one that a detection confirmed after the session ended is left alone (the vehicle is still there), a redelivered
  event finds nothing open.
* `map.updated` (parking-mapping-service): the spots of that zone (legacy and every view) are evicted from the cache, so a
  re-scan, rollback or "empty the area" applies to the next observation. Every instance has its own queue for this event
  (`map.updated.rule-engine.instance-<uuid>`: not durable, exclusive, deleted with its connection, dead-lettered like the others), so every
  instance evicts its own cache; with one shared queue only the instance that got the message would, and the others kept the old spots
  for the whole TTL. An event published while an instance is down is not needed: its cache starts empty. The durable shared queue
  `map.updated.rule-engine` of earlier releases is no longer declared; at start each instance deletes it if it has no consumer (a queue that is
  gone from the code is not gone from a broker, and it would otherwise collect every event for ever). During a rolling deployment an older instance may
  still read it: the delete is then refused, logged at INFO, and the next start removes it. A zone without spots is cached for
  `spot-cache-negative-ttl-seconds` only. An observation for a spot that was deleted meanwhile (foreign key failure on
  `spot_id`) is dropped and acknowledged, the zone evicted, instead of being retried into the dead-letter queue.

## Structure

Package-by-layer under `com.parkview.ruleengine`; a call goes `controller`/`messaging` -> `service` -> `repository` and the ports in `client`. Classes stay under about 200 lines, methods under about 40.

| Layer | Package | What lives there |
|---|---|---|
| Edge | `controller`, `controller/internal`, `messaging` | Thin: parse and validate, authorize (`@PreAuthorize` + `AccessPolicy`), call one service, map to a DTO. No repositories, no business rules. `FineController` (reads), `FineManagementController` (void, mark-paid), `ViolationController`, `PermitController`, `InternalFineController`; listeners `VehicleEventListener` and `PaymentEventListener` |
| Use cases | `service` | One class per use case area (below) |
| Rules | `engine` | `ViolationEvaluator` (decision order), `RuleResolver`, `RuleSchedule`, `VehicleContext`; no I/O except the ports |
| Domain | `domain`, `geometry` | Entities with their own state transitions (`Fine.markPaid`, `Fine.voidFine`, `Violation`) in `domain`; `domain/type` (enums), `domain/converter` (JPA converters), `domain/value` (read-only values such as `ParkingSpot`, `ZoneInfo`); `RuleEngineProperties.fineAmountFor` / `graceMinutesFor` hold the amount and grace rules. `geometry/PolygonGeometry` answers how much two polygons overlap (area, intersection, IoU, containment) with JTS, the standard Java geometry library |
| Services by area | `service/fine` (fines, evidence, disputes: a dispute belongs to a fine and the two call each other), `service/violation` (violation lifecycle, detection, boundary and permit checks), `service/outbox` (outbox, relay, cleanup), `service/cache` | One package per area; `util` holds the small static helpers (`Plates`, `ZoneIds`, `ReasonTexts`); `client/jpa`, `client/http`, `client/holiday` hold the adapters behind the ports in `client/` |
| Ports | `client` | `*Directory` interfaces and their adapters (`Jpa...` for tables of other services, `HttpVehicleDirectory`, `JollydayHolidayCalendar`) |
| Mapping | `mapper` | Pure entity to DTO functions |

Services:

* `DetectionService`: one observation to violation state. `BoundaryService`: which spot. `SpotCacheService` / `RuleCacheService`: cached master data.
* `ViolationService`: violation lifecycle (open, confirm, resolve). `ViolationJobs`: the two sweeps.
* `FineIssuingService`: creates the fine of a due violation and freezes its evidence (inside the sweep transaction). `FineEvidenceService`: writes and reads the frozen evidence, builds the driver's explanation. `FineManagementService`: operator void and mark-paid. `FineSettlementService`: online payment. `FineQueryService`: reads, audit trail and the dispute state of fines. `FineEvents`: payload and routing key of the fine events.
* `DisputeService`: open, withdraw and decide a dispute (the void of a decision goes through `FineManagementService.voidFine`). `DisputeCloser`: the one place that ends a dispute (status, audit row, `fine.dispute.decided`). `DisputeQueryService`: reads and the operator list.
* `OutboxService`: writes an event to the outbox (refuses to run without a transaction). `OutboxRelay` / `OutboxCleanup`: publish the rows, remove old ones.
* `PermitService`: permits (the engine question `holdsAll` and operator CRUD). `Plates`, `ZoneIds`: normal forms of ids from requests.

Every fine command locks the row (`select ... for update`), writes its `fine_actions` row and its outbox event in the same transaction; the relay publishes the event after commit (see "Transactional outbox").

Where to add things:

* A new REST use case: the endpoint in the controller of its area (a new controller for a new area, URLs under `/api/v1/<area>`), the logic in a service of that area (a new `<Area>...Service` rather than growing one past about 200 lines), the response mapping in `mapper`.
* A new fine command: a method in `FineManagementService` (lock, decide on the current state, mutate through a `Fine` method, audit row, event via `FineEvents`), a `FineActionType` value (and the value in the `fine_actions_action_check` constraint) and the endpoint in `FineManagementController`.
* A new event: a record in `dto/event`, a routing key in `RabbitConfig`, a method in `FineEvents` (or `OutboxService.enqueue` directly) and its JSON schema in `contracts/events.json` of smart-parking-e2e. Never publish with `RabbitTemplate` directly: the outbox is the only way out.
* A new violation type: `ViolationType`, its grace and fine amount in `RuleEngineProperties`, its check in `ViolationEvaluator`.
* Data of another service: a port and adapter in `client` named `<Thing>Directory`.

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

Authorization uses the permissions in `app_metadata.permissions` (string[], one authority per permission, named exactly like it;
ADR 0002 and `contracts/permissions.json` in smart-parking-e2e) plus the zone scope in `app_metadata.zones` (`AccessPolicy`, unchanged).
A token WITHOUT the claim gets the default permissions of its role while `parkview.security.legacy-role-fallback` is true (default);
a present claim, even an empty list, is never overridden. By default an operator holds `violation:read`, `violation:resolve` and
`permit:manage`, an admin everything (`fine:manage` is an admin default only; an operator needs it granted).

| Endpoint | Access | Notes |
|---|---|---|
| `GET /api/v1/violations/zone/{zoneId}` | `violation:read` + zone in `zones` claim (admin: any zone) | active (not resolved, not fined) violations, newest first |
| `GET /api/v1/violations/plate/{plate}` | driver (own plate), or `violation:read` | all violations of the plate, newest first; an operator sees only those in their `zones`, an administrator all; `imagePath` only with `violation:read` |
| `PATCH /api/v1/violations/{id}/resolve` | `violation:resolve` + zone of the violation (admin: any zone) | 204; 404 unknown; 409 when already fined; publishes `violation.resolved` (`MANUAL`) |
| `POST /api/v1/permits` | `permit:manage` + zone (global permit: admin) | 201 + `Location`; a permit without `zoneId` is valid everywhere and needs an admin |
| `GET /api/v1/permits?plate=&zoneId=` | `permit:manage` | operators see their zones and global permits |
| `GET /api/v1/permits/{id}` | `permit:manage` + zone of the permit | |
| `DELETE /api/v1/permits/{id}` | `permit:manage` + zone of the permit (global: admin) | 204 |
| `GET /api/v1/fines/plate/{plate}?includeVoided=` | driver (own plate in `plates` claim), or `violation:read` | fines of the plate, newest first; another driver's plate is 403; an operator sees only the fines in their `zones` (a plate with fines elsewhere gives an empty list), an administrator all; voided fines only for staff with `includeVoided=true` (drivers never see them); `imagePath` only with `violation:read` |
| `GET /api/v1/fines/zone/{zoneId}?paid=&includeVoided=` | `violation:read` + zone in `zones` claim (admin: any zone) | fines of the zone, newest first; optional `paid=true\|false` filter; voided fines left out unless `includeVoided=true` |
| `GET /api/v1/fines/{id}` | driver (plate of the fine in `plates` claim), or `violation:read` + zone of the fine (admin: any zone) | 404 unknown, 403 for someone else's fine; a driver gets 404 for a voided fine |
| `GET /api/v1/fines/{id}/history` | `violation:read` + zone of the fine (admin: any zone) | audit trail, newest first, paged; 404 unknown |
| `POST /api/v1/fines/{id}/void` | `fine:manage` + zone of the fine (admin: any zone) | body `{reason}`; 200 + the fine; 409 when the fine is paid; idempotent (already voided: 200, same body, nothing new); publishes `fine.voided` |
| `POST /api/v1/fines/{id}/mark-paid` | `fine:manage` + zone of the fine (admin: any zone) | body `{reason, method}`; 200 + the fine; 409 when the fine is voided; idempotent (already paid: 200, nothing new); publishes `fine.paid` |
| `GET /api/v1/fines/{id}/evidence` | `violation:read` + zone of the fine (admin: any zone) | the frozen evidence incl. snapshot keys; `{recorded:false, fineId}` for fines issued before evidence was recorded; **404** for an unknown fine and for a fine outside the caller's zones |
| `GET /api/v1/fines/{id}/explanation` | driver (plate of the fine in `plates` claim), or `violation:read` + zone | plain-language view, never snapshots or detection ids; 404 for someone else's fine; also for voided fines (the driver can see how a dispute ended) |
| `POST /api/v1/fines/{id}/dispute` | driver (own plate, channel `driver`), or `fine:manage` + zone (channel `operator`, "customer called") | body `{message}` (10-1000 characters after trimming); 201 + `Location` + the dispute; 404 for someone else's fine; 409 when the fine is paid or voided, already has an open dispute or was already upheld |
| `GET /api/v1/fines/{id}/dispute` | driver (own plate), or `violation:read` / `fine:manage` + zone | the latest dispute of the fine; 404 when there is none |
| `POST /api/v1/disputes/{id}/withdraw` | driver (own plate) | 200 + the dispute; 404 for someone else's; 409 when not open any more |
| `GET /api/v1/disputes?status=open\|decided\|all&zoneId=&page=&size=` | `fine:manage`, limited to the zones in the token (admin: all) | newest first, total in `X-Total-Count`; `status` defaults to `open`, `decided` = upheld, voided and withdrawn; `zoneId` must be one of the caller's zones (403 otherwise) |
| `POST /api/v1/disputes/{id}/decision` | `fine:manage` + zone of the dispute (admin: any zone) | body `{outcome: upheld\|voided, note}` (note 10-1000 characters, mandatory); 200 + the dispute; 404 outside the caller's zones; 409 when already decided or withdrawn |
| `GET /internal/v1/fines/{id}` | `X-Internal-Api-Key` | `{id, plate, userId, zoneId, amountSek, currency, paid, voided, disputed}`; payment-service must not charge a voided fine nor start a payment while `disputed` |

Fine JSON (read API and the response of the two commands; online payment goes through payment-service): `id, plate, zoneId, cameraId, reason, reasonCode, reasonText, amountSek,
issuedAt, paid, imagePath, paidAt, voided, voidedAt, disputed, disputeStatus`. `reason` is the stored lower-case value (`no_parking | overstay | wrong_permit | boundary_exceeded | wrong_vehicle_type`),
`amountSek` a number, `issuedAt`, `paidAt` and `voidedAt` ISO-8601 instants; `cameraId`, `reasonCode`, `reasonText`, `imagePath`, `paidAt` and `voidedAt` may be null
(`paidAt` is also null for fines paid before it was recorded). `disputed` is true while a dispute is open, `disputeStatus` is the status of the latest dispute (`open | upheld | voided | withdrawn`) or null. The owner's `userId`, the void reason and the actors are never returned here. The lists are served from
the indexes `fines_plate_issued_idx` and `fines_zone_issued_idx` (changeset 007). These endpoints replace the frontend's direct read of the `fines` table, so the row level
security policy that lets every signed-in user read all fines can be dropped.

Void and mark-paid (permission `fine:manage`, an admin default; an operator needs it granted):

* Bodies: `{"reason": "3..300 characters"}` for void, `{"reason": "3..300 characters", "method": "cash|transfer|other"}` for mark-paid. Surrounding spaces are removed
  before the length check; a missing or blank field is 400 (problem+json with `errors[]`).
* State rules: a fine is either paid or voided, never both (also enforced by the check constraint `fines_not_paid_and_voided_check`). A paid fine cannot be voided (409,
  a refund is a separate flow); a voided fine cannot be marked as paid (409) and a later `payment.completed.fine` for it is logged for a refund and ignored (the fine
  stays voided, nothing is published). Repeating a command that already holds is 200 with the same body and changes, writes and publishes nothing.
* Void sets `voided_at`, `void_reason`, `voided_by` (the token subject, which must be a user id); mark-paid sets `paid`, `paid_at`, `paid_method`, `paid_by`. The online payment
  sets `paid`, `paid_at`, `paid_method = online` and no `paid_by`. All three decide under a row lock (`select ... for update`), so concurrent commands cannot interleave.
* The `reason` is operator free text: it is stored and shown to staff, never logged and never part of an event.

Audit trail `GET /api/v1/fines/{id}/history`: `[{id, at, actorId, action, reason, details}]`, newest first (`?page=0&size=100`, total in `X-Total-Count`).
`action` is `void | mark_paid | paid_online | dispute_opened | dispute_upheld | dispute_voided | dispute_withdrawn | payment_needs_review`; `actorId` is the operator or driver (null for `paid_online` and
for a dispute the system upheld); `reason` is null for `paid_online` and a withdrawal (for a dispute it is the message or the decision note); `details` is `{}` for void,
`{"method": "cash"}` for mark-paid, `{"method": "online", "paymentId": "..."}` for the online payment, `{"disputeId", "channel"}` for `dispute_opened` and `{"disputeId"}` for the other
dispute actions. Each command writes its row in the same transaction as the change.

### Evidence and disputes

**Frozen evidence.** When the fine sweep issues a fine, `FineIssuingService` writes one `fine_evidence` row in the same transaction (a rollback leaves neither fine nor evidence).
It holds `plate, zoneId, zoneAddress, spotId, spotNumber, violationType, violationId, ruleSummary, graceMinutes, violationDetectedAt, graceExpiredAt, issuedAt, detectionIds, imagePaths,
minConfidence, decisionBasis` (`automatic`). `ruleSummary` is plain text built when the violation was opened from the rule that decided it ("Parking is not allowed while this applies:
zone rule "Weekday" (Mon-Fri 08:00-18:00)."), stored with the violation (`spot_violations.rule_summary`) and copied into the evidence, so editing the zone rules later changes nothing.
`imagePaths` / `detectionIds` / `minConfidence` come from the newest (at most 10) non-rejected detections of the plate in the zone from 5 minutes before the violation until the fine
(`detections`, read model `DetectionView`, ADR 0001); they are empty / null when there are none. Nothing updates or deletes evidence: the entity is `@Immutable`, the repository only
has `save` and `findById`, and a trigger (`fine_evidence_immutable`) rejects every UPDATE and DELETE in the database (TRUNCATE is deliberately not covered). Fines issued before this
change have no evidence (`recorded:false`, "not recorded"); nothing is back-filled. Snapshots can show other people's cars: only `GET .../evidence` (operators with `violation:read`)
returns their keys, the driver's explanation never does. A key is used like the `imagePath` of a fine or detection (the existing snapshot endpoints of file-service/ingestion-service).

**Disputes** (`fine_disputes`): `id, fine_id, plate, zone_id, opened_by_user_id, channel (driver|operator), message, status (open|upheld|voided|withdrawn), opened_at, decided_by_user_id,
decided_at, decision_note`. At most one dispute per fine is open (partial unique index `fine_disputes_one_open_uq`). Rules:

* A dispute can be opened for a fine that is neither paid nor voided; a second open dispute is 409; a fine that an operator already upheld cannot be disputed again (a withdrawn
  dispute can be followed by a new one). The conflict messages are plain English sentences for the user interface.
* While a dispute is open the fine cannot be paid: `disputed` in the internal API makes payment-service refuse to start a payment, and an operator's mark-paid is 409 ("decide the dispute first").
* `decision` `voided` calls the existing `FineManagementService.voidFine` (same audit row, same `fine.voided` event, same row lock; the note is the void reason), which also ends the dispute as
  voided. An operator's plain `void` of a fine with an open dispute ends the dispute as voided too. `upheld` ends it and releases the hold. A decided or withdrawn dispute is final.
* A payment that completes for a fine whose dispute is still open (payment-service refuses to start one, so this is a race) is never refused or refunded here: the fine stays paid, the
  system closes the dispute as `upheld` (no `decided_by_user_id`, note "Closed automatically: the fine was paid while the dispute was open.", audit `dispute_upheld` without actor) and
  `fine.dispute.decided` is published. payment-service counts such payments in `payment_completed_for_disputed_total`.
* Every command locks the fine row first (the same lock as void, mark-paid and the payment event) and then reads the dispute, so none of them interleave. The message and the decision
  note are personal text: stored, shown to the driver and to staff, never logged and never part of an event. A fine or dispute the caller may not see is answered with 404, not 403.

Violation JSON: `id, spotId, zoneId, plate, userId, violationType, detectedAt, graceUntil, lastSeenAt, resolvedAt,
resolutionReason, fineIssuedAt, fineId, imagePath, notifiedAt, createdAt`. `violationType` is `BOUNDARY_EXCEEDED | WRONG_PERMIT | OVERSTAY | NO_PARKING | WRONG_VEHICLE_TYPE`.

## Events

| Direction | Exchange / routing key | Queue | Payload |
|---|---|---|---|
| consumed | `parkview.vehicle` / `vehicle.detected` | `vehicle.detected` | `cameraId, zoneId, plate?, vehiclePolygon[[x,y]], spotId?, spotNumber?, iou, confidence, timestamp(ms), vehicleClass?, viewId?` |
| consumed | `parkview.payment` / `payment.completed.fine` | `payment.completed.rule-engine` | `paymentId, referenceId (fine id), referenceType, userId?, amountSek, timestamp(ms)` |
| consumed | `parkview.session` / `session.ended` | `session.ended.rule-engine` | `plate, zoneId, endedAt, occurredAt` (read; the other fields of session-service are ignored) |
| consumed | `parkview.mapping` / `map.updated` | `map.updated.rule-engine.instance-<uuid>` (one per instance) | `zoneId` (read; `versionNumber, spotCount, changeReason, timestamp(ms)` are ignored). Also published after zone-rule changes: harmless, the zone's spots are reloaded once |
| published | `parkview.violation` / `violation.created` | | `violationId, plate, zoneId, zoneAddress?, cameraId?, userId?, spotId, spotNumber?, violationType, graceMinutes, timestamp(ms)` |
| published | `parkview.violation` / `violation.resolved` | | `violationId, plate, zoneId, reason (CORRECTED\|LEFT\|MANUAL), occurredAt` |
| published | `parkview.violation` / `fine.issued` | | `fineId, violationId, plate, userId?, zoneId, zoneAddress?, amountSek, timestamp(ms), violationType` |
| published | `parkview.violation` / `fine.paid` | | `fineId, plate, zoneId, amountSek, method (online\|cash\|transfer\|other), occurredAt` |
| published | `parkview.violation` / `fine.voided` | | `fineId, plate, zoneId, occurredAt` |
| published | `parkview.violation` / `fine.disputed` | | `disputeId, fineId, plate, zoneId, channel (driver\|operator), occurredAt` |
| published | `parkview.violation` / `fine.dispute.decided` | | `disputeId, fineId, plate, zoneId, userId?, outcome (upheld\|voided\|withdrawn), occurredAt`; `userId` is the owner of the plate when known |

`vehicle.detected` is an **observation** published by ingestion for every occupied spot (throttled to about one per
spot and plate every 30 s), not only for spots the detector flagged; the plate may be null (such observations are
ignored). `vehicleClass` is `car | motorcycle | bus | truck`, absent or null when unknown; other values are tolerated and
treated as unknown. `viewId` (uuid, optional) is the camera view the frame was checked against; it selects which spots the
vehicle is matched against (see flow step 2) and is absent for observations of a camera without views. `violationType` of `violation.created` and `fine.issued` is the upper-case enum name
(`BOUNDARY_EXCEEDED | WRONG_PERMIT | OVERSTAY | NO_PARKING | WRONG_VEHICLE_TYPE`); `fine.issued.violationType` is the
type of the violation the fine was issued for.

Payloads are validated; invalid ones go to `parkview.dlq` without retry, transient failures are retried 6 times
(exponential: 2 s, 6 s, 18 s, 54 s, 60 s, about 2.5 minutes in all) before dead-lettering. The window is that long on purpose: a
database restart or failover must not dead-letter `payment.completed.fine`, because then the money is taken and the fine stays
unpaid. It is the Spring Boot listener retry (`spring.rabbitmq.listener.simple.retry.*`, same property names as before),
set by `RABBIT_RETRY_MAX_ATTEMPTS` (6), `RABBIT_RETRY_INITIAL_INTERVAL` (2s), `RABBIT_RETRY_MULTIPLIER` (3.0) and
`RABBIT_RETRY_MAX_INTERVAL` (60s). It applies to every listener of the service, and a retrying message blocks its listener thread, so
a message that keeps failing for a transient-looking reason holds up the queue behind it for that long. Messages that can never succeed (unparseable, failing validation, or rejected on purpose:
`MessageConversionException`, `MethodArgumentNotValidException`, `AmqpRejectAndDontRequeueException`, also when wrapped by the listener, see `ListenerRetryConfig`) are not retried at all and go to `parkview.dlq` at once. Handlers are idempotent. Events are written to the outbox in the transaction of the state change and published after it committed (see "Transactional outbox").
`fine.issued` replaces the former key `violation.expired` (same payload).

`fine.paid` is published once, on the actual transition from unpaid to paid: by the first `payment.completed.fine` of a fine (`method` `online`) or by an operator's
mark-paid (`cash | transfer | other`). A repeated payment event, a repeated command or a payment of a fine that is already paid publishes nothing. `fine.voided` is published
once when an operator voids a fine (also when a dispute decision voids it). `fine.disputed` is published once when a dispute is opened, `fine.dispute.decided` once when it ends (also for a withdrawal
and when the system upholds it because the fine was paid). None carries free text or the actor (that is in the audit trail); `amountSek` is the fine's amount, not the amount of the payment.

## Transactional outbox

Every event this service publishes (`violation.created`, `violation.resolved`, `fine.issued`, `fine.paid`, `fine.voided`, `fine.disputed`, `fine.dispute.decided`) is a row in `rule_outbox_events`, written by
`OutboxService` in the same transaction as the state change (it refuses to run without one: a rollback leaves no event, a commit never loses one). The table is called `rule_outbox_events` because
payment-service owns `outbox_events` in the same database and its relay publishes every row of that table. `OutboxRelay` (scheduled every `relay-interval`) claims due rows with `FOR UPDATE SKIP LOCKED`, sends them
with publisher confirms and marks them published after the ack; consumers get what they got before: persistent JSON message, the row id as `messageId` (idempotency key), headers `x-event-type`, `x-source`
(`rule-service`) and, when a request caused the event, `x-request-id` (the former `__TypeId__` header is gone, consumers infer the type from their listener).

* Ordering: the claim query only offers the oldest unpublished row of each aggregate (a fine or a violation id), so the events of one fine leave in the order they were written and a failing row holds back only its own
  fine. There is no order between different fines. The payload is stored as text (not jsonb), so the JSON the consumers get is byte-identical to the former direct publish.
* Retries: nack, missing confirm or an unreachable broker leave the row in the outbox with exponential back-off (1 s, 2 s, 4 s ... `max-backoff`); rows are never given up, so a broker outage delays events and loses none.
  Delivery is at least once: consumers de-duplicate on `messageId`.
* A message the broker confirms but cannot route (no queue bound, e.g. no consumer connected yet) is counted (`rule_outbox_unroutable_total`), logged and treated as delivered, like the direct publish it replaces; with
  `retry-unroutable=true` it stays in the outbox until a queue exists.
* Clean-up: published rows older than `retention` are deleted hourly. Metrics: `rule_outbox_published_total`, `rule_outbox_failed_total`, `rule_outbox_unroutable_total`, `rule_outbox_pending`,
  `rule_outbox_oldest_pending_seconds` (alert when it keeps growing: the broker is down or a row keeps failing; the row's `last_error` says why).

## Data

Owned: `spot_violations`, `fines`, `plate_permits`, `fine_actions`, `fine_evidence`, `fine_disputes`, `rule_outbox_events` (Liquibase `db/changelog`, idempotent and reconciling because the
Supabase migrations created older shapes of the first two). Read only: `parking_spots`, `zones`, `zone_rules`, `sessions`, `user_plates`, `detections`
(see "Data read from other services").

* `spot_violations.violation_type` is upper-case; `fines.reason` is lower-case (JPA converter, e.g. `wrong_vehicle_type`).
  Changeset 006 widens `spot_violations_violation_type_check` and `fines_reason_check` idempotently.
* `fines` also carries `paid_at`, `paid_method` (`online | cash | transfer | other`), `paid_by`, `voided_at`, `void_reason`, `voided_by` (changeset 008, add-if-missing;
  constraints `fines_paid_method_check` and `fines_not_paid_and_voided_check`). Fines paid before 008 have `paid_at` / `paid_method` null.
* `fine_actions(id, fine_id -> fines on delete cascade, at, actor_id null, action void|mark_paid|paid_online|dispute_opened|dispute_upheld|dispute_voided|dispute_withdrawn|payment_needs_review, reason null, details jsonb)` with the index
  `fine_actions_fine_at_idx (fine_id, at desc)`; row level security is on without policies (as for `plate_permits`): not reachable through PostgREST.
* `fine_evidence(fine_id pk -> fines, plate, zone_id, zone_address, spot_id, spot_number, violation_type, violation_id, rule_summary, grace_minutes, violation_detected_at, grace_expired_at, issued_at,
  detection_ids jsonb, image_paths jsonb, min_confidence numeric(5,4), decision_basis 'automatic', created_at)` (changeset 009; no cascade; trigger `fine_evidence_immutable` rejects UPDATE and DELETE),
  and `spot_violations.rule_summary` (the rule text frozen into it).
* `fine_disputes(id, fine_id -> fines on delete cascade, plate, zone_id, opened_by_user_id null, channel driver|operator, message, status open|upheld|voided|withdrawn, opened_at, decided_by_user_id null,
  decided_at null, decision_note null)` (changeset 010) with the partial unique index `fine_disputes_one_open_uq (fine_id) where status = 'open'`, `fine_disputes_fine_opened_idx` and
  `fine_disputes_zone_opened_idx`; row level security on without policies, like `fine_actions`.
* `rule_outbox_events(id, seq identity, aggregate_type, aggregate_id, exchange, routing_key, payload text, request_id, created_at, published_at, attempts, next_attempt_at, last_error)` (changeset 011) with
  partial indexes on the unpublished rows.
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
| `sessions` | session-service | `id`, `plate`, `zone_id`, `started_at`, `ended_at`; `voided_at` (not mapped: a voided session is not active, so it never causes an `OVERSTAY`; read through a native query when the column exists, probed at most once a minute while it does not, so rule-service may start before session-service migrated) | `SessionView` |
| `user_plates` | Supabase schema (driver plates) | `id`, `user_id`, `plate` | `PlateOwnerView` |
| `parking_spots` | parking-mapping-service | `id`, `zone_id`, `spot_number`, `type`, `shape` (only `polygon` is read for legacy spots), `permit_type`, `coordinates` (jsonb, null for a spot that only has a geo polygon: skipped), `boundary_grace_min`, `permit_grace_min`, `max_minutes` | `ParkingSpotView` |
| `parking_spots` (camera views) | parking-mapping-service | `view_id` (uuid, null = legacy spot), `image_polygon` (jsonb, polygon in the view's corrected image) | `SpotPlacementView` (a `@Subselect`, see below) |
| `detections` | ingestion-service | `id`, `plate`, `corrected_plate`, `zone_id`, `image_path`, `confidence`, `review_status`, `at` (the snapshots that go into the evidence of a fine; rejected detections are left out) | `DetectionView` |

**Camera views.** A spot is either a legacy spot (`view_id` null, polygon in `coordinates`) or drawn on a camera view
(`view_id` set, polygon in `image_polygon`). `SpotDirectory` has `polygonSpotsOf(zone)` for the first kind and
`polygonSpotsOfView(zone, viewId)` for the second; `SpotCacheService` caches each separately (key: zone and view id,
the same TTL; `map.updated` evicts a zone at once, the TTL is the safety net).
The two columns are read through `SpotPlacementView`, a `@Subselect` entity and not a `@Table` one, because
`ddl-auto: validate` would refuse to start against a database that has not got the migration yet; `SpotViewSchema`
asks `information_schema` whether both columns exist each time a zone is loaded. In a database without them every
polygon spot is a legacy spot and there are no view spots (no error, and the migration is noticed without a restart).
A view query also filters on the zone of the event, so a view id never yields spots of another zone.
The test schema (`src/test/resources/test-schema.sql`) has the new columns and `camera_views`;
`test-schema-before-views.sql` takes it back to the old shape for `SpotsBeforeViewsMigrationTest`.

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
| `LEGACY_ROLE_FALLBACK` | `true` | no | `parkview.security.legacy-role-fallback`: tokens without `app_metadata.permissions` get their role's default permissions; set `false` once every token carries the claim |
| `INTERNAL_API_KEY` | empty | no | secret for `/internal/**` and non-health actuator endpoints; empty disables them. Also sent as `X-Internal-Api-Key` to lookup-service |
| `LOOKUP_SERVICE_URL` | `http://localhost:8089` | no | lookup-service base URL for vehicle registry data |
| `DOCS_ENABLED` | `false` | no | Swagger UI and OpenAPI spec |

Transactional outbox, Spring property `parkview.outbox.*` (set with `SPRING_APPLICATION_JSON` or the matching `PARKVIEW_OUTBOX_*` variables):

| Property | Default | Description |
|---|---|---|
| `relay-enabled` | `true` | switch the scheduled relay and the clean-up off (tests, maintenance); events then accumulate in `rule_outbox_events` |
| `relay-interval` | `250ms` | delay between two relay runs; also the delay between two events of one fine |
| `batch-size` | `50` | rows claimed per run (at most one per fine or violation) |
| `confirm-timeout` | `5s` | how long to wait for the broker's publisher confirm |
| `max-backoff` | `60s` | upper bound of the exponential retry delay of a failing row |
| `retention` | `14d` | how long published rows are kept |
| `retry-unroutable` | `false` | `true`: a message without a bound queue stays in the outbox and is retried; `false`: it is counted and treated as delivered |

Business settings, Spring property `parkview.rule-engine.*` (set with `SPRING_APPLICATION_JSON` or the matching `PARKVIEW_RULE_ENGINE_*` variables):

| Property | Default | Description |
|---|---|---|
| `iou-threshold` / `containment-threshold` | `0.85` / `0.90` | vehicle counts as inside the lines when either is reached |
| `min-overlap` | `0.5` | share of the vehicle that must lie in a spot for it to be parked there |
| `default-boundary-grace-minutes` / `default-permit-grace-minutes` | `10` / `30` | grace of `BOUNDARY_EXCEEDED` / `WRONG_PERMIT` when the spot defines none (`boundary_grace_min` / `permit_grace_min` is null or negative); an explicit `0` on the spot is a grace of 0 |
| `no-parking-grace-minutes` | `5` | grace of `NO_PARKING` |
| `overstay-grace-minutes` | `5` | grace of `OVERSTAY` |
| `wrong-vehicle-type-grace-minutes` | `10` | grace of `WRONG_VEHICLE_TYPE` |
| `fine-amount-{no-parking,overstay,wrong-permit,boundary-exceeded,wrong-vehicle-type}-sek` | `900` / `450` / `700` / `900` / `700` | fine amounts |
| `expire-sweep-ms` / `expire-batch-size` | `60000` / `20` | fine sweep delay and batch size |
| `stale-minutes` / `stale-sweep-ms` | `15` / `60000` | re-confirmation window (resolve as `LEFT`) and sweep delay |
| `fine-freshness-seconds` | `120` | a violation is fined only if a detection confirmed it within this time when the fine sweep claims it (the vehicle must demonstrably still be there) |
| `observation-max-age-seconds` | `600` | an observation older than this (event time) is dropped |
| `refine-block-hours` | `6` | a plate fined for a reason in a zone gets no new violation of that reason there within this time, whatever happens to the spot; `0` turns it off. Product decision: a car that comes back within the window and offends again is not fined again |
| `touch-interval-seconds` | `10` | minimum time between `last_seen_at` writes per violation |
| `spot-cache-ttl-minutes` | `5` | how long zone polygons are cached (per zone and view); `map.updated` evicts a zone at once |
| `spot-cache-negative-ttl-seconds` | `15` | how long a zone or view WITHOUT spots is cached |
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

## Tracing

Distributed tracing (OpenTelemetry through Micrometer Tracing, W3C `traceparent`) is **off by default**: while
`TRACING_ENABLED` is unset there is no tracer, no exporter, no trace header and no connection attempt, and the
service behaves exactly as before. It is switched on by environment variables alone:

| Variable | Default | Description |
|---|---|---|
| `TRACING_ENABLED` | `false` | The single switch (`management.tracing.enabled`) |
| `TRACING_SAMPLING` | `0.1` | Share of new traces recorded (`0` to `1`); a caller's sampled trace is always continued |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTLP/HTTP collector, spans go to `<endpoint>/v1/traces`; `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` takes a full URL |
| `OTEL_SERVICE_NAME` | `rule-service` | Service name on the spans (default: `spring.application.name`) |

Traced: incoming requests, the calls to lookup-service (client spans carrying `traceparent`), published events and consumed
messages (continuing the publisher's trace).

Not traced: `/actuator/**` (probes and scrapes are dropped before export). Spans never carry request or response
bodies or tokens, and the raw URL (paths and queries hold plates and, on the stream, tokens) is removed before
export: only the route template, method and status are recorded. `requestId` in the log pattern is unchanged; with
tracing on Boot adds `[traceId-spanId]` to every log line.

## Run locally

```bash
export JAVA_HOME=<jdk 21>
SPRING_PROFILES_ACTIVE=local SUPABASE_URL=https://<ref>.supabase.co mvn spring-boot:run   # needs Postgres and RabbitMQ
mvn -B -ntp verify        # unit, web-layer and Testcontainers tests (needs a Docker daemon)
docker build -t rule-service . && docker run --rm -p 8081:8081 -e DB_URL=... -e DB_PASS=... -e SUPABASE_URL=... rule-service
```

The `local` profile only sets a database password and an internal API key for development.
Tests: geometry table tests (clockwise and counter-clockwise polygons, crossing edges), table-driven rule engine tests (time windows
incl. midnight crossing, holidays and Europe/Stockholm DST dates; priority and first match wins; every violation type and
fail-open case; vehicle-code mapping; grace values; overstay with a fixed clock), the Jollyday calendar for the Swedish
2026 holidays, the lookup client (MockRestServiceServer), violation lifecycle (type change, grace per type), fine issuing,
MockMvc security tests (401/403/404/409/400, problem+json), listener payloads, Testcontainers tests (unique index,
`SKIP LOCKED` claim, permit queries, read models against Supabase-style tables with extra columns, `time`/array columns,
Liquibase 006 and 008 on the legacy `fines` shape) and a full flow test against real Postgres and RabbitMQ (incl. void, manual and online payment events, and a void racing a payment).
Evidence and disputes: `FineEvidenceServiceTest` (what is frozen, snapshots, fallbacks), `RuleSummariesTest` (the rule text per violation type), `FineEvidenceRepositoryTest` (round trip, the trigger
rejects UPDATE and DELETE, the entity cannot change a row, no update/delete API), `FineIssuingTransactionTest` (fine, evidence and outbox row commit together, a rollback leaves none), `DisputeServiceTest`
(rules, audit rows, events, void through the existing logic, payment hold, system upheld after a race, nothing logged), `DisputeRepositoryTest` (unique open index, constraints, latest status, list queries),
`DisputeControllerTest` and `FineEvidenceControllerTest` (anonymous 401, other plate / other zone 404, missing permission 403, admin, validation 400, state errors 409, shapes, no snapshots in the explanation).
Outbox: `OutboxRelayTest` (confirms, back-off, unroutable, order), `OutboxServiceTest` (bytes identical to the former direct publish), `OutboxEventRepositoryTest` (claim order, per-aggregate ordering, SKIP LOCKED
with two sessions, clean-up), `OutboxRelayBrokerTest` and `RuleServiceFlowTest` (real RabbitMQ).
The `fine:manage` / `violation:read` / zone matrices (admin, operator with and without the permission, other zone, driver, anonymous), body validation, the 409 cases,
idempotency, voided fines hidden from lists and drivers, audit rows and event payloads are covered in `FineControllerTest`, `FineManagementControllerTest`, `FineManagementServiceTest`, `FineSettlementServiceTest` and `FineIssuingServiceTest`.

## Operations

* Health: `/actuator/health/liveness` and `/readiness` (public); metrics: `/actuator/prometheus` (internal key). Logs carry `requestId`; plates are masked.
* Several instances may run: inserts are protected by the unique index, fines by `SKIP LOCKED`.
* Messages in `parkview.dlq` are invalid payloads or exhausted retries; inspect `x-death` headers.
* Fines are never issued while the database is unreachable; the sweep retries every `expire-sweep-ms`. A broker outage no longer blocks anything: events wait in `rule_outbox_events`
  (watch `rule_outbox_pending` and `rule_outbox_oldest_pending_seconds`) and go out in order when the broker is back. A violation that failed repeatedly is logged at ERROR with its id.
* Disputes pile up when nobody reads `GET /api/v1/disputes`: the `fine.disputed` event reaches notification-service and the operator stream. A fine under dispute that nobody decides stays unpayable;
  an operator can always decide it or void the fine. A payment that raced with a dispute is logged at WARN ("paid while a dispute was open").
* Snapshots in the evidence are storage keys: deleting a snapshot in storage does not change the evidence row, the key then simply resolves to nothing.
* Without detections (camera or ingestion down) open violations are resolved as `LEFT` after `stale-minutes`
  instead of being fined.
* Observations without a plate, polygons that cannot be parsed and observations older than `observation-max-age-seconds` are ignored.
* A payment that completes for a fine that is already paid (by hand, or by another payment), voided, or for another amount than the fine
  writes an audit row `payment_needs_review` (`actorId` null, `reason` the plain-language cause, `details`
  `{paymentId, causes: [voided|already_paid|amount_mismatch], paidAmountSek, fineAmountSek}`; one row per payment, a redelivery adds none)
  and increments `ruleengine_payments_needs_review_total{cause}` (`unknown_fine` counts a payment for a fine that does not exist, which
  has no row to write to). Nothing is published and nothing is refunded: a person looks at the fine history and refunds or corrects. A
  first payment of another amount still settles an unpaid fine, it is only flagged. Alert on the counter.
* lookup-service down: decisions that need vehicle data are skipped (fail open) and `ruleengine_vehicle_lookups_failed_total`
  rises; after `negative-cache-ttl-seconds` the next observation asks again.
* Rules are read through a cache: after an operator edit in the UI allow `rule-cache-ttl-seconds` before it is enforced.
* The Supabase migration `20260512_spot_violations.sql` schedules a `pg_cron` job that also fines expired
  violations; it must be unscheduled (`select cron.unschedule('process-spot-violations')`) so this service is the only issuer.
