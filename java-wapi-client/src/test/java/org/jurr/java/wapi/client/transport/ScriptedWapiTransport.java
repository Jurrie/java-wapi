package org.jurr.java.wapi.client.transport;

import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

/**
 * Transport that plays back queued responses and records what is sent. It behaves like a socket:
 * a receive waits until a response is queued, until its timeout passes, or until the transport is
 * disconnected. The end of the stream must be queued explicitly with {@link #enqueueEndOfStream()};
 * a new connection starts after it.
 * It is thread-safe, so responses can be queued while another thread is receiving.
 * <p>
 * Like a console, which only replies after a request, a response queued with
 * {@link #enqueueReply(WapiPacket)} arrives only after a packet has been sent; a response queued with
 * {@link #enqueueResponse(WapiPacket)}, such as an event, arrives as soon as it is next in line.
 * <p>
 * Like a console, it answers the client's keep-alive (a data request for
 * {@code $syscfg.consolename}) by itself, apart from the queued responses: the keep-alive does not
 * release a queued reply and is not in {@link #getSentPackets()}, but in {@link #getKeepAlives()}.
 * Turn this off with {@link #setAnswersKeepAlive(boolean)} to script the keep-alive.
 */
public final class ScriptedWapiTransport extends AbstractWapiTransport
{
	private enum Kind
	{
		/** Arrives as soon as it is next in line, after its delay. */
		RESPONSE,
		/** Arrives once a packet has been sent after it came next in line. */
		REPLY,
		/** A silence: the receive that reaches it returns {@code null} after its timeout. */
		TIMEOUT,
		/** The end of the stream: receives return an empty packet, as for a closed socket. */
		END_OF_STREAM,
		/** A failed read: the receive that reaches it throws, as for a dead connection. */
		RECEIVE_ERROR
	}

	private static final class Entry
	{
		private final Kind kind;
		private final WapiPacket packet;
		/** Delay in milliseconds, counted from when the entry is next in line. */
		private final long delayMillis;
		private final WapiException error;
		/** {@link System#nanoTime()} at which a delayed entry arrives, once it is next in line. */
		private Long arrival;

		private Entry(final Kind kind, final WapiPacket packet, final long delayMillis)
		{
			this(kind, packet, delayMillis, null);
		}

		private Entry(final Kind kind, final WapiPacket packet, final long delayMillis, final WapiException error)
		{
			this.kind = kind;
			this.packet = packet;
			this.delayMillis = delayMillis;
			this.error = error;
		}
	}

