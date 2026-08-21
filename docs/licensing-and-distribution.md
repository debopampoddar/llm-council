# Licensing And Distribution Decision Record

Plan status: **deferred pending a final legal/licensing decision. Do not execute
the migration steps in this document yet.**

> **Current legal/build state, checked 2026-08-20:** the repository is still
> version `2.0.0`, groupId `com.debopam`, and carries the GPL-3.0 license text.
> The AGPL/commercial model, `io.contextruntime` coordinates, Maven Central, CLA,
> NOTICE, and SPDX work below are decisions for a future 2.1.0 release; they are
> not claims about the code users receive today. The separate evaluation
> repository currently has no `LICENSE` file and must receive an explicit license
> before it is marketed for reuse.

The intended future copyright holder is ContextRuntime LLC. A GitHub issue in the
relevant repository is acceptable as the interim public contact for commercial
licensing questions; a custom domain or mailbox is not a prerequisite for that
contact path. Copyright ownership, contributor assignments, and final license
language still require legal verification.

This is an engineering release plan, not legal advice. Revalidate the licence
strategy with counsel before changing the repository licence or selling
commercial exceptions.

This document exists so the analysis behind these decisions is not repeated. The
dependency licence audit in §6 in particular took a full scan of the tree and
should be re-run only when dependencies change.

---

## 1. Decisions taken

| # | Decision | Chosen | Status |
|---|---|---|---|
| D1 | Licence model | AGPL-3.0-or-later + commercial was explored; personal/non-commercial source-available terms were also discussed | **not final** |
| D2 | Version carrying any new licence | A future version only; never retroactive | agreed principle |
| D3 | `<licenses>` block | Both entries if dual licensing is selected | conditional |
| D4 | Distribution | Maven Central and/or GitHub Packages | under consideration |
| D5 | Commercial contact | GitHub issue in the relevant repository | interim choice |
| D6 | groupId | `io.contextruntime`, artifactId `llm-council` | deferred pending namespace ownership |
| D7 | Publish a thin library jar as well | **No.** This is an application; the fat jar is the deliverable | agreed |
| D8 | Fail the build on an unknown dependency licence | **Yes** — otherwise the notices file goes stale silently | agreed |
| D9 | SPDX header scope | `src/main/java`, `src/test/java`, static JS/CSS/HTML (~220 files) | agreed |

### Why AGPL was considered rather than GPL

GPL obligations trigger on **distribution**, not on use. This application is a
service people run. A company deploying it internally never distributes it, so
GPL-3.0 would ask nothing of them and they would never need a commercial
licence — which leaves the commercial tier with almost no one to sell to.

AGPL-3.0 §13 extends the source obligation to users interacting with the
software **over a network**, which is exactly this deployment shape. That is
what makes the commercial tier real: the paying customers are the organisations
whose policies forbid AGPL.

### Why a dual-licensed repository would stay public

Dual licensing means *anyone may use it under AGPL; pay if you cannot accept
AGPL*. If the source is not reachable, nobody can take the first option and
there is nothing to buy an exception from. Private-and-dual-licensed is not a
coherent arrangement.

### What relicensing can and cannot do

- **v2.0.0 and earlier remain GPL-3.0 permanently.** GPL grants are irrevocable
  (§2). Anyone who already has that code keeps the right to use, modify, and
  redistribute it. Making the repository private would not have changed this.
- **A future version can use different terms only if the necessary rights are
  held.** Do not rely on the old contributor-history snapshot in this document.
  Re-run the audit and have counsel verify any assignment to ContextRuntime LLC
  before relicensing or selling exceptions.
- **Outside contributions affect future relicensing.** Choose and document a CLA,
  copyright assignment, or other counsel-approved contribution policy before
  accepting contributions intended for dual licensing.

---

## 2. Future Distribution Work

Do these only after the license model, copyright ownership, public contact path,
and release coordinates are final.

### 2.1 Claim the Maven Central namespace

Sonatype grants a namespace on proof of control of the matching domain.
`io.contextruntime` requires `contextruntime.io`.

