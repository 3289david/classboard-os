# 중동중학교 전자칠판 데이터 서버 - server 폴더만 있으면 빌드 · 실행됩니다.
#   docker compose up -d --build     (server 폴더에서)

FROM eclipse-temurin:17-jdk AS build
WORKDIR /build
COPY src ./src
ADD https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar lib/json.jar
ADD https://repo1.maven.org/maven2/org/apache/pdfbox/pdfbox/2.0.32/pdfbox-2.0.32.jar lib/pdfbox.jar
ADD https://repo1.maven.org/maven2/org/apache/pdfbox/fontbox/2.0.32/fontbox-2.0.32.jar lib/fontbox.jar
ADD https://repo1.maven.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.jar lib/commons-logging.jar
RUN mkdir classes \
 && javac -nowarn --release 17 -encoding UTF-8 -cp "lib/*" -d classes $(find src -name '*.java') \
 && cd classes && for j in ../lib/*.jar; do jar xf "$j"; done \
 && rm -f META-INF/*.SF META-INF/*.RSA META-INF/*.DSA META-INF/MANIFEST.MF && cd .. \
 && printf 'Main-Class: kr.classboard.server.ServerMain\n' > manifest.txt \
 && jar cfm jdms-server.jar manifest.txt -C classes .

FROM eclipse-temurin:17-jre
# LibreOffice turns HWP / Office attachments into PDF; Korean fonts so pages render correctly.
# (The HWP import extension is added automatically into /data/lo-profile on first start.)
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libreoffice-writer-nogui libreoffice-calc-nogui libreoffice-impress-nogui libreoffice-java-common \
      fonts-nanum fonts-noto-cjk fontconfig \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/jdms-server.jar /app/jdms-server.jar
ENV PORT=8080 DATA_DIR=/data TZ=Asia/Seoul
VOLUME ["/data"]
EXPOSE 8080
CMD ["java", "-Xmx512m", "-jar", "/app/jdms-server.jar"]
