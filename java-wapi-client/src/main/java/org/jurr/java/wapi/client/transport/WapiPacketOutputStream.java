package org.jurr.java.wapi.client.transport;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

import org.jurr.java.wapi.protocol.WapiPacket;

/** Writes raw native-protocol bytes to the socket; channel framing is encoded by the client. */
public class WapiPacketOutputStream extends FilterOutputStream
{
	/**
	 * Writes to the socket through a buffer.
	 *
	 * @param socket connected socket
	 * @throws IOException if the socket's output stream cannot be opened
	 */
	public WapiPacketOutputStream(final Socket socket) throws IOException
	{
		this(new BufferedOutputStream(socket.getOutputStream()));
	}

	private WapiPacketOutputStream(final OutputStream out)
	{
		super(out);
	}

	@Override
	public void write(final int b) throws IOException
	{
		super.write(b);
	}

	@Override
	public void write(final byte[] b, final int off, final int len) throws IOException
	{
		super.write(b, off, len);
	}

	/**
	 * Writes a packet as it is, and sends it at once.
	 *
	 * @param packet non-null packet, framed already
	 * @throws IOException if writing to the socket fails
	 */
	public void write(final WapiPacket packet) throws IOException
	{
		write(packet.payload());
		flush();
	}
}
