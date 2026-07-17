package io.github.weidongxu.agentframework.foundry;

import com.azure.core.credential.TokenCredential;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FoundryConfigTest {

    @Test
    void parsesEndpointAndDefaultsCredentialToDefault() {
        Map<String, String> env = new HashMap<>();
        env.put(FoundryConfig.ENDPOINT_ENV, "https://proj.example.com");

        FoundryConfig config = FoundryConfig.fromEnvironment(env);

        assertEquals("https://proj.example.com", config.getProjectEndpoint());
        assertEquals(FoundryConfig.CredentialType.DEFAULT, config.getCredentialType());
        assertNull(config.getManagedIdentityClientId());
    }

    @Test
    void parsesCredentialTypeCaseAndSeparatorInsensitive() {
        assertEquals(FoundryConfig.CredentialType.AZURE_CLI,
                FoundryConfig.CredentialType.parse("Azure-CLI"));
        assertEquals(FoundryConfig.CredentialType.MANAGED_IDENTITY,
                FoundryConfig.CredentialType.parse("managed identity"));
        assertEquals(FoundryConfig.CredentialType.MANAGED_IDENTITY,
                FoundryConfig.CredentialType.parse("MSI"));
        assertEquals(FoundryConfig.CredentialType.DEFAULT,
                FoundryConfig.CredentialType.parse(null));
        assertEquals(FoundryConfig.CredentialType.DEFAULT,
                FoundryConfig.CredentialType.parse("  "));
    }

    @Test
    void rejectsUnknownCredentialType() {
        assertThrows(IllegalArgumentException.class,
                () -> FoundryConfig.CredentialType.parse("kerberos"));
    }

    @Test
    void readsManagedIdentityClientId() {
        Map<String, String> env = new HashMap<>();
        env.put(FoundryConfig.ENDPOINT_ENV, "https://proj.example.com");
        env.put(FoundryConfig.CREDENTIAL_ENV, "managed_identity");
        env.put(FoundryConfig.MANAGED_IDENTITY_CLIENT_ID_ENV, "client-123");

        FoundryConfig config = FoundryConfig.fromEnvironment(env);

        assertEquals(FoundryConfig.CredentialType.MANAGED_IDENTITY, config.getCredentialType());
        assertEquals("client-123", config.getManagedIdentityClientId());
    }

    @Test
    void missingEndpointFails() {
        Map<String, String> env = new HashMap<>();
        assertThrows(IllegalStateException.class, () -> FoundryConfig.fromEnvironment(env));
    }

    @Test
    void blankEndpointFails() {
        assertThrows(IllegalArgumentException.class,
                () -> FoundryConfig.builder().projectEndpoint("  ").build());
    }

    @Test
    void createsCredentialForEachType() {
        for (FoundryConfig.CredentialType type : FoundryConfig.CredentialType.values()) {
            FoundryConfig config = FoundryConfig.builder()
                    .projectEndpoint("https://proj.example.com")
                    .credentialType(type)
                    .build();
            TokenCredential credential = config.createCredential();
            assertNotNull(credential, "credential for " + type);
        }
    }

    @Test
    void createsClientFactoryFromConfig() {
        FoundryConfig config = FoundryConfig.builder()
                .projectEndpoint("https://proj.example.com")
                .credentialType(FoundryConfig.CredentialType.AZURE_CLI)
                .build();
        FoundryClientFactory factory = config.createClientFactory();
        assertNotNull(factory);
    }
}
