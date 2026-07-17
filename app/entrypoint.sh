#!/bin/sh
# Foundry hosted-agent sandboxes route outbound HTTPS through an egress proxy that terminates TLS
# with a platform CA NOT present in the JVM truststore. The Python/.NET adapters trust it via the
# OS trust store (/etc/ssl/certs); the JVM uses its own cacerts and ignores the OS store, so
# outbound calls to FOUNDRY_PROJECT_ENDPOINT fail with SSLHandshakeException / PKIX path errors.
#
# The platform exposes the egress proxy CA as a single PEM file via NODE_EXTRA_CA_CERTS
# (observed: /etc/ssl/certs/adc-egress-proxy-ca.crt). We import just that one cert into a writable
# copy of the JVM cacerts and point the JVM at it. Importing only the single proxy CA (rather than
# looping keytool over the ~140 certs in ca-certificates.crt) keeps startup fast so the platform
# readiness probe succeeds.
set -e

CACERTS_SRC="${JAVA_HOME}/lib/security/cacerts"
CACERTS="/tmp/cacerts"
cp "${CACERTS_SRC}" "${CACERTS}"
chmod u+w "${CACERTS}"

import_cert() {
  alias="$1"
  file="$2"
  [ -f "$file" ] || return 0
  echo "=== [entrypoint] importing CA: $file (alias=$alias) ==="
  keytool -importcert -noprompt -keystore "${CACERTS}" -storepass changeit \
    -alias "$alias" -file "$file" || echo "[entrypoint] WARN: import of $file failed"
}

# Primary: the egress proxy CA the platform points NODE_EXTRA_CA_CERTS at.
import_cert "foundry-egress-proxy" "${NODE_EXTRA_CA_CERTS:-/etc/ssl/certs/adc-egress-proxy-ca.crt}"

# Enable the Application Insights Java agent only when Foundry injected a connection string.
# The agent reads APPLICATIONINSIGHTS_CONNECTION_STRING from the environment and forwards Logback
# logs (INFO+) to App Insights AppTraces; the augmented truststore below also covers its egress.
AI_AGENT=""
if [ -n "${APPLICATIONINSIGHTS_CONNECTION_STRING:-}" ] && [ -f /app/applicationinsights-agent.jar ]; then
  echo "=== [entrypoint] App Insights Java agent enabled ==="
  AI_AGENT="-javaagent:/app/applicationinsights-agent.jar"
fi

echo "=== [entrypoint] starting JVM with augmented truststore ==="
exec java \
  ${AI_AGENT} \
  -Djavax.net.ssl.trustStore="${CACERTS}" \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -jar /app/app.jar