1. Register at <https://central.sonatype.com> (the Central Portal; it replaced
   the older OSSRH/Nexus workflow, so ignore pre-2024 guides).
2. Add namespace `io.contextruntime`.
3. Publish the DNS **TXT** record they generate on `contextruntime.io`.
4. Wait for verification.

> Verify these steps against Sonatype's current documentation when you do it —
> the publishing workflow changed recently and may change again.

### 2.2 Create and publish a signing key

Central requires every artifact to be GPG-signed.

```bash
gpg --gen-key                                  # RSA 4096, no expiry or a long one
gpg --list-keys --keyid-format SHORT           # note the key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID>      # for the GitHub Actions secret
```

Store as repository secrets: `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`.

**The private key never enters this repository.** Same rule the application
follows for provider credentials.

### 2.3 Confirm the commercial contact

Use a GitHub issue link as the interim public contact. If a dedicated mailbox is
added later, verify that it is monitored before placing it in a license or release
artifact.

---

## 3. Already committed and merged

The dependency/package changes and Requirement Advisor are on `main`.

| Commit | What |
|---|---|
| `04c141a` | Dependency trim, 100 MB → 77 MB |
| `aaadf90` | GitHub Packages `distributionManagement` + release publish workflow |
| `5eebb1a` | PR #32, Requirement Advisor merge |
| `743390e` | PR #33, dependency/package trim merge |
| `767231d` | PR #34, test fixture builders merge |

---

## 4. Conditional Changes, File By File

This section is an implementation sketch for the AGPL-plus-commercial option. It
must be revalidated or discarded after the final license decision; none of it is
authorized merely because it appears here.

### 4.1 `pom.xml` — coordinates

```xml
<groupId>io.contextruntime</groupId>
<artifactId>llm-council</artifactId>
<version>2.1.0</version>
<name>LLM Council</name>
<description>A configurable council of language models with anti-sycophancy
             deliberation, anonymised peer review, and dissent-preserving synthesis.</description>
<url>https://contextruntime.io/llm-council</url>
<inceptionYear>2025</inceptionYear>
```

**The Java package stays `com.debopam.llmcouncil`.** groupId and package are
independent; they conventionally match but nothing breaks when they do not, and
renaming 220 files buys nothing.

### 4.2 `pom.xml` — licences, developer, SCM

```xml
<licenses>
  <license>
    <name>GNU Affero General Public License v3.0 or later</name>
    <url>https://www.gnu.org/licenses/agpl-3.0.html</url>
    <distribution>repo</distribution>
    <comments>Default licence. Network use triggers the source obligation (§13).</comments>
  </license>
  <license>
    <name>Commercial License</name>
    <url>https://github.com/debopampoddar/llm-council/issues</url>
    <distribution>manual</distribution>
    <comments>Illustrative only. Final terms require legal review; use the repository issue tracker as the interim contact.</comments>
  </license>
</licenses>

<developers>
  <developer>
    <id>debopampoddar</id>
    <name>Debopam Poddar</name>
    <organization>ContextRuntime LLC</organization>
  </developer>
</developers>

<scm>
  <connection>scm:git:https://github.com/debopampoddar/llm-council.git</connection>
  <developerConnection>scm:git:git@github.com:debopampoddar/llm-council.git</developerConnection>
  <url>https://github.com/debopampoddar/llm-council</url>
  <tag>HEAD</tag>
</scm>
```

### 4.3 `pom.xml` — third-party notices (D8)

Redistributing a fat jar means redistributing ~110 Apache-2.0 libraries, and
**Apache-2.0 §4(d) requires their NOTICE files to travel with it.** The jar
currently ships none. Generated at build time so it cannot go stale:

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>license-maven-plugin</artifactId>
  <version>2.4.0</version>
  <executions>
    <execution>
      <id>third-party-notices</id>
      <phase>generate-resources</phase>
      <goals><goal>add-third-party</goal></goals>
      <configuration>
        <outputDirectory>${project.build.outputDirectory}/META-INF</outputDirectory>
        <thirdPartyFilename>THIRD-PARTY-NOTICES.txt</thirdPartyFilename>
        <excludedScopes>test,provided</excludedScopes>
        <!-- D8: an unresolved licence is a gap in an obligation, not a warning. -->
        <failOnMissing>true</failOnMissing>
        <licenseMerges>
          <licenseMerge>Apache License, Version 2.0|Apache 2.0|Apache-2.0|The Apache Software License, Version 2.0|Apache 2|The Apache License, Version 2.0|Apache License 2.0</licenseMerge>
        </licenseMerges>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Expect `failOnMissing` to fail the first run: 38 dependencies inherit their
