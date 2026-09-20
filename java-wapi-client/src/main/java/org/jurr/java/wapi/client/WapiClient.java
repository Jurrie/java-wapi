package org.jurr.java.wapi.client;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenRegistry;
import org.jurr.java.wapi.WapiTokenValue;
import org.jurr.java.wapi.WapiType;
import org.jurr.java.wapi.WapiValue;
import org.jurr.java.wapi.client.discovery.WapiDiscovery;
import org.jurr.java.wapi.client.transport.WapiTransport;
import org.jurr.java.wapi.discovery.WapiConsoleInfo;
import org.jurr.java.wapi.meter.WapiMeterData;
import org.jurr.java.wapi.meter.WapiMeterRequest;
import org.jurr.java.wapi.node.WapiNodeAssignmentCodec;
import org.jurr.java.wapi.node.WapiNodeDefinition;
import org.jurr.java.wapi.node.WapiTokenDefinition;
import org.jurr.java.wapi.protocol.WapiEncoder;
import org.jurr.java.wapi.protocol.WapiEventDecoder;
import org.jurr.java.wapi.protocol.WapiEventDecoder.StreamRecord;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;

/**
 * High-level client for reading and writing WING WAPI tokens and receiving events.
 * Construction connects the supplied transport to the supplied session. Methods
 * that perform I/O require a usable connected transport unless stated otherwise.
 * <p>
 * Unlike C, which only reads inside a call, the client reads on a background reader thread while
 * connected. Events go to the listeners as soon as they arrive, on a separate dispatcher thread
 * (so a listener may call this client), and into the queue that {@code wGetParsedEvents} reads.
 * Requests are sent one at a time; each waits until the reader thread has collected its reply.
 * <p>
 * An unknown or misplaced token in the received stream is a protocol error: the transport is
 * disconnected, and the next call that reads throws a {@link WapiException} with
 * {@link WapiError#WTOKEN}; use {@link #wOpen(String)} to connect again.
 * <p>
 * A lost connection, found by TCP keep-alive, a missing keep-alive reply, the console closing it or
 * a protocol error, goes to the listeners of {@link #addConnectionListener(WapiConnectionListener)}.
 * The client does not reconnect by itself; a listener may call {@link #wOpen()}.
 */
public class WapiClient implements AutoCloseable
{
	/** Timeout of the functions without one: 200 ms, the time the PDF gives for C {@code wGetTokenDef}. */
	private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(200);
	private volatile WapiSession session;
	private final WapiEncoder encoder;
	private final WapiTokenRegistry tokenRegistry;
	private final Connection connection;
	private final EventDelivery delivery = new EventDelivery();
	private final KeepAlive keepAlive;
	private final Meters meters;

	/**
	 * Creates a client and connects {@code transport} to {@code session}. The reader thread starts
	 * right away, so events that arrive before a listener is added go only to the event queue; use
	 * {@link #WapiClient(WapiTransport)} to add listeners first.
	 *
	 * @param session session configuration to retain and connect; must not be {@code null}
	 * @param transport transport used for all protocol I/O; must not be {@code null}
	 */
	public WapiClient(final WapiSession session, final WapiTransport transport)
	{
		this(session, transport, true);
	}

	/**
	 * Creates a client that is not connected yet, with a session on port
	 * {@value WapiSession#DEFAULT_PORT}. Add listeners and register token names first, then connect
	 * with {@link #wOpen(String)}, so that no event is missed or left unnamed. Calls that use the
	 * connection before that fail with "not connected".
	 *
	 * @param transport transport used for all protocol I/O; must not be {@code null}
	 */
	public WapiClient(final WapiTransport transport)
	{
		this(new WapiSession(), transport, false);
	}

	/**
	 * Creates a client for {@code session}, and connects it right away when {@code connect} is set; see
	 * {@link #WapiClient(WapiSession, WapiTransport)} and {@link #WapiClient(WapiTransport)}.
	 *
	 * @param session session configuration to retain; must not be {@code null}
	 * @param transport transport used for all protocol I/O; must not be {@code null}
	 * @param connect whether to connect now; otherwise connect later with {@link #wOpen()}
	 */
	public WapiClient(final WapiSession session, final WapiTransport transport, final boolean connect)
	{
		this.session = session;
		encoder = new WapiEncoder();
		tokenRegistry = new WapiTokenRegistry();
		connection = new Connection(transport, new WapiEventDecoder(tokenRegistry), delivery, this::acceptEvent);
		keepAlive = new KeepAlive(connection);
		meters = new Meters(payload -> connection.send(WapiNativeChannelCodec.NRP_METER_CHANNEL_ID, payload), connection::isConnected);
		if (connect)
		{
			connection.open(this.session);
			keepAlive.start();
			keepAlive.greet();
		}
	}

	/**
	 * Returns where the client connects to. {@link #wOpen(String)} and {@link #wOpen(WapiConsoleInfo)}
	 * replace the session.
	 *
	 * @return the current session
	 */
	public WapiSession getSession()
	{
		return session;
	}

