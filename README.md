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
* Publishes every event through a transactional outbox (`outbox_events`), written in the transaction of the state change.

## Architecture position

```
ingestion --vehicle.detected--> [ rule-service ] --violation.created / violation.resolved / fine.issued / fine.paid / fine.voided / fine.disputed / fine.dispute.decided--> notification, event-streaming
payment-service --payment.completed.fine--> [ rule-service ]
session-service --session.ended--> [ rule-service ]
parking-mapping-service --map.updated--> [ rule-service ]
payment-service --GET /internal/v1/fines/{id}--> [ rule-service ]
frontend --JWT via api-gateway--> [ rule-service ]  (violations, fines, permits)
rule-service reads the published views mapping.v_zones/v_sites/v_zone_rules/v_parking_spots, sessions.v_sessions, identity.v_user_plates, identity.v_user_sites, ingestion.v_detections (owned by other services, behind the ports in client/)
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
   violation of the old spot is closed, see `map.updated`, and the new spot has a new id, so the fine itself is what remembers the stay; a voided
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
* `map.updated` (parking-mapping-service): the spots and rules of that site (legacy and every view) are evicted from the cache, so a
  re-scan, rollback or "empty the area" applies to the next observation. Every instance has its own queue for this event
  (`map.updated.rule-engine.instance-<uuid>`: not durable, exclusive, deleted with its connection, dead-lettered like the others), so every
  instance evicts its own cache; with one shared queue only the instance that got the message would, and the others kept the old spots
  for the whole TTL. An event published while an instance is down is not needed: its cache starts empty. A site without spots is cached for
  `spot-cache-negative-ttl-seconds` only.
  After the eviction the listener closes the active violations of the site whose spot is no longer in `mapping.v_parking_spots`
  (`ViolationService.resolveOfRemovedSpots`, resolved as `LEFT`, `violation.resolved` is published): no foreign key to the spots of
  another service takes them along (ADR 0010). A violation that already has a fine stays as it is. A violation opened from a stale
  cache in the instant before the eviction is closed by the stale sweep, since no detection can confirm it any more. A violation
  whose zone is deleted needs nothing: it belongs to its site and is still valid.

## Sites (ADR 0009)

The place is the **site**; a zone is an optional part of it with its own rules. Spots, sessions, detections, violations, fines
and disputes belong to a site (`site_id`) and only optionally to a zone (`zone_id` may be null).

* **Effective rules of a spot**: in a zone the zone's parameters (`zones.max_minutes`, `zones.allows_parking`, country,
  timezone), its zone rules and the site rules of its site; without a zone the site's parameters (`sites.max_minutes`,
  `sites.allows_parking`, ...) and its site rules (`zone_rules` rows without a zone). At equal priority a zone rule is evaluated
  before a site rule. `allows_parking = false` is a `NO_PARKING`. A spot without a zone is controlled exactly like one in a
  zone: violations and fines are raised and carry `site_id`.
* Spots are read and cached per site (and view); `map.updated` evicts the spots and rules of its `siteId` (an event with only a
  `zoneId` evicts the zone's site). Sessions match on plate + site (+ zone when the spot has one); evidence is read per site.
* Permits: `site_id` null = every site, `zone_id` null = the whole site. A permit for a zone also stores the zone's site
  (`zone.otherSite`, 422, when `siteId` names another site).
* Scope: the operator's current sites, looked up through `OperatorSites` (`identity.v_user_sites`, not in the token, see "Plates of a driver"
  and ADR 0011). A record is reachable when its `site_id` is in the
  scope; a zone-keyed endpoint resolves the zone's site through `mapping.v_zones` and checks it (an unknown zone is reachable by
  administrators only; granting a permit for an unknown zone is a 404).
* Consumed events accept `siteId` next to `zoneId` and need at least one of them (else invalid, dead-lettered); without `siteId`
  the zone's site is used. Published violation, fine and dispute events carry `siteId` (and `siteAddress` where `zoneAddress`
  exists); `zoneId`/`zoneAddress` are null without a zone.
* Schema: `site_id NOT NULL` on `spot_violations`, `fines`, `fine_disputes`, `fine_evidence` (+ `site_address`), nullable on
  `plate_permits`; `zone_id` nullable. No fill trigger: rule-service writes `site_id` itself. The read models map the `site_id`
  columns of the catalogue views of parking-mapping-service, session-service and ingestion-service; the startup schema validation
  fails when a view is missing, so deploy rule-service after them.

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
* `RetentionJob`, `RuleRetention`, `PostgresRetentionLock` (package `service.retention`): the data-retention job, its batch work and its advisory lock, see "Data retention".
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
with `X-Internal-Api-Key` (`LOOKUP_INTERNAL_API_KEY`, the key of lookup-service); only `vehicleType` and `fuelType` are read (never owner data). Found vehicles are cached
`parkview.lookup.cache-ttl-minutes`; "not found" and failed lookups for `negative-cache-ttl-seconds`.
Explicit connect/read timeouts, no retries. `parkview.rule-engine.vehicle-lookup-enabled=false` switches it off.

### Metrics

`ruleengine_observations_evaluated_total`, `ruleengine_violations_created_total{type}`,
`ruleengine_vehicle_lookups_failed_total`, `ruleengine_rules_matched_total{outcome=no_parking|permit|max_minutes}`.

## API

Interactive docs: `/swagger-ui.html` and `/v3/api-docs` when `DOCS_ENABLED=true`. Errors are RFC 7807
`application/problem+json`. Lists are plain JSON arrays, bounded by `?page=0&size=100` (max 500), total in `X-Total-Count`.

Authorization uses the permissions in `app_metadata.permissions` (string[], one authority per permission, named exactly like it;
ADR 0002 and `contracts/permissions.json` in smart-parking-e2e) plus the operator's site scope looked up through `OperatorSites` (`AccessPolicy`); there is no `plates` and no `sites` claim, see "Plates of a driver".
A token WITHOUT the claim gets no permissions (`parkview.security.legacy-role-fallback` is `false` by default, so a token with `role=admin` and no claim is not authorised);
with the flag set to true it gets the default permissions of its role; a present claim, even an empty list, is never overridden. By default an operator holds `violation:read`, `violation:resolve` and
`permit:manage`, an admin everything (`fine:manage` is an admin default only; an operator needs it granted).

| Endpoint | Access | Notes |
|---|---|---|
| `GET /api/v1/violations/site/{siteId}` | `violation:read` + site in the scope (admin: any) | active violations of the site, in its zones and outside them, newest first |
| `GET /api/v1/violations/zone/{zoneId}` | `violation:read` + site of the zone is one of the operator's sites (admin: any zone) | active (not resolved, not fined) violations, newest first |
| `GET /api/v1/violations/plate/{plate}` | driver (own plate), or `violation:read` | all violations of the plate, newest first; an operator sees only those in their `zones`, an administrator all; `imagePath` only with `violation:read` |
| `PATCH /api/v1/violations/{id}/resolve` | `violation:resolve` + zone of the violation (admin: any zone) | 204; 404 unknown; 409 when already fined; publishes `violation.resolved` (`MANUAL`) |
| `POST /api/v1/permits` | `permit:manage` + zone (global permit: admin) | 201 + `Location`; a permit without `zoneId` is valid everywhere and needs an admin |
| `GET /api/v1/permits?plate=&zoneId=` | `permit:manage` | operators see their zones and global permits |
| `GET /api/v1/permits/{id}` | `permit:manage` + zone of the permit | |
| `DELETE /api/v1/permits/{id}` | `permit:manage` + zone of the permit (global: admin) | 204 |
| `GET /api/v1/fines/plate/{plate}?includeVoided=` | driver (own plate: registered for the user, see "Plates of a driver"), or `violation:read` | fines of the plate, newest first; another driver's plate is 403; an operator sees only the fines in their `sites` (a plate with fines elsewhere gives an empty list), an administrator all; voided fines only for staff with `includeVoided=true` (drivers never see them); `imagePath` only with `violation:read` |
| `GET /api/v1/fines/site/{siteId}?paid=&includeVoided=` | `violation:read` + site in the scope (admin: any) | fines of the site, in its zones and outside them; filters as for a zone |
| `GET /api/v1/fines/zone/{zoneId}?paid=&includeVoided=` | `violation:read` + site of the zone is one of the operator's sites (admin: any zone) | fines of the zone, newest first; optional `paid=true\|false` filter; voided fines left out unless `includeVoided=true` |
| `GET /api/v1/fines/{id}` | driver (plate of the fine is registered for the user), or `violation:read` + zone of the fine (admin: any zone) | 404 unknown, 403 for someone else's fine; a driver gets 404 for a voided fine |
| `GET /api/v1/fines/{id}/history` | `violation:read` + zone of the fine (admin: any zone) | audit trail, newest first, paged; 404 unknown |
| `POST /api/v1/fines/{id}/void` | `fine:manage` + zone of the fine (admin: any zone) | body `{reason}`; 200 + the fine; 409 when the fine is paid; idempotent (already voided: 200, same body, nothing new); publishes `fine.voided` |
| `POST /api/v1/fines/{id}/mark-paid` | `fine:manage` + zone of the fine (admin: any zone) | body `{reason, method}`; 200 + the fine; 409 when the fine is voided; idempotent (already paid: 200, nothing new); publishes `fine.paid` |
| `GET /api/v1/fines/{id}/evidence` | `violation:read` + zone of the fine (admin: any zone) | the frozen evidence incl. snapshot keys; `{recorded:false, fineId}` for fines issued before evidence was recorded; **404** for an unknown fine and for a fine outside the caller's zones |
| `GET /api/v1/fines/{id}/explanation` | driver (plate of the fine is registered for the user), or `violation:read` + zone | plain-language view, never snapshots or detection ids; 404 for someone else's fine; also for voided fines (the driver can see how a dispute ended) |
| `POST /api/v1/fines/{id}/dispute` | driver (own plate, channel `driver`), or `fine:manage` + zone (channel `operator`, "customer called") | body `{message}` (10-1000 characters after trimming); 201 + `Location` + the dispute; 404 for someone else's fine; 409 when the fine is paid or voided, already has an open dispute or was already upheld |
| `GET /api/v1/fines/{id}/dispute` | driver (own plate), or `violation:read` / `fine:manage` + zone | the latest dispute of the fine; 404 when there is none |
| `POST /api/v1/disputes/{id}/withdraw` | driver (own plate) | 200 + the dispute; 404 for someone else's; 409 when not open any more |
| `GET /api/v1/disputes?status=open\|decided\|all&zoneId=&page=&size=` | `fine:manage`, limited to the zones in the token (admin: all) | newest first, total in `X-Total-Count`; `status` defaults to `open`, `decided` = upheld, voided and withdrawn; `zoneId` must be one of the caller's zones (403 otherwise) |
| `POST /api/v1/disputes/{id}/decision` | `fine:manage` + zone of the dispute (admin: any zone) | body `{outcome: upheld\|voided, note}` (note 10-1000 characters, mandatory); 200 + the dispute; 404 outside the caller's zones; 409 when already decided or withdrawn |
| `GET /internal/v1/fines/{id}` | `X-Internal-Api-Key` | `{id, plate, userId, zoneId, amountSek, currency, paid, voided, disputed}`; payment-service must not charge a voided fine nor start a payment while `disputed` |

Fine JSON (read API and the response of the two commands; online payment goes through payment-service): `id, plate, siteId, zoneId, reason, amountSek,
issuedAt, paid, imagePath, paidAt, voided, voidedAt, disputed, disputeStatus`. `reason` is the stored lower-case value (`no_parking | overstay | wrong_permit | boundary_exceeded | wrong_vehicle_type`),
`amountSek` a number, `issuedAt`, `paidAt` and `voidedAt` ISO-8601 instants; `zoneId`, `imagePath`, `paidAt` and `voidedAt` may be null. `disputed` is true while a dispute is open, `disputeStatus` is the status of the latest dispute (`open | upheld | voided | withdrawn`) or null. The owner's `userId`, the void reason and the actors are never returned here. The lists are served from
the indexes `fines_plate_issued_idx`, `fines_site_issued_idx` and `fines_zone_issued_idx`. These endpoints replace the frontend's direct read of the `fines` table, so the row level
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

### Plates of a driver

The access token carries identity and coarse authorisation only: `sub`, `app_metadata.role` and `permissions`.
**There is no `plates` claim**: plates are personal data, would be stale until the token is renewed (a newly added plate invisible for up to
the token lifetime) and have no size bound. Whenever a driver addresses a plate (`AccessPolicy.canAccessPlate`) the service asks
`PlateDirectory.platesOf(userId)`; `ReadModelPlateDirectory` reads `identity.v_user_plates` (`PlateOwnerView.findByUserId`) and caches the
set per user for `parkview.access.plates-cache-ttl` (default 30 s, at most `plates-cache-max` users): a plate registered by the driver is
accepted within that time, and no cache entry outlives it. Staff (operator, admin) do not need the directory.

**There is no `sites` claim either** (an access-control list inside a bearer token stays valid after an administrator removed a site and grows
without bound). `CurrentUser` resolves the sites of an **operator** once per request through `OperatorSites.sitesOf(userId)`;
`ReadModelOperatorSites` reads `identity.v_user_sites` (`UserSiteView.findByUserId`) and caches the set per user for
`parkview.access.sites-cache-ttl` (default 30 s, at most `sites-cache-max` users): a removed site stops working and a new one starts
within that time, with no new token. Admins mean every site and drivers none, neither triggers a lookup. A database error refuses the
request (never an empty or cached-forever answer).

### Error texts and languages

The texts of API errors (`detail` and `message` of the problem+json, `title`, the `errors[].message` of validation failures, also the 401/403 of the
security chain) are in the language of the `Accept-Language` header: Swedish for `sv` (also `sv-SE`), English when the header is missing or the language
is not offered (`fr`, `*`, malformed). Never the language of the server (`spring.web.locale: en`, `locale-resolver: accept-header`). Logs, event payloads and
HTTP statuses are not affected and stay English.

* Bundles: `src/main/resources/messages/errors.properties` (English base, complete) and `errors_sv.properties`. UTF-8; `{0}` is the parameter
  (the id of a `notFound.*`). A message without parameters is used as it is, one with parameters goes through `MessageFormat` (a literal apostrophe is
  written `''` there); the texts avoid apostrophes. `ErrorMessagesBundleTest` checks that every English key has a Swedish text with the same placeholders.
* An exception carries a stable `code`: `ConflictException.coded("dispute.alreadyOpen", "<English text>", args...)`, likewise `BusinessRuleException.coded(...)`.
  `ResourceNotFoundException(resource, id)` derives `notFound.<resource>` (lower case, spaces as `-`). The English text in the code is also the text when a
  bundle has no entry, so an exception without a code (`new ConflictException("...")`) keeps working and stays English.
* The problem gets one extra member, `code` (a client may switch on it, not on the text): `<area>.<reason>` in lower camelCase, unique in this service and
  never renamed. The common ones are `error.forbidden`, `error.unauthorized`, `error.dataConflict`, `error.validation.params`, `error.validation.body`,
  `error.internal`.
* Codes of this service: `dispute.fineAlreadyPaid`, `dispute.fineVoided`, `dispute.alreadyOpen`, `dispute.alreadyUpheld`, `dispute.withdrawn`,
  `dispute.alreadyDecided` (409); `fine.voidPaid`, `fine.payVoided`, `fine.payUnderReview` (409); `violation.fineAlreadyIssued` (409);
  `permit.plateEmpty`, `permit.validToNotAfterValidFrom` (422); `notFound.fine`, `notFound.dispute`, `notFound.dispute-of-fine`, `notFound.permit`,
  `notFound.violation` (404).
* DTO validation: the literal `message` of `@Pattern` is `{validation.<rule>}` (`plateCharacters`, `disputeOutcome`, `paidMethod`, `disputeStatusFilter`) and
  `@NotBlank`/`@Size` use the bundles too, so a field error is Swedish or English, never the language of the validator library. `ErrorMessagesConfig` wires
  the bundles into the validator.
* Adding a message: new code in the exception, one line in `errors.properties` and one in `errors_sv.properties` (same placeholders). Internal guards
  (`IllegalStateException`, `IllegalArgumentException`, which are 500 and never shown) stay literal on purpose.

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

Every event this service publishes (`violation.created`, `violation.resolved`, `fine.issued`, `fine.paid`, `fine.voided`, `fine.disputed`, `fine.dispute.decided`) is a row in `outbox_events`, written by
`OutboxService` in the same transaction as the state change (it refuses to run without one: a rollback leaves no event, a commit never loses one). The table, its columns and the relay are the same in every
service that publishes events (platform standards, "Transactional outbox"); the table lives in this service's schema, so no other relay sees it. `OutboxRelay` (scheduled every `relay-interval`) claims due rows with `FOR UPDATE SKIP LOCKED`, sends them
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

Schema `rules`, owned by the role `svc_rule` (ADR 0010). Everything is created by the one baseline changelog
`db/changelog/001-baseline.sql` (the schema, the role and its `search_path = rules, public` are created by the infrastructure;
Liquibase's tracking tables `databasechangelog*` live in the same schema). Owned tables: `spot_violations`, `fines`,
`plate_permits`, `fine_actions`, `fine_evidence`, `fine_disputes`, `outbox_events`, and the published views `rules.v_fines` and `rules.v_fine_evidence`.
No foreign key leaves the schema: `spot_violations.spot_id/zone_id/user_id`, `fines.zone_id/user_id` and the ids in the
other tables are plain columns. Inside the schema every foreign key restricts: nothing is deleted by a cascade, only by the retention job.
Read only: the catalogue views of other services (see "Data read from other services").

* `spot_violations.violation_type` and `fine_evidence.violation_type` are upper-case, `fines.reason` is lower-case (JPA converter, e.g. `wrong_vehicle_type`); all
  are limited by a check constraint.
* `fines(id, plate, site_id, zone_id null, reason, amount_sek, issued_at, paid, user_id null, image_path null, created_at, paid_at, paid_method, paid_by, voided_at, void_reason, voided_by)`. Constraints:
  `fines_reason_check`, `fines_paid_method_check` (`online | cash | transfer | other`), `fines_not_paid_and_voided_check` (never both),
  `fines_amount_check` (`amount_sek >= 0`), `fines_paid_consistent_check` (`paid`, `paid_at` and `paid_method` are set together, as `Fine.markPaid` does) and
  `fines_voided_consistent_check` (`voided_at` and `voided_by` are set together, `void_reason` only on a voided fine; it may be null there because the retention job clears it).
  Indexes `fines_plate_issued_idx`, `fines_site_issued_idx`, `fines_zone_issued_idx`.
* One fine per violation, enforced by the database: `fine_evidence.violation_id` is unique, `spot_violations.fine_id` has the foreign key `spot_violations_fine_fk -> fines` and the partial unique
  index `spot_violations_fine_uq (fine_id) where fine_id is not null`, and the check `spot_violations_fine_paired_check` says that `fine_id` and `fine_issued_at` are set together
  (`Violation.markFined` sets both). The claim with `FOR UPDATE SKIP LOCKED` already makes a second fine unlikely; should one be attempted anyway (`ViolationJobs`), its transaction
  fails on the constraint, rolls back as a whole (no second fine, evidence or event) and is logged as "fined already" instead of as an error.
* `fine_actions(id, fine_id -> fines on delete restrict, at, actor_id null, action void|mark_paid|paid_online|dispute_opened|dispute_upheld|dispute_voided|dispute_withdrawn|payment_needs_review, reason null, details jsonb)` with the index
  `fine_actions_fine_at_idx (fine_id, at desc)`.
* `fine_evidence(fine_id pk -> fines, plate, site_id, site_address, zone_id, zone_address, spot_id, spot_number, violation_type, violation_id unique, rule_summary, grace_minutes, violation_detected_at, grace_expired_at, issued_at,
  detection_ids jsonb, image_paths jsonb, min_confidence numeric(5,4), decision_basis 'automatic', created_at)` (no cascade) and `spot_violations.rule_summary` (the rule text frozen into it).
  The trigger `fine_evidence_immutable` rejects every DELETE and every UPDATE, with the single exception of the retention job (see "Data retention"). Indexes: `gin (detection_ids jsonb_path_ops)` and
  `gin (image_paths jsonb_path_ops)`, because ingestion-service asks through `v_fine_evidence` with jsonb containment which detections and pictures are evidence.
* `fine_disputes(id, fine_id -> fines on delete restrict, plate, site_id, zone_id, opened_by_user_id null, channel driver|operator, message, status open|upheld|voided|withdrawn, opened_at, decided_by_user_id null,
  decided_at null, decision_note null)` with the partial unique index `fine_disputes_one_open_uq (fine_id) where status = 'open'`, `fine_disputes_fine_opened_idx`,
  `fine_disputes_site_opened_idx` and `fine_disputes_zone_opened_idx`.
* `outbox_events(id, seq identity unique, aggregate_type, aggregate_id, exchange, routing_key, payload text, request_id, created_at, published_at, attempts, next_attempt_at, last_error)` with exactly the standard
  indexes: `outbox_events_due_idx (seq)` and `outbox_events_pending_aggregate_idx (aggregate_id, seq)` on the unpublished rows, `outbox_events_published_idx (published_at)` on the published ones.
* One open violation per plate, spot and type: partial unique index `spot_violations_open_uq`. Other `spot_violations` indexes: `spot_violations_active_idx`, `_stale_idx`, `_site_active_idx`, `_zone_active_idx`
  (the sweeps and the read API look at active rows only), `spot_violations_plate_detected_idx`, and `spot_violations_resolved_idx (resolved_at)` for the retention job.
* `plate_permits(plate, permit_type, site_id null = every site, zone_id null = whole site, valid_from, valid_to null = open ended, created_by)`.
  Permit types are compared case-insensitively against `mapping.v_parking_spots.permit_type`.

**Published views** (changeset `3-published-views`, `runOnChange:true`: dropped and created again whenever it changes, separate from the tables). They carry only the columns that the readers use, no
free text and no personal data. The only reader is ingestion-service through PostgREST (role `service_role`, `Accept-Profile: rules`); the grants (`usage` on the schema, `select` on the two
views) are made when that role exists and go to no other role:

| View | Columns | Used for |
|---|---|---|
| `rules.v_fines` | `id`, `site_id`, `image_path` | authorise the picture of a fine (the site of the fine, the key of the picture) |
| `rules.v_fine_evidence` | `detection_ids`, `image_paths` | ingestion's retention keeps the detections and pictures that a fine still needs |

Nothing else of this schema is readable by other services. A new reader column means a change of the view in that changeset.

### Data retention

A scheduled job (`service.retention.RetentionJob`, properties `parkview.retention.*`) limits how long personal data of fines and violations stays. **It is a mechanism only: no legal period is
decided here.** It is disabled (`enabled=false`) and a dry run (`dry-run=true`) by default, and the three periods have no default: with `enabled=true` the service refuses to start unless all three are set.
Do not switch it on before the product owner and legal have decided the periods (accounting rules for paid fines, the period for appeals and disputes, GDPR storage limitation).

| Step (period) | Counted from | What happens |
|---|---|---|
| `evidence` | `fine_evidence.issued_at` | plate replaced by `ANONYMISED`, `image_paths` and `detection_ids` emptied (not for a fine with an open dispute). The rest of the evidence stays as frozen. Without the references, ingestion's retention no longer keeps those detections and pictures. |
| `closed-records` | decision of a dispute, payment or void of a fine | `fine_disputes`: plate anonymised, `opened_by_user_id` and `decided_by_user_id` cleared, `message` emptied, `decision_note` cleared. `fines`: plate anonymised, `user_id`, `image_path` and `void_reason` cleared. `fine_actions.reason` cleared (of closed fines and of decided disputes). Amounts, times, states and the ids of operators stay. |
| `violations` | `spot_violations.resolved_at` | resolved violations that never became a fine are deleted. A violation with a fine is kept, the evidence points to it. |

* `fine_evidence_immutable` allows exactly this path: an UPDATE of `plate`, `image_paths` and `detection_ids` is accepted when the transaction did `set_config('parkview.retention', 'on', true)` (the job does
  that inside each evidence batch, the setting ends with the transaction); every other column and every DELETE stays rejected.
* Each step runs in batches (`batch-size` rows, one short transaction each, `FOR UPDATE SKIP LOCKED`, at most `max-batches` per step and run; the rest waits for the next run). The statements are idempotent.
  One instance at a time runs (Postgres advisory lock, key `PVRULE`). A dry run only counts. Logs carry table names and counts, never a plate, an id or a text; each run writes `AUDIT retention run started/finished/failed`.
* Metrics: `retention_rows_total{service="rule-service",table=...,action="anonymised"|"would_anonymise"|"deleted"|"would_delete"}`, `retention_run_seconds`, `retention_last_success_timestamp_seconds`.
* Switch on: set the three periods, run with `dry-run=true` and read the `would_*` counts, then set `RETENTION_DRY_RUN=false`.

| Property (env) | Default | Description |
|---|---|---|
| `enabled` (`RETENTION_ENABLED`) | `false` | switch the job on |
| `dry-run` (`RETENTION_DRY_RUN`) | `true` | only count and log |
| `cron` (`RETENTION_CRON`) | `0 25 3 * * *` | schedule (Spring cron, server time zone) |
| `batch-size` (`RETENTION_BATCH_SIZE`) | `1000` | rows per transaction |
| `max-batches` (`RETENTION_MAX_BATCHES`) | `200` | batches per step and run |
| `evidence` (`PARKVIEW_RETENTION_EVIDENCE`) | none, required when enabled | period of the evidence step |
| `closed-records` (`PARKVIEW_RETENTION_CLOSED_RECORDS`) | none, required when enabled | period of the closed-records step |
| `violations` (`PARKVIEW_RETENTION_VIOLATIONS`) | none, required when enabled | period of the violations step |

Periods are Spring period values (`25m` = 25 months, `760d`, `2y1m`). Example values for a test environment, **not a recommendation and not a decision**: `evidence=13m`, `closed-records=25m`, `violations=3m`.

## Data read from other services

Data of other services is read only through their published views (ADR 0010, section 4), with small read-only JPA read models owned by
this service (package `com.parkview.ruleengine.readmodel`): `@Entity @Immutable @Table(schema = ..., name = "v_...")` classes that map only the columns
used, with getters only, and Spring Data repositories that extend `Repository` (no save or delete API). The ports in `client/`
are implemented on top of them. Extra columns in the view do not matter; `ddl-auto: validate` only checks
the mapped ones. Liquibase never creates or alters these views and this service never writes to them. The owners grant `select` on them to `svc_rule`.

| View | Owner | Columns used | Read model class |
|---|---|---|---|
| `mapping.v_zones` | parking-mapping-service | `id`, `site_id`, `address`, `allows_parking`, `max_minutes`, `country`, `timezone` (null: `0` / `SE` / `Europe/Stockholm`) | `ZoneView` |
| `mapping.v_sites` | parking-mapping-service | `id`, `address`, `country`, `currency`, `timezone`, `price_per_hour`, `max_minutes`, `allows_parking` | `SiteView` |
| `mapping.v_zone_rules` | parking-mapping-service | `id`, `site_id`, `zone_id` (null = site rule), `label`, `priority`, `enabled`, `vehicle_types` (`text[]`), `permit_required`, `weekdays` (`int[]`), `start_time`, `end_time` (`time`, read as text and parsed), `applies_holidays`, `max_minutes`, `no_parking` | `ZoneRuleView` |
| `mapping.v_parking_spots` | parking-mapping-service | `id`, `site_id`, `zone_id`, `spot_number`, `type`, `shape` (only `polygon` is read for legacy spots), `permit_type`, `coordinates` (jsonb, null for a spot that only has a geo polygon: skipped), `boundary_grace_min`, `permit_grace_min`, `max_minutes` | `ParkingSpotView` |
| `mapping.v_parking_spots` (camera views) | parking-mapping-service | `id`, `view_id` (uuid, null = legacy spot), `image_polygon` (jsonb, polygon in the view's corrected image) | `SpotPlacementView` |
| `sessions.v_sessions` | session-service | `id`, `plate`, `site_id`, `zone_id`, `started_at`, `ended_at`, `voided_at` (a voided session is not active, so it never causes an `OVERSTAY`) | `SessionView` |
| `identity.v_user_plates` | authentication-service | `id`, `user_id`, `plate` | `PlateOwnerView` |
| `identity.v_user_sites` | authentication-service | `user_id`, `site_id` | `UserSiteView` |
| `ingestion.v_detections` | ingestion-service | `id`, `plate`, `corrected_plate`, `site_id`, `zone_id`, `image_path`, `confidence`, `review_status`, `at` (the snapshots that go into the evidence of a fine; rejected detections are left out) | `DetectionView` |

**Camera views.** A spot is either a legacy spot (`view_id` null, polygon in `coordinates`) or drawn on a camera view
(`view_id` set, polygon in `image_polygon`). `SpotDirectory` has `polygonSpotsOf(site)` for the first kind and
`polygonSpotsOfView(site, viewId)` for the second; `SpotCacheService` caches each separately (key: site and view id,
the same TTL; `map.updated` evicts a site at once, the TTL is the safety net).
The two columns are read through `SpotPlacementView`, a second read model of `mapping.v_parking_spots`. A view query also filters
on the site of the event, so a view id never yields spots of another site.
The test schema (`src/test/resources/test-schema.sql`) creates the schemas of the other services, their base tables and the
published views, and sets the `search_path` of the test role to `rules, public` like the infrastructure does.

## Configuration

| Variable | Default | Required | Description |
|---|---|---|---|
| `PORT` | `8081` | no | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/parkview` | no | JDBC URL |
| `DB_USER` | `svc_rule` | no | database role of this service (owns schema `rules`, `search_path = rules, public`, `select` on the catalogue views of the other services) |
| `DB_PASS` | none | yes | database password |
| `DB_SCHEMA` | `rules` | no | the service's schema: Liquibase default and tracking schema (`spring.liquibase.default-schema` / `liquibase-schema`) and `hibernate.default_schema` |
| `DB_POOL_SIZE` | `10` | no | Hikari pool size |
| `RABBIT_HOST` / `RABBIT_PORT` | `localhost` / `5672` | no | broker |
| `RABBIT_USER` / `RABBIT_PASS` | `guest` / `guest` | no | broker credentials (set real ones outside local use) |
| `SUPABASE_URL` | none | yes | JWT issuer is `${SUPABASE_URL}/auth/v1`, keys from its `.well-known/jwks.json` |
| `SUPABASE_AUDIENCE` | `authenticated` | no | required `aud` claim |
| `LEGACY_ROLE_FALLBACK` | `false` | no | `parkview.security.legacy-role-fallback`: `true` gives tokens without `app_metadata.permissions` their role's default permissions; off by default |
| `INTERNAL_API_KEY` | empty | no | the OWN key of this service: `/internal/**` (except retention) accepts it as `X-Internal-Api-Key` (`ROLE_INTERNAL`); callers hold it as `RULE_INTERNAL_API_KEY`. Not sent anywhere |
| `INTERNAL_API_KEY_PREVIOUS` | empty | no | the key before a rotation, accepted as well while it is set (see "Rotating a key") |
| `OPS_API_KEY` | empty | no | key of the operations endpoints: every non-health `/actuator/**` (prometheus) and `/internal/v1/retention/**` (`ROLE_OPS`). With none of the three keys set the internal API and actuator are disabled |
| `LOOKUP_INTERNAL_API_KEY` | empty | no | the key of lookup-service, sent as `X-Internal-Api-Key` on the vehicle lookup; no fallback to `INTERNAL_API_KEY` (the `local` profile sets `local-internal-key`); empty sends no key and every lookup fails |
| `SITES_CACHE_TTL` / `SITES_CACHE_MAX` | `30s` / `10000` | no | `parkview.access.sites-cache-ttl` / `sites-cache-max`: how long the sites of an operator are cached (a site added or removed applies within it) and how many users |
| `PLATES_CACHE_TTL` / `PLATES_CACHE_MAX` | `30s` / `10000` | no | `parkview.access.plates-cache-ttl` / `plates-cache-max`: how long the registered plates of a driver are cached (a new plate is visible within it) and how many users |
| `LOOKUP_SERVICE_URL` | `http://localhost:8089` | no | lookup-service base URL for vehicle registry data |
| `DOCS_ENABLED` | `false` | no | Swagger UI and OpenAPI spec |

