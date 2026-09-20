package org.jurr.java.wapi.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.client.transport.WapiTransport;
import org.jurr.java.wapi.protocol.WapiEventDecoder;
import org.jurr.java.wapi.protocol.WapiEventDecoder.StreamRecord;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec.ChannelFrame;
import org.jurr.java.wapi.protocol.WapiPacket;

/**
 * The connection of a {@link WapiClient}: the transport, the reader thread that receives and decodes
 * the stream, requests and the routing of their replies, sending, and the handling of a connection
 * that fails or is lost.
 * <p>
 * Requests are sent one at a time; each waits until the reader thread has collected its reply. An
 * unknown or misplaced token in the received stream is a protocol error: the transport is
 * disconnected, and the next call that reads throws it.
 */
final class Connection
{
	private final WapiTransport transport;
	private final WapiEventDecoder eventDecoder;
	private final EventDelivery delivery;
	/** Delivers an event that is not found by the reader thread, such as one left in a reply that timed out. */
	private final Consumer<WapiEvent> accept;
	private final WapiNativeChannelCodec channelCodec = new WapiNativeChannelCodec();
	/** One request at a time waits for its reply, so every end-of-data token belongs to it. */
	private final ReentrantLock requestLock = new ReentrantLock();
	private final Object sendLock = new Object();
	/** The request whose reply the reader thread is collecting, or {@code null}. */
	private volatile ReplyHandler<?> currentRequest;
	private volatile Thread reader;
	/** Whether the reader thread has stopped, because the connection ended, failed or was closed. */
	private volatile boolean readerStopped = true;
	/** Whether the client is closing the connection itself, so that the reader's failure is expected. */
	private volatile boolean closing;
	/** A failure found by the reader thread, thrown once by the next call that reads. */
	private final AtomicReference<WapiException> readerError = new AtomicReference<>();
	/** Why the client ended the connection itself, for the reader thread to report; or {@code null}. */
	private final AtomicReference<WapiException> lostCause = new AtomicReference<>();
	/** {@link System#nanoTime()} of the last packet sent. */
	private volatile long lastSendNanos = System.nanoTime();
	/** {@link System#nanoTime()} of the last packet received. */
	private volatile long lastReceiveNanos = System.nanoTime();
	/** Events of the packet the reader thread is processing; used by that thread only. */
	private final List<WapiEvent> packetEvents = new ArrayList<>();

	/**
	 * @param accept delivers an event that is not found by the reader thread
	 */
	Connection(final WapiTransport transport, final WapiEventDecoder eventDecoder, final EventDelivery delivery, final Consumer<WapiEvent> accept)
	{
		this.transport = transport;
		this.eventDecoder = eventDecoder;
		this.delivery = delivery;
		this.accept = accept;
	}

	/** {@return whether the transport is connected and the reader thread runs} */
	boolean isConnected()
	{
		return transport.isConnected() && !readerStopped;
	}

	/** {@return whether the reader thread is running; for tests} */
	boolean isReading()
	{
		final var running = reader;
		return running != null && running.isAlive() && !readerStopped;
	}

	/** {@return whether the reader thread has stopped} */
	boolean isReaderStopped()
	{
		return readerStopped;
	}

	/** {@return whether the client is closing the connection itself} */
	boolean isClosing()
	{
		return closing;
	}

	/** {@return {@link System#nanoTime()} of the last packet sent} */
	long lastSendNanos()
	{
		return lastSendNanos;
	}

	/** {@return {@link System#nanoTime()} of the last packet received} */
	long lastReceiveNanos()
	{
		return lastReceiveNanos;
	}

	/**
	 * Connects the transport to {@code session} and starts the reader thread, after the previous one
	 * has stopped. Bytes kept from a previous connection are discarded.
	 *
	 * @throws WapiException if the transport cannot connect
	 */
	void open(final WapiSession session)
	{
		stopReader();
		transport.connect(session);
		// Bytes kept from the previous connection must not be glued onto the new stream
		eventDecoder.reset();
		readerError.set(null);
		startReader();
	}

