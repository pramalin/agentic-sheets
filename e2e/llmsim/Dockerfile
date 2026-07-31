# llmsim-build:0.10.1 -- confirmed working directly (not guessed): this
# version was actually pulled and built successfully in a real E2E run,
# resolving what had been two competing unverified claims (0.1.0, an
# illustrative example from llmsim's own README, not necessarily its
# latest tag; and 0.10.1, asserted without shown verification at the
# time). If llmsim ever tags a newer release worth moving to, that's a
# deliberate future upgrade, not something to change without re-running
# the golden-path test against it.
FROM ghcr.io/pramalin/llmsim-build:0.10.1 AS build
COPY HoldingsHappyPath.scala /build/src/main/scala/com/alai/agenticsheets/llmsim/HoldingsHappyPath.scala
RUN sbt assembly

FROM eclipse-temurin:21-jre-jammy
COPY --from=build /build/target/scala-3.3.3/llmsim.jar /app/llmsim.jar
ENV LLMSIM_SCRIPT=com.alai.agenticsheets.llmsim.HoldingsHappyPath
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "/app/llmsim.jar"]