Transactional outbox, Spring property `parkview.outbox.*` (set with `SPRING_APPLICATION_JSON` or the matching `PARKVIEW_OUTBOX_*` variables):

| Property | Default | Description |
|---|---|---|
| `relay-enabled` | `true` | switch the scheduled relay and the clean-up off (tests, maintenance); events then accumulate in `outbox_events` |
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
| `internal-api-key` | `${LOOKUP_INTERNAL_API_KEY:}` | key of lookup-service (`X-Internal-Api-Key`) |

### Internal keys and rotation

Service-to-service calls use one secret per CALLEE (header `X-Internal-Api-Key`):

* This service as callee: `INTERNAL_API_KEY` is its own key (callers hold it as `RULE_INTERNAL_API_KEY`) and grants `ROLE_INTERNAL` on
  `/internal/**`; `INTERNAL_API_KEY_PREVIOUS` is accepted as well while it is set; `OPS_API_KEY` grants `ROLE_OPS`, which is required for
  `/internal/v1/retention/**` and every non-health `/actuator/**` (prometheus). Comparisons take constant time, an unset key never matches,
  and with none of the three set the internal API and actuator are denied entirely.
* This service as caller: it calls lookup-service with `LOOKUP_INTERNAL_API_KEY`. There is no fallback to its own `INTERNAL_API_KEY`; only the
  `local` profile defaults the keys to `local-internal-key`.

