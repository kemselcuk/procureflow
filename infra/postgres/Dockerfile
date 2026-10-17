FROM postgres:18.6-bookworm@sha256:afc7e2d441324c0388fa80c3d24f733b4194a4eb7f47dd8ee2b08eb1a24a647c
# Copy rather than bind-mount executable hooks: desktop file sharing can misreport X_OK.
COPY --chmod=0644 infra/postgres/init-databases.sh /docker-entrypoint-initdb.d/10-procureflow.sh
