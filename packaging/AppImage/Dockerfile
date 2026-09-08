FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        wget \
        ca-certificates \
        binutils \
        desktop-file-utils \
        file \
        imagemagick \
    && rm -rf /var/lib/apt/lists/*

ARG JDK_URL=https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse
RUN wget -q -O /tmp/jdk.tar.gz "$JDK_URL" \
    && mkdir -p /opt/jdk \
    && tar -xzf /tmp/jdk.tar.gz --strip-components=1 -C /opt/jdk \
    && rm /tmp/jdk.tar.gz \
    && /opt/jdk/bin/java -version

ARG MAVEN_URL=https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz
RUN wget -q -O /tmp/maven.tar.gz "$MAVEN_URL" \
    && mkdir -p /opt/maven \
    && tar -xzf /tmp/maven.tar.gz --strip-components=1 -C /opt/maven \
    && rm /tmp/maven.tar.gz

ENV JAVA_HOME=/opt/jdk \
    PATH=/opt/jdk/bin:/opt/maven/bin:$PATH

WORKDIR /work

ADD source.tar.gz /work/

RUN mvn -q clean package

RUN /opt/jdk/bin/jlink --add-modules \
        java.base,java.desktop,java.logging,java.prefs \
        --output /opt/runtime \
        --strip-debug --no-header-files --no-man-pages --compress=2

RUN jpackage --type app-image \
        --name prop-loc-edit \
        --app-version 2.0 \
        --vendor svkreml \
        --input /work/target \
        --main-jar prop-loc-edit-2.0-jar-with-dependencies.jar \
        --main-class svkreml.prop_loc_edit.gui.TranslationEditor \
        --runtime-image /opt/runtime \
        --java-options "-Dfile.encoding=UTF-8" \
        --dest /app

RUN mkdir -p /stage/appdir/usr \
    && cp -r /app/prop-loc-edit/* /stage/appdir/usr/

COPY prop-loc-edit.desktop /stage/appdir/prop-loc-edit.desktop
COPY AppRun /stage/appdir/AppRun

RUN convert -size 256x256 xc:'#1e3a8a' \
        -fill '#3b82f6' -draw 'rectangle 0,128 256,256' \
        -fill white -draw 'roundrectangle 32,48 224,80 12,12' \
        -fill white -draw 'roundrectangle 32,96 224,128 12,12' \
        -fill white -draw 'roundrectangle 32,144 224,176 12,12' \
        -fill white -draw 'roundrectangle 32,192 224,224 12,12' \
        /stage/appdir/prop-loc-edit.png

RUN chmod +x /stage/appdir/AppRun \
    && desktop-file-validate /stage/appdir/prop-loc-edit.desktop || true

RUN mkdir -p /tools \
    && wget -q -O /tools/appimagetool.AppImage \
        https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage \
    && chmod +x /tools/appimagetool.AppImage

RUN /tools/appimagetool.AppImage --appimage-extract-and-run \
        /stage/appdir /work/prop-loc-edit-2.0-x86_64.AppImage

RUN ls -lh /work/prop-loc-edit-2.0-x86_64.AppImage
