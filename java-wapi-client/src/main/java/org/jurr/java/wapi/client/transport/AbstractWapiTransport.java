package org.jurr.java.wapi.client.transport;

import java.time.Duration;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.protocol.WapiPacket;

/** Base transport implementation with connection state. */
public abstract class AbstractWapiTransport implements WapiTransport
{
	/** Whether the transport is connected; set by {@link #connect} and {@link #disconnect}. */
	protected boolean connected;

	/** Creates a transport that is not connected yet. */
	protected AbstractWapiTransport()
	{
	}

	/** {@inheritDoc} This base implementation marks the transport connected without opening a socket. */
	@Override
	public void connect(final WapiSession session) throws WapiException
	{
		connected = true;
	}

	/** {@inheritDoc} This base implementation clears the connection state. */
	@Override
	public void disconnect() throws WapiException
	{
		connected = false;
	}

	/** {@inheritDoc} */
	@Override
	public boolean isConnected()
	{
		return connected;
	}

	/** {@inheritDoc} The base implementation returns the request unchanged. */
	@Override
	public WapiPacket sendAndReceive(final WapiPacket request) throws WapiException
	{
		if (!connected)
		{
			throw new WapiException("Transport is not connected");
		}
		return request;
	}

	/** {@inheritDoc} The base implementation checks connection state but sends no bytes. */
	@Override
	public void send(final WapiPacket request) throws WapiException
	{
		if (!connected)
		{
			throw new WapiException("Transport is not connected");
		}
	}

	/** {@inheritDoc} The base implementation returns an empty packet. */
	@Override
	public WapiPacket receive(final Duration timeout) throws WapiException
	{
		if (timeout == null || timeout.isNegative())
		{
			throw new IllegalArgumentException("Timeout must be zero or positive: " + timeout);
		}
		if (!connected)
		{
			throw new WapiException("Transport is not connected");
		}
		return new WapiPacket(new byte[0]);
	}

	/** {@inheritDoc} The base implementation returns an empty packet. */
	@Override
	public WapiPacket receive() throws WapiException
	{
		if (!connected)
		{
			throw new WapiException("Transport is not connected");
		}
		return new WapiPacket(new byte[0]);
	}
}
