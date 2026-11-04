# SchemaForge

Runtime SQL-to-REST API generator built with Java 17+, Spring Boot, Spring JDBC, HikariCP, Spring Security, Redis, Bucket4j, and OpenAPI. It introspects an existing schema and exposes APIs without entities, repositories, or generated controllers.

## Run locally

Requirements: Docker with Compose, or JDK 17+ and an existing database. Maven is pinned through `mvnw`.

```sh
cp .env.example .env
# Generate TWO independent values with: openssl rand -hex 32
# Set DB_PASSWORD and JWT_SECRET in .env. Never commit this file.
docker compose up --build -d
```

Compose starts the application, PostgreSQL 17 with sample users/products/orders, and Redis 7. The API binds to host loopback port 8080. The database and Redis have no published ports. The sample SQL runs only when PostgreSQL's data volume is first initialized. Local generated credentials may already exist in `.env` in the prepared development workspace; preserve them.

For a native Java process, provide configuration through environment variables (or an ignored `config/application.yml`):

```sh
export DB_URL='jdbc:postgresql://localhost:5432/your_database'
export DB_SCHEMA=public
export DB_USERNAME=your_app_user
# Set DB_PASSWORD and JWT_SECRET securely in the process environment.
./mvnw spring-boot:run
```

Use a database account restricted to intended tables, schema introspection, and required CRUD privileges. The Compose account and seed data are for development. Enable database TLS and put the application behind HTTPS in deployments.

## Authentication and documentation

There is no password login endpoint or built-in identity provider. Obtain an **HS256** JWT from your trusted issuer with:

- `iss`: `schemaforge` (override `JWT_ISSUER`).
- `aud`: `schemaforge-api` (override `JWT_AUDIENCE`).
- `sub`: nonempty stable user ID.
- `exp`: required expiration; `nbf`, when present, is validated.
- `roles`: array such as `["ADMIN"]` or `["GUEST"]`, without `ROLE_` prefix.

The issuer and API share `JWT_SECRET`, a cryptographically random secret of at least 32 bytes. Signature algorithm, signature, issuer, audience, expiration, and subject are checked. SchemaForge does not mint production tokens. JWT validation allows Spring Security's default 60-second clock skew.

Open **`/docs.html`** on your running instance and enter a bearer token or API key to load the role-scoped Swagger UI. Credentials stay in page memory; they are not persisted. `/openapi.json` returns the live OpenAPI 3.0.3 schema for authorized tables and operations. The standard `/swagger-ui/index.html` assets are also provided, but `/docs.html` handles authenticated specification loading.

All data endpoints and schema discovery documentation require authentication. Health is public; Prometheus metrics require ADMIN. Sessions, HTTP Basic, form login, and cookies are not used for authentication. CSRF is disabled for this stateless header-authenticated API. CORS is not enabled by default.

## API examples

Set `TOKEN` to a valid JWT in your shell without placing it in source control:

