# syntax=docker/dockerfile:1
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /out/container-exporter .

FROM scratch
COPY --from=build /out/container-exporter /container-exporter
USER 10001:10001
EXPOSE 9338
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 CMD ["/container-exporter", "-healthcheck"]
ENTRYPOINT ["/container-exporter"]
