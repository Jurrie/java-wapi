package org.jurr.java.wapi.client.transport;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketOption;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.protocol.WapiPacket;

/** TCP socket transport for the native protocol; received bytes are passed on unchanged. */
public class TcpWapiTransport extends AbstractWapiTransport
{
	private static final int KEEP_ALIVE_IDLE_SECONDS = 1;
	private static final int KEEP_ALIVE_INTERVAL_SECONDS = 1;
	private static final int KEEP_ALIVE_COUNT = 3;
	private final Supplier<Socket> sockets;
	private Socket socket;
	private WapiPacketInputStream input;
	private WapiPacketOutputStream output;

	/** Creates a transport that is not connected. */
	public TcpWapiTransport()
	{
		this(Socket::new);
	}

	/** @param sockets creates the socket of each connection; for tests */
	TcpWapiTransport(final Supplier<Socket> sockets)
	{
		this.sockets = sockets;
	}

	/**
	 * {@inheritDoc} Closes any existing connection, then connects to the session host/port with a
	 * five-second connect timeout. It first turns on keep-alive, then turns off Nagle's algorithm
	 * ({@code TCP_NODELAY}); a failure of either is {@link WapiError#WSKAL_NTW_ERROR} or
	 * {@link WapiError#WSNDL_NTW_ERROR}.
	 */
	@Override
	public void connect(final WapiSession session) throws WapiException
	{
		if (socket != null && !socket.isClosed())
		{
			disconnect();
		}
		socket = sockets.get();
		try
		{
			enableKeepAlive(socket);
			enableTcpNoDelay(socket);
			socket.connect(session.socketAddress(), 5000);
			input = new WapiPacketInputStream(socket);
			output = new WapiPacketOutputStream(socket);
			connected = true;
		}
		catch (final IOException e)
		{
			closeQuietly(socket);
			throw new WapiException("Failed to open TCP connection to WING", WapiError.WSCON_NTW_ERROR, e);
		}
		catch (final WapiException e)
		{
			closeQuietly(socket);
			throw e;
		}
	}

	private void closeQuietly(final Closeable closeable)
	{
		if (closeable == null)
		{
			return;
		}

		try
		{
			closeable.close();
		}
		catch (final IOException ignored)
		{
			// Error is ignored because this method is supposed to be 'quitely'
		}
	}

	/**
	 * Turns on TCP keep-alive, so that a console that is switched off or a cable that is pulled is
	 * found while the connection is silent: probes after {@value #KEEP_ALIVE_IDLE_SECONDS} s of
	 * silence, {@value #KEEP_ALIVE_INTERVAL_SECONDS} s apart, and the connection fails after
	 * {@value #KEEP_ALIVE_COUNT} unanswered ones. Where the platform does not support these timings,
	 * such as Windows on Java 17 or Android, which has no {@code jdk.net}, the system defaults apply,
	 * which take hours.
	 */
	private static void enableKeepAlive(final Socket socket)
	{
		try
		{
			socket.setKeepAlive(true);
			final var idle = extendedOption("TCP_KEEPIDLE");
			final var interval = extendedOption("TCP_KEEPINTERVAL");
			final var count = extendedOption("TCP_KEEPCOUNT");
			final var supported = socket.supportedOptions();
			if (idle.isPresent() && interval.isPresent() && count.isPresent() && supported.contains(idle.get())
					&& supported.contains(interval.get()) && supported.contains(count.get()))
			{
				socket.setOption(idle.get(), KEEP_ALIVE_IDLE_SECONDS);
				socket.setOption(interval.get(), KEEP_ALIVE_INTERVAL_SECONDS);
				socket.setOption(count.get(), KEEP_ALIVE_COUNT);
			}
		}
		catch (final IOException e)
		{
			throw new WapiException("Failed to turn on TCP keep-alive", WapiError.WSKAL_NTW_ERROR, e);
		}
	}

	/**
	 * Looks up an integer socket option of {@code jdk.net.ExtendedSocketOptions} by name. The class is
	 * not referred to directly, so that this transport also loads where it is missing, such as on
	 * Android.
	 *
	 * @param name name of the field, such as {@code TCP_KEEPIDLE}
	 * @return the option, or empty when the class or the field is not there
	 */
	@SuppressWarnings("unchecked")
	static Optional<SocketOption<Integer>> extendedOption(final String name)
	{
		try
		{
			final var field = Class.forName("jdk.net.ExtendedSocketOptions").getField(name);
			return Optional.of((SocketOption<Integer>) field.get(null));
		}
		catch (final ReflectiveOperationException | LinkageError e)
		{
			return Optional.empty();
		}
	}

	private static void enableTcpNoDelay(final Socket socket)
	{
		try
		{
			socket.setTcpNoDelay(true);
		}
		catch (final IOException e)
		{
			throw new WapiException("Failed to turn on TCP_NODELAY", WapiError.WSNDL_NTW_ERROR, e);
		}
	}

	/** {@return the socket of the current connection, or {@code null}; for tests} */
	Socket socket()
	{
		return socket;
	}

	/** {@inheritDoc} Closes the socket and discards queued channel data. */
	@Override
	public void disconnect() throws WapiException
	{
		if (socket != null && !socket.isClosed())
		{
			try
			{
				socket.close();
			}
			catch (final IOException e)
			{
				throw new WapiException("Failed to close TCP connection", WapiError.WSOCK_NTW_ERROR, e);
			}
		}
		closeQuietly(input);
		closeQuietly(output);
		connected = false;
	}

	@Override
	public WapiPacket sendAndReceive(final WapiPacket request) throws WapiException
	{
		send(request);
		return receive();
	}

	/** {@inheritDoc} Flushes the packet bytes to the connected socket. */
	@Override
	public void send(final WapiPacket request) throws WapiException
	{
		if (!isConnected())
		{
			throw new WapiException("Transport is not connected");
		}

		try
		{
			output.write(request);
		}
		catch (final IOException e)
		{
			throw new WapiException("TCP send failed", WapiError.WSEND_TCP_ERROR, e);
		}
	}

	/** {@inheritDoc} Returns {@code null} on timeout or end of stream. */
	@Override
	public WapiPacket receive(final Duration timeout) throws WapiException
	{
		if (timeout == null || timeout.isNegative())
		{
			throw new IllegalArgumentException("Timeout must be zero or positive: " + timeout);
		}
		if (!isConnected())
		{
			throw new WapiException("Transport is not connected");
		}
		return input.receive(timeout);
	}

	/** {@inheritDoc} Returns {@code null} at end of stream. */
	@Override
	public WapiPacket receive() throws WapiException
	{
		if (!isConnected())
		{
			throw new WapiException("Transport is not connected");
		}
		return input.receive();
	}
}