```sh
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/v1/products'
curl -H "Authorization: Bearer $TOKEN" --get \
  --data-urlencode 'age[gt]=18' --data-urlencode 'sort=-age,name' \
  --data-urlencode 'select=id,name' --data-urlencode 'limit=10' \
  'http://localhost:8080/api/v1/users'
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Pencil","price":1.25,"metadata":{"color":"blue"}}' \
  'http://localhost:8080/api/v1/products'
```

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/api/v1/{table}` | Filtered, sorted, projected page |
| POST | `/api/v1/{table}` | Insert; returns 201 and primary key fields |
| GET | `/api/v1/{table}/{id}` | Fetch one row |
| PATCH | `/api/v1/{table}/{id}` | Partial update; 204 |
| PUT | `/api/v1/{table}/{id}` | Requires all writable non-key fields; 204 |
| DELETE | `/api/v1/{table}/{id}` | Delete; 204 |
| GET | `/api/v1/{table}/{id}/{childTable}` | Page through referencing child rows |

Collection responses have `data`, `hasMore`, and `nextCursor`. There is no expensive count query. Filtering uses equality (`age=25`) or `column[eq|ne|gt|gte|lt|lte|like|isnull]=value`. LIKE uses SQL `%` and `_` wildcards on text columns. Shorthand `age>18` is accepted; URL-encoded bracket operators avoid proxy/query parsing ambiguity. Null checks use `column[isnull]=true` or `false`. Filters are AND-combined. Repeated parameters and unknown identifiers are rejected.

`sort=-created_at,name` orders descending then ascending; primary keys are appended as tie-breakers. Offset pages start at `page=1`, with `limit=20` by default and maximum 200. Cursor pagination starts with `cursor=first`, then uses the returned `nextCursor` with the same filters/projection. It traverses a single-column PK in ascending order and cannot be combined with custom sorting or `page`. Cursors are opaque base64 positions, not authorization grants; every request re-applies RBAC and RLS. `select=id,name` restricts returned columns. `page`, `limit`, `sort`, `select`, and `cursor` are reserved query option names.

Generated/identity columns are not writable; updates cannot change PKs. Database defaults apply to omitted insert columns. POST returns keys only so write permission never implicitly grants read access. PUT requires every non-generated, non-PK column except an enforced ownership column. Constraint failures return 409; malformed requests return 400; absent or inaccessible rows return 404. SQL and driver errors are not returned to clients.

Relationships require exactly one child foreign key referencing the parent. Composite foreign keys are supported when the parent itself has a single-column primary key. Both parent/child authorization and ownership predicates apply; ambiguous relationships return 400.

## Table permissions, row ownership, and API keys

Default permissions give only ADMIN read/write access to all discovered tables. Every other role is denied until configured. Restrict discovery with `DB_TABLES=users,orders` (case-sensitive database identifiers). `DB_SCHEMA` selects a single schema; metadata is cached as an immutable in-memory snapshot and refreshed on restart.

Create an ignored `config/application.yml` for policy overrides. With Docker, mount it at `/app/config/application.yml` using a local Compose override. Example:

```yaml
forge:
  policies:
    "[*]":
      read: [ADMIN]
      write: [ADMIN]
    users:
      read: [ADMIN, GUEST]
      write: [ADMIN]
    orders:
      read: [ADMIN, USER]
      write: [ADMIN, USER]
      row-column: user_id
      row-claim: sub
  security:
    api-keys:
      - sha256: <SHA-256 hex digest of a high-entropy API key>
        subject: service-identity
        roles: [GUEST]
```

Named policies replace the wildcard policy for that table. RLS always applies, including to ADMIN: SELECT, related reads, UPDATE, DELETE, and INSERT all enforce the ownership claim. Inserts fill omitted ownership values; attempts to change ownership return 403. Missing/non-scalar claims fail closed. RLS-protected rows with numeric ownership require a numeric subject/claim. API keys can supply only their configured subject as `sub`; custom JWT claims are unavailable to keys.

Send machine credentials using `X-API-KEY`. Store **only their SHA-256 digest** in configuration and use randomly generated high-entropy raw keys. Digest comparisons are constant-time. Requests presenting both an API key and an Authorization header are rejected. Bucket4j limits authenticated API calls per subject (default 120/minute, in-process, at most 10,000 identity buckets); 429 responses include Retry-After.

## Databases, types, and caching

PostgreSQL, MySQL, and Oracle JDBC drivers are included. Schema-qualified identifiers use the driver's quoting rules; all values use prepared-statement bindings. MySQL uses LIMIT/OFFSET; PostgreSQL and Oracle use OFFSET/FETCH. No Hibernate/JPA is used.

| Database | Example URL | Schema setting |
| --- | --- | --- |
| PostgreSQL | `jdbc:postgresql://host:5432/app` | `DB_SCHEMA=public` |
| MySQL 8+ | `jdbc:mysql://host:3306/app` | `DB_SCHEMA=app` (catalog) |
| Oracle 12c+ | `jdbc:oracle:thin:@//host:1521/service` | `DB_SCHEMA=APP_OWNER` (exact case) |

