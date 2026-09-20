package org.jurr.java.wapi.client.meter;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.meter.WapiMeterData;

/** UDP receiver for Channel 3 meter reports. */
public final class WapiMeterReceiver implements AutoCloseable
{
	private final DatagramSocket socket;

	/**
	 * Opens a UDP socket on the requested local port.
	 *
	 * @param port port to bind, with zero requesting an ephemeral port
	 * @throws WapiException if the socket cannot be bound
	 */
	public WapiMeterReceiver(final int port) throws WapiException
	{
		try
		{
			socket = new DatagramSocket(port);
		}
		catch (final SocketException e)
		{
			throw new WapiException("Failed to bind meter UDP port", WapiError.WBIND_UDP_ERROR, e);
		}
	}

	/** {@return actual local port bound by the socket} */
	public int getPort()
	{
		return socket.getLocalPort();
	}

	/**
	 * Receives and decodes one UDP meter datagram, waiting up to the timeout.
	 *
	 * @param timeout maximum wait, rounded up to whole milliseconds; {@link Duration#ZERO} waits at
	 *            most 1 ms, as a UDP socket cannot check for a datagram without waiting
	 * @return decoded report, or {@code null} on timeout
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if receiving fails or the datagram is malformed
	 */
	public WapiMeterData receive(final Duration timeout) throws WapiException
	{
		if (timeout == null || timeout.isNegative())
		{
			throw new IllegalArgumentException("Timeout must be zero or positive: " + timeout);
		}
		return receive((int) Math.min(Integer.MAX_VALUE, Math.max(1L, (timeout.toNanos() + 999_999L) / 1_000_000L)));
	}

	/**
	 * Receives and decodes one UDP meter datagram, waiting until one arrives.
	 *
	 * @return decoded report
	 * @throws WapiException if receiving fails or the datagram is malformed
	 */
	public WapiMeterData receive() throws WapiException
	{
		return receive(0);
	}

	/** Receives one datagram, waiting up to {@code timeoutMillis}, or indefinitely for 0. */
	private WapiMeterData receive(final int timeoutMillis) throws WapiException
	{
		try
		{
			socket.setSoTimeout(timeoutMillis);
			final var buffer = new byte[65535];
			final var packet = new DatagramPacket(buffer, buffer.length);
			socket.receive(packet);
			final var data = new byte[packet.getLength()];
			System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
			return WapiMeterData.decode(data);
		}
		catch (final SocketTimeoutException e)
		{
			return null;
		}
		catch (final IOException e)
		{
			throw new WapiException("Failed to receive meter report", WapiError.WRECV_ERROR, e);
		}
	}

	@Override
	/** Closes the UDP socket; subsequent receives fail. */
	public void close()
	{
		socket.close();
	}
}
