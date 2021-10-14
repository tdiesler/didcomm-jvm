package org.didcommx.didcomm

import org.didcommx.didcomm.crypto.key.SenderKeySelector
import org.didcommx.didcomm.message.Message
import org.didcommx.didcomm.mock.AliceSecretResolverMock
import org.didcommx.didcomm.mock.BobSecretResolverMock
import org.didcommx.didcomm.mock.CharlieSecretResolverMock
import org.didcommx.didcomm.mock.DIDDocResolverMockWithNoSecrets
import org.didcommx.didcomm.mock.Mediator1SecretResolverMock
import org.didcommx.didcomm.mock.Mediator2SecretResolverMock
import org.didcommx.didcomm.model.PackEncryptedParams
import org.didcommx.didcomm.model.UnpackParams
import org.didcommx.didcomm.operations.wrapInForward
import org.didcommx.didcomm.utils.toJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DIDCommForwardTest {

    val ALICE_DID = "did:example:alice"
    val BOB_DID = "did:example:bob"
    val CHARLIE_DID = "did:example:charlie"
    val MEDIATOR2_DID = "did:example:mediator2"

    @Test
    fun `Test_single_mediator`() {
        val didComm = DIDComm(DIDDocResolverMockWithNoSecrets(), AliceSecretResolverMock())

        val message = Message.builder(
            id = "1234567890",
            body = mapOf("messagespecificattribute" to "and its value"),
            type = "http://example.com/protocols/lets_do_lunch/1.0/proposal"
        )
            .from(ALICE_DID)
            .to(listOf(BOB_DID))
            .createdTime(1516269022)
            .expiresTime(1516385931)
            .build()

        val packResult = didComm.packEncrypted(
            PackEncryptedParams.builder(message, BOB_DID)
                .from(ALICE_DID)
                .build()
        )

        // BOB MEDIATOR
        val forwardBob = didComm.unpackForward(
            UnpackParams.Builder(packResult.packedMessage)
                .secretResolver(Mediator1SecretResolverMock())
                .build()
        )

        val forwardedMsg = toJson(forwardBob.forwardedMsg)

        // BOB
        val unpackResult = didComm.unpack(
            UnpackParams.Builder(forwardedMsg)
                .secretResolver(BobSecretResolverMock())
                .build()
        )

        assertEquals(message, unpackResult.message)
        with(unpackResult.metadata) {
            assertTrue { encrypted }
            assertTrue { authenticated }
            assertFalse { nonRepudiation }
            assertFalse { anonymousSender }
            assertFalse { reWrappedInForward }
        }
    }

    @Test
    fun `Test_multiple_mediators`() {
        val didComm = DIDComm(DIDDocResolverMockWithNoSecrets(), AliceSecretResolverMock())

        val message = Message.builder(
            id = "1234567890",
            body = mapOf("messagespecificattribute" to "and its value"),
            type = "http://example.com/protocols/lets_do_lunch/1.0/proposal"
        )
            .from(ALICE_DID)
            .to(listOf(CHARLIE_DID))
            .createdTime(1516269022)
            .expiresTime(1516385931)
            .build()

        val packResult = didComm.packEncrypted(
            PackEncryptedParams.Builder(message, CHARLIE_DID)
                .from(ALICE_DID)
                .build()
        )

        // TODO make focused on initial subject (without forward)
        // CHARLIE's first mediator (MEDIATOR2)
        var forwardCharlie = didComm.unpackForward(
            UnpackParams.Builder(packResult.packedMessage)
                .secretResolver(Mediator2SecretResolverMock())
                .build()
        )

        var forwardedMsg = toJson(forwardCharlie.forwardedMsg)

        // CHARLIE's second mediator (MEDIATOR1)
        forwardCharlie = didComm.unpackForward(
            UnpackParams.Builder(forwardedMsg)
                .secretResolver(Mediator1SecretResolverMock())
                .build()
        )

        forwardedMsg = toJson(forwardCharlie.forwardedMsg)

        // CHARLIE
        val unpackResult = didComm.unpack(
            UnpackParams.Builder(forwardedMsg)
                .secretResolver(CharlieSecretResolverMock())
                .expectDecryptByAllKeys(true)
                .build()
        )

        val expectedKids = listOf(
            "did:example:charlie#key-x25519-1",
            "did:example:charlie#key-x25519-3"
        )

        assertEquals(message, unpackResult.message)
        with(unpackResult.metadata) {
            assertTrue { encrypted }
            assertTrue { authenticated }
            assertFalse { nonRepudiation }
            assertFalse { anonymousSender }
            assertFalse { reWrappedInForward }
        }
    }

    @Test
    fun `Test_single_mediator_re_wrap_to_unknown`() {
        val didDocResolver = DIDDocResolverMockWithNoSecrets()
        val secretResolver = AliceSecretResolverMock()

        val didComm = DIDComm(didDocResolver, secretResolver)
        val senderKeySelector = SenderKeySelector(didDocResolver, secretResolver)

        val message = Message.builder(
            id = "1234567890",
            body = mapOf("messagespecificattribute" to "and its value"),
            type = "http://example.com/protocols/lets_do_lunch/1.0/proposal"
        )
            .from(ALICE_DID)
            .to(listOf(BOB_DID))
            .createdTime(1516269022)
            .expiresTime(1516385931)
            .build()

        val packResult = didComm.packEncrypted(
            PackEncryptedParams.builder(message, BOB_DID)
                .from(ALICE_DID)
                .build()
        )

        // BOB's MEDIATOR
        var forwardBob = didComm.unpackForward(
            UnpackParams.Builder(packResult.packedMessage)
                .secretResolver(Mediator1SecretResolverMock())
                .build()
        )

        val nextTo = forwardBob.forwardMsg.forwardNext
        assertNotNull(nextTo)

        // re-wrap to unexpected mediator (MEDIATOR2 here)
        val wrapResult = wrapInForward(
            forwardBob.forwardedMsg,
            nextTo,
            senderKeySelector,
            routingKeys = listOf(MEDIATOR2_DID),
            headers = mapOf("somefield" to 99999)
        )

        assertNotNull(wrapResult)

        // MEDIATOR2
        forwardBob = didComm.unpackForward(
            UnpackParams.Builder(wrapResult.msgEncrypted.packedMessage)
                .secretResolver(Mediator2SecretResolverMock())
                .build()
        )

        val forwardedMsg = toJson(forwardBob.forwardedMsg)

        // BOB
        val unpackResult = didComm.unpack(
            UnpackParams.Builder(forwardedMsg)
                .secretResolver(BobSecretResolverMock())
                .build()
        )

        assertEquals(message, unpackResult.message)
        with(unpackResult.metadata) {
            assertTrue { encrypted }
            assertTrue { authenticated }
            assertFalse { nonRepudiation }
            assertFalse { anonymousSender }
            assertFalse { reWrappedInForward }
        }
    }

    @Test
    fun `Test_single_mediator_re_wrap_to_receiver`() {
        val didDocResolver = DIDDocResolverMockWithNoSecrets()
        val secretResolver = AliceSecretResolverMock()

        val didComm = DIDComm(didDocResolver, secretResolver)
        val senderKeySelector = SenderKeySelector(didDocResolver, secretResolver)

        val message = Message.builder(
            id = "1234567890",
            body = mapOf("messagespecificattribute" to "and its value"),
            type = "http://example.com/protocols/lets_do_lunch/1.0/proposal"
        )
            .from(ALICE_DID)
            .to(listOf(BOB_DID))
            .createdTime(1516269022)
            .expiresTime(1516385931)
            .build()

        val packResult = didComm.packEncrypted(
            PackEncryptedParams.builder(message, BOB_DID)
                .from(ALICE_DID)
                .build()
        )

        // BOB's MEDIATOR
        var forwardBob = didComm.unpackForward(
            UnpackParams.Builder(packResult.packedMessage)
                .secretResolver(Mediator1SecretResolverMock())
                .build()
        )

        val nextTo = forwardBob.forwardMsg.forwardNext
        assertNotNull(nextTo)

        // re-wrap to the receiver
        val wrapResult = wrapInForward(
            forwardBob.forwardedMsg,
            nextTo,
            senderKeySelector,
            routingKeys = listOf(nextTo),
            headers = mapOf("somefield" to 99999)
        )

        assertNotNull(wrapResult)

        // BOB
        val unpackResult = didComm.unpack(
            UnpackParams.Builder(wrapResult.msgEncrypted.packedMessage)
                .secretResolver(BobSecretResolverMock())
                .unwrapReWrappingForward(true)
                .build()
        )

        assertEquals(message, unpackResult.message)
        // FIXME here first anon for forward is mixed with innder auth for initial message
        //       in the same metadata
        with(unpackResult.metadata) {
            assertTrue { encrypted }
            assertTrue { authenticated }
            assertFalse { nonRepudiation }
            assertTrue { anonymousSender }
            assertTrue { reWrappedInForward }
        }
    }
}
