package org.keycloak.testsuite.broker;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.IdentityProviderResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.broker.oidc.OIDCIdentityProviderConfig;
import org.keycloak.broker.oidc.mappers.ExternalKeycloakRoleToRoleMapper;
import org.keycloak.broker.oidc.mappers.UserAttributeMapper;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.crypto.Algorithm;
import org.keycloak.models.IdentityProviderMapperModel;
import org.keycloak.models.IdentityProviderMapperSyncMode;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.IdentityProviderSyncMode;
import org.keycloak.models.utils.TimeBasedOTP;
import org.keycloak.protocol.ProtocolMapperUtils;
import org.keycloak.protocol.oidc.OIDCConfigAttributes;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.FederatedIdentityRepresentation;
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.OAuth2ErrorRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.testsuite.Assert;
import org.keycloak.testsuite.updaters.RealmAttributeUpdater;
import org.keycloak.testsuite.util.AccountHelper;
import org.keycloak.testsuite.util.OAuthClient;
import org.keycloak.testsuite.util.WaitUtils;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.keycloak.models.utils.TimeBasedOTP.DEFAULT_INTERVAL_SECONDS;
import static org.keycloak.testsuite.admin.ApiUtil.removeUserByUsername;
import static org.keycloak.testsuite.broker.BrokerRunOnServerUtil.configurePostBrokerLoginWithOTP;
import static org.keycloak.testsuite.broker.BrokerTestConstants.IDP_OIDC_ALIAS;
import static org.keycloak.testsuite.broker.BrokerTestConstants.REALM_PROV_NAME;
import static org.keycloak.testsuite.broker.BrokerTestTools.waitForPage;
import static org.keycloak.testsuite.util.ProtocolMapperUtil.createHardcodedClaim;
import static org.keycloak.testsuite.broker.BrokerTestTools.getConsumerRoot;
import static org.keycloak.testsuite.broker.BrokerTestTools.getProviderRoot;

/**
 * Final class as it's not intended to be overriden. Feel free to remove "final" if you really know what you are doing.
 */
public final class KcOidcBrokerTest extends AbstractAdvancedBrokerTest {
    private final static String USER_ATTRIBUTE_NAME = "user-attribute";
    private final static String USER_ATTRIBUTE_VALUE = "attribute-value";
    private final static String CLAIM_FILTER_REGEXP = ".*-value";

    @Override
    protected BrokerConfiguration getBrokerConfiguration() {
        return BROKER_CONFIG_INSTANCE;
    }

    @Before
    public void setUpTotp() {
        totp = new TimeBasedOTP();
    }

    @Override
    protected Iterable<IdentityProviderMapperRepresentation> createIdentityProviderMappers(IdentityProviderMapperSyncMode syncMode) {
        IdentityProviderMapperRepresentation attrMapper1 = new IdentityProviderMapperRepresentation();
        attrMapper1.setName("manager-role-mapper");
        attrMapper1.setIdentityProviderMapper(ExternalKeycloakRoleToRoleMapper.PROVIDER_ID);
        attrMapper1.setConfig(ImmutableMap.<String, String>builder()
                .put(IdentityProviderMapperModel.SYNC_MODE, syncMode.toString())
                .put("external.role", ROLE_MANAGER)
                .put("role", ROLE_MANAGER)
                .build());

        IdentityProviderMapperRepresentation attrMapper2 = new IdentityProviderMapperRepresentation();
        attrMapper2.setName("user-role-mapper");
        attrMapper2.setIdentityProviderMapper(ExternalKeycloakRoleToRoleMapper.PROVIDER_ID);
        attrMapper2.setConfig(ImmutableMap.<String,String>builder()
                .put(IdentityProviderMapperModel.SYNC_MODE, syncMode.toString())
                .put("external.role", ROLE_USER)
                .put("role", ROLE_USER)
                .build());

        return Lists.newArrayList(attrMapper1, attrMapper2);
    }

    @Override
    protected void createAdditionalMapperWithCustomSyncMode(IdentityProviderMapperSyncMode syncMode) {
        IdentityProviderMapperRepresentation friendlyManagerMapper = new IdentityProviderMapperRepresentation();
        friendlyManagerMapper.setName("friendly-manager-role-mapper");
        friendlyManagerMapper.setIdentityProviderMapper(ExternalKeycloakRoleToRoleMapper.PROVIDER_ID);
        friendlyManagerMapper.setConfig(ImmutableMap.<String,String>builder()
                .put(IdentityProviderMapperModel.SYNC_MODE, syncMode.toString())
                .put("external.role", ROLE_FRIENDLY_MANAGER)
                .put("role", ROLE_FRIENDLY_MANAGER)
                .build());
        friendlyManagerMapper.setIdentityProviderAlias(bc.getIDPAlias());
        RealmResource realm = adminClient.realm(bc.consumerRealmName());
        IdentityProviderResource idpResource = realm.identityProviders().get(bc.getIDPAlias());
        idpResource.addMapper(friendlyManagerMapper).close();
    }

