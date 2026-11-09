package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** M8-03: access rules (vhost, user, permission, topic permission) and the guest and algorithm rules (P11, P12). */
class AccessRulesTest {
    private fun users(vararg u: UserDefinition) = analyze(D.document(users = u.toList()))
    private fun declared(r: CompatibilityReport, user: String) = r.withRule("access-declare-user", user)

    @Test
    fun `access-declare-vhost fires for each virtual host entry`() {
        val r = analyze(D.document(virtualHosts = listOf(D.virtualHost("/"), D.virtualHost("orders"))))
        val found = r.withRule("access-declare-vhost").sortedBy { it.name }
        assertEquals(listOf("/", "orders"), found.map { it.name })
        found.forEach {
            it.assertShape(Severity.Info, FindingClass.Access, verified = true)
            assertEquals(listOf(ItemKind.VirtualHost, null), listOf(it.kind, it.vhost))
            assertEquals(Proposal.DeclareVirtualHost(it.name!!), it.proposal)
            assertContainsText(it.text(), "access migration", "access-declare-vhost")
        }
    }

    @Test
    fun `access-declare-vhost does not fire without virtual host entries`() {
        analyze(D.document(queues = listOf(D.quorumQueue("q")))).assertNone("access-declare-vhost")
    }

    @Test
    fun `access-declare-user fires for a user with an allowed algorithm and carries name, tags, algorithm and hash flag`() {
        val f = users(D.user("svc", tags = listOf("management", "policymaker"))).only("access-declare-user", "svc")
        f.assertShape(Severity.Info, FindingClass.Access, verified = true)
        assertEquals(listOf(ItemKind.User, null), listOf(f.kind, f.vhost))
        assertEquals(Proposal.DeclareUser("svc", listOf("management", "policymaker"), D.SHA256, true, null), f.proposal)
        assertContainsText(f.text(), "carried over, never shown", "access-declare-user")
    }

    @ParameterizedTest
    @ValueSource(strings = ["rabbit_password_hashing_sha256", "rabbit_password_hashing_sha512", "rabbit_password_hashing_md5"])
    fun `the three allowed algorithms are declared and not blocked`(algorithm: String) {
        val r = users(D.user("u", algorithm = algorithm))
        r.only("access-declare-user", "u")
        r.assertNone("user-hash-algorithm-unsupported")
        r.assertNone("user-hash-without-algorithm")
    }

    @Test
    fun `access-set-permissions and access-set-topic-permissions fire for each entry`() {
        val r = analyze(D.document(permissions = listOf(D.permission("/", "svc"), D.permission("v1", "svc")), topicPermissions = listOf(D.topicPermission("/", "svc", "ex1"))))
        val perms = r.withRule("access-set-permissions")
        assertEquals(setOf(Proposal.SetPermissions("/", "svc"), Proposal.SetPermissions("v1", "svc")), perms.map { it.proposal }.toSet())
        perms.forEach {
            it.assertShape(Severity.Info, FindingClass.Access, verified = true)
            assertEquals(ItemKind.Permission, it.kind)
        }
        val t = r.only("access-set-topic-permissions")
        t.assertShape(Severity.Info, FindingClass.Access, verified = true)
        assertEquals(ItemKind.TopicPermission, t.kind)
        assertEquals(Proposal.SetTopicPermissions("/", "svc", "ex1"), t.proposal)
    }

    @Test
    fun `no permission rule fires without permissions`() {
        val r = users(D.user("svc"))
        r.assertNone("access-set-permissions")
        r.assertNone("access-set-topic-permissions")
    }

    // ---- user-guest ----

    @Test
    fun `user-guest replaces access-declare-user, is a warning, skipped by default, and needs the known-password tick`() {
        val r = users(D.user("guest", tags = listOf("administrator")))
        val f = r.only("user-guest", "guest")
        f.assertShape(Severity.Warning, FindingClass.Access, verified = true, defaultDecision = DefaultDecision.Skip)
        assertEquals(Proposal.DeclareUser("guest", listOf("administrator"), D.SHA256, true, Acknowledgement.PubliclyKnownPassword), f.proposal)
        assertContainsText(f.text(), "has a publicly known password", "user-guest")
        assertContainsText(f.text(), "skipped by default", "user-guest")
        r.assertNone("access-declare-user", "guest")
    }

    @Test
    fun `user-guest does not fire for other names, not even guest2 or Guest`() {
        val r = users(D.user("guest2"), D.user("Guest"), D.user("svc"))
        r.assertNone("user-guest")
        assertEquals(3, r.withRule("access-declare-user").size)
    }

    @Test
    fun `guest with an unsupported algorithm gets both the guest warning and the blocker, and no declare`() {
        val r = users(D.user("guest", algorithm = "rabbit_password_hashing_bcrypt"))
        r.only("user-guest", "guest")
        r.only("user-hash-algorithm-unsupported", "guest")
        r.assertNone("access-declare-user", "guest")
    }

    // ---- user-administrator-tag ----

    @Test
    fun `user-administrator-tag fires for the administrator tag only`() {
        val r = users(D.user("adm", tags = listOf("management", "administrator")), D.user("mon", tags = listOf("monitoring", "administrator-ish")))
        val f = r.only("user-administrator-tag", "adm")
        f.assertShape(Severity.Warning, FindingClass.Access, verified = true)
        assertEquals(Proposal.None, f.proposal)
        assertContainsText(f.text(), "administrator tag", "user-administrator-tag")
        assertContainsText(f.text(), "on 4.x", "user-administrator-tag")
        r.assertNone("user-administrator-tag", "mon")
        r.only("access-declare-user", "adm") // the tag does not replace the declare
    }

