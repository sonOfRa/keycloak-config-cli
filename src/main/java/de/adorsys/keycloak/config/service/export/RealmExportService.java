/*-
 * ---license-start
 * keycloak-config-cli
 * ---
 * Copyright (C) 2017 - 2022 adorsys GmbH & Co. KG @ https://adorsys.com
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package de.adorsys.keycloak.config.service.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import de.adorsys.keycloak.config.KeycloakConfigRunner;
import de.adorsys.keycloak.config.properties.ExportConfigProperties;
import de.adorsys.keycloak.config.properties.KeycloakConfigProperties;
import org.javers.core.Javers;
import org.javers.core.JaversBuilder;
import org.javers.core.diff.ListCompareAlgorithm;
import org.javers.core.diff.changetype.PropertyChange;
import org.javers.core.metamodel.clazz.EntityDefinition;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.ScopeMappingRepresentation;
import org.keycloak.representations.idm.authorization.ResourceServerRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Service
public class RealmExportService {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakConfigRunner.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new YAMLMapper();

    private static final String PLACEHOLDER = "REALM_NAME_PLACEHOLDER";
    private static final Javers JAVERS;

    static {
        YAML_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var realmIgnoredProperties = new ArrayList<String>();
        realmIgnoredProperties.add("id");
        realmIgnoredProperties.add("groups");
        realmIgnoredProperties.add("roles");
        realmIgnoredProperties.add("defaultRole");
        realmIgnoredProperties.add("clientProfiles"); //
        realmIgnoredProperties.add("clientPolicies"); //
        realmIgnoredProperties.add("users");
        realmIgnoredProperties.add("federatedUsers");
        realmIgnoredProperties.add("scopeMappings"); //
        realmIgnoredProperties.add("clientScopeMappings"); //
        realmIgnoredProperties.add("clients"); //
        realmIgnoredProperties.add("clientScopes"); //
        realmIgnoredProperties.add("userFederationProviders");
        realmIgnoredProperties.add("userFederationMappers");
        realmIgnoredProperties.add("identityProviders");
        realmIgnoredProperties.add("identityProviderMappers");
        realmIgnoredProperties.add("protocolMappers"); //
        realmIgnoredProperties.add("components");
        realmIgnoredProperties.add("authenticationFlows");
        realmIgnoredProperties.add("authenticatorConfig");
        realmIgnoredProperties.add("requiredActions");
        realmIgnoredProperties.add("applicationScopeMappings");
        realmIgnoredProperties.add("applications");
        realmIgnoredProperties.add("oauthClients");
        realmIgnoredProperties.add("clientTemplates");

        JAVERS = JaversBuilder.javers()
                .registerEntity(new EntityDefinition(RealmRepresentation.class, "realm", realmIgnoredProperties))
                .registerEntity(new EntityDefinition(ClientRepresentation.class, "clientId",
                        List.of("id", "authorizationSettings", "protocolMappers")))
                .registerEntity(new EntityDefinition(ProtocolMapperRepresentation.class, "name", List.of("id")))
                .withListCompareAlgorithm(ListCompareAlgorithm.LEVENSHTEIN_DISTANCE)
                .build();
    }

    private final ExportConfigProperties exportConfigProperties;
    private final KeycloakConfigProperties keycloakConfigProperties;

    @Autowired
    public RealmExportService(ExportConfigProperties exportConfigProperties,
                              KeycloakConfigProperties keycloakConfigProperties) {
        this.exportConfigProperties = exportConfigProperties;
        this.keycloakConfigProperties = keycloakConfigProperties;

        // TODO allow extra "default" values to be ignored?

        // TODO Ignore clients by regex
    }

    public void doExports() throws Exception {
        var outputLocation = Paths.get(exportConfigProperties.getLocation());
        if (!Files.exists(outputLocation)) {
            Files.createDirectories(outputLocation);
        }
        if (!Files.isDirectory(outputLocation)) {
            logger.error("Output location '{}' is not a directory. Aborting.", exportConfigProperties.getLocation());
        }
        var keycloakConfigVersion = keycloakConfigProperties.getVersion();
        var exportVersion = exportConfigProperties.getKeycloakVersion();
        if (!exportVersion.equals(keycloakConfigVersion)) {
            logger.warn("Keycloak-Config-CLI keycloak version {} and export keycloak version {} are not equal."
                            + " This may cause problems if the API changed."
                            + " Please compile keycloak-config-cli with a matching keycloak version!",
                    keycloakConfigVersion, exportVersion);
        }
        var inputFile = Paths.get(exportConfigProperties.getLocation(), "in", "realm.json");
        try (var is = Files.newInputStream(inputFile)) {
            var exportedRealm = OBJECT_MAPPER.readValue(is, RealmRepresentation.class);
            var exportedRealmRealm = exportedRealm.getRealm();
            RealmRepresentation baselineRealm;
            try (var defaultRealmIs = getClass()
                    .getResourceAsStream(String.format("/reference-realms/%s/realm.json", exportConfigProperties.getKeycloakVersion()))) {
                if (defaultRealmIs == null) {
                    logger.error("Reference realm for version {} does not exist", exportConfigProperties.getKeycloakVersion());
                    return;
                }
                /*
                 * Replace the placeholder with the realm name to import. This sets some internal values like role names,
                 * baseUrls and redirectUrls so that they don't get picked up as "changes"
                 */
                var realmString = new String(defaultRealmIs.readAllBytes(), StandardCharsets.UTF_8).replace(PLACEHOLDER, exportedRealmRealm);
                baselineRealm = OBJECT_MAPPER.readValue(realmString, RealmRepresentation.class);
            }
            /*
             * Trick javers into thinking this is the "same" object, by setting the ID on the reference realm
             * to the ID of the current realm. That way we only get actual changes, not a full list of changes
             * including the "object removed" and "object added" changes
             */
            logger.info("Exporting realm {}", exportedRealmRealm);
            baselineRealm.setRealm(exportedRealm.getRealm());
            var minimizedRealm = new RealmRepresentation();

            handleBaseRealm(exportedRealm, baselineRealm, minimizedRealm);

            var clients = getMinimizedClients(exportedRealm, baselineRealm);
            if (!clients.isEmpty()) {
                minimizedRealm.setClients(clients);
            }

            // No setter for some reason...
            var minimizedScopeMappings = getMinimizedScopeMappings(exportedRealm, baselineRealm);
            if (!minimizedScopeMappings.isEmpty()) {
                var scopeMappings = minimizedRealm.getScopeMappings();
                if (scopeMappings == null) {
                    minimizedRealm.clientScopeMapping("dummy");
                    scopeMappings = minimizedRealm.getScopeMappings();
                    scopeMappings.clear();
                }
                scopeMappings.addAll(getMinimizedScopeMappings(exportedRealm, baselineRealm));
            }

            var clientScopeMappings = getMinimizedClientScopeMappings(exportedRealm, baselineRealm);
            if (!clientScopeMappings.isEmpty()) {
                minimizedRealm.setClientScopeMappings(clientScopeMappings);
            }

            var outputFile = Paths.get(exportConfigProperties.getLocation(), "out", String.format("%s.yaml", exportedRealmRealm));
            try (var os = new FileOutputStream(outputFile.toFile())) {
                YAML_MAPPER.writeValue(os, minimizedRealm);
            }
        }
    }

    private List<ClientRepresentation> getMinimizedClients(RealmRepresentation exportedRealm, RealmRepresentation baselineRealm)
            throws IOException, NoSuchFieldException, IllegalAccessException {
        // Get a client map for better lookups
        var exportedClientMap = new HashMap<String, ClientRepresentation>();
        for (var exportedClient : exportedRealm.getClients()) {
            exportedClientMap.put(exportedClient.getClientId(), exportedClient);
        }

        var baselineClientMap = new HashMap<String, ClientRepresentation>();

        var clients = new ArrayList<ClientRepresentation>();
        for (var baselineRealmClient : baselineRealm.getClients()) {
            var clientId = baselineRealmClient.getClientId();
            baselineClientMap.put(clientId, baselineRealmClient);
            var exportedClient = exportedClientMap.get(clientId);
            if (exportedClient == null) {
                logger.warn("Default realm client '{}' was deleted in exported realm. It will be reintroduced during import!", clientId);
                /*
                 * Here we need to define a configuration parameter: If we want the import *not* to reintroduce default clients that were
                 * deleted, we need to add *all* clients, not just default clients to the dump. Then during import, set the mode that
                 * makes clients fully managed, so that *only* clients that are in the dump end up in the realm
                 */
                continue;
            }
            if (clientChanged(baselineRealmClient, exportedClient)) {
                // We know the client has changed in some way. Now, compare it to a default client to minimize it
                clients.add(getMinimizedClient(exportedClient, clientId));
            }
        }

        // Now iterate over all the clients that are *not* default clients
        for (Map.Entry<String, ClientRepresentation> e : exportedClientMap.entrySet()) {
            var clientId = e.getKey();
            if (!baselineClientMap.containsKey(clientId)) {
                clients.add(getMinimizedClient(e.getValue(), clientId));
            }
        }
        return clients;
    }

    private ClientRepresentation getMinimizedClient(ClientRepresentation exportedClient, String clientId)
            throws IOException, NoSuchFieldException, IllegalAccessException {
        var baselineClient = getBaselineClient(clientId);
        var clientDiff = JAVERS.compare(baselineClient, exportedClient);
        var minimizedClient = new ClientRepresentation();
        for (var change : clientDiff.getChangesByType(PropertyChange.class)) {
            applyChange(minimizedClient, change);
        }
        // For now, don't minimize authorizationSettings and protocolMappers. Add them as-is
        minimizedClient.setProtocolMappers(exportedClient.getProtocolMappers());
        minimizedClient.setAuthorizationSettings(exportedClient.getAuthorizationSettings());
        minimizedClient.setClientId(clientId);
        return minimizedClient;
    }


    private void handleBaseRealm(RealmRepresentation exportedRealm, RealmRepresentation baselineRealm, RealmRepresentation minimizedRealm)
            throws NoSuchFieldException, IllegalAccessException {
        var diff = JAVERS.compare(baselineRealm, exportedRealm);
        for (var change : diff.getChangesByType(PropertyChange.class)) {
            applyChange(minimizedRealm, change);
        }

        // Now that Javers is done, clean up a bit afterwards. We always need to set the realm and enabled fields
        minimizedRealm.setRealm(exportedRealm.getRealm());
        minimizedRealm.setEnabled(exportedRealm.isEnabled());

        // If the realm ID diverges from the name, include it in the dump, otherwise remove it
        if (Objects.equals(exportedRealm.getRealm(), exportedRealm.getId())) {
            minimizedRealm.setId(null);
        } else {
            minimizedRealm.setId(exportedRealm.getId());
        }
    }

    private List<ScopeMappingRepresentation> getMinimizedScopeMappings(RealmRepresentation exportedRealm, RealmRepresentation baselineRealm) {
        /*
         * TODO: are the mappings in scopeMappings always clientScope/role? If not, this breaks
         */
        // First handle the "default" scopeMappings present in the
        var exportedMappingsMap = new HashMap<String, ScopeMappingRepresentation>();
        for (var exportedMapping : exportedRealm.getScopeMappings()) {
            exportedMappingsMap.put(exportedMapping.getClientScope(), exportedMapping);
        }

        var baselineMappingsMap = new HashMap<String, ScopeMappingRepresentation>();

        var mappings = new ArrayList<ScopeMappingRepresentation>();
        for (var baselineRealmMapping : baselineRealm.getScopeMappings()) {
            var clientScope = baselineRealmMapping.getClientScope();
            baselineMappingsMap.put(clientScope, baselineRealmMapping);
            var exportedMapping = exportedMappingsMap.get(clientScope);
            if (exportedMapping == null) {
                logger.warn("Default realm scopeMapping '{}' was deleted in exported realm. It will be reintroduced during import!", clientScope);
                continue;
            }
            // If the exported scopeMapping is different from the one that is present in the baseline realm, export it in the yml
            if (scopeMappingChanged(baselineRealmMapping, exportedMapping)) {
                mappings.add(exportedMapping);
            }
        }

        for (Map.Entry<String, ScopeMappingRepresentation> e : exportedMappingsMap.entrySet()) {
            var clientScope = e.getKey();
            if (!baselineMappingsMap.containsKey(clientScope)) {
                mappings.add(e.getValue());
            }
        }
        return mappings;
    }

    private Map<String, List<ScopeMappingRepresentation>> getMinimizedClientScopeMappings(RealmRepresentation exportedRealm,
                                                                                          RealmRepresentation baselineRealm) {
        var baselineMappings = baselineRealm.getClientScopeMappings();
        var exportedMappings = exportedRealm.getClientScopeMappings();

        var mappings = new HashMap<String, List<ScopeMappingRepresentation>>();
        for (var e : baselineMappings.entrySet()) {
            var key = e.getKey();
            if (!exportedMappings.containsKey(key)) {
                logger.warn("Default realm clientScopeMapping '{}' was deleted in exported realm. It will be reintroduced during import!", key);
                continue;
            }
            var scopeMappings = exportedMappings.get(key);
            if (JAVERS.compareCollections(e.getValue(), scopeMappings, ScopeMappingRepresentation.class).hasChanges()) {
                mappings.put(key, scopeMappings);
            }
        }

        for (var e : exportedMappings.entrySet()) {
            var key = e.getKey();
            if (!baselineMappings.containsKey(key)) {
                mappings.put(key, e.getValue());
            }
        }
        return mappings;
    }


    private boolean scopeMappingChanged(ScopeMappingRepresentation baselineRealmMapping, ScopeMappingRepresentation exportedMapping) {
        return JAVERS.compare(baselineRealmMapping, exportedMapping).hasChanges();
    }

    private void applyChange(Object object, PropertyChange<?> change) throws NoSuchFieldException, IllegalAccessException {
        var field = object.getClass().getDeclaredField(change.getPropertyName());
        field.setAccessible(true);
        field.set(object, change.getRight());
    }

    private boolean clientChanged(ClientRepresentation baselineClient, ClientRepresentation exportedClient) {
        var diff = JAVERS.compare(baselineClient, exportedClient);
        if (diff.hasChanges()) {
            return true;
        }
        if (protocolMappersChanged(baselineClient.getProtocolMappers(), exportedClient.getProtocolMappers())) {
            return true;
        }
        return authorizationSettingsChanged(baselineClient.getAuthorizationSettings(), exportedClient.getAuthorizationSettings());
    }

    private boolean protocolMappersChanged(List<ProtocolMapperRepresentation> defaultMappers, List<ProtocolMapperRepresentation> exportedMappers) {
        // CompareCollections doesn't handle nulls gracefully
        return JAVERS.compareCollections(defaultMappers == null ? List.of() : defaultMappers,
                exportedMappers == null ? List.of() : exportedMappers, ProtocolMapperRepresentation.class).hasChanges();
    }

    private boolean authorizationSettingsChanged(ResourceServerRepresentation defaultSettings, ResourceServerRepresentation exportedSettings) {
        return JAVERS.compare(defaultSettings, exportedSettings).hasChanges();
    }

    private ClientRepresentation getBaselineClient(String clientId) throws IOException {
        try (var is = getClass()
                .getResourceAsStream(String.format("/reference-realms/%s/client.json", exportConfigProperties.getKeycloakVersion()))) {
            var client = OBJECT_MAPPER.readValue(is, ClientRepresentation.class);
            client.setClientId(clientId);
            return client;
        }
    }
}