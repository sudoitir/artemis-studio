package io.github.sudoitir.artemisstudio.kernel.security;

import static io.github.sudoitir.artemisstudio.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.kernel.security.internal.RoleService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.TeamService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.UserService;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.artemisstudio.platform.broker.QueueRow;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.github.sudoitir.artemisstudio.support.TeamAccessFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queue and address operations as the people of two teams on one cluster (team-access spec): what each sees,
 * what a refused resource looks like, composite operations, and where names may be created. Brokers are not
 * reached: every call that passes its checks is a dry run or a read the snapshot answers.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ResourceEnforcementIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    TeamService teamService;

    @Autowired
    RoleService roleService;

    @Autowired
    UserService userService;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository brokerNodes;

    @Autowired
    QueueSnapshotUpsert snapshots;

    final JsonMapper json = new JsonMapper();

    MockMvc mvc;
    TeamAccessFixture fixture;
    UUID cluster;
    UUID orders;
    UUID billing;
    UsernamePasswordAuthenticationToken ordersViewer;
    UsernamePasswordAuthenticationToken ordersOperator;
    UsernamePasswordAuthenticationToken platformOperator;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        fixture = new TeamAccessFixture(teamService, roles, users);

        cluster = clusters.save(new ClusterEntity("team-" + UUID.randomUUID(), null, null))
                .getId();
        // A node with a management URL that is not live: a dry run reports it skipped, with no broker to reach.
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                cluster, "n1", "PRIMARY", UUID.randomUUID().toString());
        node.attachManagementUrl("http://127.0.0.1:1/console/jolokia");
        UUID nodeId = brokerNodes.save(node).getId();
        snapshots.upsertBatch(List.of(
                row(nodeId, "orders.in", 10),
                row(nodeId, "orders.out", 5),
                row(nodeId, "billing.in", 7),
                row(nodeId, "billing.payments", 3),
                row(nodeId, "misc.audit", 1)));

        orders = fixture.team("orders", cluster, "orders.#");
        billing = fixture.team("billing", cluster, "billing.#");
        ordersViewer = fixture.session(fixture.member(orders, "TEAM_VIEWER"));
        ordersOperator = fixture.session(fixture.member(orders, "TEAM_OPERATOR"));
        platformOperator = platformOperator();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(cluster);
    }

    private QueueRow row(UUID node, String name, long messages) {
        return new QueueRow(cluster, node, name, name, "ANYCAST", true, messages, 0, 0, 0, 0, 0, 0, false);
    }

    /** A user whose only access is the built-in Operator role, granted on every cluster. */
    private UsernamePasswordAuthenticationToken platformOperator() {
        String name = "operator-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(name, null, "{noop}x");
        user.setMustChangePassword(false);
        UUID userId = users.save(user).getId();
        userService.addGrant(
                userId,
                new GrantRequest(roles.findByName("OPERATOR").orElseThrow().getId(), "GLOBAL", null));
        return fixture.session(userId);
    }

    private String base() {
        return "/api/v1/clusters/" + cluster;
    }

    private MockHttpServletResponse call(
            MockHttpServletRequestBuilder request, UsernamePasswordAuthenticationToken session) throws Exception {
        return mvc.perform(request.with(authentication(session)).with(csrf()))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse postJson(String path, String body, UsernamePasswordAuthenticationToken session)
            throws Exception {
        return call(post(path).contentType(MediaType.APPLICATION_JSON).content(body), session);
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    private List<String> names(MockHttpServletResponse response) throws Exception {
        return body(response)
                .get("data")
                .valueStream()
                .map(r -> r.get("queueName").asString())
                .toList();
    }

    // ---- lists and counts ---------------------------------------------------------------------

    @Test
    void aTeamOnlyUserListsOnlyTheirQueuesAndTheTotalsCountOnlyThose() throws Exception {
        MockHttpServletResponse response = call(get(base() + "/queues"), ordersViewer);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(names(response)).containsExactlyInAnyOrder("orders.in", "orders.out");
        assertThat(body(response).get("count").asLong()).isEqualTo(2);
    }

    @Test
    void aSearchNeverCountsWhatTheCallerCannotRead() throws Exception {
        MockHttpServletResponse response = call(get(base() + "/queues?q=billing"), ordersViewer);

        assertThat(names(response)).isEmpty();
        assertThat(body(response).get("count").asLong()).isZero();
    }

    @Test
    void rowsCarryTheActionsTheCallerHoldsOnThem() throws Exception {
        JsonNode viewerRow = body(call(get(base() + "/queues?q=orders.in"), ordersViewer))
                .get("data")
                .get(0);
        JsonNode operatorRow = body(call(get(base() + "/queues?q=orders.in"), ordersOperator))
                .get("data")
                .get(0);

        assertThat(viewerRow.get("allowedActions").valueStream().map(JsonNode::asString))
                .contains("queue:read", "message:read")
                .doesNotContain("queue:purge");
        assertThat(operatorRow.get("allowedActions").valueStream().map(JsonNode::asString))
                .contains("queue:purge", "queue:update", "queue:delete");
    }

    @Test
    void aClusterWidePlatformGrantSeesEveryQueueAndEveryAction() throws Exception {
        MockHttpServletResponse response = call(get(base() + "/queues"), platformOperator);

        assertThat(names(response))
                .containsExactlyInAnyOrder("orders.in", "orders.out", "billing.in", "billing.payments", "misc.audit");
        assertThat(body(response)
                        .get("data")
                        .get(0)
                        .get("allowedActions")
                        .valueStream()
                        .map(JsonNode::asString))
                .contains("queue:purge", "queue:delete");
    }

    @Test
    void aUserWithNothingOnTheClusterDoesNotFindItEvenToList() throws Exception {
        UsernamePasswordAuthenticationToken stranger = fixture.session(fixture.member(
                fixture.team(
                        "elsewhere",
                        clusters.save(new ClusterEntity("other-" + UUID.randomUUID(), null, null))
                                .getId(),
                        "x.#"),
                "TEAM_VIEWER"));

        assertThat(call(get(base() + "/queues"), stranger).getStatus()).isEqualTo(404);
        assertThat(call(get(base() + "/addresses"), stranger).getStatus()).isEqualTo(404);
    }

    // ---- a refused resource looks like a missing one ------------------------------------------

    @Test
    void aQueueTheCallerCannotReadIsNotFoundExactlyLikeOneThatDoesNotExist() throws Exception {
        MockHttpServletResponse foreign = call(get(base() + "/queues/billing.payments/configuration"), ordersViewer);
        MockHttpServletResponse missing = call(get(base() + "/queues/nowhere.at.all/configuration"), ordersViewer);

        assertThat(foreign.getStatus()).isEqualTo(404);
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(body(foreign).get("type")).isEqualTo(body(missing).get("type"));
        assertThat(body(foreign).get("detail").asString())
                .isEqualTo(body(missing).get("detail").asString().replace("nowhere.at.all", "billing.payments"));
    }

    @Test
    void aQueueTheCallerMayReadHasItsConfigurationRead() throws Exception {
        assertThat(call(get(base() + "/queues/orders.in/configuration"), ordersViewer)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aTeamViewerPurgingAReadableQueueIsToldWhichPermissionIsMissing() throws Exception {
        MockHttpServletResponse response =
                call(delete(base() + "/queues/orders.in/messages?dryRun=true"), ordersViewer);

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode problem = body(response);
        assertThat(problem.get("type").asString()).endsWith("/resource-forbidden");
        assertThat(problem.get("detail").asString()).contains("queue:purge", "orders.in");
        assertThat(problem.get("permission").asString()).isEqualTo("queue:purge");
    }

    @Test
    void aTeamViewerBrowsingAForeignQueueDoesNotLearnItExists() throws Exception {
        assertThat(call(get(base() + "/queues/billing.in/messages"), ordersViewer)
                        .getStatus())
                .isEqualTo(404);
    }

    // ---- operations touching several resources ----------------------------------------------

    private static final String MOVE_TO_BILLING = "{\"messageIds\":[1],\"targetQueue\":\"billing.in\"}";

    @Test
    void movingToAnotherTeamsQueueIsRefusedBeforeAnythingIsMoved() throws Exception {
        MockHttpServletResponse response = postJson(
                base() + "/queues/orders.in/messages/actions/move?dryRun=true", MOVE_TO_BILLING, ordersOperator);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).doesNotContain("billing.in");
    }

    @Test
    void movingToAQueueTheOtherTeamSharedIsAllowed() throws Exception {
        fixture.share(billing, orders, cluster, "billing.in", "TEAM_OPERATOR");

        MockHttpServletResponse response = postJson(
                base() + "/queues/orders.in/messages/actions/move?dryRun=true", MOVE_TO_BILLING, ordersOperator);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aReadOnlyShareDoesNotAllowSendingToIt() throws Exception {
        fixture.share(billing, orders, cluster, "billing.in", "TEAM_VIEWER");

        MockHttpServletResponse response = postJson(
                base() + "/queues/orders.in/messages/actions/move?dryRun=true", MOVE_TO_BILLING, ordersOperator);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("permission").asString()).isEqualTo("message:send");
    }

    /** A purge plan with the given selection, written the way the console sends one. */
    private static String bulk(String selection) {
        return "{\"operation\":\"PURGE\"," + selection + ",\"disconnectConsumers\":false}";
    }

    @Test
    void aBulkPlanWithAForeignQueueIsRefusedAndNothingIsPlanned() throws Exception {
        MockHttpServletResponse response =
                postJson(base() + "/bulk/preview", bulk("\"names\":[\"orders.in\",\"billing.in\"]"), ordersOperator);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(call(get(base() + "/bulk/runs"), ordersOperator).getContentAsString())
                .doesNotContain("PURGE");
    }

    @Test
    void aBulkPlanOfTheCallersOwnQueuesIsPlanned() throws Exception {
        MockHttpServletResponse response =
                postJson(base() + "/bulk/preview", bulk("\"names\":[\"orders.in\",\"orders.out\"]"), ordersOperator);

        assertThat(response.getStatus())
                .describedAs(response.getContentAsString())
                .isEqualTo(201);
    }

    @Test
    void aBulkPlanBySearchNeverReachesQueuesTheCallerCannotRead() throws Exception {
        MockHttpServletResponse response = postJson(base() + "/bulk/preview", bulk("\"q\":\"in\""), ordersOperator);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).contains("orders.in").doesNotContain("billing.in");
    }

    // ---- creating names ------------------------------------------------------------------------

    private static String createQueue(String name) {
        return "{\"address\":\"" + name + "\",\"name\":\"" + name + "\",\"routingType\":\"ANYCAST\"}";
    }

    @Test
    void aTeamOperatorMayCreateAQueueInsideTheTeamsPattern() throws Exception {
        assertThat(postJson(base() + "/queues?dryRun=true", createQueue("orders.retry"), ordersOperator)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void creatingOutsideTheTeamsPatternIsRefusedNamingWhereTheUserMayCreate() throws Exception {
        MockHttpServletResponse response =
                postJson(base() + "/queues?dryRun=true", createQueue("misc.temp"), ordersOperator);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("detail").asString()).contains("queue:create", "misc.temp", "orders.#");
    }

    @Test
    void aTeamViewerMayNotCreateInsideTheirOwnPatternEither() throws Exception {
        assertThat(postJson(base() + "/queues?dryRun=true", createQueue("orders.retry"), ordersViewer)
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void aQueueCannotBeBoundToAnAddressOfAnotherTeam() throws Exception {
        MockHttpServletResponse response = postJson(
                base() + "/queues?dryRun=true",
                "{\"address\":\"billing.in\",\"name\":\"orders.spy\",\"routingType\":\"ANYCAST\"}",
                ordersOperator);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("permission").asString()).isEqualTo("address:create");
    }

    @Test
    void aPlatformOperatorMayCreateAnywhere() throws Exception {
        assertThat(postJson(base() + "/queues?dryRun=true", createQueue("misc.temp"), platformOperator)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void anotherTeamsQueueCannotBeChangedOrDeleted() throws Exception {
        assertThat(call(delete(base() + "/queues/billing.in?dryRun=true"), ordersOperator)
                        .getStatus())
                .isEqualTo(404);
        assertThat(postJson(base() + "/queues/billing.in/pause?dryRun=true", "", ordersOperator)
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void anAddressIsDestroyedOnlyWithAddressDelete() throws Exception {
        assertThat(call(delete(base() + "/addresses/orders.in?dryRun=true"), ordersViewer)
                        .getStatus())
                .isEqualTo(403);
        assertThat(call(delete(base() + "/addresses/orders.in?dryRun=true"), ordersOperator)
                        .getStatus())
                .isEqualTo(200);
        assertThat(call(delete(base() + "/addresses/billing.in?dryRun=true"), ordersOperator)
                        .getStatus())
                .isEqualTo(404);
    }
}
