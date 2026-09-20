package org.jurr.java.wapi.client;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

/** The connection listener hears of a connection that ended without the application closing it. */
class WapiConnectionListenerTest
{
	private static final WapiToken MUTE = new WapiToken("ch.1.mute");

	@Test
	void theConsoleClosingTheConnectionCallsTheListener() throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var lost = new LostRecorder();
			client.addConnectionListener(lost);

			transport.enqueueEndOfStream();

			assertTrue(lost.await());
			assertEquals(WapiError.WRECV_ERROR, lost.causes.get(0).getError());
			assertFalse(client.isConnected());
			client.awaitListeners();
			assertEquals(1, lost.causes.size());
		}
	}

	@Test
	void aProtocolErrorCallsTheListenerWithTheError() throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var lost = new LostRecorder();
			client.addConnectionListener(lost);

			// e0: unused in the binary stream protocol (PDF Table 2)
			transport.enqueueResponse(frame("e0"));

			assertTrue(lost.await());
			assertEquals(WapiError.WTOKEN, lost.causes.get(0).getError());
		}
	}

	@Test
	void aFailedReadCallsTheListenerAndDisconnects() throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var lost = new LostRecorder();
			client.addConnectionListener(lost);

			// As TCP keep-alive reports a dead connection
			transport.enqueueReceiveError(new WapiException("TCP receive failed", WapiError.WRECV_ERROR));

			assertTrue(lost.await());
			assertEquals(WapiError.WRECV_ERROR, lost.causes.get(0).getError());
			assertFalse(transport.isConnected());
			assertEquals(WapiError.WRECV_ERROR, assertThrows(WapiException.class, () -> client.wGetTokenInt(MUTE)).getError());
			final var next = assertThrows(WapiException.class, () -> client.wGetTokenInt(MUTE));
			assertTrue(next.getMessage().contains("not connected"), next.getMessage());
		}
	}

	@Test
	void eventsThatArrivedBeforeComeFirst() throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(transport))
		{
			final var heard = Collections.synchronizedList(new ArrayList<String>());
			final var lost = new LostRecorder();
			client.addEventListener(event -> heard.add("event"));
			client.addConnectionListener(cause -> {
				heard.add("lost");
				lost.onConnectionLost(cause);
			});
			// d7 <ch.1.mute> 01, then the console closes the connection
			transport.enqueueResponse(frame("d7" + WapiBinaryDataCodec.encodeHex(MUTE.hashBytes()) + "01"));
			transport.enqueueEndOfStream();

			client.wOpen("192.168.1.62");

			assertTrue(lost.await());
			assertEquals(List.of("event", "lost"), heard);
		}
	}

	@Test
	void theApplicationClosingOrReopeningDoesNotCallTheListener() throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var lost = new LostRecorder();
			client.addConnectionListener(lost);

			client.wOpen("192.168.1.62");
			client.awaitListeners();
			client.wClose();

			await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(lost.causes::isEmpty);
		}
	}

	@Test
	void aClosedSubscriptionIsNotCalled()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var lost = new LostRecorder();
			client.addConnectionListener(lost).close();

			transport.enqueueEndOfStream();

			await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(lost.causes::isEmpty);
		}
	}

	@Test
	void theListenerCanReconnect() throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var heard = new CountDownLatch(1);
			client.addEventListener(event -> heard.countDown());
			client.addConnectionListener(cause -> client.wOpen());

			transport.enqueueEndOfStream();
			// d7 <ch.1.mute> 01: sent on the new connection
			transport.enqueueResponse(frame("d7" + WapiBinaryDataCodec.encodeHex(MUTE.hashBytes()) + "01"));

			assertTrue(heard.await(1, TimeUnit.SECONDS));
			assertTrue(client.isConnected());
			assertEquals(List.of("connect 127.0.0.1", "disconnect", "connect 127.0.0.1"), transport.getConnections());
		}
	}

	private static WapiPacket frame(final String hex)
	{
		return new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(hex, 64));
	}

	private static final class LostRecorder implements WapiConnectionListener
	{
		private final List<WapiException> causes = Collections.synchronizedList(new ArrayList<>());
		private final CountDownLatch called = new CountDownLatch(1);

		@Override
		public void onConnectionLost(final WapiException cause)
		{
			causes.add(cause);
			called.countDown();
		}

		boolean await() throws InterruptedException
		{
			return called.await(1, TimeUnit.SECONDS);
		}
	}
}
