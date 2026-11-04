# Perspective Project Module Framework

A self-contained, gateway-scoped Ignition module that embeds a Perspective
project. No sibling repository is required to build or sign it.

## Branch Workflow

- `main` contains the reusable framework; its module intentionally installs no project.
- Create a separate branch from `main` for each application, such as `project/site-a`.
- Each branch gets a deterministic module ID and project name from its full branch
  name: a normalized slug plus a short SHA-256 hash. Multiple branch modules can coexist.
- Branch renames create new identities; remove the previous module explicitly.
- Merge framework updates from `main` into project branches, not application
  resources back into `main`. Builds do not create branches or commit/push changes.

## Compatibility

| Target | Minimum Gateway / SDK | Project Behavior |
| --- | --- | --- |
| 8.1 | 8.1.33 | Editable managed copy, replaced on delivered version/content changes |
| 8.3 | 8.3.0 | In-memory immutable project; cannot be opened, edited, or exported in Designer |

Artifacts are built separately for each SDK using Java 17 and Gradle 8.10.2. They
share the branch's module ID but are for different Gateway versions. Never install
an 8.1 artifact on 8.3: the minimum-version descriptor does not enforce an upper bound.

The Perspective module must be installed and appropriately licensed or in trial.
Ignition 8.3 module changes require normal acceptance and a Gateway restart.
Self-signed modules may require certificate trust confirmation. Signing does not
mean the module has been certified by Inductive Automation.

## Project Resources

On an application branch, unpack a standard Ignition project export into
`perspective-project/`:

```text
perspective-project/
  project.json
  com.inductiveautomation.perspective/
    views/...
    page-config/...
  ignition/...
```

Preserve resource manifests and associated files. Do not add a containing
project-name folder. No production application is provided on `main`; the project
under gateway test resources is only a packaging fixture and is not included in
the framework JAR. Application branches fail when content is missing or invalid.

SDK parsing validates the bundled export. Archive limits are 64 MiB compressed,
256 MiB expanded, and 50,000 entries. Unsafe paths and project symlinks are rejected.
Resources must match the target Gateway: an 8.3 export is not downgraded for 8.1.
Use `-ProjectSource` / `PROJECT_SOURCE` to select a different unpacked export.
Direct Gradle builds also support `projectSource81` and `projectSource83`.

The runtime project name is branch-derived, not the original export name. Adjust
hardcoded project names in URLs, scripts, messages, and cross-project references;
application resource contents are not rewritten. Do not embed Gateway passwords
or credentials in project resources.

## Independent Signing Identity

This project has its own signing identity with alias `ign-project-module`:

```text
signing/
  ign-project-module.keystore
  ign-project-module.cer
  signing.properties
```

The local `signing/` directory is excluded from Git and Docker build contexts.
Its keystore and properties are supplied only as BuildKit secrets. The properties
contain the randomly generated keystore/key password; it is not printed or passed
on command lines. Preserve these files in a secure backup/password vault: losing
the private key changes the signing identity of future releases. All application
branches may use the same local identity without committing it to any branch.

The identity has already been generated in this working copy. On a new clone,
restore your existing signing identity to retain trust, or intentionally generate
a new self-signed identity with JDK 17+:

```powershell
java tools/CreateSigningIdentity.java
```

Alternatively, generate it using the same Docker builder image:

```powershell
docker run --rm --mount "type=bind,source=$($PWD.Path),target=/workspace" `
  -w /workspace gradle:8.10.2-jdk17 java tools/CreateSigningIdentity.java
