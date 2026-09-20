package org.jurr.java.wapi.client.transport;

import java.time.Duration;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.protocol.WapiPacket;

/** Transport boundary for WAPI packets. */
public interface WapiTransport
{
	/**
	 * Opens the transport using the supplied connection settings.
	 *
	 * @param session non-null connection configuration
	 * @throws WapiException if the connection cannot be established
	 */
	void connect(WapiSession session) throws WapiException;

	/**
	 * Closes the transport.
	 *
	 * @throws WapiException if closing the transport fails
	 */
	void disconnect() throws WapiException;

	/** {@return whether the transport currently considers itself connected} */
	boolean isConnected();

	/**
	 * Sends one packet and waits for a response according to the transport's request semantics.
	 *
	 * @param request non-null packet to send
	 * @return received response packet
	 * @throws WapiException if disconnected or packet I/O fails
	 */
	WapiPacket sendAndReceive(WapiPacket request) throws WapiException;

	/**
	 * Sends one packet without waiting for a response.
	 *
	 * @param request non-null packet to send
	 * @throws WapiException if disconnected or sending fails
	 */
	void send(WapiPacket request) throws WapiException;

	/**
	 * Receives the bytes that are available, exactly as they arrived, including channel
	 * selectors and escapes. A packet is not a message or frame boundary; the caller decodes
	 * the channel framing and retains incomplete protocol records.
	 *
	 * @param timeout maximum wait; {@link Duration#ZERO} returns only bytes that have already arrived
	 * @return received bytes, or {@code null} / an empty packet on timeout or end of stream
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if disconnected or receiving fails
	 */
	WapiPacket receive(Duration timeout) throws WapiException;

	/**
	 * Receives the bytes that are available, waiting until at least one byte arrives or the
	 * stream ends. See {@link #receive(Duration)}.
	 *
	 * @return received bytes, or {@code null} / an empty packet at end of stream
	 * @throws WapiException if disconnected or receiving fails
	 */
	WapiPacket receive() throws WapiException;
}