	/**
	 * Retrieves all queued unsolicited events, waiting until one arrives if the queue is empty, like
	 * C {@code wGetParsedEvents}. Events arrive on a background reader thread; the queue keeps the
	 * newest 10,000.
	 *
	 * @return the events in the order they arrived; empty when the connection ends
	 * @throws WapiException if the connection failed, for example on a protocol error
	 */
	public List<WapiEvent> wGetParsedEvents()
	{
		return wGetParsedEvents(Integer.MAX_VALUE);
	}

	/**
	 * Retrieves at most {@code maxEvents} queued unsolicited events, waiting until one arrives if the
	 * queue is empty. Events beyond the maximum stay queued for the next call.
	 *
	 * @param maxEvents maximum number of events to return; 0 returns an empty list at once
	 * @return the events in the order they arrived; empty when the connection ends
	 * @throws IllegalArgumentException if {@code maxEvents} is negative
	 * @throws WapiException if the connection failed, for example on a protocol error
	 */
	public List<WapiEvent> wGetParsedEvents(final int maxEvents)
	{
		requireMaxEvents(maxEvents);
		connection.checkConnection();
		return awaitEvents(maxEvents, Timeouts.FOREVER);
	}

	/**
	 * Retrieves all queued unsolicited events, waiting up to the timeout when the queue is empty, like
	 * C {@code wGetParsedEventsTimed}.
	 *
	 * @param timeout maximum wait; {@link Duration#ZERO} returns the queued events without waiting.
	 *            To wait indefinitely, use {@link #wGetParsedEvents()}
	 * @return the events in the order they arrived, or an empty list if none arrived in time
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if the connection failed, for example on a protocol error
	 */
	public List<WapiEvent> wGetParsedEvents(final Duration timeout)
	{
		return wGetParsedEvents(Integer.MAX_VALUE, timeout);
	}

	/**
	 * Retrieves at most {@code maxEvents} queued unsolicited events, waiting up to the timeout when the
	 * queue is empty. Events beyond the maximum stay queued for the next call.
	 *
	 * @param maxEvents maximum number of events to return; 0 returns an empty list at once
	 * @param timeout maximum wait; {@link Duration#ZERO} returns the queued events without waiting
	 * @return the events in the order they arrived, or an empty list if none arrived in time
	 * @throws IllegalArgumentException if {@code maxEvents} is negative, or the timeout is {@code null} or negative
	 * @throws WapiException if the connection failed, for example on a protocol error
	 */
	public List<WapiEvent> wGetParsedEvents(final int maxEvents, final Duration timeout)
	{
		requireMaxEvents(maxEvents);
		final var deadline = Timeouts.deadline(timeout);
		connection.checkConnection();
		return awaitEvents(maxEvents, deadline);
	}

	/**
	 * Tells whether the client is connected: the transport is connected and the reader thread runs.
	 * C has no such call. False before {@link #wOpen(String)}, after {@link #wClose()}, when the
	 * console closed the connection and after a protocol error.
	 *
	 * @return whether calls that use the connection can succeed
	 */
	public boolean isConnected()
	{
		return connection.isConnected();
	}

	/** {@return whether the reader thread is running; for tests} */
	boolean isReading()
	{
		return connection.isReading();
	}

	/**
	 * Waits until the listeners have been called for every event delivered so far; for tests.
	 */
	void awaitListeners() throws InterruptedException
	{
		delivery.awaitListeners();
	}

	private static void requireMaxEvents(final int maxEvents)
	{
		if (maxEvents < 0)
		{
			throw new IllegalArgumentException("maxEvents must not be negative: " + maxEvents);
		}
	}

	/** @param deadline {@link System#nanoTime()} to wait until, or {@link Timeouts#FOREVER} */
	private List<WapiEvent> awaitEvents(final int maxEvents, final long deadline)
	{
		while (true)
		{
			connection.checkConnection();
			final var events = delivery.drain(maxEvents);
			if (!events.isEmpty() || maxEvents == 0 || connection.isReaderStopped() || deadline != Timeouts.FOREVER && deadline - System.nanoTime() <= 0)
			{
				return events;
			}
			try
			{
				delivery.await(deadline);
			}
			catch (final InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return List.of();
			}
		}
	}

	/**
	 * Returns the registry used to resolve hashes in unsolicited event records.
	 * Register tokens before receiving events for their hashes.
	 *
	 * @return the client's live token registry
	 */
	public WapiTokenRegistry getTokenRegistry()
	{
		return tokenRegistry;
	}

	/**
	 * Opens or replaces the UDP port for meter reports, like C {@code wMeterUDPPort}. Unlike C, a
	 * background thread receives the reports right away and queues the newest 1000 for
	 * {@link #wGetMeters(Duration)}; a malformed datagram is skipped.
	 *
	 * @param port local UDP port to bind; zero requests an automatically assigned port
	 * @return the actual local port bound by the receiver
	 * @throws WapiException if the UDP receiver cannot be opened
	 */
	public int wMeterUDPPort(final int port)
	{
		return meters.openPort(port);
	}

