package org.jurr.java.wapi.client.transport;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.protocol.WapiPacket;

/** Reads raw native-protocol bytes from the socket; channel framing is decoded by the client. */
public class WapiPacketInputStream extends FilterInputStream
{
	private final Socket socket;
	private boolean connected;

	/**
	 * Reads from the socket through a buffer.
	 *
	 * @param socket connected socket
	 * @throws IOException if the socket's input stream cannot be opened
	 */
	public WapiPacketInputStream(final Socket socket) throws IOException
	{
		super(new BufferedInputStream(socket.getInputStream()));
		this.socket = socket;
		connected = !socket.isClosed();
	}

	/**
	 * Returns the bytes available from the socket, waiting up to the timeout for at least one.
	 *
	 * @param timeout maximum wait, rounded up to whole milliseconds; {@link Duration#ZERO} returns
	 *            only bytes that have already arrived
	 * @return received bytes exactly as read, or {@code null} on timeout or end of stream
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if the stream is closed or reading fails
	 */
	public WapiPacket receive(final Duration timeout) throws WapiException
	{
		if (timeout == null || timeout.isNegative())
		{
			throw new IllegalArgumentException("Timeout must be zero or positive: " + timeout);
		}
		try
		{
			if (timeout.isZero())
			{
				return connected && available() > 0 ? readPacket(0) : null;
			}
			return readPacket((int) Math.min(Integer.MAX_VALUE, (timeout.toNanos() + 999_999L) / 1_000_000L));
		}
		catch (final IOException e)
		{
			throw new WapiException("TCP receive failed", WapiError.WRECV_ERROR, e);
		}
	}

	/**
	 * Returns the bytes available from the socket, waiting until at least one arrives.
	 *
	 * @return received bytes exactly as read, or {@code null} at end of stream
	 * @throws WapiException if the stream is closed or reading fails
	 */
	public WapiPacket receive() throws WapiException
	{
		return readPacket(0);
	}

	/** Reads what is available, waiting up to {@code timeoutMillis}, or indefinitely for 0. */
	private WapiPacket readPacket(final int timeoutMillis) throws WapiException
	{
		if (!connected)
		{
			throw new WapiException("Transport is not connected");
		}
		try
		{
			socket.setSoTimeout(timeoutMillis);
			final var buffer = new byte[4096];
			final int count = read(buffer);
			if (count < 0)
			{
				connected = false;
				return null;
			}
			return new WapiPacket(Arrays.copyOf(buffer, count));
		}
		catch (final SocketTimeoutException e)
		{
			return null;
		}
		catch (final IOException e)
		{
			throw new WapiException("TCP receive failed", WapiError.WRECV_ERROR, e);
		}
	}
}
