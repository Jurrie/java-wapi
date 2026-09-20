package org.jurr.java.wapi.examples;

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.HexFormat;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.WapiClient;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.jurr.java.wapi.client.transport.WapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only diagnostic example that sends one request for a token and prints everything the console
 * sends back as raw hex, without interpreting it.
 *
 * <p>Usage: {@code WapiRawRequestExample <host> <port> <token> <data|definition>}. The only requests
 * this example can send are {@code 0xd7 <hash> 0xdc} (data request) and {@code 0xd7 <hash> 0xdd}
 * (node-definition request); neither changes anything on the console. In {@code data} mode it then
 * also calls {@link WapiClient#wGetBinaryNode(WapiToken, Duration)}, which sends the same data
 * request, and prints its result for comparison.
 *
 * <p>Navigation mode: {@code WapiRawRequestExample <host> <port> nav <path>} sends one request built from
 * a comma-separated path of navigation steps, ending with a data or definition request, for example
 * {@code root,hash:$ctl.layer.L.$spill,index:1,def}. The allowed steps are {@code root} ({@code 0xda}),
 * {@code up} ({@code 0xdb}), {@code hash:<name>} ({@code 0xd7} and the name's hash) and {@code index:<n>}
 * (the short node index {@code 0x40..0x7f} for 1..64), and as the last step {@code data} ({@code 0xdc}) or
 * {@code def} ({@code 0xdd}). Navigation only selects a node, so these requests are read-only too. The long
 * node index ({@code 0xd2}) is deliberately not offered: a console does not handle it as the PDF describes,
 * so the bytes after it cannot be guaranteed to be read as an index.
 */
public final class WapiRawRequestExample
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static final Duration CAPTURE = Duration.ofSeconds(1);

	/** The only requests this example can send. */
	enum Mode
	{
		DATA(WapiBinaryStreamCodec.TOKEN_DATA_REQUEST), DEFINITION(WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF);

		private final int token;

		Mode(final int token)
		{
			this.token = token;
		}

		/**
		 * {@return the matching mode}
		 *
		 * @param text {@code data} or {@code definition}
		 * @throws IllegalArgumentException for any other text
		 */
		static Mode parse(final String text)
		{
			if ("data".equals(text))
			{
				return DATA;
			}
			if ("definition".equals(text))
			{
				return DEFINITION;
			}
			throw new IllegalArgumentException("Mode must be 'data' or 'definition', not '" + text + "'");
		}
	}

	private WapiRawRequestExample()
	{
	}

	/**
	 * Runs the example; the class comment describes the arguments.
	 *
	 * @param args the command-line arguments
	 */
	public static void main(final String[] args)
	{
		if (args.length != 4)
		{
			throw new IllegalArgumentException("Usage: WapiRawRequestExample <host> <port> <token> <data|definition>"
					+ " or WapiRawRequestExample <host> <port> nav <path>");
		}
		if ("nav".equals(args[2]))
		{
			// Validate the path before connecting
			final byte[] request = encodePath(args[3]);
			final var session = new WapiSession(args[0], Integer.parseInt(args[1]));
			runNavigation(session, new TcpWapiTransport(), args[3], request);
			return;
		}
		// Validate everything before connecting
		final var mode = Mode.parse(args[3]);
		final var session = new WapiSession(args[0], Integer.parseInt(args[1]));
		run(session, new TcpWapiTransport(), new WapiToken(args[2]), mode);
	}

	static void run(final WapiSession session, final WapiTransport transport, final WapiToken token, final Mode mode)
	{
		transport.connect(session);
		try
		{
			LOGGER.info("token: {} (hash 0x{})", token.name(), String.format("%08X", token.hash()));
			capture(transport, encodeRequest(token, mode));
			if (mode == Mode.DATA)
			{
				try (var client = new WapiClient(session, transport))
				{
					try
					{
						LOGGER.info("wGetBinaryNode: {}", toHex(client.wGetBinaryNode(token, CAPTURE)));
					}
					catch (final WapiException e)
					{
						LOGGER.info("wGetBinaryNode failed: {}", e.getMessage());
					}
				}
			}
		}
		finally
		{
			transport.disconnect();
		}
	}

	/** Sends one navigation request and prints the raw reply. */
	static void runNavigation(final WapiSession session, final WapiTransport transport, final String path, final byte[] request)
	{
		transport.connect(session);
		try
		{
			LOGGER.info("path: {}", path);
			capture(transport, request);
		}
		finally
		{
			transport.disconnect();
		}
	}

	/**
	 * Translates a navigation path into request bytes.
	 *
	 * @throws IllegalArgumentException for an unknown step, a value out of range, or a path that does not end
	 *             with exactly one {@code data} or {@code def}
	 */
	static byte[] encodePath(final String path)
	{
		final var request = new ByteArrayOutputStream();
		final var steps = path.split(",");
		for (var i = 0; i < steps.length; i++)
		{
			final var step = steps[i].trim();
			final var last = i == steps.length - 1;
			final var isEnd = step.equals("data") || step.equals("def");
			if (isEnd && !last)
			{
				throw new IllegalArgumentException("'" + step + "' must be the last step: " + path);
			}
			if (isEnd)
			{
				request.write(step.equals("data") ? WapiBinaryStreamCodec.TOKEN_DATA_REQUEST : WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF);
				return request.toByteArray();
			}
			writeStep(request, step, path);
		}
		throw new IllegalArgumentException("The path must end with 'data' or 'def': " + path);
	}

	/** Writes one navigation step: {@code root}, {@code up}, {@code hash:<name>} or {@code index:<1..64>}. */
	private static void writeStep(final ByteArrayOutputStream request, final String step, final String path)
	{
		switch (step)
		{
			case "root" -> request.write(WapiBinaryStreamCodec.TOKEN_ROOT);
			case "up" -> request.write(WapiBinaryStreamCodec.TOKEN_PARENT);
			default -> writeStepWithArgument(request, step, path);
		}
	}

	/** Writes a {@code hash:<name>} or {@code index:<1..64>} step. */
	private static void writeStepWithArgument(final ByteArrayOutputStream request, final String step, final String path)
	{
		if (step.startsWith("hash:") && step.length() > 5)
		{
			request.write(WapiBinaryStreamCodec.TOKEN_NODE_HASH);
			final var hash = new WapiToken(step.substring(5)).hashBytes();
			request.write(hash, 0, hash.length);
			return;
		}
		if (step.startsWith("index:"))
		{
			request.write(WapiBinaryStreamCodec.encodeNodeIndex(number(step.substring(6), 1, 64, path)));
			return;
		}
		throw new IllegalArgumentException("Unknown step '" + step + "': " + path);
	}

	private static int number(final String text, final int min, final int max, final String path)
	{
		final int value;
		try
		{
			value = Integer.parseInt(text);
		}
		catch (final NumberFormatException e)
		{
			throw new IllegalArgumentException("Not a number '" + text + "': " + path, e);
		}
		if (value < min || value > max)
		{
			throw new IllegalArgumentException("Value must be between " + min + " and " + max + ": " + text);
		}
		return value;
	}

	/** {@return {@code 0xd7 <hash>} followed by the mode's request token} */
	static byte[] encodeRequest(final WapiToken token, final Mode mode)
	{
		final byte[] hash = token.hashBytes();
		return new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3], (byte) mode.token };
	}

	private static void capture(final WapiTransport transport, final byte[] request)
	{
		final var channelCodec = new WapiNativeChannelCodec();
		LOGGER.info("tx: {}", toHex(request));
		transport.send(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, request));

		final long deadline = System.nanoTime() + CAPTURE.toNanos();
		while (System.nanoTime() < deadline)
		{
			final var packet = transport.receive(Duration.ofNanos(Math.max(1L, deadline - System.nanoTime())));
			if (packet == null || packet.length() == 0)
			{
				break;
			}
			for (final var frame : channelCodec.decode(packet))
			{
				LOGGER.info("rx channel {}: {}", frame.channelId(), toHex(frame.payload()));
			}
		}
	}

	private static String toHex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}
}