Numbers, booleans, ISO dates/times/timestamps, UUIDs, PostgreSQL JSON/JSONB, and base64 binary/BLOB data are mapped dynamically. Local TIMESTAMP values have no invented timezone; timezone-aware timestamps retain their offset. CLOBs become strings. Arbitrary vendor objects/arrays/spatial types are not a portable supported contract.

Redis response caching is **opt-in** (`CACHE_ENABLED=true`). It caches collection/relationship GET responses, including filters, projection, paging and RLS-bound values in the cache key, for 15 seconds by default. Version keys invalidate a table after committed writes. Redis failures fall back to JDBC. External database writes, cascades/triggers changing other tables, or failed invalidation can leave cached results stale until TTL expiry; keep caching off when strict freshness is required. Use a dedicated Redis database per SchemaForge deployment and trusted/private Redis networking. Schema metadata itself is cached in application memory.

## Build and test

```sh
./mvnw verify
```

The default suite exercises the real Spring HTTP/security stack against H2: CRUD, filters, projections, both pagination modes, relationships, RBAC/RLS, JWT validation, API keys, SQL injection attempts, binary/UUID/time mapping, OpenAPI, metrics, and Bucket4j. The PostgreSQL/Redis and MySQL service tests are explicitly skipped unless enabled.

For the real-service test, start Compose's database and Redis with a **local override** publishing ports 15432:5432 and 16379:6379 on loopback, then:

```sh
# Supply DB_PASSWORD securely, matching the development database.
FORGE_SERVICE_TESTS=true \
FORGE_PG_URL=jdbc:postgresql://localhost:15432/schemaforge \
FORGE_REDIS_PORT=16379 ./mvnw verify
```

It verifies actual PostgreSQL JSONB and Redis caching/invalidation and deletes its generated product afterward.

For MySQL 8.4, provide a `schemaforge` database/user and a `products` table with `id BIGINT AUTO_INCREMENT PRIMARY KEY`, `name VARCHAR(100) NOT NULL`, `price DECIMAL(12,2) NOT NULL`, and `metadata JSON`, then set `FORGE_MYSQL_TESTS=true` and `FORGE_MYSQL_URL` to its JDBC URL (and supply `DB_PASSWORD`). The MySQL test covers generated-key labels, JSON, CRUD, offset and cursor pagination, and removes its own rows. Both service suites can run in the same Maven invocation.

Validation performed during implementation: H2 integration tests, PostgreSQL 17 + Redis 7 service tests, MySQL 8.4 service tests, Docker image build, and packaged-container HTTP smoke checks. GitHub Actions runs the default suite on Java 21. The Docker build runs that suite and produces a non-root Java 21 runtime image. Java bytecode targets 17.

In managed cloud builds, preserve the proxy and mount its CA when needed:

```sh
docker build --secret id=proxy_ca,src="$CODEX_PROXY_CERT" -t schemaforge:dev .
```

For Compose in that environment, first build the image as above and set the `app.image` in a local Compose override, then run with `--no-build`.

## Observability and current boundaries

`/actuator/health` reports database health (and Redis when caching is enabled). `/actuator/prometheus` exposes HTTP latency/error metrics and Hikari pool metrics to ADMIN. Console logging is structured JSON. Database query timeouts are 15 seconds, pool acquisition times out after 10 seconds, and Hikari closes connections on application shutdown.

This is a working initial implementation, not a completed enterprise platform. It manages **one operator-configured datasource per process**, with startup-time credentials; there is no web connection wizard, runtime datasource registry, schema hot reload, or tenant provisioning. Single-row CRUD and cursor pagination require one PK column; composite/no-PK tables support collection reads and inserts. Views are excluded. RLS is application-enforced, so direct database access must have its own controls. Permissions are per table, not per column. Rate limits are per instance, not distributed. Native Oracle-specific types and Oracle deployment remain unverified; test against your exact database version before deploying. Production asymmetric JWT/JWKS key rotation, a shared rate-limit backend, and tenant lifecycle management are follow-up work.