```

Generation refuses to overwrite an existing identity. On Windows, host-Java
generation restricts ACLs to the file owner; on POSIX it uses owner-only access.
For generation through Docker on Windows, separately verify host filesystem ACLs.
The certificate uses RSA 3072 / SHA256withRSA and is valid for ten years. This is a
self-signed development/distribution identity, not an organizational CA certificate.

## Docker Builds

Docker Desktop's Linux engine and Buildx are required. No host Java/Gradle or
frontend toolchain is needed for builds. Initial dependency resolution requires
network access to IA's Maven repository, Maven Central, and the Gradle plugin portal.

PowerShell, when local script execution is permitted:

```powershell
.\docker-build.ps1 -IgnitionTarget 8.3 -ModuleVersion 1.0.0
.\docker-build.ps1 -IgnitionTarget 8.1 -ModuleVersion 1.0.0
```

Linux/macOS or Git Bash:

```bash
MODULE_VERSION=1.0.0 bash docker-build.sh 8.3
MODULE_VERSION=1.0.0 bash docker-build.sh 8.1
```

Scripts derive the branch from Git; detached HEAD builds require `-Branch` /
`PROJECT_BRANCH`. Keep CI branch names consistent: a pull-request merge ref has
a different identity from its source branch. Scripts record the commit when available.
The release version defaults to `0.1.0`; pass it explicitly when releasing.

Signing paths default to this repository's ignored `signing/` directory. Override
them with `-KeystorePath` / `-SigningProperties` or `SIGNING_KEYSTORE` /
`SIGNING_PROPERTIES` if your secure files are stored elsewhere.

The framework never changes Windows execution policy. If scripts are blocked,
run Docker directly instead:

```powershell
docker buildx build --target output --no-cache-filter signed `
  --output type=local,dest=./dist `
  --build-arg PROJECT_BRANCH=main --build-arg IGNITION_TARGET=8.3 `
  --secret id=keystore,src=signing/ign-project-module.keystore `
  --secret id=signing_properties,src=signing/signing.properties .
```

Signed `.modl` files, SHA-256 sidecars, and identity metadata are exported to `dist/`.
There is no silent unsigned fallback. The signing stage reruns even when earlier
stages are cached. Do not enable build scans/configuration caching for signing or
pass secrets as build arguments. Do not commit any signing properties or private keys.

## Development Checks

The verification stage needs no signing credentials:

```powershell
docker buildx build --target verify --build-arg PROJECT_BRANCH=main --build-arg IGNITION_TARGET=8.1 .
docker buildx build --target verify --build-arg PROJECT_BRANCH=main --build-arg IGNITION_TARGET=8.3 .
docker buildx build --target verify --build-arg PROJECT_BRANCH=project/test `
  --build-arg PROJECT_SOURCE=gateway/src/test/resources/project .
```

With JDK 17 installed, the Gradle wrapper can also run `check` or `build`. Signed
local builds need `-PsigningKeystore=<path>` and `-PsigningProperties=<path>`.

## Deployment and Recovery

Install the matching signed module through the Gateway's normal workflow. Back up
the Gateway first. Tags, connections, devices, authentication providers, external
images, credentials, and other Gateway configuration are not provisioned.

On **8.3**, the immutable bundle is registered during module startup, after the
project manager has loaded, so an existing project name causes failure rather
than intentional shadowing. Restart reloads the bundle from the installed module.
Removing the module and restarting removes its in-memory project. Keep editable
source in Git; the Gateway cannot export the immutable project.

On **8.1**, state lives below the Gateway data directory at
`etech-project-modules/<module-id>/`. An ownership record is required to replace
an existing project. Do not reuse reserved module project names for unrelated
projects, even after manually deleting a managed project: ownership records are
administrative state, not a tamper-proof marker on every project resource.

Each update first exports and validates a backup. Export failure aborts deployment;
replacement failure attempts restoration. The last five backup files are retained.
An unchanged bundle hash/version preserves Designer edits on restart, but those
edits are overwritten by the next delivered content/version change. Uninstalling
the 8.1 module preserves its project and backups.

Interrupted updates or failed restoration leave `pending.properties` and block
further mutation. Stop the module, inspect that record and the referenced backup,
restore the desired project using normal Gateway tools, reconcile the ownership
record with the deployed version, then remove the pending record before retrying.
A first-install failure may leave partial content without ownership: back it up
and reconcile/remove it manually. Never delete deployment state blindly. Gateway
replacement is not an atomic transaction; quiesce Designer edits during upgrades.

Migration from an 8.1 editable copy to an 8.3 immutable module requires an explicit
Gateway backup and retirement of the editable copy first. Automatic conversion is
not implemented. Module rollback does not recover overwritten Designer edits;
restore the appropriate project backup. Local locks/state are not a complete
redundancy synchronization solution; redundant deployments need separate testing.

## Gateway Acceptance Gate

Compilation and unit tests are not live-Gateway certification. Before production
use, test installation/launch, update with changed and removed resources, restart,
downgrade, collision refusal, backup/restoration failure, two branch modules together,
and removal on disposable Gateways running both minimum versions. Specifically
verify 8.3 startup registration/collision visibility, 8.1 manager timing, crash
recovery, recovery-state backup inclusion, and your redundancy configuration.