	/**
	 * Registers a listener for every received meter report. The listeners are called on a dispatcher
	 * thread of their own, so they do not hold up the event listeners, nor the other way round. C has
	 * no such call.
	 *
	 * @param listener callback to register; must not be {@code null}
	 * @return a handle whose {@link WapiSubscription#close()} removes this registration
	 * @throws WapiException if {@code listener} is {@code null}
	 */
	public WapiSubscription addMeterListener(final WapiMeterListener listener)
	{
		return meters.addListener(listener);
	}

	/**
	 * Sends a meter request, like C {@code wSetMetersRequest}. Unlike C, the client renews it every
	 * 4 seconds by itself while connected, and sends it again after a reconnect, until
	 * {@link #wStopMeters(int)} or {@link #wClose()}.
	 *
	 * @param request encoded meter groups and destination port; must not be {@code null}
	 * @throws WapiException if the request is {@code null} or sending fails
	 */
	public void wSetMetersRequest(final WapiMeterRequest request)
	{
		meters.request(request);
	}

	/**
	 * Requests meters with the 30-byte selection bitmask of C {@code wSetMetersRequest}, sent to the
	 * port of the receiver opened with {@link #wMeterUDPPort(int)}. See
	 * {@link WapiMeterRequest#fromBitmask(int, int, byte[])} for the layout.
	 *
	 * @param requestId request identifier, also used to renew the request
	 * @param mask 30-byte selection bitmask
	 * @throws WapiException if no meter receiver is open, the mask is invalid, or sending fails
	 */
	public void wSetMetersRequest(final int requestId, final byte[] mask)
	{
		meters.request(requestId, mask);
	}

	/**
	 * Sends a renewal for an active meter subscription, like C {@code wRenewMeters}. Not needed: the
	 * client renews every request by itself.
	 *
	 * @param requestId identifier used when the meter subscription was configured
	 * @throws WapiException if sending fails
	 */
	public void wRenewMeters(final int requestId)
	{
		meters.renew(requestId);
	}

	/**
	 * Stops renewing a meter request; the console then stops sending its reports within 5 seconds.
	 * C has no such call: in C, the application stops calling {@code wRenewMeters}. An unknown
	 * request ID is ignored.
	 *
	 * @param requestId identifier used when the meter request was sent
	 */
	public void wStopMeters(final int requestId)
	{
		meters.stop(requestId);
	}

	/**
	 * Sets how often meter requests are renewed; for tests.
	 */
	void setMeterRenewalInterval(final Duration interval)
	{
		meters.setRenewalInterval(interval);
	}

	/**
	 * Takes the oldest received meter report, waiting up to the timeout when none is queued, like C
	 * {@code wGetMeters}. The reports are received on a background thread; see
	 * {@link #wMeterUDPPort(int)}.
	 *
	 * @param timeout maximum wait; {@link Duration#ZERO} returns a queued report without waiting
	 * @return the decoded report, or empty when the timeout expires or the client is closed
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if no meter UDP port is open
	 */
	public Optional<WapiMeterData> wGetMeters(final Duration timeout)
	{
		Timeouts.requireTimeout(timeout);
		return meters.poll(timeout);
	}

	/**
	 * Delivers a decoded event: it is queued for {@code wGetParsedEvents}, and the listeners that are
	 * registered when it arrives are called on the dispatcher thread, in the order the events arrived.
	 *
	 * @param event decoded event to deliver; must not be {@code null}
	 */
	protected void acceptEvent(final WapiEvent event)
	{
		delivery.offer(event);
	}

	/**
	 * Registers a listener for every accepted decoded token event.
	 *
	 * @param listener callback to register; must not be {@code null}
	 * @return a handle whose {@link WapiSubscription#close()} removes this registration
	 * @throws WapiException if {@code listener} is {@code null}
	 */
	public WapiSubscription addEventListener(final WapiEventListener listener)
	{
		return delivery.addEventListener(listener);
	}

	/**
	 * Registers a listener for a connection that ended without the application closing it; see
	 * {@link WapiConnectionListener}. C has no such call.
	 *
	 * @param listener callback to register; must not be {@code null}
	 * @return a handle whose {@link WapiSubscription#close()} removes this registration
	 * @throws WapiException if {@code listener} is {@code null}
	 */
	public WapiSubscription addConnectionListener(final WapiConnectionListener listener)
	{
		return delivery.addConnectionListener(listener);
	}

	/**
	 * Connects to a console found by {@link WapiDiscovery}, at the address its reply came from. With
	 * the unconnected constructor this gives the flow: discover, create the client, add listeners and
	 * token names, connect. As {@link #wOpen(String)}, this always opens a new connection.
	 *
	 * <pre>
	 * WapiConsoleInfo console = WapiDiscovery.discoverLocal(WapiDiscovery.DEFAULT_TIMEOUT).get(0);
	 * try (var client = new WapiClient(new TcpWapiTransport()))
	 * {
	 * 	client.addEventListener(event -&gt; ...);
	 * 	client.wOpen(console);
	 * 	...
	 * }
	 * </pre>
	 *
	 * @param console console to connect to; must not be {@code null}
	 * @throws WapiException if {@code console} is {@code null}, or the transport cannot connect
	 */
	public void wOpen(final WapiConsoleInfo console)
	{
		if (console == null)
		{
			throw new WapiException("Console is required");
		}
		openNew(console.toSession());
	}

