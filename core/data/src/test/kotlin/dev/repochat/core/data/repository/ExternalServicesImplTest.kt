package dev.repochat.core.data.repository

import dev.repochat.core.data.remote.CloudflareApi
import dev.repochat.core.data.remote.CloudflareWorkerDto
import dev.repochat.core.data.remote.CloudflareWorkersDto
import dev.repochat.core.data.remote.CloudflareZoneDto
import dev.repochat.core.data.remote.CloudflareZonesDto
import dev.repochat.core.data.remote.FirebaseApi
import dev.repochat.core.data.remote.FirebaseProjectDto
import dev.repochat.core.data.remote.FirebaseTokenDto
import dev.repochat.core.data.remote.VercelApi
import dev.repochat.core.data.remote.VercelCreateDeploymentDto
import dev.repochat.core.data.remote.VercelDeploymentDto
import dev.repochat.core.data.remote.VercelDeploymentsDto
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.ServiceConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalServicesImplTest {

    private class FakeCloudflare(
        private val zonesResult: List<CloudflareZoneDto> = emptyList(),
        private val workersResult: List<CloudflareWorkerDto> = emptyList(),
    ) : CloudflareApi {
        var zonesUrl: String? = null
        var zonesBearer: String? = null
        var workersUrl: String? = null
        var workersBearer: String? = null

        override suspend fun zones(url: String, bearer: String): CloudflareZonesDto {
            zonesUrl = url
            zonesBearer = bearer
            return CloudflareZonesDto(success = true, result = zonesResult)
        }

        override suspend fun workers(url: String, bearer: String): CloudflareWorkersDto {
            workersUrl = url
            workersBearer = bearer
            return CloudflareWorkersDto(success = true, result = workersResult)
        }
    }

    private class FakeVercel(
        var deployments: List<VercelDeploymentDto> = emptyList(),
    ) : VercelApi {
        var deploymentsUrl: String? = null
        var createUrl: String? = null
        var createBearer: String? = null
        var createBody: VercelCreateDeploymentDto? = null

        override suspend fun deployments(
            url: String,
            bearer: String,
        ): VercelDeploymentsDto {
            deploymentsUrl = url
            return VercelDeploymentsDto(deployments = deployments)
        }

        override suspend fun createDeployment(
            url: String,
            bearer: String,
            body: VercelCreateDeploymentDto,
        ): VercelDeploymentDto {
            createUrl = url
            createBearer = bearer
            createBody = body
            return VercelDeploymentDto(id = "dep_1", name = body.project.orEmpty(), state = "READY")
        }
    }

    private class FakeFirebase(
        private val token: FirebaseTokenDto = FirebaseTokenDto(accessToken = "ignored"),
        private val project: FirebaseProjectDto = FirebaseProjectDto(),
    ) : FirebaseApi {
        var projectUrl: String? = null
        var projectHeaders: Map<String, String> = emptyMap()
        var oauthUrl: String? = null
        var oauthGrant: String? = null
        var oauthAssertion: String? = null

        override suspend fun project(
            url: String,
            headers: Map<String, String>,
        ): FirebaseProjectDto {
            projectUrl = url
            projectHeaders = headers
            return project
        }

        override suspend fun oauthToken(
            url: String,
            grantType: String,
            assertion: String,
        ): FirebaseTokenDto {
            oauthUrl = url
            oauthGrant = grantType
            oauthAssertion = assertion
            return token
        }
    }

    private fun connection(
        type: ConnectionType,
        apiKey: String = "",
        accountId: String = "",
        projectId: String = "",
        teamId: String = "",
    ) = ServiceConnection(
        id = "c1",
        type = type,
        label = type.name,
        baseUrl = "",
        apiKey = apiKey,
        accountId = accountId,
        projectId = projectId,
        teamId = teamId,
    )

    @Test
    fun `cloudflare summary builds account scoped urls and formats counts`() = runBlocking {
        val cf = FakeCloudflare(
            zonesResult = listOf(CloudflareZoneDto(id = "z1", name = "example.com")),
            workersResult = listOf(CloudflareWorkerDto(id = "w1", name = "worker-a")),
        )
        val svc = ExternalServicesImpl(cf, FakeVercel(), FakeFirebase())
        val detail = svc.summarize(
            connection(ConnectionType.CLOUDFLARE, apiKey = "cf-token", accountId = "acct-1"),
        )

        assertEquals(
            "https://api.cloudflare.com/client/v4/zones?account.id=acct-1",
            cf.zonesUrl,
        )
        assertEquals("Bearer cf-token", cf.zonesBearer)
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acct-1/workers/scripts",
            cf.workersUrl,
        )
        assertEquals("Bearer cf-token", cf.workersBearer)
        assertTrue(detail.contains("1 zone(s)"))
        assertTrue(detail.contains("example.com"))
        assertTrue(detail.contains("1 Worker script(s)"))
        assertTrue(detail.contains("worker-a"))
    }

    @Test
    fun `vercel summary includes project and team query params`() = runBlocking {
        val v = FakeVercel(
            deployments = listOf(
                VercelDeploymentDto(
                    id = "dep1",
                    name = "app",
                    state = "READY",
                    url = "https://app.vercel.app",
                ),
            ),
        )
        val svc = ExternalServicesImpl(FakeCloudflare(), v, FakeFirebase())
        val detail = svc.summarize(
            connection(ConnectionType.VERCEL, apiKey = "v-token", projectId = "prj-1", teamId = "team-9"),
        )

        assertEquals(
            "https://api.vercel.com/v6/deployments?projectId=prj-1&teamId=team-9",
            v.deploymentsUrl,
        )
        assertTrue(detail.contains("latest deployment: app"))
        assertTrue(detail.contains("READY"))
        assertTrue(detail.contains("https://app.vercel.app"))
    }

    @Test
    fun `vercel trigger posts production body with team query`() = runBlocking {
        val v = FakeVercel()
        val svc = ExternalServicesImpl(FakeCloudflare(), v, FakeFirebase())
        val detail = svc.triggerDeployment(
            connection(ConnectionType.VERCEL, apiKey = "v-token", projectId = "prj-1", teamId = "team-9"),
            project = "prj-1",
        )

        assertEquals("https://api.vercel.com/v13/deployments?teamId=team-9", v.createUrl)
        assertEquals("Bearer v-token", v.createBearer)
        assertEquals("production", v.createBody?.target)
        assertEquals("prj-1", v.createBody?.project)
        assertTrue(detail.contains("Vercel deployment triggered"))
        assertTrue(detail.contains("prj-1"))
    }

    @Test
    fun `vercel trigger omits team query when no team configured`() = runBlocking {
        val v = FakeVercel()
        val svc = ExternalServicesImpl(FakeCloudflare(), v, FakeFirebase())
        svc.triggerDeployment(
            connection(ConnectionType.VERCEL, apiKey = "v-token", projectId = "prj-2"),
            project = "prj-2",
        )
        assertEquals("https://api.vercel.com/v13/deployments", v.createUrl)
        assertFalse(v.createUrl.orEmpty().contains("teamId"))
    }

    @Test
    fun `firebase web api key hits project url with key and no auth header`() = runBlocking {
        val f = FakeFirebase(
            project = FirebaseProjectDto(
                projectId = "smart-app",
                displayName = "Smart App",
                projectNumber = "1000001",
            ),
        )
        val svc = ExternalServicesImpl(FakeCloudflare(), FakeVercel(), f)
        val detail = svc.summarize(
            connection(ConnectionType.FIREBASE, apiKey = "web-key", projectId = "smart-app"),
        )

        assertEquals(
            "https://firebase.googleapis.com/v1beta1/projects/smart-app?key=web-key",
            f.projectUrl,
        )
        assertTrue(f.projectHeaders.isEmpty())
        assertTrue(detail.contains("Firebase OK — project smart-app"))
        assertTrue(detail.contains("Smart App"))
        assertTrue(detail.contains("1000001"))
    }

    @Test
    fun `non service connections are rejected`() = runBlocking {
        val svc = ExternalServicesImpl(FakeCloudflare(), FakeVercel(), FakeFirebase())
        try {
            svc.test(connection(ConnectionType.GITHUB, apiKey = "pat"))
            throw AssertionError("expected configuration error")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("not a Cloudflare/Vercel/Firebase"))
        }
    }
}
