# java-spring-boot-sample-app

Lab-12 — containerizing a Spring Boot application with Docker, Docker Compose, and Docker Hub.

## Architecture

```
                    Docker Host
                         |
        +----------------+----------------+
        |     lab12-network (bridge)      |
        +----------------+----------------+
                         |
        +----------------+----------------+
        |                                 |
  +-----+------+                   +------+-----+
  | Spring Boot|                   | PostgreSQL |
  | app :8080  | <---------------> | db :5432   |
  +-----+------+      JDBC         +------+-----+
        |                                 |
   Host Port 8080                   Docker Volume
   0.0.0.0:8080                lab12-postgres-data
```

The application image is pulled from Docker Hub rather than built on the target machine. A server needs only Docker and Docker Compose — no Java, no Maven, no source code.

## Application

| Item | Value |
|---|---|
| Name | demo (`com.example:demo`) |
| Version | 1.1.1 |
| Language / runtime | Java 17 |
| Framework | Spring Boot 3.2.5 |
| Build tool | Maven (via `mvnw` wrapper) |
| Port | 8080 |
| Artifact | `target/demo-1.1.1.jar` |

## Docker

The Dockerfile is a two-stage build. Stage one uses `maven:3.9-eclipse-temurin-17` to compile the jar; stage two starts fresh from `eclipse-temurin:17-jre` and copies only the jar across. Maven, the JDK, and the dependency cache never reach the final image. The application runs as a non-root user (`appuser`, uid 1001).

Build the image:

```bash
docker build -t java-sample-app:v1 .
```

Run it (requires a reachable PostgreSQL — see Database below):

```bash
docker run -d --name java-sample-app \
  --network lab12-network \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://lab12-postgres:5432/labdb \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=changeme \
  -p 8080:8080 \
  java-sample-app:v1
```

Stop, start, restart, and remove:

```bash
docker stop java-sample-app
docker start java-sample-app
docker restart java-sample-app
docker rm -f java-sample-app
```

View logs and inspect:

```bash
docker logs java-sample-app
docker logs -f java-sample-app          # follow
docker inspect java-sample-app
docker exec -it java-sample-app sh      # shell inside the container
```

`.dockerignore` excludes `.git`, `.idea`, `.vscode`, `target`, `*.log`, and `README.md` from the build context.

## Docker Compose

`compose.yaml` defines two services — `app` and `db` — sharing a user-defined network and a named volume for database storage.

Start everything:

```bash
docker compose up -d
```

Check status and logs:

```bash
docker compose ps
docker compose logs
docker compose logs app
docker compose logs -f app              # follow
```

Stop without removing:

```bash
docker compose stop
docker compose start
```

Tear down and recreate:

```bash
docker compose down
docker compose up -d
```

`docker compose down` removes the containers and the default network but keeps named volumes, so database data survives a down/up cycle. Adding `-v` would delete the volume and lose the data.

Pull the latest published image before starting:

```bash
docker compose pull
docker compose up -d
```

## Docker Hub

| Item | Value |
|---|---|
| Repository | https://hub.docker.com/r/jennaahmed/java-sample-app |
| Image name | `jennaahmed/java-sample-app` |
| Available tags | `v1`, `v2` |

Pull:

```bash
docker pull jennaahmed/java-sample-app:v1
```

Publish a new version:

```bash
docker build -t java-sample-app:v2 .
docker tag java-sample-app:v2 jennaahmed/java-sample-app:v2
docker push jennaahmed/java-sample-app:v2
```

Roll back to a previous version:

```bash
docker pull jennaahmed/java-sample-app:v1
docker run -d -p 8080:8080 jennaahmed/java-sample-app:v1
```

Versions are tagged explicitly rather than relying on `latest`. `latest` is a mutable pointer with no memory of what it previously referenced, which makes rollback impossible and lets two machines run different code from an identical command.

## Database

PostgreSQL 16 runs as a separate container on the same Docker network as the application.

| Setting | Value |
|---|---|
| Image | `postgres:16` |
| Database | `labdb` |
| User | `postgres` |
| Password | `changeme` (development only) |
| Port | 5432 (internal to the network, not published) |
| Volume | `lab12-postgres-data` → `/var/lib/postgresql/data` |
| Hostname from the app | `db` under Compose, `lab12-postgres` when run manually |

Create the volume and start the database manually:

```bash
docker volume create lab12-postgres-data
docker network create lab12-network

docker run -d --name lab12-postgres \
  --network lab12-network \
  -e POSTGRES_PASSWORD=changeme \
  -e POSTGRES_DB=labdb \
  -v lab12-postgres-data:/var/lib/postgresql/data \
  postgres:16
```

Connect to the database:

```bash
docker compose exec db psql -U postgres -d labdb
```

The volume keeps the data independent of any container's lifecycle. Deleting and recreating the PostgreSQL container leaves the data intact as long as the same volume is mounted; without a volume, the data lives in the container's writable layer and disappears with it.

Credentials are supplied as environment variables at run time, never baked into the image with `ENV`. Values written into a Dockerfile become permanent image layers readable through `docker history` by anyone who can pull the image, and removing the line later does not erase it from earlier layers.

## Troubleshooting

### 1. Maven wrapper fails inside the Maven base image

**Symptom.** The build stopped at `RUN ./mvnw dependency:go-offline -B` with:

```
[ERROR] Unknown lifecycle phase "/root/.m2". You must specify a valid lifecycle phase or a goal...
```

**Diagnosis.** The error names `/root/.m2` as though it were a build phase, so something was passing that path to Maven as a bare argument. Nothing in the project referenced it — `grep -r "/root/.m2" .` returned nothing, and `.mvn/maven.config` and `.mvn/jvm.config` did not exist.

**Cause.** The `maven:3.9-eclipse-temurin-17` image sets `MAVEN_CONFIG=/root/.m2`. The `mvnw` wrapper script passes that variable through as a positional argument, which Maven then reads as a lifecycle phase.

**Fix.** The base image already ships Maven, so the wrapper was unnecessary. Removed the `COPY .mvn` / `COPY mvnw` lines and switched both build steps to plain `mvn`.

### 2. Application crashes on startup — cannot reach the database

**Symptom.** The container exited immediately after `docker run`. It never appeared in `docker ps`. The log ended with:

```
Unable to determine Dialect without JDBC metadata
```

**Diagnosis.** `docker ps -a` showed the container had exited rather than crashed silently. `docker logs` traced the failure to Hibernate being unable to open a connection, not to a bad query or a missing class.

**Cause.** `application.properties` pointed at `jdbc:postgresql://localhost:5432/labdb`. Inside a container, `localhost` resolves to that container's own loopback interface — not the host, and not the PostgreSQL container. Nothing listens on 5432 there, so the connection was refused and Spring aborted startup.

**Fix.** Changed the host to the PostgreSQL container's name. Containers on a user-defined network resolve each other by name through Docker's embedded DNS at `127.0.0.11`:

```
spring.datasource.url=jdbc:postgresql://lab12-postgres:5432/labdb
```

### 3. Container runs but the application is unreachable

**Symptom.** After `docker run -d -p 9090:9090 java-sample-app:v1`, the container showed as `Up` in `docker ps`, but the browser and `curl` both failed:

```
curl: (56) Recv failure: Connection reset by peer
```

**Diagnosis.** `docker ps` proved the process had not exited, so this was not a crash. The PORTS column showed the problem directly:

```
8080/tcp, 0.0.0.0:9090->9090/tcp, [::]:9090->9090/tcp
```

Two different port numbers with no overlap. `docker logs` confirmed `Tomcat started on port 8080`, while `docker port` confirmed the mapping targeted container port 9090.

**Cause.** In `-p HOST:CONTAINER`, the right-hand value is the port inside the container. The application binds to 8080, so publishing host 9090 to container 9090 forwarded traffic to a port where nothing was listening. Docker's proxy accepted the connection on the host and then reset it. Container health and port correctness are independent — `docker ps` reports only the former.

**Fix.** Mapped the host port to the port the application actually binds to:

```bash
docker run -d -p 9090:8080 java-sample-app:v1
```

Alternatively, `-e SERVER_PORT=9090` moves Tomcat to 9090 inside the container, after which `-p 9090:9090` is correct.

## Deploying from scratch

On a machine with only Docker and Docker Compose installed:

```bash
git clone https://github.com/jenna-ahmed/java-spring-boot-sample-app.git
cd java-spring-boot-sample-app
docker compose pull
docker compose up -d
```

The application is then available at http://localhost:8080. No Java or Maven installation is required — the runtime travels inside the image.

## Screenshots

Evidence for each stage of the lab is in [`screenshots/`](screenshots/).
