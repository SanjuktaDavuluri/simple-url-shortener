---
status: accepted
date: 2026-10-07
---

# Java 25 + Spring Boot 4.1 + Maven as the stack

The URL shortener is built in **Java 25** with **Spring Boot 4.1.1**, using **Maven** through the committed Maven Wrapper (`./mvnw`). Java 25 is the current long-term-support (LTS) release. Spring Boot is the most widely recognised Java web framework, and its testing support fits the project's two test seams (the HTTP surface and the Rule Set) directly. Anyone who reviews or runs the project only needs a JDK. The wrapper supplies the build tool.

## Options considered and the trade-offs

### Java version

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Java 25 (LTS)** | Long-term support from every major vendor; current language features (records, pattern matching, virtual threads) | Not the newest release | **Chosen** |
| B | Java 27 (latest, non-LTS) | Newest features | About 6 months of updates before it must be replaced; a poor fit for a project with a reliability phase | Rejected |
| C | Java 21 (previous LTS) | Maximum library compatibility | Gives up two years of language and runtime improvements for no benefit here | Rejected |

### Web framework

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Spring Boot 4.1** | The industry default that reviewers know instantly; first-class testing (`@SpringBootTest`, `MockMvcTester`); built-in validation, configuration, JDBC, Flyway and Thymeleaf support | Heavier startup and memory than the alternatives, which is irrelevant for one small service | **Chosen** |
| B | Quarkus | Fast startup and a small footprint; strong container story | A smaller community; less familiar to a typical reviewer | Rejected: its advantages matter at a scale we don't have |
| C | Javalin / Helidon SE (lightweight libraries) | Minimal, explicit, little framework magic | We would hand-build validation, configuration, migrations and test support that Spring provides | Rejected: more code to own for no gain in this project |

### Build tool

| | Option | What we'd gain | What it would cost | Verdict |
|---|---|---|---|---|
| A | **Maven + Maven Wrapper** | A declarative `pom.xml` any Java reviewer can read; predictable builds; simple CI (`./mvnw verify`) | Slower incremental builds than Gradle | **Chosen** |
| B | Gradle (Kotlin DSL) + Gradle Wrapper | Faster incremental builds; more flexible | The build file is code, which is harder to scan in review | Rejected: readability matters more than build speed for one small service |

**In short:** we chose **Java 25 over 27** for long-term support, **Spring Boot over Quarkus and the lightweight libraries** because it is what reviewers expect and it gives us testing, migrations and templating out of the box, and **Maven over Gradle** for a build file anyone can read.

## Consequences

- The minor-version upgrade to Spring Boot 4.2 (due November 2026) is planned as its own brownfield change (roadmap R16), not done silently.
- CI and local builds use the same entry point, `./mvnw verify`, on the Temurin JDK 25 distribution.