	/**
	 * Reconnects to the session host, only when the connection is closed; the same as
	 * {@code wOpen((String) null)}. C has no such call.
	 *
	 * @throws WapiException if the transport cannot connect
	 */
	public void wOpen()
	{
		wOpen((String) null);
	}

	/**
	 * Opens a connection, like C {@code wOpen}. A host always opens a new connection, also when one is
	 * open; the old connection is closed first.
	 * A {@code null} host, which C does not have, reconnects to the session host only when the
	 * connection is closed.
	 * <p>
	 * Deviation from C: an empty host does not search for a console. Use
	 * {@link WapiDiscovery#discoverLocal(Duration)}, which returns every console that answers, and
	 * connect to the one you choose with {@link #wOpen(WapiConsoleInfo)}.
	 *
	 * The client gets a new session with this host and the default port.
	 *
	 * @param host host to connect to, or {@code null} to keep the session host
	 * @throws WapiException if the host is empty, or the transport cannot connect
	 */
	public void wOpen(final String host)
	{
		wOpen(host, WapiSession.DEFAULT_PORT);
	}

	/**
	 * Opens a connection, like C {@code wOpen}. A host always opens a new connection, also when one is
	 * open; the old connection is closed first.
	 * A {@code null} host, which C does not have, reconnects to the session host only when the
	 * connection is closed.
	 * <p>
	 * Deviation from C: an empty host does not search for a console. Use
	 * {@link WapiDiscovery#discoverLocal(Duration)}, which returns every console that answers, and
	 * connect to the one you choose with {@link #wOpen(WapiConsoleInfo)}.
	 *
	 * The client gets a new session with this host and port.
	 *
	 * @param host host to connect to, or {@code null} to keep the session host
	 * @param port port to connect to; not used when {@code host} is {@code null}
	 * @throws WapiException if the host is empty, or the transport cannot connect
	 */
	public void wOpen(final String host, final int port)
	{
		if (host == null)
		{
			connectIfClosed();
			return;
		}
		if (host.isEmpty())
		{
			throw new WapiException("wOpen(\"\") does not search for a console; use WapiDiscovery.discoverLocal and connect to the console you choose");
		}
		openNew(new WapiSession(host, port));
	}

	/** Opens a new connection to {@code target}, closing the old one first. */
	private void openNew(final WapiSession target)
	{
		session = target;
		// A host always opens a new connection; the old one is closed first
		connection.disconnectIfConnected();
		connectIfClosed();
	}

	/** Connects to the session host and starts the reader thread, unless connected already. */
	private void connectIfClosed()
	{
		if (!isConnected())
		{
			connection.open(session);
			keepAlive.start();
			keepAlive.greet();
			meters.resendRequests();
		}
	}

	/**
	 * Closes the meter receiver and disconnects the transport.
	 *
	 * @throws WapiException if transport disconnection fails; the client is closed regardless
	 */
	public void wClose()
	{
		meters.close();
		try
		{
			connection.disconnect();
		}
		finally
		{
			keepAlive.stop();
			connection.stopReader();
			delivery.stop();
		}
	}

	/** Closes the connection, the same as {@link #wClose()}, so the client can be used in try-with-resources. */
	@Override
	public void close()
	{
		wClose();
	}

	/**
	 * Finds a registered token by name or creates and registers one using its canonical hash.
	 *
	 * @param name non-null, non-empty token name
	 * @return the matching registered token, or a newly created token
	 * @throws WapiException if the name is missing or empty
	 */
	public WapiToken wGetTokenFromName(final String name)
	{
		return tokenRegistry.findOrCreateByName(name);
	}

	/**
	 * Finds a token previously registered under the supplied hash.
	 *
	 * @param hash 32-bit token hash
	 * @return the registered token, or empty if the registry has no such hash
	 */
	public Optional<WapiToken> wGetTokenFromHash(final int hash)
	{
		return Optional.ofNullable(tokenRegistry.findByHash(hash));
	}

	/**
	 * Resolves a registered token hash to its name.
	 *
	 * @param hash 32-bit token hash
	 * @return the registered token name, or empty if the hash is unknown or known only as a hash
	 */
	public Optional<String> wGetNameFromHash(final int hash)
	{
		return wGetTokenFromHash(hash).filter(WapiToken::hasKnownName).map(WapiToken::name);
	}

