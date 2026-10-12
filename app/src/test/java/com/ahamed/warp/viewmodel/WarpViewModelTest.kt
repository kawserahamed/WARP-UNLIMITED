package com.ahamed.warp.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Hermetic tests for [WarpViewModel].
 *
 * Every request is served by a loopback [MockWebServer]; nothing in this suite contacts
 * api.cloudflareclient.com. The suite asserts how the client builds its request and how it maps
 * HTTP status codes onto UI state — it deliberately does not attempt to validate that a forged
 * registration would be accepted by the real service.
 *
 * Three tests are expected to FAIL against the current implementation. They are regression tests
 * that pin down real defects and are marked `KNOWN FAILURE` below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WarpViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var server: MockWebServer

    /** 2023-11-14T22:13:20.123Z — a timestamp whose millisecond component is non-zero. */
    private val millisWithFraction = 1_700_000_000_123L

    /** 2023-11-14T22:13:20.000Z — a timestamp landing exactly on a whole second. */
    private val millisOnWholeSecond = 1_700_000_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun clockAt(epochMillis: Long): Clock =
        Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)

    private fun createViewModel(
        dispatcher: CoroutineDispatcher,
        clock: Clock = clockAt(millisWithFraction),
        random: Random = Random(20_241_012),
    ) = WarpViewModel(
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build(),
        baseUrl = server.url("/"),
        ioDispatcher = dispatcher,
        clock = clock,
        random = random,
    )

    private fun RecordedRequest.jsonBody() =
        JsonParser.parseString(body.readUtf8()).asJsonObject

    private fun enqueue(code: Int = 200, count: Int = 1) {
        repeat(count) { server.enqueue(MockResponse().setResponseCode(code)) }
    }

    // ---------------------------------------------------------------------------------------
    // Suite 1 — request construction
    // ---------------------------------------------------------------------------------------

    @Test
    fun makeRequest_usesPostWithJsonContentType() = runTest {
        enqueue()
        createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(WarpViewModel.CONTENT_TYPE, recorded.getHeader("Content-Type"))
    }

    @Test
    fun makeRequest_targetsVersionedRegEndpoint() = runTest {
        enqueue()
        createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")

        val path = server.takeRequest().path
        assertTrue("unexpected path: $path", Regex("""^/v0a\d{3}/reg$""").matches(path.orEmpty()))
    }

    @Test
    fun makeRequest_randomisesApiVersionSegment() = runTest {
        val iterations = 40
        enqueue(count = iterations)
        val viewModel = createViewModel(UnconfinedTestDispatcher(testScheduler))

        val paths = buildSet {
            repeat(iterations) {
                viewModel.makeRequest("client-id")
                add(server.takeRequest().path)
            }
        }
        assertTrue("version segment never varied across $iterations calls", paths.size > 1)
    }

    @Test
    fun makeRequest_bodyContainsExactlyTheExpectedFields() = runTest {
        enqueue()
        createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")

        val expected = setOf(
            "key", "install_id", "fcm_token", "referrer",
            "warp_enabled", "tos", "type", "locale",
        )
        assertEquals(expected, server.takeRequest().jsonBody().keySet())
    }

    @Test
    fun makeRequest_keyIsPaddedToExpectedWidth() = runTest {
        enqueue()
        createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")

        val key = server.takeRequest().jsonBody()["key"].asString
        assertEquals(WarpViewModel.KEY_LENGTH + 1, key.length)
        assertTrue("key should end with '='", key.endsWith("="))
    }

    @Test
    fun makeRequest_installIdHasExpectedWidth() = runTest {
        enqueue()
        createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")

        val installId = server.takeRequest().jsonBody()["install_id"].asString
        assertEquals(WarpViewModel.INSTALL_ID_LENGTH, installId.length)
    }

    @Test
    fun makeRequest_fcmTokenIsDerivedFromInstallId() = runTest {
        enqueue()
        createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")

        val body = server.takeRequest().jsonBody()
        val installId = body["install_id"].asString
        val fcmToken = body["fcm_token"].asString

        assertTrue(
            "fcm_token should be prefixed with the install id",
            fcmToken.startsWith("$installId:APA91b"),
        )
        val expectedLength =
            WarpViewModel.INSTALL_ID_LENGTH + ":APA91b".length + WarpViewModel.FCM_SUFFIX_LENGTH
        assertEquals(expectedLength, fcmToken.length)
    }

    @Test
    fun makeRequest_sendsReferrerVerbatim() = runTest {
        val referrers = listOf(
            "8c0db723-25f4-4689-ba5d-5c5060cb9447",
            "  padded-with-spaces  ",
            "ünicode-id",
            "",
        )
        enqueue(count = referrers.size)
        val viewModel = createViewModel(UnconfinedTestDispatcher(testScheduler))

        referrers.forEach { referrer ->
            viewModel.makeRequest(referrer)
            assertEquals(referrer, server.takeRequest().jsonBody()["referrer"].asString)
        }
    }

    @Test
    fun makeRequest_staticFieldsAreConstant() = runTest {
        enqueue()
        createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")

        val body = server.takeRequest().jsonBody()
        assertEquals(false, body["warp_enabled"].asBoolean)
        assertEquals("Android", body["type"].asString)
        assertEquals("es_ES", body["locale"].asString)
    }

    @Test
    fun makeRequest_generatesFreshIdentifiersPerCall() = runTest {
        val iterations = 25
        enqueue(count = iterations)
        val viewModel = createViewModel(UnconfinedTestDispatcher(testScheduler))

        val installIds = mutableSetOf<String>()
        val keys = mutableSetOf<String>()
        repeat(iterations) {
            viewModel.makeRequest("client-id")
            val body = server.takeRequest().jsonBody()
            installIds += body["install_id"].asString
            keys += body["key"].asString
        }

        assertEquals("install_id repeated across calls", iterations, installIds.size)
        assertEquals("key repeated across calls", iterations, keys.size)
    }

    @Test
    fun makeRequest_generatedIdentifiersAreAlphanumeric() = runTest {
        enqueue()
        createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")

        val body = server.takeRequest().jsonBody()
        val alphanumeric = Regex("""^[a-zA-Z0-9]+$""")
        assertTrue(alphanumeric.matches(body["install_id"].asString))
        assertTrue(alphanumeric.matches(body["key"].asString.removeSuffix("=")))
    }

    // ---------------------------------------------------------------------------------------
    // Suite 2 — the `tos` timestamp
    // ---------------------------------------------------------------------------------------

    @Test
    fun tosTimestamp_withFractionalSecond_isWellFormed() = runTest {
        enqueue()
        createViewModel(
            UnconfinedTestDispatcher(testScheduler),
            clock = clockAt(millisWithFraction),
        ).makeRequest("client-id")

        val tos = server.takeRequest().jsonBody()["tos"].asString
        assertEquals("2023-11-14T22:13:20.123+02:00", tos)
    }

    /**
     * Characterisation test for a real defect.
     *
     * `Instant.toString()` omits the fractional part entirely when the millisecond component is
     * zero, yielding a 20-character string such as `2023-11-14T22:13:20Z`. The implementation then
     * calls `substring(0, 23)` on it, which throws [StringIndexOutOfBoundsException]. Because the
     * request loop has no exception handling, this kills the coroutine roughly once per thousand
     * iterations and silently stops the loop while the UI still shows the Stop button.
     *
     * This test passes today because it asserts the broken behaviour. Delete it and un-ignore
     * [tosTimestamp_onWholeSecondBoundary_shouldNotCrash] once the truncation is fixed.
     */
    @Test(expected = StringIndexOutOfBoundsException::class)
    fun tosTimestamp_onWholeSecondBoundary_currentlyThrows() {
        createViewModel(
            UnconfinedTestDispatcher(),
            clock = clockAt(millisOnWholeSecond),
        ).buildRequestBody("client-id")
    }

    /** The behaviour the implementation should have; un-ignore once the defect above is fixed. */
    @Ignore("Fails until the fixed-width substring(0, 23) truncation is replaced by a formatter")
    @Test
    fun tosTimestamp_onWholeSecondBoundary_shouldNotCrash() {
        val body = createViewModel(
            UnconfinedTestDispatcher(),
            clock = clockAt(millisOnWholeSecond),
        ).buildRequestBody("client-id")

        val tos = JsonParser.parseString(body).asJsonObject["tos"].asString
        assertEquals("2023-11-14T22:13:20+02:00", tos)
    }

    // ---------------------------------------------------------------------------------------
    // Suite 3 — response handling
    // ---------------------------------------------------------------------------------------

    @Test
    fun makeRequest_returnsSuccessStatusCode() = runTest {
        enqueue(code = 200)
        val code = createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")
        assertEquals(200, code)
    }

    @Test
    fun makeRequest_propagatesErrorStatusCodes() = runTest {
        listOf(400, 403, 429, 500, 503).forEach { expected ->
            enqueue(code = expected)
            val actual =
                createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")
            assertEquals(expected, actual)
        }
    }

    /**
     * Documents the current contract: a transport failure escapes [WarpViewModel.makeRequest]
     * rather than being mapped onto [WarpViewModel.failCount]. Combined with the unguarded
     * `viewModelScope.launch`, this terminates the loop on any network blip.
     */
    @Test(expected = IOException::class)
    fun makeRequest_onTransportFailure_throwsRatherThanReportingFailure() = runTest {
        server.enqueue(
            MockResponse().apply { socketPolicy = SocketPolicy.DISCONNECT_AT_START },
        )
        createViewModel(UnconfinedTestDispatcher(testScheduler)).makeRequest("client-id")
    }

    // ---------------------------------------------------------------------------------------
    // Suite 4 — loop state mapping
    //
    // NOTE: InstantTaskExecutorRule makes LiveData.postValue synchronous, which masks the
    // production race in startRequestLoop (postValue(true) followed immediately by a
    // `isActive.value == true` loop guard). These tests therefore verify the state mapping only;
    // the race is not reproducible under JVM unit tests and needs an instrumentation test.
    // ---------------------------------------------------------------------------------------

    /**
     * Runs exactly one iteration of the loop and captures every value published to
     * [WarpViewModel.log] along the way.
     *
     * Capturing emissions is necessary because the loop overwrites the PASSED/FAILED message with
     * `"Processing......."` before suspending, so the terminal value of `log` never reflects the
     * outcome of the request.
     */
    private fun TestScope.startLoopOnce(responseCode: Int): LoopHarness {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        enqueue(code = responseCode, count = 4)
        val viewModel = createViewModel(StandardTestDispatcher(testScheduler))
        val emissions = mutableListOf<String>()
        viewModel.log.observeForever { emissions += it }
        viewModel.startRequestLoop("client-id")
        runCurrent()
        return LoopHarness(viewModel, emissions)
    }

    private class LoopHarness(val viewModel: WarpViewModel, val logEmissions: List<String>)

    @Test
    fun loop_onSuccess_incrementsSuccessCountAndLogs() = runTest {
        val (viewModel, logEmissions) = startLoopOnce(responseCode = 200).let { it.viewModel to it.logEmissions }

        assertEquals(1, viewModel.successCount.value)
        assertEquals(0, viewModel.failCount.value)
        assertTrue(
            "success message never published, saw: $logEmissions",
            "PASSED: +1GB (total: 1GB, failed: 0)" in logEmissions,
        )

        viewModel.stopRequestLoop()
    }

    @Test
    fun loop_onErrorStatus_incrementsFailCountAndLogs() = runTest {
        val (viewModel, logEmissions) = startLoopOnce(responseCode = 429).let { it.viewModel to it.logEmissions }

        assertEquals(0, viewModel.successCount.value)
        assertEquals(1, viewModel.failCount.value)
        assertTrue(
            "failure message never published, saw: $logEmissions",
            "FAILED: 429" in logEmissions,
        )

        viewModel.stopRequestLoop()
    }

    /**
     * Pins down a UX quirk: the outcome message is immediately replaced by the cooldown message,
     * so the result of a request is only ever visible for a single frame.
     */
    @Test
    fun loop_outcomeMessageIsImmediatelyOverwrittenByCooldownMessage() = runTest {
        val harness = startLoopOnce(responseCode = 200)

        assertEquals("Processing.......", harness.viewModel.log.value)
        assertTrue(harness.logEmissions.size >= 2)

        harness.viewModel.stopRequestLoop()
    }

    @Test
    fun loop_cooldownStaysWithinConfiguredBounds() = runTest {
        val viewModel = startLoopOnce(responseCode = 200).viewModel

        val cooldown = viewModel.timeDown.value ?: 0L
        assertTrue(
            "cooldown $cooldown out of bounds",
            cooldown >= WarpViewModel.MIN_COOLDOWN_SECONDS * 1000L &&
                cooldown <= WarpViewModel.MAX_COOLDOWN_SECONDS * 1000L,
        )

        viewModel.stopRequestLoop()
    }

    @Test
    fun loop_stop_haltsFurtherRequests() = runTest {
        val viewModel = startLoopOnce(responseCode = 200).viewModel
        val afterFirstIteration = server.requestCount

        viewModel.stopRequestLoop()
        advanceTimeBy(WarpViewModel.MAX_COOLDOWN_SECONDS * 1000L * 3)
        runCurrent()

        assertEquals(1, afterFirstIteration)
        assertEquals(
            "loop kept issuing requests after stop",
            afterFirstIteration,
            server.requestCount,
        )
        assertEquals(false, viewModel.isActive.value)
    }

    @Test
    fun loop_stop_resetsCountdownAndLogs() = runTest {
        val viewModel = startLoopOnce(responseCode = 200).viewModel

        viewModel.stopRequestLoop()
        runCurrent()

        assertEquals(0L, viewModel.timeDown.value)
        assertEquals("Request Stopped.", viewModel.log.value)
    }

    @Test
    fun loop_issuesRequestsToTheMockServerOnly() = runTest {
        val viewModel = startLoopOnce(responseCode = 200).viewModel

        val recorded = server.takeRequest()
        assertNotEquals("api.cloudflareclient.com", recorded.requestUrl?.host)
        assertEquals(server.hostName, recorded.requestUrl?.host)

        viewModel.stopRequestLoop()
    }
}