licence from a parent POM and resolve to UNKNOWN. Fix with a
`missing-file` mapping rather than by turning the flag off — §6 records what
they actually are.

### 4.4 `pom.xml` — Maven Central publishing

```xml
<plugin>
  <groupId>org.sonatype.central</groupId>
  <artifactId>central-publishing-maven-plugin</artifactId>
  <version>0.7.0</version>
  <extensions>true</extensions>
  <configuration>
    <publishingServerId>central</publishingServerId>
  </configuration>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-gpg-plugin</artifactId>
  <executions>
    <execution><id>sign-artifacts</id><phase>verify</phase><goals><goal>sign</goal></goals></execution>
  </executions>
</plugin>
```

Central also requires **sources and javadoc jars**. Note what that means: they
publish your source. That is consistent with AGPL and is the reason the open
artifact goes to Central while anything proprietary would not.

### 4.5 `LICENSE` and `LICENSING.md`

- `LICENSE` — replace GPL-3.0 text with **AGPL-3.0** text verbatim.
- `LICENSING.md` — new. Plain English: AGPL by default, what §13 requires of a
  network deployment, that a commercial licence removes those obligations, and
  the contact address. State plainly that **v2.0.0 and earlier are GPL-3.0** and
  that the change applies from v2.1.0.
- `NOTICE` — new. Copyright line for ContextRuntime LLC.

### 4.6 SPDX headers (D9)

```java
// SPDX-FileCopyrightText: 2025 ContextRuntime LLC
// SPDX-License-Identifier: AGPL-3.0-or-later
```

Applied to `src/main/java`, `src/test/java`, and the static JS/CSS/HTML —
about 220 files, scripted. Optionally enforced afterwards with
`license-maven-plugin:check` so a new file without a header fails the build.

### 4.7 README

- State the licence and that it changed at v2.1.0.
- Point commercial enquiries at `LICENSING.md`.
- Add Maven Central coordinates alongside the existing GitHub Packages section.

### 4.8 `CONTRIBUTING.md` + CLA

**Required to keep the commercial tier viable.** Without assignment from every
outside contributor, those files cannot be relicensed and exceptions cannot be
sold for them. A DCO is the lightweight option; a CLA is the safer one. Wording
is a question for a lawyer.

---

## 5. Dependency trim — what was done and why

100 MB → 77 MB. **All four provider starters are kept**; only unused transitive
dependencies were excluded.

| Starter | Excluded | Saved | Evidence |
|---|---|---|---|
| `spring-ai-starter-model-anthropic` | `io.rest-assured:json-path` (pulls Groovy) | ~8 MB | Constant-pool scan of every `spring-ai-*` binary for `restassured`, `jayway`, `groovy` → **zero references**. A test library that escaped into compile scope in Spring AI's own POM |
| `spring-ai-starter-model-vertex-ai-gemini` | `grpc-xds`, `grpc-services`, `grpc-googleapis`, `conscrypt` | ~15 MB | xDS is Envoy service discovery; grpc-services is server-side reflection/health/channelz; conscrypt is an alternative TLS provider the JDK does not need. All `runtime` scope, none on the path for a direct Vertex API call |

**Considered and rejected:** `grpc-alts` (0.31 MB) and `grpc-grpclb` (0.17 MB) —
real but tiny, and ALTS sits on the Compute Engine credentials path.

