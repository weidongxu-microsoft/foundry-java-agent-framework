package io.github.weidongxu.agentframework.foundry;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.AzureCliCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Convenience configuration for connecting to a Microsoft Foundry project.
 *
 * <p>Holds the project endpoint plus a {@link CredentialType} choice and builds the matching
 * {@link TokenCredential} and a {@link FoundryClientFactory}. The workload no longer has to
 * hardcode {@code DefaultAzureCredentialBuilder} or read the endpoint env var itself.
 *
 * <p>Config can be provided explicitly via {@link #builder()} or parsed from environment
 * variables via {@link #fromEnvironment()} / {@link #fromEnvironment(Function)}:
 * <ul>
 *   <li>{@code FOUNDRY_PROJECT_ENDPOINT} — required project endpoint.</li>
 *   <li>{@code FOUNDRY_CREDENTIAL} — {@code default} (the default), {@code azure_cli}, or
 *       {@code managed_identity}. Case- and separator-insensitive.</li>
 *   <li>{@code FOUNDRY_MANAGED_IDENTITY_CLIENT_ID} — optional user-assigned identity client id
 *       (only applies to {@code managed_identity}).</li>
 * </ul>
 */
public final class FoundryConfig {

    /** Environment variable holding the project endpoint. */
    public static final String ENDPOINT_ENV = "FOUNDRY_PROJECT_ENDPOINT";
    /** Environment variable selecting the credential type. */
    public static final String CREDENTIAL_ENV = "FOUNDRY_CREDENTIAL";
    /** Environment variable holding a user-assigned managed identity client id. */
    public static final String MANAGED_IDENTITY_CLIENT_ID_ENV = "FOUNDRY_MANAGED_IDENTITY_CLIENT_ID";

    /** Supported credential strategies. */
    public enum CredentialType {
        /** {@code DefaultAzureCredential} — chained discovery across env, managed identity, CLI, etc. */
        DEFAULT,
        /** {@code AzureCliCredential} — reuses the signed-in {@code az login} identity. */
        AZURE_CLI,
        /** {@code ManagedIdentityCredential} — for workloads running in Azure. */
        MANAGED_IDENTITY;

        /**
         * Parses a credential type from a free-form string, ignoring case and {@code -}/{@code _}
         * separators. {@code null}/blank resolves to {@link #DEFAULT}.
         */
        public static CredentialType parse(String value) {
            if (value == null || value.trim().isEmpty()) {
                return DEFAULT;
            }
            String normalized = value.trim().toLowerCase().replace('-', '_').replace(' ', '_');
            switch (normalized) {
                case "default":
                case "default_azure":
                    return DEFAULT;
                case "azure_cli":
                case "cli":
                    return AZURE_CLI;
                case "managed_identity":
                case "managed":
                case "msi":
                    return MANAGED_IDENTITY;
                default:
                    throw new IllegalArgumentException("Unknown Foundry credential type: " + value);
            }
        }
    }

    private final String projectEndpoint;
    private final CredentialType credentialType;
    private final String managedIdentityClientId;

    private FoundryConfig(Builder builder) {
        this.projectEndpoint = Objects.requireNonNull(builder.projectEndpoint, "projectEndpoint");
        if (this.projectEndpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("projectEndpoint cannot be blank");
        }
        this.credentialType = Objects.requireNonNull(builder.credentialType, "credentialType");
        this.managedIdentityClientId = builder.managedIdentityClientId;
    }

    public String getProjectEndpoint() {
        return projectEndpoint;
    }

    public CredentialType getCredentialType() {
        return credentialType;
    }

    public String getManagedIdentityClientId() {
        return managedIdentityClientId;
    }

    /** Builds the {@link TokenCredential} selected by {@link #getCredentialType()}. */
    public TokenCredential createCredential() {
        switch (credentialType) {
            case AZURE_CLI:
                return new AzureCliCredentialBuilder().build();
            case MANAGED_IDENTITY:
                ManagedIdentityCredentialBuilder mi = new ManagedIdentityCredentialBuilder();
                if (managedIdentityClientId != null && !managedIdentityClientId.trim().isEmpty()) {
                    mi.clientId(managedIdentityClientId);
                }
                return mi.build();
            case DEFAULT:
            default:
                return new DefaultAzureCredentialBuilder().build();
        }
    }

    /** Builds a {@link FoundryClientFactory} using this config's endpoint and credential. */
    public FoundryClientFactory createClientFactory() {
        return new FoundryClientFactory(projectEndpoint, createCredential());
    }

    /** Reads config from the process environment ({@link System#getenv(String)}). */
    public static FoundryConfig fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    /** Reads config from the given environment {@code Map}. */
    public static FoundryConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return fromEnvironment(environment::get);
    }

    /**
     * Reads config using the given variable lookup. Testable without touching the real process
     * environment.
     */
    public static FoundryConfig fromEnvironment(Function<String, String> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        String endpoint = lookup.apply(ENDPOINT_ENV);
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing required environment variable " + ENDPOINT_ENV);
        }
        return builder()
                .projectEndpoint(endpoint)
                .credentialType(CredentialType.parse(lookup.apply(CREDENTIAL_ENV)))
                .managedIdentityClientId(lookup.apply(MANAGED_IDENTITY_CLIENT_ID_ENV))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link FoundryConfig}. */
    public static final class Builder {
        private String projectEndpoint;
        private CredentialType credentialType = CredentialType.DEFAULT;
        private String managedIdentityClientId;

        private Builder() {
        }

        public Builder projectEndpoint(String projectEndpoint) {
            this.projectEndpoint = projectEndpoint;
            return this;
        }

        public Builder credentialType(CredentialType credentialType) {
            this.credentialType = credentialType;
            return this;
        }

        public Builder managedIdentityClientId(String managedIdentityClientId) {
            this.managedIdentityClientId = managedIdentityClientId;
            return this;
        }

        public FoundryConfig build() {
            return new FoundryConfig(this);
        }
    }
}
