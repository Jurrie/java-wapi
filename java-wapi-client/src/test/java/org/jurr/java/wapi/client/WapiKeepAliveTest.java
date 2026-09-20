package org.jurr.java.wapi.client;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

/**
 * The client keeps an idle connection alive by itself; WING closes it after 10 s without traffic. It
 * also sends the keep-alive right after connecting: the console sends no events before it has received
 * something.
 */
class WapiKeepAliveTest
{
	/** d7c9cb20e0dc: a data request for $syscfg.consolename, the keep-alive. */
	private static final String KEEP_ALIVE = "d7c9cb20e0dc";
	private static final WapiToken KEEP_ALIVE_TOKEN = new WapiToken("$syscfg.consolename");
	/** d7c9cb20e0 8357494e47 de: the reply "WING". */
	private static final String KEEP_ALIVE_REPLY = "d7c9cb20e08357494e47de";
	private static final Duration INTERVAL = Duration.ofMillis(100);

	@Test
	void wOpenSendsTheKeepAliveFirst()
	{
		final var transport = new ScriptedWapiTransport();
		transport.setAnswersKeepAlive(false);
		transport.enqueueReply(frame(KEEP_ALIVE_REPLY));
		try (var client = new WapiClient(transport))
		{
			client.wOpen("192.168.1.62");

			assertArrayEquals(expectedKeepAlive(), transport.getSentPackets().get(0).payload());
		}
	}

	@Test
	void theConnectingConstructorSendsTheKeepAliveFirst()
	{
		final var transport = new ScriptedWapiTransport();
		transport.setAnswersKeepAlive(false);
		transport.enqueueReply(frame(KEEP_ALIVE_REPLY));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			assertArrayEquals(expectedKeepAlive(), transport.getSentPackets().get(0).payload());
			assertEquals(0, client.wGetParsedEvents(1, Duration.ZERO).size(), "the reply belongs to the keep-alive");
		}
	}

	@Test
	void noReplyAfterConnectingIsFine()
	{
		// The client waits up to 0.9 s for the reply, and carries on when nothing arrives
		final var transport = new ScriptedWapiTransport();
		transport.setAnswersKeepAlive(false);
		try (var client = new WapiClient(transport))
		{
			assertTimeout(Duration.ofSeconds(2), () -> client.wOpen("192.168.1.62"));

			assertTrue(client.isConnected());
		}
	}

	@Test
	void sendsAKeepAliveWhenIdle()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var opening = countKeepAlives(transport);
			client.setKeepAliveInterval(INTERVAL);

			await().atMost(Duration.ofSeconds(1)).until(() -> countKeepAlives(transport) - opening >= 2);
		}
	}

	@Test
	void sendsNoKeepAliveWhileOtherRequestsAreSent()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var opening = countKeepAlives(transport);
			client.setKeepAliveInterval(INTERVAL);
			final var fader = new WapiToken("ch.1.fdr");

			ScenarioTiming.repeatFor(Duration.ofMillis(400), Duration.ofMillis(20), () -> {
				// d7 <ch.1.fdr> d5c0a66668 de
				transport.enqueueReply(frame("d7" + WapiBinaryDataCodec.encodeHex(fader.hashBytes()) + "d5c0a66668de"));
				client.wGetTokenFloat(fader);
			});

			assertEquals(opening, countKeepAlives(transport));
		}
	}

	@Test
	void sendsAKeepAliveWhileWritingWithoutReplies()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var opening = countKeepAlives(transport);
			client.setKeepAliveInterval(INTERVAL);
			final var fader = new WapiToken("ch.1.fdr");

			// Writes are not echoed, so nothing is received; a dead connection would go unnoticed
			ScenarioTiming.repeatFor(Duration.ofMillis(400), Duration.ofMillis(20), () -> client.wSetTokenFloat(fader, -5.2f));

			assertTrue(countKeepAlives(transport) - opening >= 2, "keep-alives sent: " + countKeepAlives(transport));
		}
	}

	@Test
	void theKeepAliveReplyIsNotAnEvent()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var opening = countKeepAlives(transport);
			client.setKeepAliveInterval(INTERVAL);

			// The second keep-alive goes out only after the reply to the first has been handled
			await().atMost(Duration.ofSeconds(1)).until(() -> countKeepAlives(transport) - opening >= 2);
			assertEquals(0, client.wGetParsedEvents(1, Duration.ZERO).size(), "the reply belongs to the keep-alive");
		}
	}

	@Test
	void wCloseStopsTheKeepAlive()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.setKeepAliveInterval(INTERVAL);
			client.wClose();
			final var sent = countKeepAlives(transport);

			await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> countKeepAlives(transport) == sent);
		}
	}

	@Test
	void aMissingKeepAliveReplyMeansTheConnectionIsLost() throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		transport.setAnswersKeepAlive(false);
		final var causes = Collections.synchronizedList(new ArrayList<WapiException>());
		final var lost = new CountDownLatch(1);
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.addConnectionListener(cause -> {
				causes.add(cause);
				lost.countDown();
			});
			client.setKeepAliveReplyTimeout(INTERVAL);
			client.setKeepAliveInterval(INTERVAL);

			assertTrue(lost.await(1, TimeUnit.SECONDS));
			assertEquals(WapiError.WRECV_ERROR, causes.get(0).getError());
			assertFalse(client.isConnected());
			assertFalse(transport.isConnected());
			// The one after connecting, which may go unanswered, and the one that is not answered in time
			assertEquals(2, countKeepAlives(transport));
			final var token = new WapiToken("ch.1.mute");
			final var thrown = assertThrows(WapiException.class, () -> client.wGetTokenInt(token));
			assertEquals("No reply to the keep-alive", thrown.getMessage());
		}
	}

	/** @return df d1 d7 c9cb20e0 dc: channel 1, then a data request for $syscfg.consolename */
	private static byte[] expectedKeepAlive()
	{
		final var hash = KEEP_ALIVE_TOKEN.hashBytes();
		return new byte[] {
				(byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE,
				(byte) (WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID),
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST
		};
	}

	/** @return the keep-alives sent: answered by the transport, or scripted */
	private static int countKeepAlives(final ScriptedWapiTransport transport)
	{
		var count = transport.getKeepAlives().size();
		for (final var packet : transport.getSentPackets())
		{
			if (WapiBinaryDataCodec.encodeHex(new WapiNativeChannelCodec().decode(packet).get(0).payload()).equals(KEEP_ALIVE))
			{
				count++;
			}
		}
		return count;
	}

	private static WapiPacket frame(final String hex)
	{
		return new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(hex, 64));
	}
}
