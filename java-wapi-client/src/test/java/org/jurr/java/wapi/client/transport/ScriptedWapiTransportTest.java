package org.jurr.java.wapi.client.transport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.protocol.WapiPacket;
import org.junit.jupiter.api.Test;

/** The test transport behaves like a socket, so the client's reader thread can be tested with it. */
class ScriptedWapiTransportTest
{
	private static final WapiPacket PACKET = new WapiPacket(new byte[] { 1, 2, 3 });

	@Test
	void receiveWaitsUntilAResponseIsQueuedByAnotherThread() throws Exception
	{
		final var transport = connected();
		final var received = CompletableFuture.supplyAsync(transport::receive);
		// receive must wait while nothing is queued
		await().during(Duration.ofMillis(50)).atMost(Duration.ofSeconds(1)).until(() -> !received.isDone());

		transport.enqueueResponse(PACKET);

		assertArrayEquals(PACKET.payload(), received.get(1, TimeUnit.SECONDS).payload());
	}

	@Test
	void disconnectEndsAWaitingReceive() throws Exception
	{
		final var transport = connected();
		final var received = CompletableFuture.supplyAsync(transport::receive);
		await().during(Duration.ofMillis(50)).atMost(Duration.ofSeconds(1)).until(() -> !received.isDone());

		transport.disconnect();

		assertEquals(0, received.get(1, TimeUnit.SECONDS).length());
	}

	@Test
	void timedReceiveReturnsNullWhenNothingArrives()
	{
		final var transport = connected();
		final long start = System.nanoTime();

		assertNull(transport.receive(Duration.ofMillis(100)));
		assertTrue(System.nanoTime() - start >= 90_000_000L, "the whole timeout must pass");
	}

	@Test
	void silenceLastsTheWholeTimeoutAndKeepsLaterResponsesQueued()
	{
		final var transport = connected();
		transport.enqueueTimeout();
		transport.enqueueResponse(PACKET);

		assertNull(transport.receive(Duration.ofMillis(50)));
		assertArrayEquals(PACKET.payload(), transport.receive(Duration.ZERO).payload());
	}

	@Test
	void delayedResponseArrivesAfterItsDelay()
	{
		final var transport = connected();
		transport.enqueueResponse(PACKET, 100L);

		assertNull(transport.receive(Duration.ofMillis(20)));
		assertArrayEquals(PACKET.payload(), transport.receive(Duration.ofSeconds(1)).payload());
	}

	@Test
	void endOfStreamIsExplicitAndLasts()
	{
		final var transport = connected();
		transport.enqueueEndOfStream();

		assertEquals(0, transport.receive().length());
		assertEquals(0, transport.receive(Duration.ZERO).length());
	}

	@Test
	void replyArrivesOnlyAfterARequest()
	{
		final var transport = connected();
		final var rest = new WapiPacket(new byte[] { 4 });
		transport.enqueueReply(PACKET);
		transport.enqueueResponse(rest);

		assertNull(transport.receive(Duration.ofMillis(20)), "no request sent yet");
		transport.send(new WapiPacket(new byte[] { 9 }));
		assertArrayEquals(PACKET.payload(), transport.receive(Duration.ZERO).payload());
		// The rest of the reply follows without another request
		assertArrayEquals(rest.payload(), transport.receive(Duration.ZERO).payload());
	}

	@Test
	void eachReplyWaitsForItsOwnRequest()
	{
		final var transport = connected();
		transport.enqueueReply(PACKET);
		transport.enqueueReply(PACKET);

		transport.send(new WapiPacket(new byte[] { 9 }));
		assertArrayEquals(PACKET.payload(), transport.receive(Duration.ZERO).payload());
		assertNull(transport.receive(Duration.ofMillis(20)), "the second reply waits for a second request");
		transport.send(new WapiPacket(new byte[] { 9 }));
		assertArrayEquals(PACKET.payload(), transport.receive(Duration.ZERO).payload());
	}

	private static ScriptedWapiTransport connected()
	{
		final var transport = new ScriptedWapiTransport();
		transport.connect(new WapiSession());
		return transport;
	}
}
