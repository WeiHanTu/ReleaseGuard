FROM alpine/git:2.52.0@sha256:4a0e72d49596a1f5d3701aeedafdadc5c0da4062be4657c7bdc4017387f591cc

USER 0
RUN apk add --no-cache git-daemon=2.52.0-r0 \
    && git config --system --add safe.directory /srv/git/releaseguard-environment.git

USER 65534:65534
EXPOSE 9418
ENTRYPOINT ["git", "daemon", "--reuseaddr", "--export-all", "--base-path=/srv/git", "--verbose"]
CMD ["/srv/git"]
