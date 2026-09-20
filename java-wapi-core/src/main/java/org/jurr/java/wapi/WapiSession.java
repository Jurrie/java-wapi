package org.jurr.java.wapi;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Where a transport connects to: the socket address of the console. Whether a connection is open is
 * up to the transport.
 *
 * @param socketAddress address of the console
 */
public record WapiSession(SocketAddress socketAddress)
{
	/** TCP port of the native protocol (PDF "Remote communications with WING"). */
	public static final int DEFAULT_PORT = 2222;

	/** Creates a session with host {@code 127.0.0.1} and port {@value #DEFAULT_PORT}. */
	public WapiSession()
	{
		this(new InetSocketAddress("127.0.0.1", DEFAULT_PORT));
	}

	/**
	 * Creates a session with the given host and port {@value #DEFAULT_PORT}.
	 *
	 * @param host host name or address; a name is resolved now
	 * @throws IllegalArgumentException if the host is {@code null}
	 */
	public WapiSession(final String host)
	{
		this(new InetSocketAddress(host, DEFAULT_PORT));
	}

	/**
	 * Creates a session with the given host and port.
	 *
	 * @param host host name or address; a name is resolved now
	 * @param port port from 0 through 65535
	 * @throws IllegalArgumentException if the host is {@code null} or the port is out of range
	 */
	public WapiSession(final String host, final int port)
	{
		this(new InetSocketAddress(host, port));
	}
}