    // ---- user-hash-without-algorithm ----

    @Test
    fun `user-hash-without-algorithm is a blocker with a manual step and no declare`() {
        val r = users(D.user("u", algorithm = null))
        val f = r.only("user-hash-without-algorithm", "u")
        f.assertShape(Severity.Blocker, FindingClass.Access, verified = true)
        assertTrue(f.proposal is Proposal.Manual, "proposal is ${f.proposal}")
        assertTrue(f.allProposals().none { it is Proposal.DeclareUser }, "no DeclareUser may be offered")
        assertContainsText(f.text(), "without the hashing algorithm the login would fail", "user-hash-without-algorithm")
        assertContainsText(f.manualTexts().joinToString(" "), "new password", "manual step")
        r.assertNone("access-declare-user", "u")
        r.assertNone("user-hash-algorithm-unsupported")
    }

    @Test
    fun `no hash and no algorithm is not the hash-without-algorithm blocker`() {
        val r = users(D.user("ext", hash = null, algorithm = null))
        r.assertNone("user-hash-without-algorithm")
        r.assertNone("user-hash-algorithm-unsupported")
    }

    // ---- user-hash-algorithm-unsupported ----

    @ParameterizedTest
    @ValueSource(strings = ["rabbit_password_hashing_bcrypt", "a_name_nobody_knows", "RABBIT_PASSWORD_HASHING_SHA256", ""])
    fun `user-hash-algorithm-unsupported is a blocker with the algorithm name in about and a manual step`(algorithm: String) {
        val r = users(D.user("u", algorithm = algorithm))
        val f = r.only("user-hash-algorithm-unsupported", "u")
        f.assertShape(Severity.Blocker, FindingClass.Access, verified = true)
        assertEquals(listOf(algorithm), f.about)
        assertTrue(f.proposal is Proposal.Manual, "proposal is ${f.proposal}")
        assertTrue(f.allProposals().none { it is Proposal.DeclareUser }, "no DeclareUser may be offered")
        assertContainsText(f.text(), "RabbitMQ 4 rejects this algorithm", "user-hash-algorithm-unsupported")
        assertContainsText(f.manualTexts().joinToString(" "), "new password on the broker", "manual step")
        assertContainsText(f.manualTexts().joinToString(" "), "outside the app", "manual step")
        r.assertNone("access-declare-user", "u")
    }

    @Test
    fun `user-hash-algorithm-unsupported needs a hash`() {
        users(D.user("u", hash = null, algorithm = "rabbit_password_hashing_bcrypt")).assertNone("user-hash-algorithm-unsupported")
    }

    // ---- user-no-password-hash ----

    @Test
    fun `user-no-password-hash is an info and the user is still declared without a hash`() {
        val r = users(D.user("ext", hash = null, algorithm = null, tags = listOf("management")))
        val f = r.only("user-no-password-hash", "ext")
        f.assertShape(Severity.Info, FindingClass.Access, verified = true)
        assertContainsText(f.text(), "does not invent a password", "user-no-password-hash")
        assertEquals(Proposal.DeclareUser("ext", listOf("management"), null, false, null), r.only("access-declare-user", "ext").proposal)
    }

    @Test
    fun `user-no-password-hash does not fire for a user with a hash`() {
        users(D.user("u")).assertNone("user-no-password-hash")
    }

    // ---- access-not-in-file and the single virtual host document ----

    @Test
    fun `access-not-in-file fires for a single virtual host document and names the virtual host`() {
        val doc = D.document(queues = listOf(D.quorumQueue("q", vhost = "orders")), origin = DefinitionsOrigin.SingleVirtualHost("orders"))
        val r = analyze(doc)
        val f = r.only("access-not-in-file")
        f.assertShape(Severity.Info, FindingClass.Access, verified = true)
        assertEquals(ItemKind.Global, f.kind)
        assertTrue(f.proposal is Proposal.Manual, "proposal is ${f.proposal}")
        assertContainsText(f.text(), "holds no virtual hosts, users, permissions or topic permissions", "access-not-in-file")
        assertContainsText(f.text(), "orders", "access-not-in-file")
        assertContainsText(f.text(), "must already exist on the 4.x broker", "access-not-in-file")
        assertEquals("orders", r.source.singleVirtualHost)
    }

    @Test
    fun `access-not-in-file does not fire for a full export`() {
        analyze(D.document(queues = listOf(D.quorumQueue("q")))).assertNone("access-not-in-file")
        assertEquals(null, analyze(D.document()).source.singleVirtualHost)
    }

    @Test
    fun `a parsed single virtual host export gives access-not-in-file, no user or virtual host finding, and queues in the assigned vhost`() {
        val doc = assigned(needsVhost(dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures.read(dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures.VHOST_V1)), "orders")
        val r = analyze(doc)
        r.only("access-not-in-file")
        r.assertAnalyzed()
        assertTrue(r.findings.none { it.kind in setOf(ItemKind.User, ItemKind.VirtualHost, ItemKind.Permission, ItemKind.TopicPermission) }, "got ${r.findings.map { it.rule }}")
        val queueFindings = r.findings.filter { it.kind == ItemKind.Queue }
        assertTrue(queueFindings.isNotEmpty() && queueFindings.all { it.vhost == "orders" }, "queue findings must name the assigned virtual host but are ${queueFindings.map { it.vhost }}")
        assertEquals("orders", r.source.singleVirtualHost)
    }
}