    @Test
    public void mapperDoesNothingForLegacyMode() {
        createRolesForRealm(bc.providerRealmName());
        createRolesForRealm(bc.consumerRealmName());

        createRoleMappersForConsumerRealm(IdentityProviderMapperSyncMode.LEGACY);

        RoleRepresentation managerRole = adminClient.realm(bc.providerRealmName()).roles().get(ROLE_MANAGER).toRepresentation();
        RoleRepresentation userRole = adminClient.realm(bc.providerRealmName()).roles().get(ROLE_USER).toRepresentation();

        UserResource userResource = adminClient.realm(bc.providerRealmName()).users().get(userId);
        userResource.roles().realmLevel().add(Collections.singletonList(managerRole));

        oauth.clientId("broker-app");
        loginPage.open(bc.consumerRealmName());
        logInAsUserInIDPForFirstTime();

        UserResource consumerUserResource = adminClient.realm(bc.consumerRealmName()).users().get(
                adminClient.realm(bc.consumerRealmName()).users().search(bc.getUserLogin()).get(0).getId());
        Set<String> currentRoles = consumerUserResource.roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());

        assertThat(currentRoles, hasItems(ROLE_MANAGER));
        assertThat(currentRoles, not(hasItems(ROLE_USER)));

        AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
        AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

        userResource.roles().realmLevel().add(Collections.singletonList(userRole));

        oauth.clientId("broker-app");
        loginPage.open(bc.consumerRealmName());

        logInAsUserInIDP();

        currentRoles = consumerUserResource.roles().realmLevel().listAll().stream()
                .map(RoleRepresentation::getName)
                .collect(Collectors.toSet());
        assertThat(currentRoles, hasItems(ROLE_MANAGER));
        assertThat(currentRoles, not(hasItems(ROLE_USER)));

