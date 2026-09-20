package org.jurr.java.wapi.examples;

import static org.jurr.java.wapi.examples.WapiRawListenExample.stamp;
import static org.jurr.java.wapi.examples.WapiRawListenExample.toHex;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenRegistry;
import org.jurr.java.wapi.WapiValue;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.jurr.java.wapi.client.transport.WapiTransport;
import org.jurr.java.wapi.protocol.WapiEncoder;
import org.jurr.java.wapi.protocol.WapiEventDecoder;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic example that writes one parameter and shows what the console sends back, to find out
 * whether the console echoes a client's own writes as events.
 *
 * <p>
 * Usage: {@code WapiWriteEchoExample <host> <port> <token> <value> [listenMillis]}, 2000 ms by
 * default. The example reads the current value with a data request, writes the new value with the
 * type of the current value, listens, reads the value back, writes the original value back, listens
 * and reads the value back again. The read-backs show whether a write was applied when it was not
 * echoed. It prints
 * every frame sent and received as raw hex with a time stamp, followed by the decoded events. These
 * are the only requests it sends; the original value is written back even when listening fails. The
 * listen time is limited to 4000 ms, so the example sends something well within the console's
 * 10-second idle timeout without a keep-alive.
 */
public final class WapiWriteEchoExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static final long DEFAULT_LISTEN_MILLIS = 2000L;
	private static final long MAX_LISTEN_MILLIS = 4000L;
	private static final long READ_TIMEOUT_MILLIS = 2000L;

	private WapiWriteEchoExample()
	{
	}

	/**
	 * Runs the example; the class comment describes the arguments.
	 *
	 * @param args the command-line arguments
	 */
	public static void main(final String[] args)
	{
		if (args.length < 4 || args.length > 5)
		{
			throw new IllegalArgumentException("Usage: WapiWriteEchoExample <host> <port> <token> <value> [listenMillis]");
		}
		final long listenMillis = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_LISTEN_MILLIS;
		if (listenMillis <= 0 || listenMillis > MAX_LISTEN_MILLIS)
		{
			throw new IllegalArgumentException("listenMillis must be between 1 and " + MAX_LISTEN_MILLIS);
		}
		final var session = new WapiSession(args[0], Integer.parseInt(args[1]));
		run(session, new TcpWapiTransport(), new WapiToken(args[2]), args[3], listenMillis);
	}

	/**
	 * Reads, writes, listens, checks, restores, listens and checks again.
	 *
	 * @param value new value as text, parsed with the type of the current value
	 */
	static void run(final WapiSession session, final WapiTransport transport, final WapiToken token, final String value, final long listenMillis)
	{
		final var registry = new WapiTokenRegistry();
		registry.register(token);
		final var listener = new Listener(transport, new WapiEventDecoder(registry));
		final var encoder = new WapiEncoder();

		transport.connect(session);
		try
		{
			listener.send("read", encoder.encodeNativeGet(token));
			final var current = listener.readValue(token, READ_TIMEOUT_MILLIS);
			if (current == null)
			{
				LOGGER.info("no current value received, nothing written");
				return;
			}
			LOGGER.info("current value: {} [{}]", current, current.getType());
			final byte[] write = encodeSet(encoder, token, parse(value, current));
			final byte[] restore = encodeSet(encoder, token, current);

			listener.send("write", write);
			try
			{
				listener.listen(listenMillis);
				listener.check(token, encoder, "write");
			}
			finally
			{
				try
				{
					listener.send("restore", restore);
				}
				catch (final WapiException e)
				{
					LOGGER.info("restore failed: {}", e.getMessage());
				}
			}
			listener.listen(listenMillis);
			listener.check(token, encoder, "restore");
		}
		finally
		{
			transport.disconnect();
		}
	}

	private static WapiValue parse(final String text, final WapiValue current)
	{
		return switch (current.getType())
		{
		case I32 -> WapiValue.ofInt(Integer.parseInt(text));
		case F32 -> WapiValue.ofFloat(Float.parseFloat(text));
		case S32 -> WapiValue.ofString(text);
		default -> throw new IllegalArgumentException("Cannot write a value of type " + current.getType());
		};
	}

	private static byte[] encodeSet(final WapiEncoder encoder, final WapiToken token, final WapiValue value)
	{
		return switch (value.getType())
		{
		case I32 -> encoder.encodeNativeSetInt(token, value.getIntValue());
		case F32 -> encoder.encodeNativeSetFloat(token, value.getFloatValue());
		case S32 -> encoder.encodeNativeSetString(token, value.getStringValue());
		default -> throw new IllegalArgumentException("Cannot write a value of type " + value.getType());
		};
	}

	/** Sends frames and prints everything received, until the stream ends. */
	private static final class Listener
	{
		private final WapiTransport transport;
		private final WapiEventDecoder decoder;
		private final WapiNativeChannelCodec channelCodec = new WapiNativeChannelCodec();
		private final long start = System.nanoTime();
		private boolean ended;

		Listener(final WapiTransport transport, final WapiEventDecoder decoder)
		{
			this.transport = transport;
			this.decoder = decoder;
		}

		void send(final String label, final byte[] payload)
		{
			transport.send(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, payload));
			LOGGER.info("{} tx {}: {}", stamp(start), label, toHex(payload));
		}

		/** {@return the first received value of {@code token}, or {@code null} if none arrives in time} */
		WapiValue readValue(final WapiToken token, final long timeoutMillis)
		{
			final var end = System.nanoTime() + timeoutMillis * 1_000_000L;
			while (!ended && System.nanoTime() < end)
			{
				for (final var event : receive(end))
				{
					if (event.token().hash() == token.hash())
					{
						return event.value();
					}
				}
			}
			return null;
		}

		/** Reads the value back, to show whether a write was applied even when it was not echoed. */
		void check(final WapiToken token, final WapiEncoder encoder, final String after)
		{
			if (ended)
			{
				return;
			}
			send("check", encoder.encodeNativeGet(token));
			final var value = readValue(token, READ_TIMEOUT_MILLIS);
			if (value == null)
			{
				LOGGER.info("no value after {}", after);
			}
			else
			{
				LOGGER.info("value after {}: {} [{}]", after, value, value.getType());
			}
		}

		void listen(final long durationMillis)
		{
			final var end = System.nanoTime() + durationMillis * 1_000_000L;
			while (!ended && System.nanoTime() < end)
			{
				receive(end);
			}
		}

		/** Receives one packet, prints its frames and events, and returns the events. */
		private List<WapiEvent> receive(final long end)
		{
			final var events = new ArrayList<WapiEvent>();
			final var packet = transport.receive(Duration.ofNanos(Math.max(1L, end - System.nanoTime())));
			if (packet == null)
			{
				return events;
			}
			if (packet.length() == 0)
			{
				LOGGER.info("{} end of stream", stamp(start));
				ended = true;
				return events;
			}
			for (final var frame : channelCodec.decode(packet))
			{
				LOGGER.info("{} rx channel {}: {}", stamp(start), frame.channelId(), toHex(frame.payload()));
				if (frame.channelId() != WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID)
				{
					continue;
				}
				try
				{
					for (final var event : decoder.decode(frame))
					{
						final var token = event.token();
						final var name = token.hasKnownName() ? token.name() : String.format("0x%08X", token.hash());
						LOGGER.info("      {} = {} [{}]", name, event.value(), event.type());
						events.add(event);
					}
				}
				catch (final WapiException e)
				{
					LOGGER.info("      decode error: {}", e.getMessage());
				}
			}
			return events;
		}
	}
}
