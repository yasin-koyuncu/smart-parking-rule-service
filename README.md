# Park & View — Spring Boot Services

Tre tjänster byggda med Spring Boot 3.x + Java 21 + Maven.

## Tjänster

| Tjänst          | Port | Ansvar                                    |
|-----------------|------|-------------------------------------------|
| api-gateway     | 8080 | JWT-auth, routing, rate limiting          |
| rule-engine     | 8081 | Boundary-check, violations, grace periods |
| session-service | 8082 | Sessioner, avgifter, betalstatus          |

## Kom igång (lokal utveckling)

### Förutsättningar
- Java 21 (`java -version`)
- Maven 3.9+ (`mvn -version`)
- Docker Desktop

### 1. Starta infrastruktur
```bash
cd spring-services
docker-compose up postgres rabbitmq -d
```

RabbitMQ Management UI: http://localhost:15672 (guest/guest)

### 2. Bygg alla tjänster
```bash
# Rule Engine
cd rule-engine && mvn clean package -DskipTests && cd ..

# Session Service
cd session-service && mvn clean package -DskipTests && cd ..

# API Gateway
cd api-gateway && mvn clean package -DskipTests && cd ..
```

### 3. Starta tjänsterna
```bash
# Alternativ A — via Docker Compose (alla tjänster)
docker-compose up --build

# Alternativ B — direkt (för utveckling med hot-reload)
cd rule-engine   && mvn spring-boot:run &
cd session-service && mvn spring-boot:run &
cd api-gateway   && mvn spring-boot:run &
```

### 4. Verifiera
```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
```

## Miljövariabler (.env i spring-services/)

```env
SUPABASE_URL=https://xxx.supabase.co
SUPABASE_PUBLISHABLE_KEY=eyJ...
SUPABASE_SERVICE_KEY=eyJ...
MAPBOX_TOKEN=pk.eyJ...
FRONTEND_URL=http://localhost:5173
```

## API-endpoints

### API Gateway (port 8080)
Alla anrop går via gateway — den routar vidare till rätt tjänst.

### Rule Engine (port 8081)
```
GET  /api/v1/violations/zone/{zoneId}    → aktiva violations per zon
GET  /api/v1/violations/plate/{plate}    → violations per skylt
PATCH /api/v1/violations/{id}/resolve   → markera som löst
```

### Session Service (port 8082)
```
GET  /api/v1/sessions/zone/{zoneId}     → aktiva sessioner per zon
GET  /api/v1/sessions/plate/{plate}     → historik per skylt
GET  /api/v1/sessions/count/active      → totalt aktiva (KPI)
POST /api/v1/sessions/start             → manuell start
PATCH /api/v1/sessions/{id}/pay         → markera som betald
```

## Event-flöde (RabbitMQ)

```
Edge-enhet
    ↓ parking.started / parking.ended
Session Service → skapar/avslutar session
    ↓ session.started
Rule Engine → boundary-check
    ↓ violation.created (om violation)
Notifieringstjänst → push till förare
    ↓ violation.expired (efter grace period)
Session Service → utfärdar bot
```

## Arkitekturprincip

AI-tjänsten säger bara: "här är ett fordon med polygon X"
Rule Engine avgör: "är det en giltig parkering?"
Session Service hanterar: "när kom bilen, vad kostar det?"