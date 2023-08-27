FROM docker.io/library/eclipse-temurin:17.0.7_7-jdk
RUN useradd -ms /bin/bash k9bot
COPY build/libs/k9-all.jar /k9.jar
RUN chown k9bot /k9.jar
USER k9bot
VOLUME ["/home/k9bot"]
VOLUME ["/policy"]
WORKDIR /home/k9bot
CMD cat /run/secrets/discord_key | xargs java --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util.regex=ALL-UNNAMED -Djava.security.policy=/policy/k9.policy -jar /k9.jar -a