**77 MB is the floor** while Gemini ships in the default build. What remains is
load-bearing: `grpc-netty-shaded` (9.3 MB) is the transport, and
`guava`/`protobuf`/`proto-google-*` are required by it. The Vertex stack totals
~23 MB. Removing it would need a Maven profile and a separate source set,
because `GeminiConditionalConfig` is compile-coupled to
`com.google.cloud.vertexai.VertexAI` — and Gemini is wanted in the default
build, so that option is closed.

`sqlite-jdbc` is 13.7 MB because it ships native binaries for twenty
platform/architecture combinations. Trimming trades size for portability on a
tool people install locally; not recommended.

**Limit of the evidence:** the test suite is hermetic, so a green run does not
exercise the Anthropic or Vertex call paths. These exclusions rest on the
bytecode scan. A live-model integration test is what would make them certain —
see §7.

**Re-check both exclusions on any Spring AI upgrade.**

---

## 6. Dependency licence audit

Run against the runtime scope. Nothing here blocks a commercial or dual-licensed
release.

| Licence | Count | Note |
|---|---|---|
| Apache-2.0 (six spellings) | 102 | Requires NOTICE redistribution — §4.3 |
| BSD-3-Clause / BSD / MIT / MIT-0 / CC0 / public domain | 8 | Permissive |
| GPL2 **with classpath exception** | 2 | `jakarta.annotation-api`, `javax.annotation-api`. The exception exists to permit linking from proprietary code. **Not a blocker** |
| EPL-1.0 **or** LGPL-2.1 (dual) | 1 | `logback-classic`. **Elect EPL-1.0** and record the election. Not modified, so no source obligation |

38 dependencies returned UNKNOWN because they inherit `<licenses>` from a parent
POM. Spot-checked: Jackson is Apache-2.0, Google/gax is Apache-2.0, victools is
Apache-2.0, logback is the dual case above. These are what §4.3's
`missing-file` mapping needs to declare.

---

## 7. Open risks

1. **Hermetic tests do not prove general provider integration.** `mvn test`
   never calls a model. Historical manual Ollama runs verified the then-shipped
   conditional rigorous path and every forced rigorous stage on the reviewed
   laptop, but that is evidence for one installed Ollama version and model set,
   not a repeatable contract and not evidence for Anthropic or Vertex. This is
   also what the dependency exclusions would rest on more comfortably.
2. **No live-provider Maven profile exists.** Do not tell contributors to run
   `-Pintegration`; add an explicit `provider-contracts` profile before
   documenting that workflow.
3. **Maven Central is immutable.** Nothing published is ever removed. Settle
   groupId, licence, and version before the first release.
4. **CLA before the first outside contribution**, not after.
5. **Resolved 2026-08-17:** pull-request CI now runs a clean Java 25 build plus
   repository YAML, documentation-link, and removed-provider checks.
6. **Resolved 2026-08-17:** the two dead README links were replaced with the
   current production-readiness and machine testing guides.

---

## 8. Resume Checklist

After legal review and an explicit final decision:

- [ ] Choose the license for each repository; do not assume the evaluation repo inherits the application license
- [ ] Verify and document copyright ownership or assignment to ContextRuntime LLC
- [ ] Confirm the GitHub issue contact and whether private security/licensing contact is also required
- [ ] Claim `io.contextruntime` on the Central Portal; publish the DNS TXT record
- [ ] Generate the GPG key, publish it, add `GPG_PRIVATE_KEY` / `GPG_PASSPHRASE` secrets
- [ ] Apply §4.1–4.4 to `pom.xml`; bump to 2.1.0
- [ ] Swap `LICENSE` to AGPL-3.0; write `LICENSING.md` and `NOTICE`
- [ ] Script the SPDX headers across ~220 files
- [ ] Generate `THIRD-PARTY-NOTICES.txt`; resolve the UNKNOWN licences
- [ ] Update the README licence and coordinates sections
- [ ] Add `CONTRIBUTING.md` and the CLA
- [ ] Extend the publish workflow to Central alongside GitHub Packages
- [ ] Tag and release v2.1.0

---

*Engineering guidance, not legal advice. The commercial licence wording and the
CLA are worth an hour with a lawyer.*
