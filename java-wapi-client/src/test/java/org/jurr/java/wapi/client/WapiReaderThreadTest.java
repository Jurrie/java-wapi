package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

/** The client reads on a background thread, so events reach the listeners without any call. */
class WapiReaderThreadTest
{
	private static final WapiToken MUTE = new WapiToken("ch.1.mute");
	private static final WapiToken FADER = new WapiToken("ch.1.fdr");

	@Test
	void listenersFireWithoutAnyCall() throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var heard = new CountDownLatch(1);
			client.addEventListener(event -> heard.countDown());

			// d7 <ch.1.mute> 01, as when someone presses mute on the console
			transport.enqueueResponse(frame("d7" + hex(MUTE) + "01"));

			assertTrue(heard.await(1, TimeUnit.SECONDS), "the listener must be called without any wGet call");
		}
	}

	@Test
	void aListenerMayCallTheClient() throws Exception
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var fader = new CompletableFuture<Float>();
			client.addEventListener(event -> {
				if (event.token().hash() == MUTE.hash())
				{
					// Runs on the dispatcher thread, while the reader thread reads the reply
					fader.complete(client.wGetTokenFloat(FADER));
				}
			});

			transport.enqueueResponse(frame("d7" + hex(MUTE) + "01"));
			// d7 <ch.1.fdr> d5c0a66668 de: -5.2 dB
			transport.enqueueReply(frame("d7" + hex(FADER) + "d5c0a66668de"));

			assertEquals(-5.2F, fader.get(1, TimeUnit.SECONDS), 0.001F);
		}
	}

	@Test
	void aRequestFailsAtOnceWhenTheConnectionEnds()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			transport.enqueueEndOfStream();

			final var timeout = Duration.ofSeconds(10);

			assertTimeoutPreemptively(Duration.ofSeconds(1),
					() -> assertThrows(WapiException.class, () -> client.wGetTokenFloat(FADER, timeout)));
		}
	}

	@Test
	void wCloseStopsTheReaderThread()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			assertTrue(client.isReading());

			client.wClose();

			assertFalse(client.isReading());
		}
	}

	@Test
	void wOpenStartsANewReaderThread()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.wClose();

			client.wOpen();
			transport.enqueueReply(frame("d7" + hex(FADER) + "d5c0a66668de"));

			assertTrue(client.isReading());
			assertEquals(-5.2F, client.wGetTokenFloat(FADER), 0.001F);
		}
	}

	private static WapiPacket frame(final String hex)
	{
		return new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(hex, 64));
	}

	private static String hex(final WapiToken token)
	{
		return WapiBinaryDataCodec.encodeHex(token.hashBytes());
	}
}