	/** df d1 d7 c9cb20e0 dc: the keep-alive, a data request for $syscfg.consolename on channel 1. */
	private static final byte[] KEEP_ALIVE = new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
			WapiBinaryDataCodec.decodeHex("d7c9cb20e0dc", 64)).payload();
	/** df d1 d7 c9cb20e0 83 57494e47 de: the answer, the console name "WING" (0x80 + length - 1). */
	private static final WapiPacket KEEP_ALIVE_ANSWER = new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
			WapiBinaryDataCodec.decodeHex("d7c9cb20e08357494e47de", 64));

	private final Object lock = new Object();
	private volatile boolean answersKeepAlive = true;
	/** Answers to keep-alives, delivered before the queued responses. */
	private final Deque<WapiPacket> keepAliveAnswers = new ArrayDeque<>();
	private final List<WapiPacket> keepAlives = Collections.synchronizedList(new ArrayList<>());
	private final Deque<Entry> entries = new ArrayDeque<>();
	/** Number of packets sent. */
	private long sends;
	/** Value of {@link #sends} when the entry that is next in line came next in line. */
	private long sendsWhenNext;
	private final List<WapiPacket> sentPackets = Collections.synchronizedList(new ArrayList<>());
	/** "connect host" and "disconnect", in call order. */
	private final List<String> connections = Collections.synchronizedList(new ArrayList<>());

	/** @param answers whether keep-alives are answered by this transport (the default) or are scripted like any request */
	public void setAnswersKeepAlive(final boolean answers)
	{
		answersKeepAlive = answers;
	}

	/** @return the keep-alives this transport answered by itself */
	public List<WapiPacket> getKeepAlives()
	{
		synchronized (keepAlives)
		{
			return new ArrayList<>(keepAlives);
		}
	}

	/** Queues a response that arrives as soon as it is next in line, such as an event. */
	public void enqueueResponse(final WapiPacket response)
	{
		enqueue(new Entry(Kind.RESPONSE, Objects.requireNonNull(response, "response"), 0L));
	}

	/** Queues a response that arrives {@code delayMillis} after it came next in line. */
	public void enqueueResponse(final WapiPacket response, final long delayMillis)
	{
		enqueue(new Entry(Kind.RESPONSE, Objects.requireNonNull(response, "response"), delayMillis));
	}

	/**
	 * Queues the start of a reply: it arrives only after a packet has been sent once it is next in
	 * line, as a console replies only to a request. Queue the rest of a reply that is split over
	 * several packets with {@link #enqueueResponse(WapiPacket)}.
	 */
	public void enqueueReply(final WapiPacket reply)
	{
		enqueue(new Entry(Kind.REPLY, Objects.requireNonNull(reply, "reply"), 0L));
	}

	/** Queues a silence: the receive that reaches it waits for its timeout and returns {@code null}. */
	public void enqueueTimeout()
	{
		enqueue(new Entry(Kind.TIMEOUT, null, 0L));
	}

	/** Queues the end of the stream: receives then return an empty packet, as a closed socket does. */
	public void enqueueEndOfStream()
	{
		enqueue(new Entry(Kind.END_OF_STREAM, null, 0L));
	}

	/** Queues a failed read: the receive that reaches it throws {@code error}, as a socket does when TCP keep-alive gives up. */
	public void enqueueReceiveError(final WapiException error)
	{
		enqueue(new Entry(Kind.RECEIVE_ERROR, null, 0L, Objects.requireNonNull(error, "error")));
	}

	private void enqueue(final Entry entry)
	{
		synchronized (lock)
		{
			if (entries.isEmpty())
			{
				sendsWhenNext = sends;
			}
			entries.addLast(entry);
			lock.notifyAll();
		}
	}

	public List<WapiPacket> getSentPackets()
	{
		synchronized (sentPackets)
		{
			return new ArrayList<>(sentPackets);
		}
	}

	public List<String> getConnections()
	{
		synchronized (connections)
		{
			return new ArrayList<>(connections);
		}
	}

	@Override
	public void connect(final WapiSession session) throws WapiException
	{
		if (session.socketAddress() instanceof InetSocketAddress inetSocketAddress)
		{
			connections.add("connect " + inetSocketAddress.getHostString());
		}
		else if (session.socketAddress() instanceof UnixDomainSocketAddress unixDomainSocketAddress)
		{
			connections.add("connect " + unixDomainSocketAddress.getPath().toString());
		}
		else if (session.socketAddress() != null)
		{
			connections.add("connect unknown socket address type");
		}
		else
		{
			connections.add("connect null");
		}

		super.connect(session);
		synchronized (lock)
		{
			// A new connection is a new stream: the end of the previous one is behind us
			keepAliveAnswers.clear();
			if (!entries.isEmpty() && entries.peekFirst().kind == Kind.END_OF_STREAM)
			{
				entries.removeFirst();
			}
			lock.notifyAll();
		}
	}

	@Override
	public void disconnect() throws WapiException
	{
		connections.add("disconnect");
		super.disconnect();
		synchronized (lock)
		{
			lock.notifyAll();
		}
	}

	@Override
	public void send(final WapiPacket request) throws WapiException
	{
		super.send(request);
		if (answersKeepAlive && Arrays.equals(KEEP_ALIVE, request.payload()))
		{
			keepAlives.add(request);
			synchronized (lock)
			{
				keepAliveAnswers.addLast(KEEP_ALIVE_ANSWER);
				lock.notifyAll();
			}
			return;
		}
		sentPackets.add(request);
		synchronized (lock)
		{
			sends++;
			lock.notifyAll();
		}
	}

	@Override
	public WapiPacket receive(final Duration timeout) throws WapiException
	{
		super.receive(timeout);
		return next(System.nanoTime() + timeout.toNanos(), true);
	}

	@Override
	public WapiPacket receive() throws WapiException
	{
		super.receive();
		return next(Long.MAX_VALUE, false);
	}

	/**
	 * @param deadline {@link System#nanoTime()} to wait until
	 * @param timed whether this receive has a timeout; a silence then waits for it, otherwise it is skipped
	 * @return the next response, {@code null} on timeout, or an empty packet at the end of the stream or after a disconnect
	 */
	private WapiPacket next(final long deadline, final boolean timed)
	{
		synchronized (lock)
		{
			while (true)
			{
				if (!isConnected())
				{
					return new WapiPacket(new byte[0]);
				}
				if (!keepAliveAnswers.isEmpty())
				{
					return keepAliveAnswers.removeFirst();
				}
				final var head = entries.peekFirst();
				final long now = System.nanoTime();
				if (head != null && head.kind == Kind.END_OF_STREAM)
				{
					return new WapiPacket(new byte[0]);
				}
				if (head != null && head.kind == Kind.RECEIVE_ERROR)
				{
					removeHead();
					throw head.error;
				}
				if (head != null && head.kind == Kind.TIMEOUT)
				{
					removeHead();
					if (!timed)
					{
						continue;
					}
					// A silence lasts the whole timeout, also when a response is queued meanwhile
					while (deadline - System.nanoTime() > 0)
					{
						await(deadline - System.nanoTime());
					}
					return null;
				}
				if (head != null && arrived(head, now))
				{
					removeHead();
					return head.packet;
				}
				if (deadline - now <= 0)
				{
					return null;
				}
				final long untilArrival = head != null && head.arrival != null ? head.arrival - now : Long.MAX_VALUE;
				await(Math.min(untilArrival, deadline - now));
			}
		}
	}

	/** @return whether the entry that is next in line has arrived; starts its delay if it has one */
	private boolean arrived(final Entry head, final long now)
	{
		if (head.kind == Kind.REPLY && sends == sendsWhenNext)
		{
			// A reply waits for a request
			return false;
		}
		if (head.delayMillis > 0 && head.arrival == null)
		{
			head.arrival = now + head.delayMillis * 1_000_000L;
		}
		return head.arrival == null || head.arrival - now <= 0;
	}

	private void removeHead()
	{
		entries.removeFirst();
		sendsWhenNext = sends;
	}

	/** Waits on the lock, at most {@code nanos}; to be called while holding it. */
	private void await(final long nanos)
	{
		if (nanos <= 0)
		{
			return;
		}
		try
		{
			lock.wait(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
		}
		catch (final InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
}