	/**
	 * Requests the node definition of a parameter, waiting up to 200 milliseconds like
	 * C {@code wGetTokenDef}. Only a definition with the token's own hash is accepted; events
	 * received while waiting are delivered as usual.
	 *
	 * @param token parameter whose definition is requested; must not be {@code null}
	 * @return the definition in C form, as received and parsed
	 * @throws WapiException if {@code token} is {@code null} or transport I/O fails; with
	 *             {@link WapiError#WRECV_ERROR} if no definition of the token arrives in time, or the
	 *             reply ends without it, as for a node, which the console answers with its children
	 *             (as in C, captured with libwapi.a for {@code ch.1.flt})
	 */
	public WapiTokenDefinition wGetTokenDef(final WapiToken token)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		return connection.request(encoder.encodeNativeDefinitionRequest(token), new TokenDefinitionReplyHandler(token), DEFAULT_TIMEOUT);
	}

	/**
	 * Requests the definitions of the top-level nodes ({@code $stat}, {@code cfg}, {@code ch}, ...),
	 * waiting up to 200 milliseconds for the reply and between its frames.
	 *
	 * @return the definitions, in the order the console sends them
	 * @throws WapiException as for {@link #getNodeDefinitions(WapiToken, Duration)}
	 * @see #getNodeDefinitions(Duration)
	 */
	public List<WapiNodeDefinition> getNodeDefinitions()
	{
		return getNodeDefinitions(DEFAULT_TIMEOUT);
	}

	/**
	 * Requests the definitions of the top-level nodes ({@code $stat}, {@code cfg}, {@code ch}, ...)
	 * with the request {@code 0xda 0xdd}; the root has no hash of its own. Their names are registered
	 * in the {@linkplain #getTokenRegistry() token registry}.
	 *
	 * @param timeout maximum wait for the reply and between its frames
	 * @return the definitions, in the order the console sends them
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException as for {@link #getNodeDefinitions(WapiToken, Duration)}
	 */
	public List<WapiNodeDefinition> getNodeDefinitions(final Duration timeout)
	{
		return readNodeDefinitions(null, encoder.encodeRootDefinitionRequest(), timeout);
	}

	/**
	 * Requests the definitions of the children of a node, waiting up to 200 milliseconds for the
	 * reply and between its frames.
	 *
	 * @param node node whose children are requested; must not be {@code null}
	 * @return the definitions, in the order the console sends them
	 * @throws WapiException as for {@link #getNodeDefinitions(WapiToken, Duration)}
	 */
	public List<WapiNodeDefinition> getNodeDefinitions(final WapiToken node)
	{
		return getNodeDefinitions(node, DEFAULT_TIMEOUT);
	}

	/**
	 * Requests the definitions of the children of a node with the request {@code 0xd7 <hash> 0xdd}.
	 * This has no C counterpart: C knows the node tree from its token table. A walk over these
	 * definitions gives the name, type and range of every parameter; the full name of a child is the
	 * parent's name followed by {@link WapiNodeDefinition#pathSegment()}. When the node's name is
	 * known, from the token or from the {@linkplain #getTokenRegistry() token registry}, the full names
	 * of the children are registered, so that a walk down the tree names the values of
	 * {@link #wGetNode(WapiToken)} and {@link #wGetNodeToTVArray(WapiToken)}. Records that are not
	 * part of the reply are delivered as events.
	 *
	 * @param node node whose children are requested; must not be {@code null}
	 * @param timeout maximum wait for the reply and between its frames
	 * @return the definitions, in the order the console sends them; empty for a node without children
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if {@code node} is {@code null}; with {@link WapiError#WNODE} if the token
	 *             is a parameter, which the console answers with its own definition, or a definition
	 *             cannot be parsed (the message holds its bytes); with {@link WapiError#WRECV_ERROR}
	 *             if the reply does not end in time
	 */
	public List<WapiNodeDefinition> getNodeDefinitions(final WapiToken node, final Duration timeout)
	{
		if (node == null)
		{
			throw new WapiException("Node token is required");
		}
		return readNodeDefinitions(node, encoder.encodeNativeDefinitionRequest(node), timeout);
	}

	/** {@return the name of the token, from the registry if the token has only a hash; {@code null} if unknown} */
	private String nameOf(final WapiToken token)
	{
		if (token.hasKnownName())
		{
			return token.name();
		}
		final var registered = tokenRegistry.findByHash(token.hash());
		return registered != null && registered.hasKnownName() ? registered.name() : null;
	}

	/**
	 * Registers the full name of a definition, when the parent's name is known and the definition's
	 * hash is the hash of that name.
	 *
	 * @param parentName name of the parent, empty for a top-level node, or {@code null} if unknown
	 */
	private void registerName(final String parentName, final WapiNodeDefinition definition)
	{
		if (parentName == null)
		{
			return;
		}
		final var name = parentName.isEmpty() ? definition.pathSegment() : parentName + "." + definition.pathSegment();
		final var token = new WapiToken(name);
		if (Integer.toUnsignedLong(token.hash()) == definition.hash())
		{
			tokenRegistry.register(token);
		}
	}

	/** Sends a definition request and reads the reply up to its end-of-data token. */
	private List<WapiNodeDefinition> readNodeDefinitions(final WapiToken node, final byte[] request, final Duration timeout)
	{
		final var parentName = node == null ? "" : nameOf(node);
		return connection.request(request, new NodeDefinitionsReplyHandler(node, definition -> registerName(parentName, definition)), timeout);
	}

	/**
	 * Sends the native toggle operation for a token.
	 *
	 * @param token target token; must not be {@code null}
	 * @throws WapiException if {@code token} is {@code null} or sending fails
	 */
	public void wToggleTokenInt(final WapiToken token)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		connection.send(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, encoder.encodeNativeToggle(token));
	}

	/**
	 * Sends a native step/click operation with the supplied byte value.
	 *
	 * @param token target token; must not be {@code null}
	 * @param value step value transmitted without numeric range conversion
	 * @throws WapiException if {@code token} is {@code null} or sending fails
	 */
	public void wClickTokenByte(final WapiToken token, final byte value)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		connection.send(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, encoder.encodeNativeStep(token, value));
	}

	/**
	 * Reads a token using the default 200-millisecond timeout and converts the
	 * response to the requested type when a supported conversion exists.
	 *
	 * @param token token to read; must not be {@code null}
	 * @param type requested non-null result type
	 * @return the received value represented as {@code type}
	 * @throws WapiException if arguments are invalid, the read times out, or conversion is unsupported
	 */
	public WapiValue wGetToken(final WapiToken token, final WapiType type)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		return readNativeToken(token, type, DEFAULT_TIMEOUT);
	}

	/**
	 * Reads a token using a caller-specified timeout, like C {@code wGetTokenTimed}, and converts the
	 * response to the requested type when a supported conversion exists.
	 *
	 * @param token token to read; must not be {@code null}
	 * @param type requested non-null result type
	 * @param timeout maximum wait for the reply
	 * @return the received value represented as {@code type}
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if arguments are invalid, the read times out, or conversion is unsupported
	 */
	public WapiValue wGetToken(final WapiToken token, final WapiType type, final Duration timeout)
	{
		return readNativeToken(token, type, timeout);
	}

	/**
	 * Requests the binary data of a node or parameter, like C {@code wGetBinaryNode}. The result
	 * consists of raw {@code 0xd7}/hash/value records, which can be sent back with
	 * {@link #wSetBinaryNode(byte[])}. The console answers a parameter with its own record, and a node
	 * with one record for every parameter at any depth below it, each carrying that parameter's own
	 * hash; both replies end with an end-of-data token, which is not part of the result (confirmed
	 * with captured replies for {@code ch.1.fdr}, {@code ch.1.flt} and {@code ch.1}). Records that are
	 * not part of the response, such as unsolicited events received before it, are delivered as
	 * events.
	 * <p>
	 * Deviation from C: there is no buffer size; the whole reply is returned.
	 *
	 * @param token node or parameter token to request; must not be {@code null}
	 * @param timeout maximum wait for the reply and between its frames
	 * @return raw response records
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if the token is missing, no response arrives in time, or the reply is only
	 *             an end-of-data token, which the console sends for a token it does not know
	 *             ({@link WapiError#WNODE}, as C returns)
	 */
	public byte[] wGetBinaryNode(final WapiToken token, final Duration timeout)
	{
		if (token == null)
		{
			throw new WapiException("Node token is required");
		}
		return concatenate(readNodeReply(token, timeout));
	}

	/**
	 * Requests the binary data of a node or parameter, like C {@code wGetBinaryNode}, waiting up to
	 * 200 milliseconds for the reply and between its frames.
	 *
	 * @param token node or parameter token to request; must not be {@code null}
	 * @return raw response records
	 * @throws WapiException if the token is missing, no response arrives in time, or the reply is only
	 *             an end-of-data token, which the console sends for a token it does not know
	 *             ({@link WapiError#WNODE})
	 * @see #wGetBinaryNode(WapiToken, Duration)
	 */
	public byte[] wGetBinaryNode(final WapiToken token)
	{
		return wGetBinaryNode(token, DEFAULT_TIMEOUT);
	}

	/**
	 * Sends a data request and reads the console's reply: the token's own record for a parameter,
	 * or the records of all parameters below a node. Other records are delivered as events. The
	 * timeout restarts with every frame that brings reply records, so it limits the silence while
	 * the reply arrives rather than the whole reply.
	 *
	 * @return the reply records, without the end-of-data token
	 * @throws WapiException if no reply arrives in time; with {@link WapiError#WNODE} if the reply is
	 *             only an end-of-data token, which the console sends for a token it does not know
	 */
	private List<StreamRecord> readNodeReply(final WapiToken token, final Duration timeout)
	{
		return connection.request(encoder.encodeNativeGet(token), new NodeReplyHandler(token), timeout);
	}

	private static byte[] concatenate(final List<StreamRecord> streamRecords)
	{
		final var bytes = new ByteArrayOutputStream();
		for (final var streamRecord : streamRecords)
		{
			final byte[] raw = streamRecord.rawBytes();
			bytes.write(raw, 0, raw.length);
		}
		return bytes.toByteArray();
	}

	/**
	 * Requests the binary data of a node or parameter given by name, like C {@code wGetBinaryData},
	 * waiting up to 200 milliseconds for the reply and between its frames.
	 *
	 * @param name dotted name such as {@code ch.1.eq} (a node) or {@code ch.1.name} (a parameter)
	 * @return raw response records
	 * @throws WapiException if the name is missing, no response arrives in time, or the console does
	 *             not know the name ({@link WapiError#WNODE})
	 * @see #wGetBinaryData(String, Duration)
	 */
	public byte[] wGetBinaryData(final String name)
	{
		return wGetBinaryData(name, DEFAULT_TIMEOUT);
	}

	/**
	 * Requests the binary data of a node or parameter given by name, like C {@code wGetBinaryData}:
	 * the data request {@code 0xd7 <hash of name> 0xdc}, so the same as
	 * {@link #wGetBinaryNode(WapiToken, Duration)} for a token with that name. The name is hashed as
	 * given, so a leading slash, as in the PDF's examples ({@code "/ch.1.eq"}), gives
	 * {@link WapiError#WNODE}: the console does not know that hash. C also fails on such a name
	 * (captured with libwapi.a: {@code "/ch.1.flt"} returns -1).
	 * <p>
	 * Deviation from C: the result is the whole reply without the closing {@code 0xde}, as for
	 * {@code wGetBinaryNode}, and there is no buffer size. C returns the reply including {@code 0xde}
	 * (captured with libwapi.a).
	 *
	 * @param name dotted name such as {@code ch.1.eq} (a node) or {@code ch.1.name} (a parameter)
	 * @param timeout maximum wait for the reply and between its frames
	 * @return raw response records
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if the name is missing, no response arrives in time, or the console does
	 *             not know the name ({@link WapiError#WNODE})
	 */
	public byte[] wGetBinaryData(final String name, final Duration timeout)
	{
		if (name == null || name.isEmpty())
		{
			throw new WapiException("Node name is required");
		}
		return wGetBinaryNode(new WapiToken(name), timeout);
	}

	/**
	 * Sends the supplied raw native binary node data on channel 1.
	 *
	 * @param data encoded protocol bytes; must not be {@code null}
	 * @throws WapiException if {@code data} is {@code null} or sending fails
	 */
	public void wSetBinaryNode(final byte[] data)
	{
		if (data == null)
		{
			throw new WapiException("Binary node data is required");
		}
		connection.send(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, data);
	}

	/**
	 * Encodes and sends a list of typed token values as one native node update.
	 *
	 * @param values non-null list of non-null token/value pairs
	 * @throws WapiException if {@code values} is {@code null} or sending fails
	 */
	public void wSetNodeFromTVArray(final List<WapiTokenValue> values)
	{
		if (values == null)
		{
			throw new WapiException("Token values are required");
		}
		connection.send(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, encoder.encodeNativeNodeValues(values));
	}

	/**
	 * Requests the values of all parameters below a node, like C {@code wGetNodeToTVArray}, in the
	 * order the console sends them. Waits up to 200 milliseconds for the reply and between its
	 * frames. Tokens come from the
	 * {@linkplain #getTokenRegistry() token registry}; a parameter whose hash is not registered gets a
	 * token without a name. Records that are not part of the response, including registered
	 * parameters outside the node that arrive before the reply, are delivered as events.
	 *
	 * @param node node token to request; must not be {@code null}
	 * @return mutable list of token/value pairs
	 * @throws WapiException if {@code node} is {@code null}, no response arrives in time, or the
	 *             token is a parameter instead of a node ({@link WapiError#WNODE}, as C returns for
	 *             {@code ch.1.fdr})
	 */
	public List<WapiTokenValue> wGetNodeToTVArray(final WapiToken node)
	{
		if (node == null)
		{
			throw new WapiException("Node token is required");
		}
		final var values = new ArrayList<WapiTokenValue>();
		for (final var streamRecord : readNodeReply(node, DEFAULT_TIMEOUT))
		{
			final var event = streamRecord.event();
			if (event.token().hash() == node.hash())
			{
				throw new WapiException("Not a node: " + node.name(), WapiError.WNODE);
			}
			if (event.token().hasKnownName() && !event.token().name().startsWith(node.name() + "."))
			{
				// Unsolicited event received before the reply
				acceptEvent(event);
				continue;
			}
			values.add(new WapiTokenValue(event.token(), event.value()));
		}
		return values;
	}

	/**
	 * Reads a token as a 32-bit float using the supplied timeout, like C {@code wGetTokenFloatTimed}.
	 *
	 * @param token token to read; must not be {@code null}
	 * @param timeout maximum wait for the reply
	 * @return the received value converted to {@code float}
	 * @throws WapiException if the read fails or cannot be converted to float
	 */
	public float wGetTokenFloat(final WapiToken token, final Duration timeout)
	{
		return wGetToken(token, WapiType.F32, timeout).getFloatValue();
	}

	/**
	 * Reads a token as a 32-bit integer using the supplied timeout, like C {@code wGetTokenIntTimed}.
	 *
	 * @param token token to read; must not be {@code null}
	 * @param timeout maximum wait for the reply
	 * @return the received value converted to {@code int}
	 * @throws WapiException if the read fails or cannot be converted to integer
	 */
	public int wGetTokenInt(final WapiToken token, final Duration timeout)
	{
		return wGetToken(token, WapiType.I32, timeout).getIntValue();
	}

	/**
	 * Reads a token as a string using the supplied timeout, like C {@code wGetTokenStringTimed}.
	 *
	 * @param token token to read; must not be {@code null}
	 * @param timeout maximum wait for the reply
	 * @return the received value formatted as a string
	 * @throws WapiException if the read fails
	 */
	public String wGetTokenString(final WapiToken token, final Duration timeout)
	{
		return wGetToken(token, WapiType.S32, timeout).getStringValue();
	}

	private WapiValue readNativeToken(final WapiToken token, final WapiType requestedType, final Duration timeout)
	{
		tokenRegistry.register(token);
		return connection.request(encoder.encodeNativeGet(token), new TokenReplyHandler(token, value -> convertValue(value, requestedType)), timeout);
	}

	/**
	 * Sets after how long without sending a keep-alive is sent; for tests.
	 */
	void setKeepAliveInterval(final Duration interval)
	{
		keepAlive.setInterval(interval);
	}

	/**
	 * Sets how long the keep-alive waits for its reply; for tests.
	 */
	void setKeepAliveReplyTimeout(final Duration timeout)
	{
		keepAlive.setReplyTimeout(timeout);
	}

	private WapiValue convertValue(final WapiValue value, final WapiType type)
	{
		if (value.getType() == type)
		{
			return value;
		}
		if (type == WapiType.I32 && value.getFloatValue() != null)
		{
			return WapiValue.ofInt(value.getFloatValue().intValue());
		}
		if (type == WapiType.F32 && value.getIntValue() != null)
		{
			return WapiValue.ofFloat(value.getIntValue().floatValue());
		}
		if (type == WapiType.S32)
		{
			return WapiValue.ofString(value.toString());
		}
		throw new WapiException("Cannot convert " + value.getType() + " to " + type);
	}

	/**
	 * Reads a token as a 32-bit integer using the default read timeout.
	 *
	 * @param token token to read; must not be {@code null}
	 * @return the received value converted to {@code int}
	 * @throws WapiException if the read fails or cannot be converted to integer
	 */
	public int wGetTokenInt(final WapiToken token)
	{
		final var value = wGetToken(token, WapiType.I32);
		return value.getIntValue();
	}

	/**
	 * Reads a token as a 32-bit float using the default read timeout.
	 *
	 * @param token token to read; must not be {@code null}
	 * @return the received value converted to {@code float}
	 * @throws WapiException if the read fails or cannot be converted to float
	 */
	public float wGetTokenFloat(final WapiToken token)
	{
		final var value = wGetToken(token, WapiType.F32);
		return value.getFloatValue();
	}

	/**
	 * Reads a token as a string using the default read timeout.
	 *
	 * @param token token to read; must not be {@code null}
	 * @return the received value formatted as a string
	 * @throws WapiException if the read fails
	 */
	public String wGetTokenString(final WapiToken token)
	{
		final var value = wGetToken(token, WapiType.S32);
		return value.getStringValue();
	}

	/**
	 * Sends a native integer value for a token.
	 *
	 * @param token target token; must not be {@code null}
	 * @param value signed 32-bit integer to send
	 * @throws WapiException if sending fails
	 */
	public void wSetTokenInt(final WapiToken token, final int value)
	{
		connection.send(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, encoder.encodeNativeSetInt(token, value));
	}

	/**
	 * Sends a native floating-point value for a token.
	 *
	 * @param token target token; must not be {@code null}
	 * @param value 32-bit floating-point value to send
	 * @throws WapiException if sending fails
	 */
	public void wSetTokenFloat(final WapiToken token, final float value)
	{
		connection.send(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, encoder.encodeNativeSetFloat(token, value));
	}

	/**
	 * Sends a native string value for a token.
	 *
	 * @param token target token; must not be {@code null}
	 * @param value string to send; must not be {@code null}
	 * @throws WapiException if sending fails
	 */
	public void wSetTokenString(final WapiToken token, final String value)
	{
		connection.send(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, encoder.encodeNativeSetString(token, value));
	}

	/**
	 * Parses comma-separated {@code name=value} assignments and sends them as one
	 * typed node update.
	 *
	 * @param node non-null assignment string accepted by {@code WapiNodeAssignmentCodec}
	 * @throws WapiException if parsing or sending fails
	 */
	public void wSetNode(final String node)
	{
		wSetNodeFromTVArray(WapiNodeAssignmentCodec.parse(node));
	}

	/**
	 * Requests the values of all parameters below a node and formats them like C {@code wGetNode},
	 * as described at {@link WapiNodeAssignmentCodec#format(String, List)}. Waits up to
	 * 200 milliseconds for the reply and between its frames. Records that are not part of the
	 * response, including registered parameters outside the node that arrive before the reply, are
	 * delivered as events.
	 * <p>
	 * Deviation from C: names come from the {@linkplain #getTokenRegistry() token registry}, as there
	 * is no token table; parameters whose hash is not registered are written as their hash.
	 *
	 * @param token node token to request; must not be {@code null}
	 * @return the formatted values, ending with a line feed
	 * @throws WapiException if {@code token} is {@code null}, no response arrives in time, or the
	 *             token is a parameter instead of a node ({@link WapiError#WNODE}, as in C)
	 */
	public String wGetNode(final WapiToken token)
	{
		if (token == null)
		{
			throw new WapiException("Node token is required");
		}
		return WapiNodeAssignmentCodec.format(token.name(), wGetNodeToTVArray(token));
	}

}