Rotating this service's key: on rule-service set `INTERNAL_API_KEY_PREVIOUS` to the old key and `INTERNAL_API_KEY` to the new one, roll the
callers (payment-service, file-service) to the new value of their `RULE_INTERNAL_API_KEY`, then unset `INTERNAL_API_KEY_PREVIOUS`.
Rotating the key of lookup-service is the mirror image: lookup-service accepts old and new, this service gets the new `LOOKUP_INTERNAL_API_KEY`.

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

The `local` profile only sets a database password and the internal, ops and lookup keys (`local-internal-key`) for development; the database needs the role `svc_rule` and the schema `rules` (created by the infrastructure, e.g. the e2e stack), and the schemas of the other services with their published views.
Tests: geometry table tests (clockwise and counter-clockwise polygons, crossing edges), table-driven rule engine tests (time windows
incl. midnight crossing, holidays and Europe/Stockholm DST dates; priority and first match wins; every violation type and
fail-open case; vehicle-code mapping; grace values; overstay with a fixed clock), the Jollyday calendar for the Swedish
2026 holidays, the lookup client (MockRestServiceServer), violation lifecycle (type change, grace per type), fine issuing,
MockMvc security tests (401/403/404/409/400, problem+json), internal and ops key matrix (own, previous, ops), `ReadModelPlateDirectoryTest` (normalised plates, cache TTL), `ReadModelOperatorSitesTest`, `CurrentUserTest` (sites looked up for operators only), listener payloads, Testcontainers tests (unique index,
`SKIP LOCKED` claim, permit queries, read models against catalogue views with extra columns, `time`/array columns)
and a full flow test against real Postgres and RabbitMQ (incl. void, manual and online payment events, and a void racing a payment).
Evidence and disputes: `FineEvidenceServiceTest` (what is frozen, snapshots, fallbacks), `RuleSummariesTest` (the rule text per violation type), `FineEvidenceRepositoryTest` (round trip, the trigger
rejects UPDATE and DELETE, the entity cannot change a row, no update/delete API), `FineIssuingTransactionTest` (fine, evidence and outbox row commit together, a rollback leaves none), `DisputeServiceTest`
(rules, audit rows, events, void through the existing logic, payment hold, system upheld after a race, nothing logged), `DisputeRepositoryTest` (unique open index, constraints, latest status, list queries),
`DisputeControllerTest` and `FineEvidenceControllerTest` (anonymous 401, other plate / other zone 404, missing permission 403, admin, validation 400, state errors 409, shapes, no snapshots in the explanation).
Outbox: `OutboxRelayTest` (confirms, back-off, unroutable, order), `OutboxServiceTest` (bytes identical to the former direct publish), `OutboxEventRepositoryTest` (claim order, per-aggregate ordering, SKIP LOCKED
with two sessions, clean-up), `OutboxRelayBrokerTest` and `RuleServiceFlowTest` (real RabbitMQ).
The `fine:manage` / `violation:read` / zone matrices (admin, operator with and without the permission, other zone, driver, anonymous), body validation, the 409 cases,
idempotency, voided fines hidden from lists and drivers, audit rows and event payloads are covered in `FineControllerTest`, `FineManagementControllerTest`, `FineManagementServiceTest`, `FineSettlementServiceTest` and `FineIssuingServiceTest`.

## Operations

* Health: `/actuator/health/liveness` and `/readiness` (public); metrics: `/actuator/prometheus` (ops key, `OPS_API_KEY`). Logs carry `requestId`; plates are masked.
* Several instances may run: inserts are protected by the unique index, fines by `SKIP LOCKED`.
* Messages in `parkview.dlq` are invalid payloads or exhausted retries; inspect `x-death` headers.
* Fines are never issued while the database is unreachable; the sweep retries every `expire-sweep-ms`. A broker outage no longer blocks anything: events wait in `outbox_events`
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
