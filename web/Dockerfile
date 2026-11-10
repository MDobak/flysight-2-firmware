# syntax=docker/dockerfile:1
#
# The website with all its files: the firmware, the public keys of the
# hardware batches, the sound files, and the Android app. The image needs no
# other files.
#
# Build from the root of the repository. The secret is the key that signs the
# Android app (a debug keystore):
#   docker build -f web/Dockerfile --build-arg VERSION="$(git describe --tags --always)" \
#     --secret id=android_keystore,src="$HOME/.android/debug.keystore" -t flysight-web .
# Run:
#   docker run -d --restart unless-stopped -p 80:8080 flysight-web

# The Android build tools have Linux programs for x86-64 only.
ARG APP_PLATFORM=linux/amd64

# The firmware. It is the same for all platforms, so it builds on the build
# platform. The toolchain is the tested version of the local build.
FROM --platform=$BUILDPLATFORM debian:trixie-slim AS firmware
ARG ARM_GCC=13.3.rel1
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl xz-utils \
 && rm -rf /var/lib/apt/lists/*
RUN set -eu; \
    arch=$(uname -m); \
    case "$arch" in \
      x86_64) sum=95c011cee430e64dd6087c75c800f04b9c49832cc1000127a92a97f9c8d83af4 ;; \
      aarch64) sum=c8824bffd057afce2259f7618254e840715f33523a3d4e4294f471208f976764 ;; \
      *) echo "No Arm toolchain for $arch" >&2; exit 1 ;; \
    esac; \
    f=arm-gnu-toolchain-$ARM_GCC-$arch-arm-none-eabi.tar.xz; \
    curl -fsSL -o "/tmp/$f" "https://developer.arm.com/-/media/Files/downloads/gnu/$ARM_GCC/binrel/$f"; \
    echo "$sum  /tmp/$f" | sha256sum -c -; \
    mkdir /opt/arm; \
    tar -xJf "/tmp/$f" -C /opt/arm --strip-components=1; \
    rm "/tmp/$f"
ENV PATH=/opt/arm/bin:$PATH
WORKDIR /src
COPY .cproject STM32WB5MMGHX_FLASH.ld ./
COPY Core Core
COPY Drivers Drivers
COPY FATFS FATFS
COPY FlySight FlySight
COPY Middlewares Middlewares
COPY STM32_WPAN STM32_WPAN
COPY USB_Device USB_Device
COPY Utilities Utilities
COPY Scripts/build.sh Scripts/build.sh
ARG VERSION=unknown
RUN FLYSIGHT_VERSION="$VERSION" Scripts/build.sh --no-sfb --out /out

# The Android app (debug build). It is the same for all platforms.
FROM --platform=$APP_PLATFORM eclipse-temurin:25-jdk AS app
ARG CMDLINE_TOOLS=16111833
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
RUN set -eu; \
    f=commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip; \
    curl -fsSL -o "/tmp/$f" "https://dl.google.com/android/repository/$f"; \
    echo "0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8  /tmp/$f" | sha256sum -c -; \
    unzip -q "/tmp/$f" -d /tmp/clt; \
    mkdir -p "$ANDROID_HOME/cmdline-tools"; \
    mv /tmp/clt/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"; \
    rm -rf "/tmp/$f" /tmp/clt; \
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null; \
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-36" >/dev/null
WORKDIR /src/android
COPY android/ ./
# The Android Gradle plugin signs the debug build with ~/.android/debug.keystore.
RUN --mount=type=secret,id=android_keystore,required=true,target=/root/.android/debug.keystore \
    ./gradlew --no-daemon --quiet assembleDebug \
 && mkdir /out \
 && cp app/build/outputs/apk/debug/app-debug.apk /out/FlySight.apk

# The web server. Go builds for the target platform without emulation.
FROM --platform=$BUILDPLATFORM golang:1.27-trixie AS server
WORKDIR /src
COPY web/go.mod ./
COPY web/*.go web/index.html ./
ARG TARGETOS TARGETARCH VERSION=unknown
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags "-s -w -X main.buildVersion=$VERSION" -o /out/flysight-web .

FROM gcr.io/distroless/static-debian13:nonroot
COPY --from=server /out/flysight-web /app/flysight-web
COPY --from=firmware /out/UserApp.bin /app/UserApp.bin
COPY --from=app /out/FlySight.apk /app/FlySight.apk
COPY Deploy/Public_Keys/ /app/keys/
COPY Audio/voice1/*.WAV /app/audio/
EXPOSE 8080
ENTRYPOINT ["/app/flysight-web"]
CMD ["-addr", ":8080", "-keys", "/app/keys", "-audio", "/app/audio", "-firmware", "/app/UserApp.bin", "-apk", "/app/FlySight.apk"]