	/** Disconnects the transport on the client's own account; the reader thread then stops without reporting. */
	void disconnect()
	{
		closing = true;
		transport.disconnect();
	}

	/** Disconnects the transport if it is connected, before a new connection is opened. */
	void disconnectIfConnected()
	{
		if (transport.isConnected())
		{
			disconnect();
		}
	}

	/** Starts the reader thread and, if needed, the dispatcher thread for a new connection. */
	private void startReader()
	{
		delivery.start();
		readerStopped = false;
		closing = false;
		lostCause.set(null);
		reader = Threads.daemon(this::read, "wapi-reader");
		reader.start();
		lastSendNanos = System.nanoTime();
		lastReceiveNanos = lastSendNanos;
	}

	/** Waits for the reader thread to stop; the caller closes the connection first. */
	void stopReader()
	{
		final var stopping = reader;
		reader = null;
		if (stopping != null && stopping != Thread.currentThread())
		{
			try
			{
				stopping.join(1000L);
			}
			catch (final InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Sends a request on channel 1 and waits until the reader thread has offered its reply to the
	 * handler, or the timeout passes. Requests are sent one at a time.
	 *
	 * @param timeout maximum wait for the reply; for some handlers, between its records
	 * @return the handler's result
	 * @throws IllegalArgumentException if the timeout is {@code null} or negative
	 * @throws WapiException if the handler fails, the connection failed, or on a timeout for most handlers
	 */
	<T> T request(final byte[] request, final ReplyHandler<T> handler, final Duration timeout)
	{
		return request(request, handler, timeout, true);
	}

	/**
	 * Sends a request unless another request is waiting for its reply, without reporting a failure of
	 * the reader thread; for the keep-alive.
	 *
	 * @return whether the request was sent
	 * @throws WapiException if the request fails or gets no reply in time
	 */
	<T> boolean requestIfIdle(final byte[] request, final ReplyHandler<T> handler, final Duration timeout)
	{
		if (!requestLock.tryLock())
		{
			return false;
		}
		try
		{
			request(request, handler, timeout, false);
			return true;
		}
		finally
		{
			requestLock.unlock();
		}
	}

	/**
	 * @param reportReaderError whether a failure of the reader thread is thrown by this request; the
	 *            keep-alive leaves it for the application's next call
	 */
	<T> T request(final byte[] request, final ReplyHandler<T> handler, final Duration timeout, final boolean reportReaderError)
	{
		final long deadline = Timeouts.deadline(timeout);
		requestLock.lock();
		try
		{
			requireConnection(reportReaderError);
			currentRequest = handler;
			sendRequest(request, reportReaderError);
			return awaitReply(handler, timeout, deadline, reportReaderError);
		}
		finally
		{
			currentRequest = null;
			requestLock.unlock();
		}
	}

	/** @throws WapiException if the connection is not up, with the reader thread's failure when that is reported */
	private void requireConnection(final boolean reportReaderError)
	{
		if (reportReaderError)
		{
			checkConnection();
		}
		else if (!isConnected())
		{
			throw new WapiException("Transport is not connected");
		}
	}

	/** Sends a request on channel 1; when that fails because the reader closed the connection just now, reports why. */
	private void sendRequest(final byte[] request, final boolean reportReaderError)
	{
		try
		{
			send(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, request);
		}
		catch (final WapiException e)
		{
			if (reportReaderError)
			{
				checkConnection();
			}
			throw e;
		}
	}

	/**
	 * Waits until the reader thread has handed over the reply, the deadline passes, or the reader
	 * thread stops. Progress restarts the timeout for handlers that ask for it.
	 */
	private <T> T awaitReply(final ReplyHandler<T> handler, final Duration timeout, final long firstDeadline, final boolean reportReaderError)
	{
		synchronized (handler)
		{
			var deadline = firstDeadline;
			while (!handler.isDelivered())
			{
				if (handler.takeProgress() && handler.restartsTimeoutOnProgress())
				{
					deadline = Timeouts.deadline(timeout);
				}
				final long remaining = deadline - System.nanoTime();
				final var over = remaining <= 0 || readerStopped;
				if (over && !handler.isDone())
				{
					return replyMissing(handler, reportReaderError);
				}
				if (over)
				{
					break;
				}
				waitFor(handler, remaining);
			}
			currentRequest = null;
		}
		return handler.result();
	}

	/** Ends a request whose reply did not come: the reader thread's failure, or the handler's timeout result. */
	private <T> T replyMissing(final ReplyHandler<T> handler, final boolean reportReaderError)
	{
		currentRequest = null;
		if (reportReaderError)
		{
			throwReaderError();
		}
		return handler.onTimeout(accept);
	}

	/** Waits on the handler, at most {@code nanos}; to be called while holding its lock. */
	private static void waitFor(final ReplyHandler<?> handler, final long nanos)
	{
		try
		{
			handler.wait(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
		}
		catch (final InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new WapiException("Interrupted while waiting for a reply", e);
		}
	}

	/**
	 * Offers a received record to the current request, or delivers it as an event. Runs on the reader
	 * thread.
	 *
	 * @return the request the record was offered to, or {@code null}
	 */
	private ReplyHandler<?> route(final StreamRecord streamRecord)
	{
		final var handler = currentRequest;
		if (handler != null)
		{
			synchronized (handler)
			{
				if (currentRequest == handler && !handler.isDone())
				{
					try
					{
						handler.onRecord(streamRecord, this::deliver);
					}
					catch (final WapiException e)
					{
						handler.fail(e);
					}
					return handler;
				}
			}
		}
		if (streamRecord.isEvent())
		{
			deliver(streamRecord.event());
		}
		return null;
	}

	/**
	 * Wakes the request after a packet: a done reply is handed over only now, so the events that
	 * arrived in the same packet are delivered before the request returns.
	 */
	private static void afterPacket(final ReplyHandler<?> handler)
	{
		if (handler != null)
		{
			synchronized (handler)
			{
				if (handler.isDone())
				{
					handler.deliver();
				}
				handler.notifyAll();
			}
		}
	}

	/**
	 * Delivers an event found by the reader thread: held back until the end of its packet, so that
	 * the events of one packet are queued at once. On other threads it is delivered right away.
	 */
	private void deliver(final WapiEvent event)
	{
		if (Thread.currentThread() == reader)
		{
			packetEvents.add(event);
		}
		else
		{
			accept.accept(event);
		}
	}

	/** Queues and dispatches the events held back during a packet. Runs on the reader thread. */
	private void flushPacketEvents()
	{
		if (packetEvents.isEmpty())
		{
			return;
		}
		delivery.offerAll(packetEvents);
		packetEvents.clear();
	}

	/** Sends one packet; several threads may send, such as for the keep-alive. */
	void send(final int channelId, final byte[] payload)
	{
		synchronized (sendLock)
		{
			transport.send(channelCodec.encode(channelId, payload));
			lastSendNanos = System.nanoTime();
		}
	}

	/**
	 * Throws the failure of the reader thread, once, or reports a closed connection. When the
	 * connection is closed, the reader thread is given time to finish, so that its failure, such as a
	 * protocol error, is reported rather than the closed connection.
	 */
	void checkConnection()
	{
		throwReaderError();
		if (!transport.isConnected())
		{
			final var stopping = reader;
			if (stopping != null && stopping != Thread.currentThread())
			{
				try
				{
					stopping.join(1000L);
				}
				catch (final InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
			}
			throwReaderError();
			throw new WapiException("Transport is not connected");
		}
	}

	/**
	 * Marks a connection that the console closed, or that failed, as closed, so that later calls
	 * report "not connected". Runs on the reader thread.
	 */
	private void connectionClosed()
	{
		try
		{
			transport.disconnect();
		}
		catch (final WapiException e)
		{
			// Closed already
		}
	}

	/** Throws the failure of the reader thread, once. */
	private void throwReaderError()
	{
		final var error = readerError.getAndSet(null);
		if (error != null)
		{
			throw error;
		}
	}

	/** Ends a connection that no longer works; the reader thread then reports {@code cause}. */
	void loseConnection(final WapiException cause)
	{
		lostCause.compareAndSet(null, cause);
		connectionClosed();
	}

	/** {@return why the client ended the connection itself, or {@code fallback}} */
	private WapiException takeLostCause(final WapiException fallback)
	{
		final var cause = lostCause.getAndSet(null);
		return cause != null ? cause : fallback;
	}

	/**
	 * The reader thread: receives packets until the connection ends, and offers the records of
	 * channel 1 to the current request or delivers them as events.
	 */
	private void read()
	{
		final var decoder = new WapiNativeChannelCodec();
		WapiException lost = null;
		try
		{
			var packet = receive();
			while (packet != null && packet.length() > 0)
			{
				processPacket(decoder, packet);
				packet = receive();
			}
			lost = endOfStream();
		}
		catch (final WapiException e)
		{
			lost = readFailed(e);
		}
		finally
		{
			stopped(lost);
		}
	}

	/** Receives the next packet and notes when it arrived. Runs on the reader thread. */
	private WapiPacket receive()
	{
		final var packet = transport.receive();
		lastReceiveNanos = System.nanoTime();
		return packet;
	}

	/**
	 * Offers the records of channel 1 in a packet to the current request, or delivers them as events;
	 * then delivers the events of the packet and wakes the request. Runs on the reader thread.
	 */
	private void processPacket(final WapiNativeChannelCodec decoder, final WapiPacket packet)
	{
		ReplyHandler<?> offered = null;
		for (final var frame : decoder.decode(packet))
		{
			if (frame.channelId() == WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID)
			{
				offered = routeRecords(frame, offered);
			}
		}
		flushPacketEvents();
		afterPacket(offered);
	}

	/** {@return the request the last record was offered to, or {@code offered} when none was} */
	private ReplyHandler<?> routeRecords(final ChannelFrame frame, final ReplyHandler<?> offered)
	{
		var last = offered;
		for (final var streamRecord : decodeRecords(frame))
		{
			final var handler = route(streamRecord);
			last = handler != null ? handler : last;
		}
		return last;
	}

	/**
	 * Handles the end of the stream: unless the client closes the connection itself, it is lost.
	 *
	 * @return why the connection is lost, or {@code null} when the client closed it
	 */
	private WapiException endOfStream()
	{
		if (closing)
		{
			return null;
		}
		connectionClosed();
		final var cause = lostCause.getAndSet(null);
		if (cause == null)
		{
			return new WapiException("Connection closed by the console", WapiError.WRECV_ERROR);
		}
		readerError.compareAndSet(null, cause);
		return cause;
	}

	/**
	 * Handles a failed read, such as a dead connection found by TCP keep-alive, or a protocol error:
	 * the stream cannot continue.
	 *
	 * @return why the connection is lost, or {@code null} when the client closed it
	 */
	private WapiException readFailed(final WapiException error)
	{
		if (closing)
		{
			return null;
		}
		connectionClosed();
		final var cause = takeLostCause(error);
		readerError.compareAndSet(null, cause);
		return cause;
	}

	/** Ends the reader thread: delivers what is left, and wakes the waiting callers and the listeners. */
	private void stopped(final WapiException lost)
	{
		flushPacketEvents();
		readerStopped = true;
		delivery.wakeUp();
		if (lost != null)
		{
			delivery.connectionLost(lost);
		}
		final var handler = currentRequest;
		if (handler != null)
		{
			synchronized (handler)
			{
				handler.notifyAll();
			}
		}
	}

	private List<StreamRecord> decodeRecords(final ChannelFrame frame)
	{
		try
		{
			return eventDecoder.decodeRecords(frame);
		}
		catch (final WapiException e)
		{
			throw disconnectOnProtocolError(e);
		}
	}

	/**
	 * Disconnects the transport after an unknown or misplaced token ({@link WapiError#WTOKEN}): the
	 * stream can no longer be decoded reliably, so later reads must not continue on it.
	 */
	private WapiException disconnectOnProtocolError(final WapiException error)
	{
		if (error.getError() == WapiError.WTOKEN)
		{
			try
			{
				transport.disconnect();
			}
			catch (final WapiException e)
			{
				error.addSuppressed(e);
			}
		}
		return error;
	}
}