        logoutFromRealm(getConsumerRoot(), bc.consumerRealmName());
        logoutFromRealm(getProviderRoot(), bc.providerRealmName());
    }

    @Test
    public void loginFetchingUserFromUserEndpoint() {
        loginFetchingUserFromUserEndpoint(false);
    }

    private void loginFetchingUserFromUserEndpoint(boolean loginIsDenied) {
        RealmResource realm = realmsResouce().realm(bc.providerRealmName());
        ClientsResource clients = realm.clients();
        ClientRepresentation brokerApp = clients.findByClientId("brokerapp").get(0);

        try {
            IdentityProviderResource identityProviderResource = realmsResouce().realm(bc.consumerRealmName()).identityProviders().get(bc.getIDPAlias());
            IdentityProviderRepresentation idp = identityProviderResource.toRepresentation();

            idp.getConfig().put(OIDCIdentityProviderConfig.JWKS_URL, getProviderRoot() + "/auth/realms/" + REALM_PROV_NAME + "/protocol/openid-connect/certs");
            identityProviderResource.update(idp);

            brokerApp.getAttributes().put(OIDCConfigAttributes.USER_INFO_RESPONSE_SIGNATURE_ALG, Algorithm.RS256);
            brokerApp.getAttributes().put("validateSignature", Boolean.TRUE.toString());
            clients.get(brokerApp.getId()).update(brokerApp);

            oauth.clientId("broker-app");
            loginPage.open(bc.consumerRealmName());

            logInWithBroker(bc);

            waitForPage(driver, loginIsDenied? "We are sorry..." : "update account information", false);
            if (loginIsDenied) {
                return;
            }

            updateAccountInformationPage.assertCurrent();
            Assert.assertTrue("We must be on correct realm right now",
                    driver.getCurrentUrl().contains("/auth/realms/" + bc.consumerRealmName() + "/"));

            log.debug("Updating info on updateAccount page");
            updateAccountInformationPage.updateAccountInformation(bc.getUserLogin(), bc.getUserEmail(), "Firstname", "Lastname");

            UsersResource consumerUsers = adminClient.realm(bc.consumerRealmName()).users();

            int userCount = consumerUsers.count();
            Assert.assertTrue("There must be at least one user", userCount > 0);

            List<UserRepresentation> users = consumerUsers.search("", 0, userCount);

            boolean isUserFound = false;
            for (UserRepresentation user : users) {
                if (user.getUsername().equals(bc.getUserLogin()) && user.getEmail().equals(bc.getUserEmail())) {
                    isUserFound = true;
                    break;
                }
            }

            Assert.assertTrue("There must be user " + bc.getUserLogin() + " in realm " + bc.consumerRealmName(),
                    isUserFound);
        } finally {
            brokerApp.getAttributes().put(OIDCConfigAttributes.USER_INFO_RESPONSE_SIGNATURE_ALG, null);
            brokerApp.getAttributes().put("validateSignature", Boolean.FALSE.toString());
            clients.get(brokerApp.getId()).update(brokerApp);
        }
    }

    /**
     * Refers to in old test suite: org.keycloak.testsuite.broker.OIDCBrokerUserPropertyTest
     */
    @Test
    public void loginFetchingUserFromUserEndpointWithClaimMapper() {
        RealmResource realm = realmsResouce().realm(bc.providerRealmName());
        ClientsResource clients = realm.clients();
        ClientRepresentation brokerApp = clients.findByClientId("brokerapp").get(0);
        IdentityProviderResource identityProviderResource = getIdentityProviderResource();

        clients.get(brokerApp.getId()).getProtocolMappers().createMapper(createHardcodedClaim("hard-coded", "hard-coded", "hard-coded", "String", true, true)).close();

        IdentityProviderMapperRepresentation hardCodedSessionNoteMapper = new IdentityProviderMapperRepresentation();

        hardCodedSessionNoteMapper.setName("hard-coded");
        hardCodedSessionNoteMapper.setIdentityProviderAlias(bc.getIDPAlias());
        hardCodedSessionNoteMapper.setIdentityProviderMapper(UserAttributeMapper.PROVIDER_ID);
        hardCodedSessionNoteMapper.setConfig(ImmutableMap.<String, String>builder()
                .put(IdentityProviderMapperModel.SYNC_MODE, IdentityProviderMapperSyncMode.INHERIT.toString())
                .put(UserAttributeMapper.USER_ATTRIBUTE, "hard-coded")
                .put(UserAttributeMapper.CLAIM, "hard-coded")
                .build());

        identityProviderResource.addMapper(hardCodedSessionNoteMapper).close();

        oauth.clientId("broker-app");
        loginPage.open(bc.consumerRealmName());

        loginFetchingUserFromUserEndpoint();

        UserRepresentation user = getFederatedIdentity();

        Assert.assertEquals(1, user.getAttributes().size());
        Assert.assertEquals("hard-coded", user.getAttributes().get("hard-coded").get(0));
    }

    /**
     * Refers to in old test suite: PostBrokerFlowTest#testBrokerReauthentication_samlBrokerWithOTPRequired
     */
    @Test
    public void testReauthenticationSamlBrokerWithOTPRequired() throws Exception {
        KcSamlBrokerConfiguration samlBrokerConfig = KcSamlBrokerConfiguration.INSTANCE;
        ClientRepresentation samlClient = samlBrokerConfig.createProviderClients().get(0);
        IdentityProviderRepresentation samlBroker = samlBrokerConfig.setUpIdentityProvider();
        RealmResource consumerRealm = adminClient.realm(bc.consumerRealmName());

        try {
            updateExecutions(AbstractBrokerTest::disableUpdateProfileOnFirstLogin);
            adminClient.realm(bc.providerRealmName()).clients().create(samlClient);
            consumerRealm.identityProviders().create(samlBroker);

            oauth.clientId("broker-app");
            loginPage.open(bc.consumerRealmName());

            testingClient.server(bc.consumerRealmName()).run(configurePostBrokerLoginWithOTP(samlBrokerConfig.getIDPAlias()));
            logInWithBroker(samlBrokerConfig);

            totpPage.assertCurrent();
            String totpSecret = totpPage.getTotpSecret();
            totpPage.configure(totp.generateTOTP(totpSecret));

            AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
            AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

            setOtpTimeOffset(DEFAULT_INTERVAL_SECONDS, totp);

            oauth.clientId("broker-app");
            loginPage.open(bc.consumerRealmName());

            logInWithBroker(bc);

            waitForPage(driver, "account already exists", false);
            idpConfirmLinkPage.assertCurrent();
            idpConfirmLinkPage.clickLinkAccount();

            loginPage.clickSocial(samlBrokerConfig.getIDPAlias());
            waitForPage(driver, "sign in to", true);
            log.debug("Logging in");
            loginTotpPage.login(totp.generateTOTP(totpSecret));

            assertNumFederatedIdentities(consumerRealm.users().search(samlBrokerConfig.getUserLogin()).get(0).getId(), 2);
        } finally {
            updateExecutions(AbstractBrokerTest::setUpMissingUpdateProfileOnFirstLogin);
            removeUserByUsername(consumerRealm, "consumer");
        }
    }

    /**
     * Refers to in old test suite: PostBrokerFlowTest#testBrokerReauthentication_oidcBrokerWithOTPRequired
     */
    @Test
    public void testReauthenticationOIDCBrokerWithOTPRequired() throws Exception {
        KcSamlBrokerConfiguration samlBrokerConfig = KcSamlBrokerConfiguration.INSTANCE;
        ClientRepresentation samlClient = samlBrokerConfig.createProviderClients().get(0);
        IdentityProviderRepresentation samlBroker = samlBrokerConfig.setUpIdentityProvider();
        RealmResource consumerRealm = adminClient.realm(bc.consumerRealmName());

        try {
            updateExecutions(AbstractBrokerTest::disableUpdateProfileOnFirstLogin);
            adminClient.realm(bc.providerRealmName()).clients().create(samlClient);
            consumerRealm.identityProviders().create(samlBroker);

            oauth.clientId("broker-app");
            loginPage.open(bc.consumerRealmName());

            logInWithBroker(samlBrokerConfig);
            AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
            AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

            testingClient.server(bc.consumerRealmName()).run(configurePostBrokerLoginWithOTP(bc.getIDPAlias()));

            oauth.clientId("broker-app");
            loginPage.open(bc.consumerRealmName());

            logInWithBroker(bc);

            waitForPage(driver, "account already exists", false);
            idpConfirmLinkPage.assertCurrent();
            idpConfirmLinkPage.clickLinkAccount();
            loginPage.clickSocial(samlBrokerConfig.getIDPAlias());

            totpPage.assertCurrent();
            String totpSecret = totpPage.getTotpSecret();
            totpPage.configure(totp.generateTOTP(totpSecret));
            logoutFromRealm(getConsumerRoot(), bc.consumerRealmName());

            assertNumFederatedIdentities(consumerRealm.users().search(samlBrokerConfig.getUserLogin()).get(0).getId(), 2);
        } finally {
            updateExecutions(AbstractBrokerTest::setUpMissingUpdateProfileOnFirstLogin);
            removeUserByUsername(consumerRealm, "consumer");
        }
    }

    /**
     * Refers to in old test suite: PostBrokerFlowTest#testBrokerReauthentication_bothBrokerWithOTPRequired
     */
    @Test
    public void testReauthenticationBothBrokersWithOTPRequired() throws Exception {
        final RealmResource consumerRealm = adminClient.realm(bc.consumerRealmName());
        final RealmResource providerRealm = adminClient.realm(bc.providerRealmName());

        try (RealmAttributeUpdater rauConsumer = new RealmAttributeUpdater(consumerRealm).setOtpPolicyCodeReusable(true).update();
             RealmAttributeUpdater rauProvider = new RealmAttributeUpdater(providerRealm).setOtpPolicyCodeReusable(true).update()) {

            KcSamlBrokerConfiguration samlBrokerConfig = KcSamlBrokerConfiguration.INSTANCE;
            ClientRepresentation samlClient = samlBrokerConfig.createProviderClients().get(0);
            IdentityProviderRepresentation samlBroker = samlBrokerConfig.setUpIdentityProvider();

            try {
                updateExecutions(AbstractBrokerTest::disableUpdateProfileOnFirstLogin);
                providerRealm.clients().create(samlClient);
                consumerRealm.identityProviders().create(samlBroker);

                oauth.clientId("broker-app");
                loginPage.open(bc.consumerRealmName());

                testingClient.server(bc.consumerRealmName()).run(configurePostBrokerLoginWithOTP(samlBrokerConfig.getIDPAlias()));
                logInWithBroker(samlBrokerConfig);
                totpPage.assertCurrent();
                String totpSecret = totpPage.getTotpSecret();
                totpPage.configure(totp.generateTOTP(totpSecret));
                AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
                AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

                testingClient.server(bc.consumerRealmName()).run(configurePostBrokerLoginWithOTP(bc.getIDPAlias()));
                oauth.clientId("broker-app");
                loginPage.open(bc.consumerRealmName());

                logInWithBroker(bc);

                waitForPage(driver, "account already exists", false);
                idpConfirmLinkPage.assertCurrent();
                idpConfirmLinkPage.clickLinkAccount();
                loginPage.clickSocial(samlBrokerConfig.getIDPAlias());

                loginTotpPage.assertCurrent();
                loginTotpPage.login(totp.generateTOTP(totpSecret));
                AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
                AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

                oauth.clientId("broker-app");
                loginPage.open(bc.consumerRealmName());

                logInWithBroker(bc);

                loginTotpPage.assertCurrent();
                loginTotpPage.login(totp.generateTOTP(totpSecret));

                assertNumFederatedIdentities(consumerRealm.users().search(samlBrokerConfig.getUserLogin()).get(0).getId(), 2);
            } finally {
                updateExecutions(AbstractBrokerTest::setUpMissingUpdateProfileOnFirstLogin);
                removeUserByUsername(consumerRealm, "consumer");
            }
        }
    }

    @Test
    public void testInvalidIssuedFor() {
        loginUser();
        AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
        AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

        oauth.clientId("broker-app");
        loginPage.open(bc.consumerRealmName());

        log.debug("Clicking social " + bc.getIDPAlias());
        loginPage.clickSocial(bc.getIDPAlias());
        waitForPage(driver, "sign in to", true);

        RealmResource realm = adminClient.realm(bc.providerRealmName());
        ClientRepresentation rep = realm.clients().findByClientId(BrokerTestConstants.CLIENT_ID).get(0);
        ClientResource clientResource = realm.clients().get(rep.getId());
        ProtocolMapperRepresentation hardCodedAzp = createHardcodedClaim("hard", "azp", "invalid-azp", ProviderConfigProperty.STRING_TYPE, true, true);
        clientResource.getProtocolMappers().createMapper(hardCodedAzp);

        log.debug("Logging in");
        loginPage.login(bc.getUserLogin(), bc.getUserPassword());
        errorPage.assertCurrent();
    }

    @Test
    public void testInvalidAudience() {
        loginUser();
        AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), bc.getUserLogin());
        AccountHelper.logout(adminClient.realm(bc.providerRealmName()), bc.getUserLogin());

        oauth.clientId("broker-app");
        loginPage.open(bc.consumerRealmName());

        log.debug("Clicking social " + bc.getIDPAlias());
        loginPage.clickSocial(bc.getIDPAlias());
        waitForPage(driver, "sign in to", true);

        RealmResource realm = adminClient.realm(bc.providerRealmName());
        ClientRepresentation rep = realm.clients().findByClientId(BrokerTestConstants.CLIENT_ID).get(0);
        ClientResource clientResource = realm.clients().get(rep.getId());
        ProtocolMapperRepresentation hardCodedAzp = createHardcodedClaim("hard", "aud", "invalid-aud", ProviderConfigProperty.LIST_TYPE, true, true);
        clientResource.getProtocolMappers().createMapper(hardCodedAzp);

        log.debug("Logging in");
        loginPage.login(bc.getUserLogin(), bc.getUserPassword());
        errorPage.assertCurrent();
    }

    @Test
    public void testIdPNotFound() {
        final String notExistingIdP = "not-exists";
        final String realmName = realmsResouce().realm(bc.providerRealmName()).toRepresentation().getRealm();
        assertThat(realmName, notNullValue());
        final String LINK = OAuthClient.AUTH_SERVER_ROOT + "/realms/" + realmName + "/broker/" + notExistingIdP + "/endpoint";

        driver.navigate().to(LINK);

        errorPage.assertCurrent();
        assertThat(errorPage.getError(), is("Page not found"));

        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            SimpleHttp.Response simple = SimpleHttp.doGet(LINK, client).asResponse();
            assertThat(simple, notNullValue());
            assertThat(simple.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));

            OAuth2ErrorRepresentation error = simple.asJson(OAuth2ErrorRepresentation.class);
            assertThat(error, notNullValue());
            assertThat(error.getError(), is("Identity Provider [" + notExistingIdP + "] not found."));
        } catch (IOException ex) {
            Assert.fail("Cannot create HTTP client. Details: " + ex.getMessage());
        }
    }

    @Test
    public void testIdPForceSyncUserAttributes() {
        checkUpdatedUserAttributesIdP(true);
    }

    @Test
    public void testIdPNotForceSyncUserAttributes() {
        checkUpdatedUserAttributesIdP(false);
    }

    @Test
    public void loginWithClaimFilter() {
        IdentityProviderResource identityProviderResource = getIdentityProviderResource();

        IdentityProviderRepresentation identityProvider = identityProviderResource.toRepresentation();
        updateIdPClaimFilter(identityProvider, identityProviderResource, true, USER_ATTRIBUTE_NAME, USER_ATTRIBUTE_VALUE);
        driver.navigate().to(getAccountUrl(getConsumerRoot(), bc.consumerRealmName()));
        WaitUtils.waitForPageToLoad();

        loginFetchingUserFromUserEndpoint();

        UserRepresentation user = getFederatedIdentity();

        Assert.assertNotNull(user);
    }

    @Test
    public void loginWithClaimRegexpFilter() {
        IdentityProviderResource identityProviderResource = getIdentityProviderResource();

        IdentityProviderRepresentation identityProvider = identityProviderResource.toRepresentation();
        updateIdPClaimFilter(identityProvider, identityProviderResource, true, USER_ATTRIBUTE_NAME, CLAIM_FILTER_REGEXP);
        driver.navigate().to(getAccountUrl(getConsumerRoot(), bc.consumerRealmName()));
        WaitUtils.waitForPageToLoad();

        loginFetchingUserFromUserEndpoint();

        UserRepresentation user = getFederatedIdentity();

        Assert.assertNotNull(user);
    }

    @Test
    public void denyLoginWithClaimFilter() {
        IdentityProviderResource identityProviderResource = getIdentityProviderResource();

        IdentityProviderRepresentation identityProvider = identityProviderResource.toRepresentation();
        updateIdPClaimFilter(identityProvider, identityProviderResource, true, "hardcoded-missing-claim", "hardcoded-missing-claim-value");
        driver.navigate().to(getAccountUrl(getConsumerRoot(), bc.consumerRealmName()));
        WaitUtils.waitForPageToLoad();

        loginFetchingUserFromUserEndpoint(true);

        List<UserRepresentation> users = realmsResouce().realm(bc.consumerRealmName()).users().search(bc.getUserLogin());
        assertThat(users, Matchers.empty());
    }

    protected void postInitializeUser(UserRepresentation user) {
        user.setAttributes(ImmutableMap.<String, List<String>> builder()
                .put(USER_ATTRIBUTE_NAME, ImmutableList.<String> builder().add(USER_ATTRIBUTE_VALUE).build())
                .build());
    }


    private void updateIdPClaimFilter(IdentityProviderRepresentation idProvider, IdentityProviderResource idProviderResource, boolean filteredByClaim, String claimFilterName, String claimFilterValue) {
        assertThat(idProvider, Matchers.notNullValue());
        assertThat(idProviderResource, Matchers.notNullValue());
        assertThat(claimFilterName, Matchers.notNullValue());
        assertThat(claimFilterValue, Matchers.notNullValue());

        if (idProvider.getConfig().getOrDefault(IdentityProviderModel.FILTERED_BY_CLAIMS, "false").equals(Boolean.toString(filteredByClaim)) &&
            idProvider.getConfig().getOrDefault(IdentityProviderModel.CLAIM_FILTER_NAME, "").equals(claimFilterName) &&
            idProvider.getConfig().getOrDefault(IdentityProviderModel.CLAIM_FILTER_VALUE, "").equals(claimFilterValue)
        ) {
            return;
        }

        idProvider.getConfig().put(IdentityProviderModel.FILTERED_BY_CLAIMS, Boolean.toString(filteredByClaim));
        idProvider.getConfig().put(IdentityProviderModel.CLAIM_FILTER_NAME, claimFilterName);
        idProvider.getConfig().put(IdentityProviderModel.CLAIM_FILTER_VALUE, claimFilterValue);
        idProviderResource.update(idProvider);

        idProvider = idProviderResource.toRepresentation();
        assertThat("Cannot get Identity Provider", idProvider, Matchers.notNullValue());
        assertThat("Filtered by claim didn't change", idProvider.getConfig().get(IdentityProviderModel.FILTERED_BY_CLAIMS), Matchers.equalTo(Boolean.toString(filteredByClaim)));
        assertThat("Claim name didn't change", idProvider.getConfig().get(IdentityProviderModel.CLAIM_FILTER_NAME), Matchers.equalTo(claimFilterName));
        assertThat("Claim value didn't change", idProvider.getConfig().get(IdentityProviderModel.CLAIM_FILTER_VALUE), Matchers.equalTo(claimFilterValue));
    }

    private void checkUpdatedUserAttributesIdP(boolean isForceSync) {
        final String IDP_NAME = getBrokerConfiguration().getIDPAlias();
        final String USERNAME = "demo-user";
        final String PASSWORD = "demo-pwd";
        final String NEW_USERNAME = "demo-user-new";

        final String FIRST_NAME = "John";
        final String LAST_NAME = "Doe";
        final String EMAIL = "mail@example.com";

        final String NEW_FIRST_NAME = "Jack";
        final String NEW_LAST_NAME = "Doee";
        final String NEW_EMAIL = "mail123@example.com";

        RealmResource providerRealmResource = realmsResouce().realm(bc.providerRealmName());
        allowUserEdit(providerRealmResource);

        UsersResource providerUsersResource = providerRealmResource.users();

        String providerUserID = createUser(bc.providerRealmName(), USERNAME, PASSWORD, FIRST_NAME, LAST_NAME, EMAIL);
        UserResource providerUserResource = providerUsersResource.get(providerUserID);

        try {
            IdentityProviderResource consumerIdentityResource = getIdentityProviderResource();
            IdentityProviderRepresentation idProvider = consumerIdentityResource.toRepresentation();

            updateIdPSyncMode(idProvider, consumerIdentityResource,
                    isForceSync ? IdentityProviderSyncMode.FORCE : IdentityProviderSyncMode.IMPORT);

            oauth.clientId("broker-app");
            loginPage.open(bc.consumerRealmName());

            WaitUtils.waitForPageToLoad();

            assertThat(driver.getTitle(), Matchers.containsString("Sign in to " + bc.consumerRealmName()));
            logInWithIdp(IDP_NAME, USERNAME, PASSWORD);

            UserRepresentation userRepresentation = AccountHelper.getUserRepresentation(adminClient.realm(bc.providerRealmName()), USERNAME);

            assertThat(userRepresentation.getUsername(), Matchers.equalTo(USERNAME));
            assertThat(userRepresentation.getEmail(), Matchers.equalTo(EMAIL));
            assertThat(userRepresentation.getFirstName(), Matchers.equalTo(FIRST_NAME));
            assertThat(userRepresentation.getLastName(), Matchers.equalTo(LAST_NAME));

            RealmResource consumerRealmResource = realmsResouce().realm(bc.consumerRealmName());
            List<UserRepresentation> foundUsers = consumerRealmResource.users().searchByUsername(USERNAME, true);
            assertThat(foundUsers, Matchers.hasSize(1));
            UserRepresentation consumerUser = foundUsers.get(0);
            assertThat(consumerUser, Matchers.notNullValue());
            String consumerUserID = consumerUser.getId();
            UserResource consumerUserResource = consumerRealmResource.users().get(consumerUserID);

            checkFederatedIdentityLink(consumerUserResource, providerUserID, USERNAME);

            AccountHelper.logout(adminClient.realm(bc.consumerRealmName()), USERNAME);
            AccountHelper.logout(adminClient.realm(bc.providerRealmName()), USERNAME);

            UserRepresentation providerUser = providerUserResource.toRepresentation();
            providerUser.setUsername(NEW_USERNAME);
            providerUser.setFirstName(NEW_FIRST_NAME);
            providerUser.setLastName(NEW_LAST_NAME);
            providerUser.setEmail(NEW_EMAIL);
            providerUserResource.update(providerUser);

            oauth.clientId("broker-app");
            loginPage.open(bc.consumerRealmName());

            WaitUtils.waitForPageToLoad();

            assertThat(driver.getTitle(), Matchers.containsString("Sign in to " + bc.consumerRealmName()));
            logInWithIdp(IDP_NAME, NEW_USERNAME, PASSWORD);

            userRepresentation = AccountHelper.getUserRepresentation(adminClient.realm(bc.consumerRealmName()), USERNAME);

            // consumer username stays the same, even when sync mode is force
            assertThat(userRepresentation.getUsername(), Matchers.equalTo(USERNAME));
            // other consumer attributes are updated, when sync mode is force
            assertThat(userRepresentation.getEmail(), Matchers.equalTo(isForceSync ? NEW_EMAIL : EMAIL));
            assertThat(userRepresentation.getFirstName(), Matchers.equalTo(isForceSync ? NEW_FIRST_NAME : FIRST_NAME));
            assertThat(userRepresentation.getLastName(), Matchers.equalTo(isForceSync ? NEW_LAST_NAME : LAST_NAME));

            checkFederatedIdentityLink(consumerUserResource, providerUserID, isForceSync ? NEW_USERNAME : USERNAME);
        } finally {
            providerUsersResource.delete(providerUserID);
        }
    }

    private void allowUserEdit(RealmResource realmResource) {
        RealmRepresentation realm = realmResource.toRepresentation();
        realm.setEditUsernameAllowed(true);
        realmResource.update(realm);
    }

    private void checkFederatedIdentityLink(UserResource userResource, String userID, String username) {
        List<FederatedIdentityRepresentation> federatedIdentities = userResource.getFederatedIdentity();
        assertThat(federatedIdentities, Matchers.hasSize(1));
        FederatedIdentityRepresentation federatedIdentity = federatedIdentities.get(0);
        assertThat(federatedIdentity.getIdentityProvider(), Matchers.equalTo(IDP_OIDC_ALIAS));
        assertThat(federatedIdentity.getUserId(), Matchers.equalTo(userID));
        assertThat(federatedIdentity.getUserName(), Matchers.equalTo(username));
    }

    private void updateIdPSyncMode(IdentityProviderRepresentation idProvider, IdentityProviderResource idProviderResource, IdentityProviderSyncMode syncMode) {
        assertThat(idProvider, Matchers.notNullValue());
        assertThat(idProviderResource, Matchers.notNullValue());
        assertThat(syncMode, Matchers.notNullValue());

        if (idProvider.getConfig().get(IdentityProviderModel.SYNC_MODE).equals(syncMode.name())) {
            return;
        }

        idProvider.getConfig().put(IdentityProviderModel.SYNC_MODE, syncMode.name());
        idProviderResource.update(idProvider);

        idProvider = idProviderResource.toRepresentation();
        assertThat("Cannot get Identity Provider", idProvider, Matchers.notNullValue());
        assertThat("Sync mode didn't change", idProvider.getConfig().get(IdentityProviderModel.SYNC_MODE), Matchers.equalTo(syncMode.name()));
    }

    private UserRepresentation getFederatedIdentity() {
        List<UserRepresentation> users = realmsResouce().realm(bc.consumerRealmName()).users().search(bc.getUserLogin());

        Assert.assertEquals(1, users.size());

        return users.get(0);
    }

    private IdentityProviderResource getIdentityProviderResource() {
        return realmsResouce().realm(bc.consumerRealmName()).identityProviders().get(bc.getIDPAlias());
    }

    private static final CustomKcOidcBrokerConfiguration BROKER_CONFIG_INSTANCE = new CustomKcOidcBrokerConfiguration();
    static class CustomKcOidcBrokerConfiguration extends KcOidcBrokerConfiguration {

        @Override
        public List<ClientRepresentation> createProviderClients() {
            List<ClientRepresentation> clients = super.createProviderClients();

            ClientRepresentation client = clients.get(0);
            ProtocolMapperRepresentation userAttrMapper = new ProtocolMapperRepresentation();
            userAttrMapper.setName(USER_ATTRIBUTE_NAME);
            userAttrMapper.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
            userAttrMapper.setProtocolMapper(UserAttributeMapper.PROVIDER_ID);
    
            Map<String, String> userAttrMapperConfig = userAttrMapper.getConfig();
            userAttrMapperConfig.put(ProtocolMapperUtils.USER_ATTRIBUTE, USER_ATTRIBUTE_NAME);
            userAttrMapperConfig.put(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, USER_ATTRIBUTE_NAME);
            userAttrMapperConfig.put(OIDCAttributeMapperHelper.JSON_TYPE, ProviderConfigProperty.STRING_TYPE);
            userAttrMapperConfig.put(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, "true");
            userAttrMapperConfig.put(OIDCAttributeMapperHelper.INCLUDE_IN_ID_TOKEN, "true");
            userAttrMapperConfig.put(OIDCAttributeMapperHelper.INCLUDE_IN_USERINFO, "true");
            userAttrMapperConfig.put(ProtocolMapperUtils.MULTIVALUED, "false");
            userAttrMapperConfig.put(ProtocolMapperUtils.AGGREGATE_ATTRS, "false");
            List<ProtocolMapperRepresentation> mappers = new ArrayList<>(client.getProtocolMappers());
            mappers.add(userAttrMapper);
            client.setProtocolMappers(mappers);

            return clients;
        }    
    }
}
