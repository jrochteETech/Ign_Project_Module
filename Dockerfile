# syntax=docker/dockerfile:1.7
FROM gradle:8.10.2-jdk17@sha256:c2900027f3f0681c2cbfb09d527813851ad67aeafbb409997297efa2df20e748 AS verify
USER root
ENV GRADLE_USER_HOME=/home/gradle/.gradle
WORKDIR /workspace
COPY . .
ARG PROJECT_BRANCH
ARG IGNITION_TARGET=8.3
ARG MODULE_VERSION=0.1.0
ARG GIT_COMMIT=unknown
ARG PROJECT_SOURCE=perspective-project
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle check zipModule --no-daemon --console=plain \
    "-PprojectBranch=${PROJECT_BRANCH}" "-PignitionTarget=${IGNITION_TARGET}" \
    "-PmoduleVersion=${MODULE_VERSION}" "-PgitCommit=${GIT_COMMIT}" "-PprojectSource=${PROJECT_SOURCE}"

FROM verify AS signed
RUN --mount=type=cache,target=/home/gradle/.gradle \
    --mount=type=tmpfs,target=/workspace/.gradle \
    --mount=type=secret,id=keystore,required=true \
    --mount=type=secret,id=signing_properties,required=true \
    gradle exportArtifacts --no-daemon --no-configuration-cache --console=plain \
    "-PprojectBranch=${PROJECT_BRANCH}" "-PignitionTarget=${IGNITION_TARGET}" \
    "-PmoduleVersion=${MODULE_VERSION}" "-PgitCommit=${GIT_COMMIT}" "-PprojectSource=${PROJECT_SOURCE}"

FROM scratch AS output
COPY --from=signed /workspace/dist/ /