/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.it.cli.dist;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.keycloak.it.junit5.extension.BeforeStartDistribution;
import org.keycloak.it.junit5.extension.CLIResult;
import org.keycloak.it.junit5.extension.DistributionTest;
import org.keycloak.it.junit5.extension.RawDistOnly;
import org.keycloak.it.junit5.extension.WithDatabase;
import org.keycloak.it.utils.KeycloakDistribution;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;

@DistributionTest(defaultOptions = {"--http-enabled=true", "--hostname-strict=false"})
@RawDistOnly(reason = "Containers are immutable")
@TestMethodOrder(OrderAnnotation.class)
@WithDatabase(alias = "postgres")
public class QuarkusPropertiesAutoBuildDistTest {

    @Test
    @Launch({ "start" })
    @Order(1)
    @Disabled
    void reAugOnFirstRun(LaunchResult result) {
        CLIResult cliResult = (CLIResult) result;
        cliResult.assertBuild();
    }

    @Test
    @BeforeStartDistribution(QuarkusPropertiesAutoBuildDistTest.UpdateConsoleLogLevelToWarn.class)
    @Launch({ "start" })
    @Order(2)
    void testQuarkusRuntimePropDoesNotTriggerReAug(LaunchResult result) {
        CLIResult cliResult = (CLIResult) result;
        cliResult.assertNoBuild();
        assertFalse(cliResult.getOutput().contains("INFO  [io.quarkus]"));
    }

    @Test
    @BeforeStartDistribution(UpdateConsoleLogLevelToInfo.class)
    @Launch({ "start" })
    @Order(3)
    void testNoReAugAfterChangingRuntimeProperty(LaunchResult result) {
        CLIResult cliResult = (CLIResult) result;
        cliResult.assertNoBuild();
        assertTrue(cliResult.getOutput().contains("INFO  [io.quarkus]"));
    }

    @Test
    @BeforeStartDistribution(AddAdditionalDatasource.class)
    @Launch({ "start" })
    @Order(4)
    @Disabled
    void testReAugForAdditionalDatasource(LaunchResult result) {
        CLIResult cliResult = (CLIResult) result;
        cliResult.assertBuild();
    }

    @Test
    @BeforeStartDistribution(ChangeAdditionalDatasourceUsername.class)
    @Launch({ "start" })
    @Order(5)
    @Disabled
    void testNoReAugForAdditionalDatasourceRuntimeProperty(LaunchResult result) {
        CLIResult cliResult = (CLIResult) result;
        cliResult.assertNoBuild();
    }

    @Test
    @BeforeStartDistribution(ChangeAdditionalDatasourceDbKind.class)
    @Launch({ "start" })
    @Order(6)
    void testNoReAugWhenBuildTimePropertiesAreTheSame(LaunchResult result) {
        CLIResult cliResult = (CLIResult) result;
        cliResult.assertNoBuild();
    }

    @Test
    @BeforeStartDistribution(AddAdditionalDatasource2.class)
    @Launch({ "start" })
    @Order(7)
    @Disabled
    void testReAugWhenAnotherDatasourceAdded(LaunchResult result) {
        CLIResult cliResult = (CLIResult) result;
        cliResult.assertBuild();
    }

    @Test
    @BeforeStartDistribution(SetDatabaseKind.class)
    @Launch({ "start" })
    @Order(8)
    void testWrappedBuildPropertyTriggersBuildButGetsIgnoredWhenSetByQuarkus(LaunchResult result) {
        CLIResult cliResult = (CLIResult) result;
        cliResult.assertBuild();
        cliResult.assertStarted();
    }

    public static class UpdateConsoleLogLevelToWarn implements Consumer<KeycloakDistribution> {
        @Override
        public void accept(KeycloakDistribution distribution) {
            distribution.setQuarkusProperty("quarkus.log.console.level", "WARN");
        }
    }

    public static class UpdateConsoleLogLevelToInfo implements Consumer<KeycloakDistribution> {

        @Override
        public void accept(KeycloakDistribution distribution) {
            distribution.setQuarkusProperty("quarkus.log.console.level", "INFO");
        }
    }

    public static class AddAdditionalDatasource implements Consumer<KeycloakDistribution> {
        @Override
        public void accept(KeycloakDistribution distribution) {
            distribution.setQuarkusProperty("quarkus.datasource.user-store.db-kind", "h2");
            distribution.setQuarkusProperty("quarkus.datasource.user-store.username","sa");
            distribution.setQuarkusProperty("quarkus.datasource.user-store.jdbc.url","jdbc:h2:mem:user-store;DB_CLOSE_DELAY=-1");
        }
    }

    public static class AddAdditionalDatasource2 implements Consumer<KeycloakDistribution> {
        @Override
        public void accept(KeycloakDistribution distribution) {
            distribution.setQuarkusProperty("quarkus.datasource.user-store2.db-kind", "h2");
            distribution.setQuarkusProperty("quarkus.datasource.user-store2.db-transactions", "enabled");
            distribution.setQuarkusProperty("quarkus.datasource.user-store2.username","sa");
            distribution.setQuarkusProperty("quarkus.datasource.user-store2.jdbc.url","jdbc:h2:mem:user-store2;DB_CLOSE_DELAY=-1");
        }
    }

    public static class ChangeAdditionalDatasourceUsername implements Consumer<KeycloakDistribution> {
        @Override
        public void accept(KeycloakDistribution distribution) {
            distribution.setQuarkusProperty("quarkus.datasource.user-store.username","foo");
        }
    }

    public static class ChangeAdditionalDatasourceDbKind implements Consumer<KeycloakDistribution> {
        @Override
        public void accept(KeycloakDistribution distribution) {
            distribution.setQuarkusProperty("quarkus.datasource.user-store.db-kind","h2");
        }
    }

    public static class SetDatabaseKind implements Consumer<KeycloakDistribution> {
        @Override
        public void accept(KeycloakDistribution distribution) {
            distribution.setManualStop(true);
            distribution.setQuarkusProperty("quarkus.datasource.db-kind", "postgres");
        }
    }
}