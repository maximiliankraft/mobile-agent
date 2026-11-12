FROM eclipse-temurin:21-jdk-jammy

ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708
ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle-cache \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:$PATH

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" \
    && curl -fsSL -o /tmp/commandline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/commandline-tools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools" \
    && mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest" \
    && rm /tmp/commandline-tools.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
    && rm -rf "$ANDROID_SDK_ROOT/.cache"

WORKDIR /workspace
COPY . .

RUN chmod +x ./gradlew

CMD ["./gradlew", "assembleRelease"]