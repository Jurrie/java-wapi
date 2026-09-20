package org.jurr.java.wapi.examples;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.HexFormat;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenRegistry;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.jurr.java.wapi.client.transport.WapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiEventDecoder;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec.ChannelFrame;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only diagnostic example that listens for data the console sends on its own, such as changes
 * made on the console, and prints every received frame as raw hex with a time stamp, followed by the
 * events the library decodes from it.
 *
 * <p>Usage: {@code WapiRawListenExample <host> <port> [seconds]}, 30 seconds by default. The only
 * request this example sends is the keep-alive, every 6 seconds, which stops the console from closing
 * the connection after 10 seconds of silence. The keep-alive is a data request for
 * {@code $syscfg.consolename} ({@code 0xd7 <hash> 0xdc}) and changes nothing; its replies are marked
 * in the output.
 */
public final class WapiRawListenExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	static final WapiToken KEEP_ALIVE_TOKEN = new WapiToken("$syscfg.consolename");
	private static final long KEEP_ALIVE_INTERVAL_MILLIS = 6000L;
	private static final long DEFAULT_SECONDS = 30L;
	/** Names shown for known hashes; any other hash is printed as a number. */
	private static final String[] KNOWN_NAMES = {
			"ch.1.fdr", "ch.1.mute", "ch.1.pan", "ch.1.name", "ch.1.flt.mdl", KEEP_ALIVE_TOKEN.name()
	};

	private WapiRawListenExample()
	{
	}

	/**
	 * Runs the example; the class comment describes the arguments.
	 *
	 * @param args the command-line arguments
	 */
	public static void main(final String[] args)
	{
		if (args.length < 2 || args.length > 3)
		{
			throw new IllegalArgumentException("Usage: WapiRawListenExample <host> <port> [seconds]");
		}
		final long seconds = args.length > 2 ? Long.parseLong(args[2]) : DEFAULT_SECONDS;
		if (seconds <= 0)
		{
			throw new IllegalArgumentException("Seconds must be positive");
		}
		final var session = new WapiSession(args[0], Integer.parseInt(args[1]));
		run(session, new TcpWapiTransport(), seconds * 1000L);
	}

	/** {@return the keep-alive request: {@code 0xd7 <hash of $syscfg.consolename> 0xdc}} */
	static byte[] encodeKeepAlive()
	{
		final byte[] hash = KEEP_ALIVE_TOKEN.hashBytes();
		return new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3], (byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST
		};
	}

	/**
	 * Listens for the given duration, or until the transport reports the end of the stream.
	 *
	 * @param durationMillis how long to listen
	 */
	static void run(final WapiSession session, final WapiTransport transport, final long durationMillis)
	{
		final var registry = new WapiTokenRegistry();
		for (final var name : KNOWN_NAMES)
		{
			registry.register(new WapiToken(name));
		}
		final var channelCodec = new WapiNativeChannelCodec();
		final var decoder = new WapiEventDecoder(registry);

		transport.connect(session);
		try
		{
			final long start = System.nanoTime();
			final var end = start + durationMillis * 1_000_000L;
			long nextKeepAlive = start;
			while (System.nanoTime() < end)
			{
				final long now = System.nanoTime();
				if (now >= nextKeepAlive)
				{
					transport.send(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, encodeKeepAlive()));
					LOGGER.info("{} tx keep-alive: {}", stamp(start), toHex(encodeKeepAlive()));
					nextKeepAlive = now + KEEP_ALIVE_INTERVAL_MILLIS * 1_000_000L;
				}
				final long waitNanos = Math.min(end, nextKeepAlive) - System.nanoTime();
				final var packet = transport.receive(Duration.ofNanos(Math.max(1L, waitNanos)));
				if (packet == null)
				{
					continue;
				}
				if (packet.length() == 0)
				{
					LOGGER.info("{} end of stream", stamp(start));
					return;
				}
				for (final var frame : channelCodec.decode(packet))
				{
					LOGGER.info("{} rx channel {}: {}", stamp(start), frame.channelId(), toHex(frame.payload()));
					if (frame.channelId() == WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID)
					{
						printEvents(decoder, frame);
					}
				}
			}
		}
		finally
		{
			transport.disconnect();
		}
	}

	private static void printEvents(final WapiEventDecoder decoder, final ChannelFrame frame)
	{
		try
		{
			for (final var event : decoder.decode(frame))
			{
				final var token = event.token();
				final var name = token.hasKnownName() ? token.name() : String.format("0x%08X", token.hash());
				final var mark = token.hash() == KEEP_ALIVE_TOKEN.hash() ? " (keep-alive reply)" : "";
				LOGGER.info("      {} = {} [{}]{}", name, event.value(), event.type(), mark);
			}
		}
		catch (final WapiException e)
		{
			LOGGER.info("      decode error: {}", e.getMessage());
		}
	}

	static String stamp(final long start)
	{
		return String.format("[+%6d ms]", (System.nanoTime() - start) / 1_000_000L);
	}

	static String toHex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}
}
