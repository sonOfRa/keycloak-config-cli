/*-
 * ---license-start
 * keycloak-config-cli
 * ---
 * Copyright (C) 2017 - 2020 adorsys GmbH & Co. KG @ https://adorsys.com
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

package de.adorsys.keycloak.config.repository;

import de.adorsys.keycloak.config.exception.KeycloakRepositoryException;
import de.adorsys.keycloak.config.provider.KeycloakProvider;
import de.adorsys.keycloak.config.util.ResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RealmsResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import javax.ws.rs.WebApplicationException;

@Service
public class RealmRepository {
    public static final String MASTER_REALM = "master";

    private final KeycloakProvider keycloakProvider;

    private String version;

    @Autowired
    public RealmRepository(KeycloakProvider keycloakProvider) {
        this.keycloakProvider = keycloakProvider;
    }

    public boolean exists(String realmName) {
        return search(realmName).isPresent();
    }

    final RealmResource getResource(String realmName) {
        return keycloakProvider.get().realms().realm(realmName);
    }

    public void create(RealmRepresentation realmToCreate) {
        Keycloak keycloak = keycloakProvider.get();
        RealmsResource realmsResource = keycloak.realms();

        try {
            realmsResource.create(realmToCreate);
        } catch (WebApplicationException error) {
            String errorMessage = ResponseUtil.getErrorMessage(error);
            throw new KeycloakRepositoryException(
                    "Cannot create realm '" + realmToCreate.getRealm() + "': " + errorMessage,
                    error
            );
        }
    }

    public RealmRepresentation get(String realmName) {
        return getResource(realmName).toRepresentation();
    }

    public String getVersion() {
        if (version == null) {
            version = getResource(MASTER_REALM)
                    .partialExport(false, false)
                    .getKeycloakVersion();
        }
        return version;
    }

    public void update(RealmRepresentation realmToUpdate) {
        getResource(realmToUpdate.getRealm()).update(realmToUpdate);
    }

    public RealmRepresentation partialExport(String realmName, boolean exportGroupsAndRoles, boolean exportClients) {
        return getResource(realmName).partialExport(exportGroupsAndRoles, exportClients);
    }

    private Optional<RealmRepresentation> search(String realmName) {
        Optional<RealmRepresentation> maybeRealm;

        try {
            RealmResource realmResource = getResource(realmName);

            // check here if realmName is present, otherwise this method throws an NotFoundException
            RealmRepresentation foundRealm = realmResource.toRepresentation();

            maybeRealm = Optional.of(foundRealm);
        } catch (javax.ws.rs.NotFoundException e) {
            maybeRealm = Optional.empty();
        }

        return maybeRealm;
    }
}
